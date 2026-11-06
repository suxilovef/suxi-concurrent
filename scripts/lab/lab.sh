#!/usr/bin/env bash
# 生产故障排查实验室 · 运维入口（对应 docs/05-05-生产环境故障排查实操.md）
#
# 用法：
#   bash scripts/lab/lab.sh provision              # 一次性：服务器装 JDK17 + 建目录 + 下 Arthas
#   bash scripts/lab/lab.sh build                  # 本地打包 fat jar + scp 上传
#   bash scripts/lab/lab.sh start | stop | logs    # 第一幕：宿主机起 / 停 / 跟踪日志
#                                                  # start 可透传应用参数，如
#                                                  #   start --lab.detector.enabled=false
#   bash scripts/lab/lab.sh status                 # 看 /lab/status（故障现场确认）
#   bash scripts/lab/lab.sh inject <场景> [参数]    # 注入故障，如 inject cpu threads=2
#   bash scripts/lab/lab.sh clean  <场景|all>      # 清理故障
#   bash scripts/lab/lab.sh dump   [份数] [间隔秒]  # 间隔多抓 jstack 并对比（docs/05-03 坑 5）
#   bash scripts/lab/lab.sh docker-up | docker-down # 第二幕：容器
#   bash scripts/lab/lab.sh pidns                  # 第二幕核心：三种线程 id 对照
#   bash scripts/lab/lab.sh tunnel                 # 本地开 ssh 隧道，浏览器访问 /lab/status
#   bash scripts/lab/lab.sh ssh                    # 直接开一个到服务器的 shell
#
# 产物（服务器 ~/lab/）：app.jar、arthas-boot.jar、app.pid、logs/、dumps/
# 依赖：本地 git-bash（ssh/scp/curl）+ Maven wrapper；服务器 ssh 别名 tencent-docker
#
# ⚠️ 本脚本会启动一个"能打死自己"的故障注入服务（/lab/**）。
#    服务只绑定服务器 127.0.0.1，公网/安全组不要开端口；演练完请 stop。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOST="${LAB_HOST:-tencent-docker}"
JAR_NAME="concurrent-0.0.1-SNAPSHOT.jar"
REMOTE_DIR='$HOME/lab'                 # 故意不展开：让远端 shell 自己解析
REMOTE_PORT=8080
LOCAL_TUNNEL_PORT="${LAB_TUNNEL_PORT:-18080}"

# git-bash 会把 ssh/scp 参数里的 host:path / a:b:c 冒号当 Windows 路径处理，必须关掉。
#
# ⚠️ 但只能**逐个命令**关，绝不能全局 export：全局关掉会破坏 Maven wrapper ——
#    MSYS 的路径转换同时也负责给 Windows 版 java 传 classpath，
#    关掉之后 mvnw 直接报 ClassNotFoundException: classworlds.launcher.Launcher。
ssh_() { check_host; MSYS_NO_PATHCONV=1 ssh "$@"; }
scp_() { check_host; MSYS_NO_PATHCONV=1 scp "$@"; }

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 前置检查：ssh 别名能不能解析（只查一次）
#
# 它防的是一个很隐蔽的坑：在 PowerShell 里执行 `bash scripts/lab/lab.sh ...`，
# `bash` 会解析到 C:\Windows\System32\bash.exe（WSL 的启动器），而不是 Git Bash。
# WSL 有它自己的 $HOME（/home/<user>），那里**没有** Windows 的 ~/.ssh/config，
# 于是 tencent-docker 这个别名不存在，ssh 只会吐一句
# "Could not resolve hostname" —— 看起来像网络问题，其实是跑错 shell 了。
#
# 判据：`ssh -G <别名>` 会打印最终解析出的 hostname。别名没定义时，
#       hostname 就等于别名自己。
# ---------------------------------------------------------------------------
_HOST_CHECKED=0
check_host() {
  [[ "$_HOST_CHECKED" == "1" ]] && return 0
  local resolved
  resolved="$(MSYS_NO_PATHCONV=1 ssh -G "$HOST" 2>/dev/null | awk '/^hostname /{print $2}')"
  if [[ -z "$resolved" || "$resolved" == "$HOST" ]]; then
    die "ssh 别名 '$HOST' 解析不了。

最可能的原因：你不在 Git Bash 里，而是在 PowerShell / CMD 里跑的 ——
   PowerShell 的 \`bash\` 会走到 WSL（C:\\Windows\\System32\\bash.exe），
   而 WSL 的 \$HOME 下没有 Windows 的 ~/.ssh/config，所以别名不存在。

请改用 Git Bash 跑（开始菜单搜 \"Git Bash\"，或在仓库目录右键 → Git Bash Here）：
   bash scripts/lab/lab.sh <子命令>

想留在这个终端也行，用完整路径显式调 Git Bash：
   & \$env:GIT_BASH scripts/lab/lab.sh <子命令>     # 设 GIT_BASH 为你的 Git\\bin\\bash.exe

（不想用别名的话，也可以 LAB_HOST=ubuntu@43.142.76.19 覆盖 —— 但要确保当前 shell 能拿到私钥。）"
  fi
  _HOST_CHECKED=1
}

# 在服务器上跑一段脚本；$1 之后作为位置参数传进 heredoc（避免引号地狱）
on_remote() {
  ssh_ "$HOST" bash -s -- "$@"
}

# 在服务器上 curl 本机服务 —— 这样不需要开隧道也能注入/清理
api() {
  local method="$1" path="$2"
  on_remote "$method" "$path" "$REMOTE_PORT" <<'REMOTE'
set -euo pipefail
METHOD="$1"; PATH_="$2"; PORT="$3"
curl -sS -X "$METHOD" "http://127.0.0.1:${PORT}${PATH_}" || {
  echo "(curl 失败：服务没起来？先跑 lab.sh start)" >&2
  exit 1
}
echo
REMOTE
}

# ---------------------------------------------------------------------------
# 场景 → 端点映射（改这里就能加新场景）
# ---------------------------------------------------------------------------
inject_endpoint() {
  case "$1" in
    deadlock-sync)   echo "/lab/deadlock/sync" ;;
    deadlock-lock)   echo "/lab/deadlock/lock" ;;
    cpu)             echo "/lab/cpu/burn" ;;
    cpu-regex)       echo "/lab/cpu/burn?mode=regex" ;;
    livelock)        echo "/lab/livelock" ;;
    livelock-backoff) echo "/lab/livelock/backoff" ;;
    pool-self)       echo "/lab/pool/self-deadlock" ;;
    pool-exhaust)    echo "/lab/pool/exhaust" ;;
    blocked)         echo "/lab/blocked" ;;
    leak)            echo "/lab/leak" ;;
    *) return 1 ;;
  esac
}

clean_endpoint() {
  case "$1" in
    cpu)             echo "/lab/cpu/burn" ;;
    livelock)        echo "/lab/livelock" ;;
    pool)            echo "/lab/pool" ;;
    blocked)         echo "/lab/blocked" ;;
    leak)            echo "/lab/leak" ;;
    all)             echo "/lab/all" ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------
cmd_provision() {
  say "准备服务器环境（装 JDK17 + 建目录 + 下 Arthas）"
  on_remote <<'REMOTE'
set -euo pipefail

# --- JDK 17：宿主机本来只有 Docker，没有 java；docs/05-03 §2 的 jps/jstack 需要它 ---
if command -v jstack >/dev/null 2>&1; then
  echo "JDK 已存在，跳过安装"
else
  echo "安装 openjdk-17-jdk-headless ..."
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless
fi

mkdir -p "$HOME/lab/logs" "$HOME/lab/dumps"

# --- Arthas：第一幕在宿主机跑，用 boot jar ---
# 注意：容器里不用这个（见 docs/05-05 第二幕 —— 容器内改用镜像内置的 /opt/arthas）
if [[ ! -f "$HOME/lab/arthas-boot.jar" ]]; then
  echo "下载 arthas-boot.jar ..."
  curl -fsSL -o "$HOME/lab/arthas-boot.jar" https://arthas.aliyun.com/arthas-boot.jar
fi

echo
echo "--- 环境确认 ---"
java -version 2>&1 | head -3
# 注意：jstack / jps 没有 -version 选项（写了会打 Usage），只能查是否在 PATH 里
for tool in jps jstack jmap jcmd jstat; do
  printf '%-6s: %s\n' "$tool" "$(command -v "$tool" || echo '缺失')"
done
ls -la "$HOME/lab/arthas-boot.jar"
REMOTE
}

cmd_build() {
  check_host   # 先查，别等打包跑完 40 秒才发现连不上
  say "本地打包 fat jar"
  ( cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-21}" ./mvnw -q -DskipTests package )
  local jar="$ROOT/target/$JAR_NAME"
  [[ -f "$jar" ]] || die "没找到 $jar"

  say "上传到 $HOST:~/lab/"
  on_remote <<'REMOTE'
mkdir -p "$HOME/lab/logs" "$HOME/lab/dumps"
REMOTE
  scp_ "$jar" "$HOST:lab/app.jar"
  # 镜像文件也传到 ~/lab/，这样第二幕的构建上下文就是 ~/lab（内含 app.jar）
  scp_ "$ROOT/docker/Dockerfile" "$HOST:lab/Dockerfile"
  scp_ "$ROOT/docker/docker-compose.yml" "$HOST:lab/docker-compose.yml"
  scp_ "$ROOT/docker/.dockerignore" "$HOST:lab/.dockerignore"
  # 服务器上起服务用的脚本（部署动作才脚本化，排查动作不包）
  scp_ "$ROOT/scripts/lab/start.sh" "$HOST:lab/start.sh"
  on_remote <<'REMOTE'
ls -la "$HOME/lab/app.jar" "$HOME/lab/Dockerfile" "$HOME/lab/docker-compose.yml"
REMOTE
}

cmd_start() {
  say "第一幕：宿主机启动（绑定 127.0.0.1:$REMOTE_PORT，lab 开启）"
  # 额外参数透传给应用，如：lab.sh start --lab.detector.enabled=false
  on_remote "$REMOTE_PORT" "$@" <<'REMOTE'
set -euo pipefail
PORT="$1"; shift
EXTRA=("$@")

if [[ -f "$HOME/lab/app.pid" ]] && kill -0 "$(cat "$HOME/lab/app.pid")" 2>/dev/null; then
  echo "已有进程在跑（pid $(cat "$HOME/lab/app.pid")），先跑 lab.sh stop"
  exit 1
fi

# 为什么绑 127.0.0.1：这些接口能打死服务，绝不能暴露到公网/安全组。
# 本地要看就用 lab.sh tunnel 开 ssh 隧道。
#
# 为什么日志落文件：关掉内置 Detector 后注入死锁，tail 日志会"没有新行" ——
# 这本身是个教具（日志静默 ≠ 无活动，见 docs/05-05 3.2）。
nohup java -jar "$HOME/lab/app.jar" \
  --lab.enabled=true \
  --server.address=127.0.0.1 \
  --server.port="$PORT" \
  --logging.file.name="$HOME/lab/logs/app.log" \
  --logging.logback.rollingpolicy.max-file-size=20MB \
  --management.endpoints.web.exposure.include=health,threaddump,heapdump,metrics \
  "${EXTRA[@]+"${EXTRA[@]}"}" \
  > "$HOME/lab/logs/console.log" 2>&1 &

echo $! > "$HOME/lab/app.pid"
# 记下启动前的行数：app.log 是追加写的，只看本次新增的部分，
# 否则会捞到上一次运行的横幅（日志轮转没开的话会一直累积）
LOG_BEFORE=$(wc -l < "$HOME/lab/logs/app.log" 2>/dev/null || echo 0)
echo "已启动，pid=$(cat "$HOME/lab/app.pid")，等待就绪..."
for i in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$PORT/lab/status" >/dev/null 2>&1; then
    echo "就绪 ✅"
    echo
    echo "--- 本次启动横幅（注意 pid / availableProcessors / maxHeap）---"
    tail -n +$((LOG_BEFORE + 1)) "$HOME/lab/logs/app.log" \
      | grep -E 'LAB MODE|pid  |availableProcessors|maxHeap|jvmArgs|DeadlockDetector'
    exit 0
  fi
  sleep 1
done
echo "30s 内没起来，看 $HOME/lab/logs/console.log" >&2
tail -20 "$HOME/lab/logs/console.log" >&2
exit 1
REMOTE
}

cmd_stop() {
  say "停止宿主机进程（SIGTERM → 等 15s → SIGKILL）"
  on_remote "$REMOTE_PORT" <<'REMOTE'
set -uo pipefail
PIDFILE="$HOME/lab/app.pid"
[[ -f "$PIDFILE" ]] || { echo "没有 pid 文件，服务应该没在跑"; exit 0; }
PID="$(cat "$PIDFILE")"

if ! kill -0 "$PID" 2>/dev/null; then
  echo "进程 $PID 已经没了"; rm -f "$PIDFILE"; exit 0
fi

kill "$PID" 2>/dev/null || true
for i in $(seq 1 15); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "SIGTERM 后 ${i}s 内正常退出 ✅"
    rm -f "$PIDFILE"; exit 0
  fi
  sleep 1
done

echo "SIGTERM 等了 15s 还没退，强制 SIGKILL（有真死锁时可能是这种）"
kill -9 "$PID" 2>/dev/null || true
sleep 1
rm -f "$PIDFILE"
echo "已强杀"
REMOTE
}

cmd_logs() {
  say "跟踪 $HOST:~/lab/logs/app.log（Ctrl-C 退出）"
  ssh_ -t "$HOST" 'tail -f "$HOME/lab/logs/app.log"'
}

cmd_status() {
  api GET "/lab/status"
}

cmd_inject() {
  local scenario="${1:-}" ; shift || true
  [[ -n "$scenario" ]] || die "用法: lab.sh inject <场景> [k=v ...]
场景: deadlock-sync deadlock-lock cpu cpu-regex livelock livelock-backoff pool-self pool-exhaust blocked leak"

  local endpoint
  endpoint="$(inject_endpoint "$scenario")" \
    || die "未知场景 '$scenario'"

  # 附加参数：k=v [k2=v2 ...] → 拼到查询串上
  local path="$endpoint"
  if [[ $# -gt 0 ]]; then
    local qs="" kv
    for kv in "$@"; do
      if [[ -z "$qs" ]]; then qs="$kv"; else qs="$qs&$kv"; fi
    done
    if [[ "$endpoint" == *"?"* ]]; then
      path="${endpoint}&${qs}"
    else
      path="${endpoint}?${qs}"
    fi
  fi

  say "注入 $scenario → POST $path"
  api POST "$path"
}

cmd_clean() {
  local scenario="${1:-all}"
  local endpoint
  endpoint="$(clean_endpoint "$scenario")" || die "未知场景 '$scenario'（可选 cpu livelock pool blocked leak all）"
  say "清理 $scenario → DELETE $endpoint"
  api DELETE "$endpoint"
}

cmd_dump() {
  local count="${1:-3}" interval="${2:-5}"
  say "间隔抓 $count 份 jstack（每份间隔 ${interval}s），并跨份对比"
  on_remote "$count" "$interval" <<'REMOTE'
set -euo pipefail
COUNT="$1"; INTERVAL="$2"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="$HOME/lab/dumps/$STAMP"
mkdir -p "$DIR"

PID="$(cat "$HOME/lab/app.pid" 2>/dev/null || true)"
[[ -n "$PID" ]] || { echo "没有 pid 文件，先 lab.sh start" >&2; exit 1; }

# docs/05-03 坑 5：jstack 抓的是瞬时状态，偶发问题要多抓几次对比。
# 判据：**每一份里都**处于 BLOCKED/WAITING 的线程才是长驻嫌疑，只出现一次的可能是路过。
for i in $(seq 1 "$COUNT"); do
  jstack -l "$PID" > "$DIR/dump_$i.txt" 2>&1
  echo "  抓取第 $i 份 → $DIR/dump_$i.txt"
  [[ "$i" -lt "$COUNT" ]] && sleep "$INTERVAL"
done

echo
echo "--- 各份 dump 里的死锁报告 ---"
grep -H 'Found .* deadlock' "$DIR"/dump_*.txt || echo "  （没有任何一份报告死锁）"

echo
echo "--- 跨份对比：每份都是 BLOCKED 的线程名 ---"
for f in "$DIR"/dump_*.txt; do
  echo -n "  $(basename "$f"): "
  grep -B1 'java.lang.Thread.State: BLOCKED' "$f" \
    | grep -o '^"[^"]*"' | tr -d '"' | sort -u | tr '\n' ' '
  echo
done

echo
echo "--- 跨份对比：每份都是 WAITING (parking) 的线程名 ---"
for f in "$DIR"/dump_*.txt; do
  echo -n "  $(basename "$f"): "
  grep -B1 'java.lang.Thread.State: WAITING (parking)' "$f" \
    | grep -o '^"[^"]*"' | tr -d '"' | sort -u | tr '\n' ' '
  echo
done

echo
echo "dump 目录：$DIR"
REMOTE
}

cmd_docker_up() {
  say "第二幕：构建并启动容器"
  on_remote "$REMOTE_PORT" <<'REMOTE'
set -euo pipefail
PORT="$1"
cd "$HOME/lab"
# 构建期走宿主网络（让 RUN 能触达 link-local 的腾讯云内网 apt 源）——
# 这条约束写在 docker-compose.yml 的 build.network 里，不用命令行参数
# （`docker compose build --network` 这个 flag 不存在）
docker compose -f docker-compose.yml build
docker compose -f docker-compose.yml up -d

echo "等待就绪..."
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:8081/lab/status" >/dev/null 2>&1; then
    echo "就绪 ✅"
    echo
    echo "--- 容器感知三件套（对比第一幕：pid≠1 / 4 核 / ~900MB）---"
    curl -sS "http://127.0.0.1:8081/lab/status" | tr ',' '\n' \
      | grep -E '"(pid|availableProcessors|maxHeapMb)"'
    exit 0
  fi
  sleep 1
done
echo "60s 内没起来，docker logs lab：" >&2
docker logs --tail 40 lab >&2
exit 1
REMOTE
}

cmd_docker_down() {
  say "停止容器"
  on_remote <<'REMOTE'
set -euo pipefail
cd "$HOME/lab"
# -t 5：宽限期给 5s，超时 SIGKILL。
# 注：注入了真死锁时，进程仍会正常收到 SIGTERM —— 死锁的是业务线程，
# 不是 JVM 的关闭流程。这点和"直觉上以为死锁就关不掉"不同，docs/05-05 有实测记录。
docker compose -f docker-compose.yml down -t 5 || true
echo "已停止"
REMOTE
}

cmd_pidns() {
  say "第二幕核心：同一个线程的三种 id 对照"
  on_remote <<'REMOTE'
set -euo pipefail

HP="$(docker inspect -f '{{.State.Pid}}' lab 2>/dev/null || true)"
[[ -n "$HP" && "$HP" != "0" ]] || { echo "容器 lab 没在跑，先 lab.sh docker-up" >&2; exit 1; }

echo "容器 lab 的 PID1 在**宿主机命名空间**里的 pid = $HP"
echo

echo "=== ① 宿主机视角：/proc/$HP/status 的 NSpid ==="
grep '^NSpid' "/proc/$HP/status"
echo "   （第一个数是宿主 ns 的 pid，第二个数是容器 ns 里的 pid）"
echo

echo "=== ② 宿主机 top -Hp $HP：拿到的是**宿主 ns** 的 TID ==="
# 用 awk 只挑出「第一列是纯数字」的行，避免依赖 top 的表头行号
# （top -Hp -bn1 的线程行前面有：5 行汇总 + 1 空行 + 1 列表头）
top -Hp "$HP" -bn1 | awk '$1 ~ /^[0-9]+$/ {print}' | sort -k9 -rn | head -8
echo

echo "=== ③ 容器内 jstack 1：nid 是**容器 ns** 的 TID ==="
docker exec lab jstack 1 2>/dev/null | grep -oE '"[^"]*".*nid=0x[0-9a-f]+' | head -8
echo

echo "=== ④ 逐线程翻译：宿主 TID → 容器 TID ==="
# 取一个 CPU 最高的宿主 TID
HTID="$(top -Hp "$HP" -bn1 | awk '$1 ~ /^[0-9]+$/ {print}' | sort -k9 -rn | head -1 | awk '{print $1}')"
echo "取样线程：宿主 TID = $HTID"
echo -n "  它的 NSpid 列表： "; grep '^NSpid' "/proc/$HP/task/$HTID/status" 2>/dev/null || echo "(读不到)"
CTID="$(grep '^NSpid' "/proc/$HP/task/$HTID/status" 2>/dev/null | awk '{print $NF}')"
if [[ -n "${CTID:-}" ]]; then
  HEX="$(printf '%x' "$CTID")"
  echo "  容器 ns TID = $CTID  → 十六进制 0x$HEX"
  echo
  echo "  ✅ 用**容器 TID** 的 hex 去 grep 容器内 jstack："
  docker exec lab jstack 1 2>/dev/null | grep -A6 "nid=0x$HEX" | head -8 || echo "    （没命中）"
  echo
  HHEX="$(printf '%x' "$HTID")"
  echo "  ❌ 用**宿主 TID** 的 hex(0x$HHEX) 去 grep 容器内 jstack："
  docker exec lab jstack 1 2>/dev/null | grep -c "nid=0x$HHEX" | sed 's/^/    命中行数: /'
  echo "     ↑ 期望是 0 —— 这就是「照抄宿主机流程在容器里查不出来」的现场"
fi
echo
echo "=== ⑤ 容器内自洽：在容器里跑 top -Hp 1，它的 TID 直接对得上 nid ==="
docker exec lab top -Hp 1 -bn1 2>/dev/null | tail -n +7 | head -6 || echo "（容器内没有 procps，走 ①④ 的 /proc 翻译）"
REMOTE
}

cmd_tunnel() {
  say "本地 $LOCAL_TUNNEL_PORT → $HOST:127.0.0.1:$REMOTE_PORT（Ctrl-C 退出）"
  echo "打开： http://127.0.0.1:$LOCAL_TUNNEL_PORT/lab/status"
  echo "        http://127.0.0.1:$LOCAL_TUNNEL_PORT/actuator/threaddump"
  ssh_ -N -L "$LOCAL_TUNNEL_PORT:127.0.0.1:$REMOTE_PORT" "$HOST"
}

usage() {
  awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"
}

case "${1:-}" in
  provision)   shift; cmd_provision "$@" ;;
  build)       shift; cmd_build "$@" ;;
  start)       shift; cmd_start "$@" ;;
  stop)        shift; cmd_stop "$@" ;;
  logs)        shift; cmd_logs "$@" ;;
  status)      shift; cmd_status "$@" ;;
  inject)      shift; cmd_inject "$@" ;;
  clean)       shift; cmd_clean "$@" ;;
  dump)        shift; cmd_dump "$@" ;;
  docker-up)   shift; cmd_docker_up "$@" ;;
  docker-down) shift; cmd_docker_down "$@" ;;
  pidns)       shift; cmd_pidns "$@" ;;
  tunnel)      shift; cmd_tunnel "$@" ;;
  ssh)         ssh_ "$HOST" ;;
  ""|-h|--help|help) usage ;;
  *) die "未知子命令 '$1'（跑 lab.sh --help 看用法）" ;;
esac

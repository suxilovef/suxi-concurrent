#!/usr/bin/env bash
# 启动故障排查实验室服务（docs/05-05）
#
# 用法：  bash ~/lab/start.sh
# 日志：  ~/lab/logs/console.log     （tail -f 看）
# 停止：  jps -l 找 pid，然后 kill <pid>
#         ↑ 不配 stop 脚本：就两条命令，而且 jps 是排查第一步，留着让你敲
#
# 为什么只有这一个脚本：起服务是部署动作（要反复重启，值得脚本化）；
# 排查动作（jstack / top -Hp / printf %x / Arthas）一律手敲，不封装。
set -euo pipefail

APP="$HOME/lab/app.jar"
LOG="$HOME/lab/logs/console.log"
PORT=8080

if [[ ! -f "$APP" ]]; then
  echo "找不到 $APP —— 先在本地跑 lab.sh build 上传" >&2
  exit 1
fi
mkdir -p "$(dirname "$LOG")"

# 已经在跑就别重复起：不然两个进程抢 8080，第二个起不来还让人以为是端口问题
EXISTING="$(jps -l 2>/dev/null | awk '/app\.jar/{print $1}' || true)"
if [[ -n "$EXISTING" ]]; then
  echo "已经在跑了，pid=$EXISTING（要先停：kill $EXISTING）" >&2
  exit 1
fi

# --lab.enabled=true 是必须的：默认关，不加则 /lab/** 全 404
# --server.address=127.0.0.1 只绑 loopback：这些接口能打死服务，不能暴露
nohup java -jar "$APP" --lab.enabled=true --server.address=127.0.0.1 > "$LOG" 2>&1 &

PID=$!
echo "已启动，pid=$PID"
echo "日志： tail -f $LOG"
echo "停止： kill $PID"

# 等它真的在监听才算成功 —— 否则"启动成功"可能是假的（端口占用 / 启动即崩）
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$PORT/lab/status" >/dev/null 2>&1; then
    echo "就绪 ✅（三个基线数字见下方）"
    grep -E 'LAB MODE|pid  |availableProcessors|maxHeap' "$LOG" | tail -4
    exit 0
  fi
  sleep 1
done

echo "30s 内没起来，日志尾部：" >&2
tail -20 "$LOG" >&2
exit 1

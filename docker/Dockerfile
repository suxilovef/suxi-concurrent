# 生产故障排查实验室 · 第二幕镜像（对应 docs/05-05 第 4 节）
#
# 为什么是 17-jdk 而不是 17-jre：
#   jstack / jmap / jcmd / jps 是 JDK 的 $JAVA_HOME/bin 工具，JRE 镜像只有 java。
#   第二幕整套「容器内抓栈 + PID 命名空间对照」没有这些工具根本做不了；
#   Arthas 也依赖 JDK 的 attach 机制。所以必须用 JDK 镜像。
#
# 为什么钉 -jammy 而不是直接用 :17-jdk：
#   :17-jdk 跟随最新 Ubuntu（可能是 noble），apt 源格式会变成 /etc/apt/sources.list.d/ubuntu.sources，
#   下面那条 sed 换源就不成立了。jammy 是 Ubuntu 22.04，sources.list 格式稳定。
FROM eclipse-temurin:17-jdk-jammy

# ---------------------------------------------------------------------------
# procps：容器内复现 `top -Hp` 需要它（temurin 精简掉了 procps）
#
# ⚠️ 这段刻意写成「失败也不中断构建」：
#   腾讯云内网 apt 源 mirrors.tencentyun.com 解析到 169.254.0.3（link-local），
#   容器走默认桥接网络够不到它 —— 所以构建时要加 --network=host（见 lab.sh docker-up）。
#   万一还是装不上也不影响第二幕：对照实验可以只用 jstack + 直接读 /proc 完成。
# ---------------------------------------------------------------------------
RUN (sed -i 's@//.*archive.ubuntu.com@//mirrors.tencentyun.com@g; \
             s@//security.ubuntu.com@//mirrors.tencentyun.com@g' /etc/apt/sources.list || true) \
 && (apt-get update -qq && apt-get install -y -qq --no-install-recommends procps \
     || echo "WARN: procps 没装上 —— 改用宿主机 docker top / 直接读 /proc") \
 && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Arthas 内置进镜像
#
# 构建期拷进来 → 运行期完全离线可用（容器不出网也能诊断），且可复现。
# 备选方案（挂载宿主 ~/.arthas / docker cp）都要额外维护宿主状态，不如这个干净。
# 注意：宿主机的 arthas-boot.jar 是给第一幕用的，容器里用这份 /opt/arthas。
# ---------------------------------------------------------------------------
COPY --from=hengyunabc/arthas:latest /opt/arthas /opt/arthas

WORKDIR /app
COPY app.jar /app/app.jar

# ---------------------------------------------------------------------------
# ★ 刻意不在 ENTRYPOINT 里写 -Xmx
#
# 不写死，JVM 才会按 cgroup 限额（--memory=1500m）自动算堆：
#   JDK 10+ UseContainerSupport 默认开启，>512MB 时 max heap = 25% × 限额 ≈ 375MB。
# 一旦显式写 -Xmx，会**静默覆盖**这个行为 —— 这正是要在演练里观察的对比。
#
# 运行时想传 JVM 参数用 JAVA_TOOL_OPTIONS 环境变量（JVM 启动时会打印 "Picked up ..."，
# 看得见），不要用 compose 的 command: —— 那会追加到 -jar 后面变成应用的参数。
# ---------------------------------------------------------------------------
ENTRYPOINT ["java", "-jar", "/app/app.jar", \
    "--lab.enabled=true", \
    "--server.address=0.0.0.0", \
    "--server.port=8080", \
    "--logging.file.name=/lab/logs/app.log", \
    "--management.endpoints.web.exposure.include=health,threaddump,heapdump,metrics"]

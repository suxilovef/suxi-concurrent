# 项目说明（Claude 工作上下文）

Java 并发学习仓库：`docs/` 下 21 篇学习文档（00 全景地图 → 06 分布式），`src/test/java/com/sw/yang/concurrent/` 下按章节分包的验证代码（主工程跑 JDK 17 重构版），`jdk8-lab/` 是独立的 JDK 8 子工程（经典版 AQS/ReentrantLock 实验区，与文档逐行对应）。本文件随 git 同步，任何机器打开本仓库都会加载。

## 用户学习方式（重要）
- **追问型学习者**：不接受二手解释，习惯把结论推回第一性原理；讲解必须先给因果链/设计动机，再给结论
- 反感"为解释而解释"的文档；希望主动指出文档中论证不完整处（02-03 第 2 节已按此重构为三层因果链）
- 学习法：两遍法——先通读建全景地图（疑问记入清单不深究），再统一用代码验证疑问清单
- 每学一个新机制，用 docs/00 第一性原理节的问题审查："它在压缩哪个代价、守住哪个正确性？"

## 文档导航
- 入口：`docs/00-Java并发编程学习路线-全景地图.md`（含第一性原理骨架 + 手段光谱 + 优先级统计）
- 02-03 已重构：第 2 节三层因果链、3.4 代码位置模型、4.3 假唤醒四层机制
- 03-02 §7 已补全：Condition 四步旅程机制（7.3~7.8）、经典版源码走读（7.9/7.10）、JDK 21 差异对照（7.11）

## 构建注意
- pom 目标 Java 17；若默认 `java` 是 JRE 而非 JDK，编译报 "No compiler provided" 时需设 `JAVA_HOME` 指向真实 JDK（本机具体路径见本地记忆 build-env）
- AQS 两个形态：**经典版（JDK 8~13）**——有 SIGNAL/acquireQueued，03-01/03-02 文档按此讲解；**重构版（JDK 14+）**——initialTryLock/ConditionNode/位域状态，无 SIGNAL 常量。主工程 pom 目标 17（跑重构版）；`jdk8-lab` 子工程用 JDK 8 跑经典版（与文档逐行一致）：`JAVA_HOME=/d/Learn/soft/jdk/jdk8 MAVEN_OPTS="-Dfile.encoding=UTF-8" mvn -f jdk8-lab/pom.xml test`（MAVEN_OPTS 修正输出乱码）
- 仓库里有部分"死锁演示"测试默认 `@Disabled`（非 daemon 线程会卡死构建），观察时临时启用

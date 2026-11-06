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
- 90-知识树-全局总纲：因果链骨架版全景；SVG 放大版在 `docs/assets/`，改 mermaid 代码后跑 `bash scripts/export-mermaid.sh [docs/某文档.md]` 重导（浏览器路径在 `.tmp-mermaid/pp.json`，本机 Edge，换机器需改）

## 故障排查实验室（05-05 + src/main/.../lab/ + docker/ + scripts/lab/）
> 把 05-03 的排查流程搬上真实服务器（`ssh tencent-docker`，腾讯云 Ubuntu 4核/3.6G）实操，再搬进容器讲 PID 命名空间。
- 代码：`src/main/java/com/sw/yang/concurrent/lab/`（故障注入服务，**全部挂 `@ConditionalOnProperty(lab.enabled=true)`，默认关** —— 默认关是硬要求，`ConcurrentApplicationTests` 的裸 `@SpringBootTest` 依赖它才不会加载 lab bean）
- **一切都在服务器上做（用户明确要求）**。本地机器只负责两件事、且只在必要时：`lab.sh provision`（一次性装环境）、`lab.sh build`（**仅当改了 Java 代码**才需要重新打包上传；它同时把 `start.sh`/Dockerfile/compose 一起同步上去）。**不要为了"从本地遥控"而让用户绕道本地**
- 服务器上**起服务**用 `bash ~/lab/start.sh`（源在 `scripts/lab/start.sh`，由 build 同步）。它只是包了那条 `nohup java` + 防重复起 + 等就绪。**停服务故意不给脚本** —— 就是 `jps -l` + `kill <pid>`，`jps` 本身就是排查第一步，不能包起来
- **排查动作一律不封装、不套 ssh**。`jstack`/`top -Hp`/`printf %x`/Arthas/注入故障的 curl 全是原始命令，假设用户**已在服务器 ssh 会话里**。排查本身就是学习内容，包起来等于把练习藏了；且线上第一步永远是 `jps`/`top` 自己找进程，不能跳过
- **本地那两条命令必须在 Git Bash 里跑**：PowerShell 的 `bash` 会走到 WSL（`C:\Windows\System32\bash.exe`），WSL 的 `$HOME` 下没有 Windows 的 `~/.ssh/config`，`tencent-docker` 别名解析不了，只报 `Could not resolve hostname`（看着像网络问题，实为跑错 shell）。`lab.sh` 已加 `check_host` 前置探测
- 服务器产物在 `~/lab/`
- 文档：`docs/05-05-生产环境故障排查实操.md`（含实测修正 05-03 §5.4 的 `ps -Lp` 坑、§6 坑 1 的 ReentrantLock 报告形态）
- **待办**：`lab.sh` 现在只剩 `provision` 和 `build` 是不可替代的（本地动作）。其余子命令 `start|stop|inject|clean|dump|docker-up|docker-down|pidns` 在"一切都在服务器上做"的约定下都是多余封装，应删。文档 §2.1/§3/§4 已经是服务器上的原始命令，不受影响
- 本机 git-bash 注意：`MSYS_NO_PATHCONV=1` **只能逐个 ssh/scp 命令加**，全局 export 会破坏 mvnw

## 知识树拆图（docs/9X-并发知识树-*.md）
> 学习仓库的可视化主线：因果链图，服务"两遍法"第二遍（通读建全景后，用图 + 代码验证固化因果链）。用户会审查因果链，规则必须遵守。

### 体系与命名
- `90` = 全局总纲（脊柱：第一性原理 → 手段光谱 → 四路线）；`91/92/...` = 阶段级展开，**按学习顺序拆**
- 阶段三大，拆成多张：`93`=AQS与锁、`94`=并发容器、`95`=原子类与同步工具
- 每张图 = 文档内 mermaid 代码（**唯一源**）+ `docs/assets/` 同名 SVG（生成物，提交入库，供浏览器放大看）

### 拆图原则（硬规则）
1. **因果链图，不是分类树**：节点是"问题/方案"，边是因果动词（为了/引发/代价是），禁止"a 属于 b"
2. **因果自检**：叶子→根每步能讲出"为什么存在、带来什么新问题"才算闭合
3. **先读对应文档再画**：阶段图必须与已重构文档的因果链逐条贴合（01-02 五环链、02-03 三层因果、03-01 独占流程、02-04 permit），不许凭记忆
4. **骨架从 docs/00 手段光谱长出**：消灭共享/受控串行/无锁原子/最终一致 + "压缩哪个代价、守住哪个正确性"视角
5. **阶段标签 = docs/00 知识点章节**；跨阶段机制标多阶段（如 DB 乐观锁属阶段六，标"阶段三/六"）
6. **挂验证测试**：文本骨架每节列测试类名，兼作"疑问清单→代码验证"索引
7. **自查因果错位**：画完自查是否"多个不同因果并进一条边/一个节点"、挂点是否准——用户会审查，主动修正

### 布局与维护
- 统一 `flowchart LR` + 每个 subgraph 内 `direction LR`（整体 + 小块都左右排）
- 改图：编辑 mermaid → `bash scripts/export-mermaid.sh docs/某文档.md` → 提交 md + SVG 一起
- 脚本：`scripts/export-mermaid.sh`；浏览器路径 `.tmp-mermaid/pp.json`（本机 Edge，已 gitignore，换机器改）
- 当前进度：**90/91/92/93 已拆，94/95 待拆**（继续时从 94 开始）

## 构建注意
- pom 目标 Java 17；若默认 `java` 是 JRE 而非 JDK，编译报 "No compiler provided" 时需设 `JAVA_HOME` 指向真实 JDK（本机具体路径见本地记忆 build-env）
- AQS 两个形态：**经典版（JDK 8~13）**——有 SIGNAL/acquireQueued，03-01/03-02 文档按此讲解；**重构版（JDK 14+）**——initialTryLock/ConditionNode/位域状态，无 SIGNAL 常量。主工程 pom 目标 17（跑重构版）；`jdk8-lab` 子工程用 JDK 8 跑经典版（与文档逐行一致）：`JAVA_HOME=/d/Learn/soft/jdk/jdk8 MAVEN_OPTS="-Dfile.encoding=UTF-8" mvn -f jdk8-lab/pom.xml test`（MAVEN_OPTS 修正输出乱码）
- 仓库里有部分"死锁演示"测试默认 `@Disabled`（非 daemon 线程会卡死构建），观察时临时启用

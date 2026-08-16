# 🗺️ Java 并发编程学习路线 —— 全景地图

> **适用对象**：4 年 Java 经验，需要从"会用"进阶到"能设计、排查、优化"  
> **技术环境**：Java 17 + Spring Boot 3.3.9 + Maven  
> **建议周期**：10~12 周（按优先级分两轮学，第一轮 6 周只学 ⭐⭐⭐，第二轮深挖 ◈ 和 ○）  
> **核心理念**：先建全景认知（知道有什么），再分层深入（先重点后细节）

---

## 🧭 第一性原理：一切并发机制的共同骨架

并发编程的元问题是共享可变状态；锁把并发问题变成受控的串行问题。

```
所有锁的设计，都是在正确性的硬约束（互斥/可见性/有序性）下，把串行化的代价压到最小
——时间是最主要的代价，但不是全部：
公平性（等待可预期）、可中断性（有界等待）、资源消耗（并行度/内存）也是轴
```

五个优化方向（对应下面各阶段的机制）：

| 方向 | 做法 | 典型机制 |
|---|---|---|
| ① 缩短持锁时间 | 临界区最小化 | tryLock(timeout) 快速失败、Condition 精确唤醒 |
| ② 降低竞争频率 | 细粒度 / 读写分离 / 分段 | ReadWriteLock、ConcurrentHashMap 桶锁、LongAdder Cell[]、CopyOnWrite |
| ③ 消除竞争 | 无锁化 | CAS 自旋、Atomic 类、乐观锁（版本号） |
| ④ 延迟竞争 | 利用唤醒间隙 | 非公平锁两次插队机会 |
| ⑤ 减少无效唤醒 | 定向唤醒 | 多 Condition 替代 notifyAll、signal 替代 signalAll |

完整手段光谱（"锁的本质"只是中间一段）：

| 手段 | 思路 | 代表机制 | 对应阶段 |
|---|---|---|---|
| 消灭共享 | 不让共享发生 | ThreadLocal、不可变对象 | 阶段五 |
| 受控串行 | 共享了，用锁把并发变串行 | synchronized、ReentrantLock、AQS | 阶段二/三 |
| 无锁原子 | 共享了，冲突失败重试而非阻塞 | CAS、Atomic、乐观锁（版本号） | 阶段三/六 |
| 最终一致 | 共享了，正确性约束被放宽（CAP） | 分布式事务、BASE、MQ 最终一致性 | 阶段六 |

> **框架边界**：这个本质只是光谱中的一段。ThreadLocal 不锁任何东西（它让共享不存在）；CompletableFuture 解决的是协调而非互斥；分布式锁（阶段六）是"受控串行"跨进程尺度的延伸——难点恰在正确性约束被削弱（主从切换丢锁），横跨"受控串行"与"最终一致"两格。知道框架覆盖不到哪里，比知道框架本身更高级。

> **用法：每学一个新机制，问一句"它在压缩哪个代价、守住哪个正确性？"**
> 答得出来 = 机制真懂；答不出来 = 还没闭合（这正是疑问清单的一个驱动问题）

---

## 🛠️ 日常开发高频使用速查（先看这个）

> 以下是你写业务代码时**大概率每天都会遇到**的并发组件和模式。建议先掌握这些，边用边学。

### 每天都在用

| 组件/知识点 | 典型场景 | 对应阶段 |
|---|---|---|
| `ThreadPoolExecutor` + 自定义线程池 | 任何异步任务、批量处理、接口并行调用 | 阶段四 |
| `CompletableFuture`（supplyAsync / allOf / thenApply / exceptionally） | 接口聚合、异步编排、超时降级 | 阶段四 |
| `ConcurrentHashMap`（get / put / computeIfAbsent） | 本地缓存、配置管理、并发容器 | 阶段三 |
| `synchronized`（方法/代码块） | 简单互斥、非竞争激烈场景 | 阶段二 |
| `volatile`（状态标志位） | 开关控制、shutdown 标志 | 阶段一 |
| `ThreadLocal` + try-finally remove | 上下文传递（TraceId、用户信息）、数据库事务 | 阶段五 |
| `AtomicInteger` / `LongAdder` | 计数器、统计、限流计数 | 阶段三 |
| `ReentrantLock` + try-finally unlock | 需要 Condition / tryLock 超时 / 公平锁 | 阶段三 |

### 每周都在用

| 组件/知识点 | 典型场景 | 对应阶段 |
|---|---|---|
| `CountDownLatch` | 等待多个异步任务完成再汇总 | 阶段三 |
| `Semaphore` | 限流、连接池控制 | 阶段三 |
| `LinkedBlockingQueue` / `ArrayBlockingQueue` | 生产者-消费者、任务队列 | 阶段三 |
| `CopyOnWriteArrayList` | 监听器列表、黑名单、配置 | 阶段三 |
| `CyclicBarrier` | 多阶段并行计算、游戏房间等待 | 阶段三 |
| `ThreadPoolExecutor` 优雅停机（shutdown + awaitTermination） | 应用关闭时的资源释放 | 阶段四 |
| 线程池监控（activeCount / queueSize） | 运行时健康检查 | 阶段四 |
| DCL 单例（静态内部类/枚举） | 全局配置对象、连接池、工厂 | 阶段五 |
| Redis 分布式锁 + Lua 脚本释放 | 定时任务互斥、库存扣减、幂等控制 | 阶段六 |
| jstack + Arthas thread 排查 | 线上死锁、CPU 飙高、线程阻塞 | 阶段五 |

### 每月都在用

| 组件/知识点 | 典型场景 | 对应阶段 |
|---|---|---|
| `StampedLock` / `ReadWriteLock` | 读多写少的缓存场景 | 阶段三 |
| `DelayQueue` / `ScheduledThreadPoolExecutor` | 定时任务、延迟队列、订单超时取消 | 阶段三/四 |
| `ForkJoinPool` | CPU 密集型批量计算 | 阶段四 |
| 数据库乐观锁（version 字段） | 并发更新、库存扣减 | 阶段六 |
| 雪花算法 ID 生成 | 分布式主键 | 阶段六 |
| `ThreadMXBean` 死锁检测 | 自动化死锁告警 | 阶段五 |

---

## 📖 阅读指南

### 优先级标记说明

| 标记 | 含义 | 学习策略 |
|---|---|---|
| 🛠️ | **日常高频** | 写业务代码几乎天天用到，优先熟练掌握 API 和常见坑 |
| ⭐⭐⭐ | **核心必学** | 必须深入理解原理 + 手写代码 + 能画图解释，面试高频 + 生产常用 |
| ◈◈ | **重点掌握** | 理解原理，知道使用场景和选型依据，能写出正确代码 |
| ○ | **了解即可** | 知道有这个机制/工具，理解它解决什么问题，用到时能快速查文档深入 |
| 🔖 | **选学进阶** | 偏底层或偏冷门，学有余力或遇到相关问题时再深挖 |

> **三层过滤阅读法**：🛠️（先用起来）→ ⭐⭐⭐（深入原理）→ ◈◈ + ○（扩展体系）

### 两轮学习策略

```
第一轮（4~6 周）：只学 ⭐⭐⭐，快速建立核心能力，能干活、能面试
第二轮（4~6 周）：补 ◈◈ 和 ○，建立完整知识体系，能设计、能排坑
```

---

## 阶段一：并发基础理论与 JMM 内存模型

> **目标**：建立并发编程的理论基石 —— 理解并发 bug 的三大根源，能用 JMM 模型解释可见性/原子性/有序性问题

### 1.1 并发与并行基础

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 1.1.1 | 并发 vs 并行的概念区别 | ⭐⭐⭐ | 基础概念，面试必问 |
| 1.1.2 | CPU 核心数、时间片轮转、线程调度 | ○ | 操作系统层面的背景知识 |
| 1.1.3 | Java 线程与 OS 线程的映射关系（1:1 内核线程模型） | ○ | 知道即可，不影响日常开发 |
| 1.1.4 | 用户态与内核态切换开销 | ○ | 理解 Synchronized 重量级锁为何"重"的前提 |
| 1.1.5 | 并发问题的三大根源：缓存一致性、指令重排序、原子操作被中断 | ⭐⭐⭐ | **核心**：后续所有知识的理论基石 |

### 1.2 Java 内存模型（JMM）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 1.2.1 | JMM 抽象结构：主内存 + 工作内存 | ⭐⭐⭐ | 能画图解释线程间数据交互 |
| 1.2.2 | JMM 与 JVM 运行时数据区（堆/栈/方法区）的区别 | ⭐⭐⭐ | 很多人混淆这两个概念 |
| 1.2.3 | 可见性（Visibility）的定义与问题演示 | ⭐⭐⭐ | 一个线程修改，另一个看不到 |
| 1.2.4 | 原子性（Atomicity）的定义与问题演示 | ⭐⭐⭐ | 复合操作被打断（i++ 字节码分析） |
| 1.2.5 | 有序性（Ordering）的定义与问题演示 | ⭐⭐⭐ | 指令重排序导致 DCL 半初始化 |
| 1.2.6 | **happens-before 规则详解（8 条）** | ⭐⭐⭐ | 面试最爱问，每条都要能举例 |
| 1.2.7 | happens-before 的传递性 | ⭐⭐⭐ | 推导复杂场景的可见性 |
| 1.2.8 | as-if-serial 语义（单线程内） | ◈◈ | 理解编译器优化的前提 |
| 1.2.9 | 数据依赖性（读后写/写后写/写后读） | ○ | 重排序不会破坏数据依赖性 |
| 1.2.10 | 指令重排序的三种类型：编译器重排序、指令级并行重排序、内存系统重排序 | ○ | 知道有这几层即可 |

### 1.3 CPU 缓存与内存屏障

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 1.3.1 | CPU 多级缓存架构（L1/L2/L3 Cache） | ◈◈ | 理解为什么会有可见性问题 |
| 1.3.2 | 缓存行（Cache Line，64 字节） | ◈◈ | 理解伪共享的前置知识 |
| 1.3.3 | MESI 缓存一致性协议 | ○ | 4 种状态（Modified/Exclusive/Shared/Invalid），知道概念 |
| 1.3.4 | Store Buffer 与 Invalidate Queue | 🔖 | 理解内存屏障为什么需要 |
| 1.3.5 | 内存屏障（Memory Barrier）分类：Load Barrier / Store Barrier / Full Barrier | ◈◈ | volatile 底层依赖这些 |
| 1.3.6 | x86 的 3 种内存屏障指令：sfence / lfence / mfence | 🔖 | 知道 volatile 在 x86 上用的 lock 前缀 |

### 1.4 volatile 关键字

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 1.4.1 | volatile 的内存语义（写 = 刷新到主存，读 = 强制从主存读） | 🛠️ ⭐⭐⭐ | 核心，必须能自己讲清楚 |
| 1.4.2 | volatile 的 happens-before 规则（写先于读） | 🛠️ ⭐⭐⭐ | 单条 volatile 变量的保证 |
| 1.4.3 | volatile 禁止指令重排序的原理（内存屏障插入策略） | ⭐⭐⭐ | StoreStore → StoreLoad → LoadLoad → LoadStore |
| 1.4.4 | volatile 不保证原子性（i++ 问题） | 🛠️ ⭐⭐⭐ | 经典陷阱 |
| 1.4.5 | volatile 适用场景：状态标志位、DCL 单例、CAS 中的 value | 🛠️ ⭐⭐⭐ | 每个都要能写出示例代码 |
| 1.4.6 | volatile 不适用场景：依赖当前值的操作、多变量联动 | ◈◈ | 知道什么时候不能用 |
| 1.4.7 | volatile 与 final 的内存语义对比 | ○ | 两者都能保证不同层面的可见性 |
| 1.4.8 | volatile 在 JDK 中的实际应用（AQS state、FutureTask state 等） | ◈◈ | 看源码时会频繁遇到 |

### 1.5 CAS 入门

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 1.5.1 | CAS（Compare And Swap）基本思想与三个操作数 | ⭐⭐⭐ | AQS 和 Atomic 的基石 |
| 1.5.2 | Unsafe 类的 CAS 方法（compareAndSwapInt/Object/Long） | ⭐⭐⭐ | JDK 内部的底层操作 |
| 1.5.3 | CAS 自旋的实现模式 | ⭐⭐⭐ | 无锁并发的基本范式 |
| 1.5.4 | ABA 问题：产生原因、危害（栈的场景）、解决方案 | ◈◈ | 知道概念 + AtomicStampedReference |
| 1.5.5 | CAS 的 CPU 开销与适用场景 | ◈◈ | 竞争激烈时 CAS 不如锁 |

### 📖 阶段一推荐源码阅读

| 类 | 重点方法 | 优先级 |
|---|---|---|
| `Thread.java` | 线程状态枚举（6 种）、`start()`、`join()` | ⭐⭐⭐ |
| `Object.java` | `wait()` / `notify()` / `notifyAll()` 的 Javadoc | ⭐⭐⭐ |
| `Unsafe.java` | `compareAndSwapInt()` / `compareAndSwapObject()` | ◈◈ |

### 🧪 阶段一必做练习

1. ⭐⭐⭐ 编写可见性问题复现代码 + volatile 修复 + 解释原因
2. ⭐⭐⭐ 编写原子性问题复现代码（10 线程各 10000 次 `volatile int count++`）+ 用 AtomicInteger 修复
3. ⭐⭐⭐ 手写 DCL 单例 + 说明为何 instance 必须 volatile
4. ◈◈ 使用 JITWatch 观察有无 volatile 时的汇编指令差异（可选）

### ✅ 阶段一自测题

1. 用自己的话解释 happens-before 的"传递性"，并举一个代码例子
2. `volatile int count = 0;` 两个线程各执行 10000 次 `count++`，最终结果是多少？为什么？
3. DCL 单例中，`instance = new Singleton()` 这个赋值语句在字节码层面是原子的吗？如果不是，volatile 如何保证安全？
4. JMM 的"主内存"和 JVM 的"堆内存"是同一个概念吗？为什么？

---

## 阶段二：Synchronized 与锁机制底层原理

> **目标**：掌握 Synchronized 从使用到底层实现的全链路，能从 Mark Word 变化追踪锁状态，会排查死锁

### 2.1 Synchronized 使用全景

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.1.1 | 三种加锁形态：实例方法、静态方法、代码块 | 🛠️ ⭐⭐⭐ | 基础中的基础 |
| 2.1.2 | 不同形态锁的对象分别是什么（this / Class 对象 / 指定对象） | 🛠️ ⭐⭐⭐ | 经典面试题 |
| 2.1.3 | 字节码层面：`monitorenter` / `monitorexit` 指令 | ⭐⭐⭐ | 用 javap -v 实操 |
| 2.1.4 | 异常自动释放锁（隐式 monitorexit 插入） | 🛠️ ◈◈ | Synchronized 相比 Lock 的优势之一 |
| 2.1.5 | 可重入性（同一线程可重复获取同一把锁） | ⭐⭐⭐ | 理解锁计数器 |
| 2.1.6 | Synchronized 与 Lock 的 5 大区别对比表 | 🛠️ ⭐⭐⭐ | 面试高频，日常选型依据 |

### 2.2 对象头与 Mark Word

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.2.1 | Java 对象内存布局：对象头 + 实例数据 + 对齐填充 | ⭐⭐⭐ | 基础 |
| 2.2.2 | 对象头结构：Mark Word + Klass Pointer（压缩指针） | ⭐⭐⭐ | 32 位 vs 64 位 JVM 差异 |
| 2.2.3 | **Mark Word 五种状态的位结构（重点！）** | ⭐⭐⭐ | 能手画每种状态的位分布图 |
| 2.2.4 | 无锁状态：hash + age（分代年龄）+ biased_lock(0) + 01 | ⭐⭐⭐ | 初始状态 |
| 2.2.5 | 偏向锁状态：thread_id + epoch + age + biased_lock(1) + 01 | ⭐⭐⭐ | 记录偏向线程 ID |
| 2.2.6 | 轻量级锁状态：指向栈中 Lock Record 的指针 + 00 | ⭐⭐⭐ | CAS 设置指针 |
| 2.2.7 | 重量级锁状态：指向 ObjectMonitor 的指针 + 10 | ⭐⭐⭐ | 内核态阻塞 |
| 2.2.8 | GC 标记状态：11 | ○ | CMS 标记阶段使用 |
| 2.2.9 | 使用 JOL 工具查看对象内存布局 | ⭐⭐⭐ | 动手实操 |
| 2.2.10 | 压缩指针（CompressedOops）对对象头大小的影响 | ○ | 知道 64 位默认开启压缩 |

### 2.3 锁升级（膨胀）全链路

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.3.1 | **锁升级完整路径图（核心中的核心）** | ⭐⭐⭐ | 能口述全程 + 画图 |
| 2.3.2 | 偏向锁获取过程 | ⭐⭐⭐ | 检查 Mark Word 的 thread_id |
| 2.3.3 | 偏向锁撤销（Revoke）：时机（调用 hashCode / 竞争 / 批量操作） | ⭐⭐⭐ | 撤销需要在 safe point 执行，开销大 |
| 2.3.4 | 批量重偏向（Bulk Rebias）与批量撤销（Bulk Revoke）机制 | ◈◈ | JVM 的批量优化策略 |
| 2.3.5 | 偏向锁延迟（BiasedLockingStartupDelay，默认 4 秒） | ◈◈ | 知道 JVM 启动时偏向锁不立即生效 |
| 2.3.6 | JDK 15 默认禁用偏向锁、JDK 21 彻底废弃 | ◈◈ | 现代 JVM 趋势 |
| 2.3.7 | 轻量级锁获取（CAS 竞争 Lock Record 指针） | ⭐⭐⭐ | 自旋等待 |
| 2.3.8 | 轻量级锁膨胀为重量级锁的条件（自旋超限 / 等待队列出现） | ⭐⭐⭐ | 竞争加剧时升级 |
| 2.3.9 | 自旋锁（Spin Lock）与自适应自旋（Adaptive Spinning） | ◈◈ | JDK 6 引入的优化 |
| 2.3.10 | 重量级锁的 ObjectMonitor 结构：_owner / _WaitSet / _EntryList | ⭐⭐⭐ | wait/notify 的数据结构基础 |

### 2.4 JIT 编译器对锁的优化

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.4.1 | 锁消除（Lock Elimination）：逃逸分析判定 | ◈◈ | `StringBuffer.append` 在单线程中消除锁 |
| 2.4.2 | 锁粗化（Lock Coarsening）：连续加锁合并 | ◈◈ | 循环体内的 synchronized 可能被粗化 |
| 2.4.3 | 逃逸分析（Escape Analysis）的三个层面 | ◈◈ | 栈上分配 + 标量替换 + 锁消除 |
| 2.4.4 | `-XX:+DoEscapeAnalysis` 相关 JVM 参数 | ○ | 调优时了解 |

### 2.5 wait / notify / notifyAll 机制

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.5.1 | **wait/notify 的正确范式（while 循环 + 条件检查）** | 🛠️ ⭐⭐⭐ | 生产代码必须这样写 |
| 2.5.2 | `wait()` 为何必须在 `synchronized` 块内调用 | ⭐⭐⭐ | 条件判断与等待的原子性 |
| 2.5.3 | `wait()` 释放锁，`sleep()` 不释放锁 | 🛠️ ⭐⭐⭐ | 经典面试题 |
| 2.5.4 | `notify()` vs `notifyAll()`：假唤醒（Spurious Wakeup） | ⭐⭐⭐ | 防御性编程 |
| 2.5.5 | wait(timeout) 的超时语义 | ◈◈ | 避免永久等待 |
| 2.5.6 | ObjectMonitor 中 _WaitSet 与 _EntryList 的流转 | ◈◈ | 被 notify 的线程进入 _EntryList 而非直接获取锁 |

### 2.6 LockSupport 与 park/unpark 线程阻塞原语

> 对应文档：[02-04-LockSupport与park机制](./02-04-LockSupport与park机制.md)——AQS 阻塞线程的唯一工具，permit 机制是"不丢唤醒"的第一性原理

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.6.1 | **permit（许可）机制：unpark 可提前，唤醒信号保存到消费** | ⭐⭐⭐ | AQS 所有唤醒设计的根基 |
| 2.6.2 | 与 wait/notify 的系统对比（依赖/信号保存/中断/超时）| ⭐⭐⭐ | 两条"不丢唤醒"路线 |
| 2.6.3 | park 的中断响应：返回但不抛异常、标志保留 | ⭐⭐⭐ | parkAndCheckInterrupt 空转问题的根源 |
| 2.6.4 | 伪唤醒：park 可能无缘无故返回 → 必须 while 重查 | ◈◈ | 与 wait 的假唤醒防御同构 |
| 2.6.5 | Unsafe.park 底层与跨平台实现 | ○ | 记住 permit 是语义抽象即可 |

### 2.7 死锁

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 2.7.1 | 死锁的四个必要条件 | ⭐⭐⭐ | 互斥/持有并等待/不可抢占/循环等待 |
| 2.7.2 | 预防死锁的三种策略 | 🛠️ ⭐⭐⭐ | 锁排序 / tryLock 超时 / 死锁检测 |
| 2.7.3 | jstack 死锁检测实操 | 🛠️ ⭐⭐⭐ | `jstack -l <pid>` 看 "Found one Java-level deadlock" |
| 2.7.4 | JConsole / JVisualVM 死锁检测 | ◈◈ | 图形化工具 |
| 2.7.5 | Java API 死锁检测：`ThreadMXBean.findDeadlockedThreads()` | ◈◈ | 程序化检测 |

### 📖 阶段二推荐源码阅读

| 源码 | 重点 | 优先级 |
|---|---|---|
| `synchronizer.cpp` (HotSpot) | 偏向锁撤销、锁膨胀核心逻辑 | ◈◈ |
| `markWord.hpp` (HotSpot) | Mark Word 位定义 | ◈◈ |
| `ObjectMonitor` (HotSpot) | _owner / _WaitSet / _EntryList | ◈◈ |
| `Object.java` (JDK) | wait/notify/notifyAll 的 native 声明 | ⭐⭐⭐ |

### 🧪 阶段二必做练习

1. ⭐⭐⭐ 用 JOL 分别打印无锁/偏向锁/轻量级锁/重量级锁的 Mark Word，对比差异
2. ⭐⭐⭐ `LinkedList<Object>`（容量 10）+ 2 生产者 + 3 消费者，wait/notifyAll 实现
3. ⭐⭐⭐ 故意构造死锁 → jstack 排查 → `Found one Java-level deadlock` 输出解读 → 修复

### ✅ 阶段二自测题

1. Synchronized 修饰静态方法和实例方法，两个线程同时分别调用会互斥吗？
2. 画出 64 位 JVM 无锁状态 Mark Word 的位分布图
3. 偏向锁撤销为什么需要在 safe point？撤销一次的开销比轻量级锁大还是小？
4. `Object.wait()` 和 `Thread.sleep()` 有哪些核心区别（至少列 3 点）？

---

## 阶段三：JUC 并发工具集源码级解析

> **目标**：深入 AQS 框架核心，掌握 JUC 锁、并发容器、原子类、同步工具的原理与选型  
> **这是整个学习路线中最核心、最重要的阶段**

### 3.1 AQS（AbstractQueuedSynchronizer）—— JUC 的灵魂

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.1.1 | AQS 是什么：构建锁和同步器的框架 | ⭐⭐⭐ | 模板方法模式 |
| 3.1.2 | AQS 核心三要素：state（volatile）+ CLH 队列 + Node | ⭐⭐⭐ | 理解这三个就理解了 AQS |
| 3.1.3 | **state 变量**：volatile 修饰，CAS 修改，含义由子类定义 | ⭐⭐⭐ | 可重入次数/许可证数等 |
| 3.1.4 | **Node 内部类**：prev/next/thread/waitStatus | ⭐⭐⭐ | CLH 队列的节点 |
| 3.1.5 | Node 的 5 种 waitStatus：CANCELLED(1)/SIGNAL(-1)/CONDITION(-2)/PROPAGATE(-3)/0 | ⭐⭐⭐ | 每种状态的含义和转换 |
| 3.1.6 | **独占模式 acquire 全流程**：acquire → tryAcquire → addWaiter → acquireQueued → selfInterrupt | ⭐⭐⭐ | **必须能画流程图 + 逐行解释源码** |
| 3.1.7 | `addWaiter()`：CAS 入队，快速路径 + 完整路径 | ⭐⭐⭐ | prev 指针的 CAS |
| 3.1.8 | `acquireQueued()`：前驱是 head 才 try，否则 park | ⭐⭐⭐ | `shouldParkAfterFailedAcquire` 设置 SIGNAL |
| 3.1.9 | `release()` 流程：tryRelease → unparkSuccessor | ⭐⭐⭐ | 唤醒后继节点 |
| 3.1.10 | **共享模式 acquireShared 全流程** | ⭐⭐⭐ | doAcquireShared + setHeadAndPropagate |
| 3.1.11 | `setHeadAndPropagate` 的传播机制 | ◈◈ | 共享锁的核心，Semaphore/CountDownLatch 依赖此机制 |
| 3.1.12 | 独占模式 vs 共享模式的区别与适用场景 | ⭐⭐⭐ | 互斥 vs 共享 |
| 3.1.13 | `ConditionObject` 条件队列 | ⭐⭐⭐ | |
| 3.1.14 | `await()` 流程：释放锁 → 加入条件队列 → park | ⭐⭐⭐ | |
| 3.1.15 | `signal()` 流程：从条件队列移到同步队列队尾 → unpark | ⭐⭐⭐ | |
| 3.1.16 | Condition 的 `signalAll()` vs Object 的 `notifyAll()` | ◈◈ | |
| 3.1.17 | 可中断获取：`acquireInterruptibly` → `doAcquireInterruptibly` | ◈◈ | 被中断抛 InterruptedException |
| 3.1.18 | 可超时获取：`tryAcquireNanos` → `doAcquireNanos` | ◈◈ | |
| 3.1.19 | 取消获取：`cancelAcquire` 的节点清理逻辑 | ○ | |

### 3.2 ReentrantLock 源码解析

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.2.1 | ReentrantLock 的类结构：继承 AQS，公平/非公平两种 Sync | 🛠️ ⭐⭐⭐ | |
| 3.2.2 | **公平锁 FairSync.tryAcquire**：先检查 hasQueuedPredecessors | ⭐⭐⭐ | 有前驱则排队 |
| 3.2.3 | **非公平锁 NonfairSync.tryAcquire**：直接 CAS 抢锁 | ⭐⭐⭐ | 两次抢锁机会（lock 时 + tryAcquire 时） |
| 3.2.4 | 可重入实现：`getExclusiveOwnerThread()` + state 累加 | ⭐⭐⭐ | |
| 3.2.5 | `lock()` vs `lockInterruptibly()` vs `tryLock()` vs `tryLock(timeout)` | 🛠️ ⭐⭐⭐ | 四种获取锁方式的使用场景 |
| 3.2.6 | `unlock()` 实现：state 递减 → 0 则 setExclusiveOwnerThread(null) | 🛠️ ⭐⭐⭐ | |
| 3.2.7 | **公平锁 vs 非公平锁的性能差异原因** | ⭐⭐⭐ | 减少线程切换开销 vs 避免饥饿 |
| 3.2.8 | Condition 的使用：`newCondition()` → `await()` → `signal()` | 🛠️ ⭐⭐⭐ | |
| 3.2.9 | **ReentrantLock vs Synchronized 全面对比（选型决策表）** | 🛠️ ⭐⭐⭐ | 面试必问 |

### 3.3 ReentrantReadWriteLock 与 StampedLock

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.3.1 | 读写锁设计思想：读读不互斥、读写互斥、写写互斥 | 🛠️ ⭐⭐⭐ | |
| 3.3.2 | **state 高低位拆分**：高 16 位存读锁计数，低 16 位存写锁计数 | ⭐⭐⭐ | `sharedCount()` / `exclusiveCount()` |
| 3.3.3 | 读锁获取（`tryAcquireShared`）：无写锁 + 无等待写者 → CAS 读计数 | ⭐⭐⭐ | 有等待写者则排队（避免写者饥饿） |
| 3.3.4 | 写锁获取（`tryAcquire`）：无读锁 + 无其他写者 → CAS 写计数 | ⭐⭐⭐ | |
| 3.3.5 | **写锁降级为读锁**的正确写法与必要性 | 🛠️ ⭐⭐⭐ | 先获取写锁 → 再获取读锁 → 释放写锁 |
| 3.3.6 | 读锁升级为写锁（不支持，会死锁） | ◈◈ | |
| 3.3.7 | HoldCounter + ThreadLocal 记录每个线程的读锁重入次数 | ○ | |
| 3.3.8 | 写者饥饿问题与处理策略 | ◈◈ | |
| 3.3.9 | **StampedLock（JDK 8+）**：乐观读/悲观读/写三种模式 | ◈◈ | |
| 3.3.10 | `tryOptimisticRead()`：零锁读 + `validate()` 校验 | ◈◈ | 乐观读期间无锁 |
| 3.3.11 | StampedLock 不可重入的原因与设计考量 | ◈◈ | |
| 3.3.12 | StampedLock vs ReadWriteLock 性能对比 | ◈◈ | StampedLock 写多读少场景更优 |
| 3.3.13 | Synchronized vs ReentrantLock vs ReadWriteLock vs StampedLock 选型决策 | 🛠️ ⭐⭐⭐ | 知道各自最佳场景 |

### 3.4 并发容器源码分析

#### 3.4.1 ConcurrentHashMap（重点）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.4.1.1 | JDK 7 分段锁设计：Segment[] + HashEntry[]，并发度 = segment 数量 | ◈◈ | 理解演进历史 |
| 3.4.1.2 | JDK 7 ConcurrentHashMap 的问题：Segment 粒度粗、初始化开销大 | ○ | |
| 3.4.1.3 | **JDK 8 彻底重构为 CAS + synchronized** | 🛠️ ⭐⭐⭐ | 更细粒度 = 更高并发 |
| 3.4.1.4 | **sizeCtl 变量的多重角色**：初始化(-1)/表大小/扩容阈值/扩容线程数 | ⭐⭐⭐ | 一个变量承担多种职责 |
| 3.4.1.5 | `initTable()`：CAS 设置 sizeCtl 为 -1 竞争初始化权 | ⭐⭐⭐ | |
| 3.4.1.6 | **`putVal()` 全流程**：spread hash → 空桶 CAS 插入 → 非空桶 synchronized 头节点 → 链表/红黑树 | 🛠️ ⭐⭐⭐ | 能画完整流程图 |
| 3.4.1.7 | 链表转红黑树阈值：TREEIFY_THRESHOLD = 8 | ⭐⭐⭐ | 为什么是 8？（泊松分布） |
| 3.4.1.8 | 红黑树退化为链表：UNTREEIFY_THRESHOLD = 6 | ◈◈ | 为什么不是 8？（避免反复转换） |
| 3.4.1.9 | 最小树化容量：MIN_TREEIFY_CAPACITY = 64 | ◈◈ | |
| 3.4.1.10 | **`transfer()` 多线程协同扩容**：ForwardingNode 标记已迁移桶 | ⭐⭐⭐ | 每个线程领取 stride 个桶迁移 |
| 3.4.1.11 | `helpTransfer()`：put 时发现正在扩容则协助 | ◈◈ | |
| 3.4.1.12 | `addCount()` + `size()`：baseCount + CounterCell[] 机制 | ◈◈ | 类似 LongAdder 的分段计数 |
| 3.4.1.13 | `get()` 不加锁的原因（Node.val 是 volatile） | 🛠️ ⭐⭐⭐ | |
| 3.4.1.14 | ConcurrentHashMap 的 key/value 为什么不能为 null | ◈◈ | HashMap 可以，这是故意的设计 |

#### 3.4.2 CopyOnWriteArrayList

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.4.2.1 | 写时复制（COW）机制：`add()` → `Arrays.copyOf()` → 新数组 | 🛠️ ⭐⭐⭐ | |
| 3.4.2.2 | ReentrantLock 保护写操作，读操作无锁 | ⭐⭐⭐ | |
| 3.4.2.3 | 迭代器的快照特性：不抛 ConcurrentModificationException | ⭐⭐⭐ | 遍历的是旧数组 |
| 3.4.2.4 | 适用场景：读多写极少（配置、黑名单、监听器列表） | 🛠️ ⭐⭐⭐ | |
| 3.4.2.5 | 局限性：内存开销（双份数组）、实时性弱（读操作可能读到旧数据） | 🛠️ ⭐⭐⭐ | |

#### 3.4.3 BlockingQueue 体系

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.4.3.1 | BlockingQueue 接口方法：put/take（阻塞）vs offer/poll（非阻塞） | 🛠️ ⭐⭐⭐ | |
| 3.4.3.2 | **ArrayBlockingQueue**：数组 + 单锁（ReentrantLock）+ notEmpty/notFull | 🛠️ ⭐⭐⭐ | 有界、公平模式可选 |
| 3.4.3.3 | **LinkedBlockingQueue**：链表 + 双锁（takeLock/putLock 分离） | 🛠️ ⭐⭐⭐ | 更高吞吐量 |
| 3.4.3.4 | ArrayBlockingQueue vs LinkedBlockingQueue 对比 | ◈◈ | 单锁 vs 双锁、数组 vs 链表 |
| 3.4.3.5 | **SynchronousQueue**：零容量，公平（TransferQueue）/非公平（TransferStack） | ◈◈ | newCachedThreadPool 使用 |
| 3.4.3.6 | **PriorityBlockingQueue**：无界 + 二叉堆排序 | ◈◈ | 优先级任务调度 |
| 3.4.3.7 | **DelayQueue**：PriorityQueue + Leader-Follower 模式 | ◈◈ | 定时任务、缓存过期 |
| 3.4.3.8 | **LinkedTransferQueue**：transfer 方法（JDK 7 加入） | ○ | 高性能无界队列，Netty 使用 |
| 3.4.3.9 | 5 种 BlockingQueue 的选型对比表 | 🛠️ ⭐⭐⭐ | |

#### 3.4.4 ConcurrentLinkedQueue（了解）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.4.4.1 | 基于 Michael-Scott 无锁算法 | ○ | |
| 3.4.4.2 | CAS 操作实现无锁并发 | ○ | 知道有这个东西就行 |

#### 3.4.5 ConcurrentSkipListMap / ConcurrentSkipListSet（了解）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.4.5.1 | 跳表（SkipList）数据结构 | ○ | 知道是 ConcurrentHashMap 的有序替代 |
| 3.4.5.2 | 与 ConcurrentHashMap 的区别（有序 vs 无序） | ○ | |

### 3.5 原子类（Atomic 系列）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.5.1 | AtomicInteger / AtomicLong / AtomicBoolean | 🛠️ ⭐⭐⭐ | |
| 3.5.2 | **`getAndIncrement()` 的 CAS 自旋循环源码** | 🛠️ ⭐⭐⭐ | 无锁自旋的经典模式 |
| 3.5.3 | `compareAndSet` vs `weakCompareAndSet` | ◈◈ | |
| 3.5.4 | AtomicIntegerArray / AtomicLongArray / AtomicReferenceArray | ◈◈ | |
| 3.5.5 | AtomicReference（无锁栈、无锁链表的实现基础） | ◈◈ | |
| 3.5.6 | AtomicStampedReference：ABA 问题的解决方案 | ◈◈ | 版本号（stamp） |
| 3.5.7 | AtomicMarkableReference：布尔标记解决 ABA | ○ | |
| 3.5.8 | AtomicIntegerFieldUpdater / AtomicLongFieldUpdater / AtomicReferenceFieldUpdater | ○ | 减少对象创建开销 |
| 3.5.9 | **LongAdder / DoubleAdder（JDK 8+）** | 🛠️ ⭐⭐⭐ | |
| 3.5.10 | LongAdder 的 Cell[] 分段累加设计 | 🛠️ ⭐⭐⭐ | 高并发远超 AtomicLong |
| 3.5.11 | LongAdder 的 `add()` / `sum()` / `reset()` 源码 | ◈◈ | add 定位 Cell，sum 合并所有 Cell |
| 3.5.12 | LongAdder 的扩容与伪共享处理 | ◈◈ | |
| 3.5.13 | **伪共享（False Sharing）+ @Contended 注解** | 🛠️ ⭐⭐⭐ | Cell 类的 @Contended 实际应用 |
| 3.5.14 | LongAccumulator / DoubleAccumulator | ○ | LongAdder 的通用化版本 |
| 3.5.15 | **AtomicLong vs LongAdder 选型**：低竞争用 AtomicLong，高竞争用 LongAdder | 🛠️ ⭐⭐⭐ | 注意 LongAdder 的 sum() 不是强一致的 |

### 3.6 同步工具类

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 3.6.1 | **CountDownLatch**：AQS 共享模式，countDown→releaseShared，await→acquireShared | 🛠️ ⭐⭐⭐ | |
| 3.6.2 | CountDownLatch 典型场景：多任务汇总、启动门 | 🛠️ ⭐⭐⭐ | |
| 3.6.3 | CountDownLatch 不可重置，用一次就废 | ◈◈ | |
| 3.6.4 | **CyclicBarrier**：ReentrantLock + Condition + Generation | 🛠️ ⭐⭐⭐ | |
| 3.6.5 | CyclicBarrier 的 `await()` 与 Generation 破坏 | ◈◈ | 超时或中断会破坏当前代 |
| 3.6.6 | CyclicBarrier vs CountDownLatch 对比（可重置/计数方式/使用场景） | ⭐⭐⭐ | |
| 3.6.7 | **Semaphore**：AQS 共享模式，许可证管理 | 🛠️ ⭐⭐⭐ | |
| 3.6.8 | Semaphore 公平/非公平模式 | ◈◈ | |
| 3.6.9 | Semaphore 典型场景：限流、连接池控制 | 🛠️ ⭐⭐⭐ | |
| 3.6.10 | **Exchanger**：配对线程数据交换，Slot + CAS + park/unpark | ○ | JDK 并发工具中最复杂的源码 |
| 3.6.11 | Exchanger 典型场景：遗传算法、数据校对 | ○ | |
| 3.6.12 | **Phaser**（JDK 7+）：多阶段同步屏障，动态注册/注销 | ○ | CyclicBarrier 的增强版 |
| 3.6.13 | Phaser 的 arrive / arriveAndAwaitAdvance / arriveAndDeregister | ○ | |

### 📖 阶段三推荐源码阅读

| 类 | 重点方法 | 优先级 | 预估行数 |
|---|---|---|---|
| `AbstractQueuedSynchronizer` | acquire/release/acquireShared/releaseShared | ⭐⭐⭐ | ~500（核心链路） |
| `ReentrantLock` | Sync/NonfairSync/FairSync 的 tryAcquire | ⭐⭐⭐ | ~100 |
| `ReentrantReadWriteLock` | Sync 的 tryAcquireShared/tryAcquire | ⭐⭐⭐ | ~200 |
| `ConcurrentHashMap` | putVal/initTable/transfer/addCount | ⭐⭐⭐ | ~1000 |
| `ArrayBlockingQueue` | enqueue/dequeue + Condition 协作 | ◈◈ | ~200 |
| `CountDownLatch` | AQS 共享模式应用 | ◈◈ | ~100 |
| `LongAdder` | add/sum/Cell 结构 | ◈◈ | ~300 |
| `CopyOnWriteArrayList` | add/set/getArray | ◈◈ | ~100 |

### 🧪 阶段三必做练习

1. ⭐⭐⭐ 基于 AQS 实现自定义 Mutex 互斥锁（独占模式），支持 lock/unlock
2. ⭐⭐⭐ 基于 AQS 实现自定义 Semaphore（共享模式），支持 acquire/release
3. ⭐⭐⭐ 用 Lock + 双 Condition 实现有界阻塞队列（ArrayBlockingQueue 简化版）
4. ⭐⭐⭐ JMH 压测：AtomicLong vs LongAdder vs synchronized long count++，三种竞争强度
5. ◈◈ 手写简易 ConcurrentHashMap（只实现 get/put，CAS + synchronized 桶锁）

### ✅ 阶段三自测题

1. AQS 的 `acquireQueued` 方法中，为什么只有当前驱节点是 head 时才会 `tryAcquire`？
2. 非公平锁为什么在 `lock()` 和 `tryAcquire()` 中分别有一次 CAS 抢锁？少一次会怎样？
3. ConcurrentHashMap JDK 8 为什么用 `synchronized` 替代 JDK 7 的 Segment + `ReentrantLock`？
4. `CountDownLatch` 和 `CyclicBarrier` 分别基于 AQS 的什么模式？为什么 CountDownLatch 不能复用？
5. `LongAdder` 的 `sum()` 方法为什么不是原子的？（提示：并发写 Cell + 非原子累加）

---

## 阶段四：线程池与异步编程高阶实战

> **目标**：掌握线程池的设计原理、参数调优与监控，熟练使用 CompletableFuture 进行复杂异步编排

### 4.1 ThreadPoolExecutor 源码全解析

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.1.1 | **ThreadPoolExecutor 的 7 大核心参数详解** | 🛠️ ⭐⭐⭐ | 必须能手写每个参数的含义 |
| 4.1.2 | **ctl 变量的巧妙设计**：高 3 位存运行状态 + 低 29 位存工作线程数 | ⭐⭐⭐ | 一个 AtomicInteger 存两种信息 |
| 4.1.3 | 5 种运行状态：RUNNING/SHUTDOWN/STOP/TIDYING/TERMINATED | ⭐⭐⭐ | 状态流转图要能画 |
| 4.1.4 | 运行状态的大小关系（RUNNING < SHUTDOWN < STOP < TIDYING < TERMINATED） | ◈◈ | ctl 的位运算比较技巧 |
| 4.1.5 | **`execute()` 方法全流程**：核心→队列→最大→拒绝 | 🛠️ ⭐⭐⭐ | 最核心的方法，逐行解读 |
| 4.1.6 | `addWorker()`：新建 Worker 的完整过程（firstTask + core 标记） | ⭐⭐⭐ | 双重检查运行状态 + CAS 增加线程数 |
| 4.1.7 | **Worker 内部类**：继承 AQS + 实现 Runnable | ⭐⭐⭐ | Worker 本身就是一把不可重入的互斥锁 |
| 4.1.8 | Worker 的 AQS 为什么设置 state=-1（防止被中断） | ◈◈ | |
| 4.1.9 | **`runWorker()` 方法**：自旋取任务 + beforeExecute/afterExecute 钩子 | ⭐⭐⭐ | 线程复用的核心 |
| 4.1.10 | `getTask()`：从队列取任务，阻塞/超时/返回 null | ⭐⭐⭐ | 线程回收的关键 |
| 4.1.11 | `processWorkerExit()`：Worker 退出的清理 + 补充 Worker | ◈◈ | |
| 4.1.12 | `allowCoreThreadTimeOut`：核心线程是否可超时回收 | ◈◈ | |

### 4.2 预定义线程池与生产陷阱

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.2.1 | `newFixedThreadPool(n)`：固定线程 + **无界队列（LinkedBlockingQueue）** | 🛠️ ⭐⭐⭐ | **OOM 风险！** |
| 4.2.2 | `newCachedThreadPool()`：弹性线程 + SynchronousQueue + 60s 超时 | ⭐⭐⭐ | **线程数爆炸风险！** |
| 4.2.3 | `newSingleThreadExecutor()`：单线程 + 无界队列 | ⭐⭐⭐ | 保证串行，但有 OOM 风险 |
| 4.2.4 | `newScheduledThreadPool(n)`：定时调度 + DelayedWorkQueue | ◈◈ | |
| 4.2.5 | `newWorkStealingPool()`（JDK 8+）：ForkJoinPool 的工作窃取 | ○ | |
| 4.2.6 | **阿里规范：禁止用 Executors，必须用 new ThreadPoolExecutor** | 🛠️ ⭐⭐⭐ | 生产铁律 |
| 4.2.7 | 线程工厂（ThreadFactory）的使用：命名/守护/优先级/异常处理 | 🛠️ ⭐⭐⭐ | |

### 4.3 拒绝策略

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.3.1 | **AbortPolicy（默认）**：抛 RejectedExecutionException | 🛠️ ⭐⭐⭐ | 让调用方感知拒绝 |
| 4.3.2 | **CallerRunsPolicy**：提交线程自己执行 | 🛠️ ⭐⭐⭐ | 流量削峰，但可能拖慢提交线程 |
| 4.3.3 | **DiscardPolicy**：静默丢弃 | ⭐⭐⭐ | 允许丢任务时使用 |
| 4.3.4 | **DiscardOldestPolicy**：丢弃队首（最老）任务 | ◈◈ | |
| 4.3.5 | 自定义拒绝策略（如记录日志+告警） | 🛠️ ⭐⭐⭐ | 生产通常需要记录 + 监控 |
| 4.3.6 | 4 种拒绝策略的源码分析（都实现 RejectedExecutionHandler） | ◈◈ | |
| 4.3.7 | 拒绝策略的选型决策 | 🛠️ ⭐⭐⭐ | 每种策略的最佳场景 |

### 4.4 线程池调优与参数配置

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.4.1 | CPU 密集型任务：线程数 = CPU 核数 + 1 | ⭐⭐⭐ | |
| 4.4.2 | IO 密集型任务：线程数 = CPU 核数 × 2 | ⭐⭐⭐ | |
| 4.4.3 | 更精确的 IO 密集型公式：N_threads = N_cpu × (1 + W/C) | ⭐⭐⭐ | W=平均等待时间，C=平均计算时间 |
| 4.4.4 | 上述公式的局限性：依赖压测验证，没有万能公式 | ⭐⭐⭐ | |
| 4.4.5 | 队列长度的选择策略 | ◈◈ | |
| 4.4.6 | **美团动态化线程池方案**：配置中心动态调 coreSize/maxSize | ◈◈ | 知道思路 |
| 4.4.7 | `prestartAllCoreThreads()`：预启动所有核心线程 | ○ | |
| 4.4.8 | `allowsCoreThreadTimeOut(true)`：核心线程也参与回收 | ○ | |

### 4.5 线程池监控与优雅停机

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.5.1 | **监控指标**：activeCount / poolSize / queueSize / completedTaskCount / rejectedCount | 🛠️ ⭐⭐⭐ | 生产必须监控 |
| 4.5.2 | 自定义拒绝策略 + 计数（AtomicLong count）→ Prometheus 暴露 | ◈◈ | |
| 4.5.3 | 定时打印线程池状态 | ◈◈ | |
| 4.5.4 | **`shutdown()`**：不再接受新任务，等待已提交任务执行完 | 🛠️ ⭐⭐⭐ | 温和关闭 |
| 4.5.5 | **`shutdownNow()`**：尝试中断正在执行的任务，返回未执行任务列表 | 🛠️ ⭐⭐⭐ | 暴力关闭 |
| 4.5.6 | `awaitTermination(timeout, unit)`：等待终止 | 🛠️ ⭐⭐⭐ | |
| 4.5.7 | **优雅停机的最佳实践**：shutdown → awaitTermination → shutdownNow → awaitTermination | 🛠️ ⭐⭐⭐ | 两阶段关闭 |
| 4.5.8 | Spring `@PreDestroy` 或 `ApplicationListener<ContextClosedEvent>` 集成 | ◈◈ | |
| 4.5.9 | Runtime.getRuntime().addShutdownHook | ◈◈ | |

### 4.6 CompletableFuture 异步编程

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.6.1 | CompletableFuture 的实现接口：Future + CompletionStage | 🛠️ ⭐⭐⭐ | |
| 4.6.2 | `supplyAsync()` / `runAsync()` 的默认线程池（ForkJoinPool.commonPool()） | 🛠️ ⭐⭐⭐ | **默认线程池是守护线程，可能被 JVM 提前关闭！生产必须传自定义线程池** |
| 4.6.3 | `thenApply()` / `thenApplyAsync()`：转换上一个结果 | 🛠️ ⭐⭐⭐ | 同步 vs 异步 |
| 4.6.4 | `thenAccept()` / `thenRun()`：消费结果 / 不关心结果 | 🛠️ ⭐⭐⭐ | |
| 4.6.5 | **`thenCompose()` vs `thenCombine()`**：依赖组合 vs 独立组合 | 🛠️ ⭐⭐⭐ | 关键区分 |
| 4.6.6 | `thenCompose()`：扁平化，避免 Future<Future<T>> | ⭐⭐⭐ | |
| 4.6.7 | `thenCombine()` + `BiFunction`：两个独立任务结果合并 | ◈◈ | |
| 4.6.8 | `applyToEither()` / `acceptEither()`：竞速，取最先完成的 | ◈◈ | 超时降级场景 |
| 4.6.9 | **`allOf()`**：等待所有任务完成 | 🛠️ ⭐⭐⭐ | |
| 4.6.10 | **`anyOf()`**：等待任一任务完成 | ⭐⭐⭐ | |
| 4.6.11 | **`exceptionally()`**：异常恢复，返回默认值 | 🛠️ ⭐⭐⭐ | |
| 4.6.12 | **`handle()`**：正常/异常都执行，类似 finally | 🛠️ ⭐⭐⭐ | |
| 4.6.13 | `whenComplete()`：不改变结果，只做副作用 | ◈◈ | 类似观察者 |
| 4.6.14 | `complete()` / `completeExceptionally()`：手动完成 | ◈◈ | |
| 4.6.15 | `orTimeout()` / `completeOnTimeout()`（JDK 9+） | ○ | |
| 4.6.16 | **CompletableFuture 的异常传播机制**：沿链传播直到被 exceptionally/handle 截获 | 🛠️ ⭐⭐⭐ | 必须理解 |
| 4.6.17 | 实战：商品详情聚合（查商品+查库存+查价格+查评价→聚合） | 🛠️ ⭐⭐⭐ | 经典异步编排场景 |
| 4.6.18 | 多任务并发 → allOf 聚合 → 超时降级的完整模式 | 🛠️ ⭐⭐⭐ | 生产常用 |

### 4.7 ForkJoinPool 与工作窃取

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.7.1 | 分治思想（Divide and Conquer） | ⭐⭐⭐ | |
| 4.7.2 | **工作窃取（Work Stealing）算法**：空闲线程从繁忙线程队列尾端窃取 | ⭐⭐⭐ | |
| 4.7.3 | ForkJoinTask：RecursiveTask\<T\>（有返回） vs RecursiveAction（无返回） | ◈◈ | |
| 4.7.4 | `fork()` / `join()` 的基本用法 | ◈◈ | |
| 4.7.5 | ForkJoinPool 的共同池（commonPool()）及其大小（CPU 核数 - 1） | ◈◈ | CompletableFuture 默认使用 |
| 4.7.6 | ForkJoinPool 的适用场景：CPU 密集型、递归分解问题 | ◈◈ | 不适合 IO 密集型 |

### 4.8 Future / FutureTask（基础）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 4.8.1 | Future 接口的 5 个方法 | ⭐⭐⭐ | |
| 4.8.2 | FutureTask 的状态机：NEW→COMPLETING→NORMAL/EXCEPTIONAL/CANCELLED→INTERRUPTING→INTERRUPTED | ◈◈ | |
| 4.8.3 | FutureTask 的 `get()` + `awaitDone()` 的阻塞等待（Treiber Stack） | ◈◈ | |
| 4.8.4 | Future 的局限性：无法编排、get 阻塞、不支持异常处理 | ⭐⭐⭐ | 引出 CompletableFuture |
| 4.8.5 | `ScheduledThreadPoolExecutor` 的 `scheduleAtFixedRate` vs `scheduleWithFixedDelay` | ◈◈ | |

### 📖 阶段四推荐源码阅读

| 类 | 重点方法 | 优先级 | 预估行数 |
|---|---|---|---|
| `ThreadPoolExecutor` | execute/addWorker/runWorker/getTask/shutdown | ⭐⭐⭐ | ~1000 |
| `Worker`（内部类） | runWorker + AQS 继承 | ⭐⭐⭐ | ~150 |
| `CompletableFuture` | thenApply/thenCompose/allOf/exceptionally | ◈◈ | ~500 |
| `FutureTask` | run + get + awaitDone | ◈◈ | ~200 |

### 🧪 阶段四必做练习

1. ⭐⭐⭐ 自定义线程池 + 定时打印监控指标（activeCount/queueSize/rejectedCount）+ 模拟拒绝 + 优雅停机
2. ⭐⭐⭐ 商品详情聚合：4 个异步服务调用（各模拟 200-500ms），CompletableFuture 编排 + allOf 聚合 + 超时降级
3. ◈◈ 基于 ArrayBlockingQueue 实现简易线程池（支持核心线程/最大线程/队列）

### ✅ 阶段四自测题

1. `newFixedThreadPool` 的 OOM 风险根源是什么？为什么用有界队列可以避免？
2. `CallerRunsPolicy` 为什么能实现流量削峰？有什么副作用？
3. `CompletableFuture.thenApply()` 和 `thenCompose()` 的区别？假设返回类型分别是 `CompletableFuture<User>` 和 `User`，各自该用哪个？
4. ForkJoinPool 的工作窃取算法解决了什么问题？为什么不适合 IO 密集型任务？

---

## 阶段五：并发设计模式、问题排查与性能调优

> **目标**：掌握经典并发设计模式，具备线上并发问题的系统性排查与 JMH 基准测试能力

### 5.1 单例模式与并发安全

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.1.1 | **饿汉式**：类加载时初始化，线程安全但可能浪费内存 | ⭐⭐⭐ | |
| 5.1.2 | **DCL 双检锁**：两次 null 检查 + volatile | 🛠️ ⭐⭐⭐ | 面试最爱问 |
| 5.1.3 | DCL 中 volatile 的必要性（半初始化对象图解） | 🛠️ ⭐⭐⭐ | 能画对象的半初始化状态 |
| 5.1.4 | **静态内部类（Holder）模式**：类加载机制保证延迟+安全 | 🛠️ ⭐⭐⭐ | 推荐生产使用 |
| 5.1.5 | **枚举单例**：防反射攻击 + 防序列化破坏 | 🛠️ ⭐⭐⭐ | Effective Java 推荐 |
| 5.1.6 | 反射破坏单例 + 解决方案 | ◈◈ | `setAccessible(true)` 调用私有构造器 |
| 5.1.7 | 序列化破坏单例 + `readResolve()` 修复 | ◈◈ | |

### 5.2 生产者-消费者模式

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.2.1 | BlockingQueue 版本（最简洁） | ⭐⭐⭐ | |
| 5.2.2 | Lock + 双 Condition 精确唤醒版本 | ⭐⭐⭐ | notFull.signal() 只唤醒生产者 |
| 5.2.3 | wait/notifyAll 传统版本 | ◈◈ | |
| 5.2.4 | 背压（Back Pressure）处理策略 | ◈◈ | 队列满时的降级策略 |
| 5.2.5 | 多级消费者流水线模式 | ○ | |

### 5.3 读写锁模式

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.3.1 | 缓存设计中的读写锁应用 | ⭐⭐⭐ | 读多写少场景 |
| 5.3.2 | 缓存失效 + 并发重建时的锁策略 | ◈◈ | |
| 5.3.3 | 写锁降级为读锁的正确姿势 | ⭐⭐⭐ | |

### 5.4 ThreadLocal 模式（重点）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.4.1 | **ThreadLocal 内部结构**：Thread → ThreadLocalMap → Entry(WeakRef<ThreadLocal>, Value) | 🛠️ ⭐⭐⭐ | 能画出引用关系图 |
| 5.4.2 | ThreadLocalMap 的 Entry 为何使用 WeakReference\<ThreadLocal\> | ⭐⭐⭐ | 防止 ThreadLocal 对象本身无法回收 |
| 5.4.3 | **内存泄漏成因全链路分析** | 🛠️ ⭐⭐⭐ | 强引用链：Thread → ThreadLocalMap → Entry → Value |
| 5.4.4 | `set()` / `get()` 中 `expungeStaleEntry()` 的清理机制 | ◈◈ | 惰性清理 |
| 5.4.5 | ThreadLocalMap 使用开放定址法（线性探测）处理冲突 | ○ | |
| 5.4.6 | `remove()` 的重要性 + **阿里规范：必须 try-finally 中 remove** | 🛠️ ⭐⭐⭐ | 生产铁律 |
| 5.4.7 | Tomcat 线程池复用场景下的 ThreadLocal 泄漏风险 | 🛠️ ⭐⭐⭐ | 线程不销毁 → ThreadLocalMap 不清理 |
| 5.4.8 | **InheritableThreadLocal**：父子线程数据传递 | ◈◈ | `new Thread()` 时复制 |
| 5.4.9 | InheritableThreadLocal 在线程池场景下的失效 | ◈◈ | 线程复用 → 不再复制 |
| 5.4.10 | **TransmittableThreadLocal（阿里 TTL）**：线程池场景下的上下文传递 | ◈◈ | 知道它能解决什么问题 |
| 5.4.11 | ThreadLocal 典型应用：Spring 事务管理、链路追踪 TraceId、PageHelper 分页 | ◈◈ | |

### 5.5 限流模式

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.5.1 | 四种限流算法：计数器 / 滑动窗口 / 漏桶 / 令牌桶 | ⭐⭐⭐ | 能画出每种算法的示意图 |
| 5.5.2 | 四种算法的对比与适用场景 | ⭐⭐⭐ | |
| 5.5.3 | Guava RateLimiter：平滑突发限流（SmoothBursty） | ◈◈ | |
| 5.5.4 | Guava RateLimiter：平滑预热限流（SmoothWarmingUp） | ○ | |
| 5.5.5 | 基于 Semaphore 的简易限流器 | ◈◈ | |
| 5.5.6 | Sentinel / Hystrix 的限流熔断思想 | ○ | 知道概念 |

### 5.6 常见并发问题诊断

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.6.1 | **死锁**：四条件 + 三种预防（锁排序/tryLock超时/死锁检测） | ⭐⭐⭐ | |
| 5.6.2 | jstack 死锁检测实操 | ⭐⭐⭐ | |
| 5.6.3 | **活锁**（Livelock）：不断改变状态但没有进展 | ◈◈ | 随机退避解决 |
| 5.6.4 | **饥饿**（Starvation）：低优先级线程永远等不到资源 | ◈◈ | 公平锁解决 |
| 5.6.5 | **伪共享**（False Sharing）：缓存行竞争 | ⭐⭐⭐ | @Contended / 填充 |
| 5.6.6 | 伪共享的验证实验（填充前后性能对比） | ◈◈ | |

### 5.7 性能压测与调优

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 5.7.1 | **JMH 基础**：@BenchmarkMode / @State / @Setup / @TearDown | ⭐⭐⭐ | |
| 5.7.2 | JMH 模式：Throughput / AverageTime / SampleTime / SingleShotTime | ◈◈ | |
| 5.7.3 | **JMH 常见陷阱**：死代码消除（Blackhole）/ 循环展开 / 预热不足 / Fork 数不足 | ⭐⭐⭐ | 不知道怎么用等于没测 |
| 5.7.4 | `@State` 的三种作用域：Benchmark / Thread / Group | ◈◈ | |
| 5.7.5 | **Arthas 线上诊断**：thread / monitor / watch / trace / vmstat | 🛠️ ⭐⭐⭐ | 线上问题排查利器 |
| 5.7.6 | `thread -b`：查找当前阻塞其他线程的锁 | 🛠️ ⭐⭐⭐ | 死锁排查 |
| 5.7.7 | `thread -n 3`：CPU 使用率最高的 3 个线程 | 🛠️ ⭐⭐⭐ | CPU 飙高排查 |
| 5.7.8 | `monitor -c 5`：方法调用统计 | ◈◈ | |
| 5.7.9 | **CPU 飙升至 100% 的排查全流程** | 🛠️ ⭐⭐⭐ | 实战场景 |
| 5.7.10 | JFR（JDK Flight Recorder）+ JMC 生产级性能剖析 | ○ | 低开销 profiling |
| 5.7.11 | jstack / jmap / jstat / jinfo 基础使用 | 🛠️ ⭐⭐⭐ | |

### 5.8 并发编程中的常见陷阱汇总

| 序号 | 陷阱 | 优先级 | 说明 |
|---|---|---|---|
| 5.8.1 | 在循环中创建线程（线程爆炸） | ⭐⭐⭐ | 用线程池 |
| 5.8.2 | 在 finally 中未释放锁 → 死锁 | ⭐⭐⭐ | Lock 必须 try-finally unlock |
| 5.8.3 | 未处理中断异常 → 线程无法停止 | ⭐⭐⭐ | 正确处理 InterruptedException |
| 5.8.4 | ConcurrentHashMap 的复合操作不原子（contains+put 等） | ⭐⭐⭐ | 用 computeIfAbsent 等方法 |
| 5.8.5 | Stream.parallel() 的默认线程池问题 | ◈◈ | |
| 5.8.6 | SimpleDateFormat 的线程不安全 | ◈◈ | 用 DateTimeFormatter 或 ThreadLocal |
| 5.8.7 | HashMap 并发 resize 死循环（JDK 7） | ◈◈ | 扩容时的环形链表，JDK 8 已修复 |
| 5.8.8 | 在 ForkJoinPool.commonPool() 中执行阻塞任务 | ◈◈ | 会耗尽工作线程 |

### 📖 阶段五推荐源码阅读

| 类 | 重点 | 优先级 |
|---|---|---|
| `ThreadLocal` | set/get/remove + ThreadLocalMap + InheritableThreadLocal | ⭐⭐⭐ |
| `ThreadLocal.ThreadLocalMap` | Entry 结构 + expungeStaleEntry + rehash | ◈◈ |
| Guava `RateLimiter` | SmoothBursty + SmoothWarmingUp | ○ |

### 🧪 阶段五必做练习

1. ⭐⭐⭐ ThreadLocal 内存泄漏实验：不 remove → 观察堆内存 → 加 remove 对比
2. ⭐⭐⭐ `ThreadMXBean.findDeadlockedThreads()` 实现死锁自动检测告警
3. ⭐⭐⭐ JMH 压测 ReentrantLock vs Synchronized（高/中/低三种竞争强度）
4. ◈◈ 基于 Semaphore 实现限流器 + 测试

### ✅ 阶段五自测题

1. 详解 ThreadLocal 内存泄漏的完整链路，Entry 为何用 WeakReference？
2. 死锁的四种预防策略分别破解了死锁的哪个必要条件？
3. 伪共享是什么？LongAdder 中如何避免？
4. JMH 测试中为什么需要 Blackhole.consume()？不用会怎样？
5. CPU 飙升 100%，你如何用 Arthas 排查到具体代码行？

---

## 阶段六：分布式并发控制与架构演进

> **目标**：将并发视野从单机拓展到分布式系统，掌握分布式锁、分布式 ID 生成的业界方案

### 6.1 分布式锁

#### 6.1.1 数据库实现

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.1.1.1 | 基于唯一索引 `INSERT`：插入锁记录 | ◈◈ | |
| 6.1.1.2 | 基于 `SELECT ... FOR UPDATE`：行锁 | ◈◈ | |
| 6.1.1.3 | 数据库锁的缺点：无超时释放、单点瓶颈 | ◈◈ | |

#### 6.1.2 Redis 实现（重点）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.1.2.1 | **基础版**：`SET lock_key value NX EX 30` | 🛠️ ⭐⭐⭐ | 单命令原子性 |
| 6.1.2.2 | **释放锁的 Lua 脚本**：原子校验 value + DEL | 🛠️ ⭐⭐⭐ | 防止误删别人的锁 |
| 6.1.2.3 | 为什么释放锁必须用 Lua 脚本（两个操作必须原子） | 🛠️ ⭐⭐⭐ | |
| 6.1.2.4 | value 为什么用 UUID/线程标识（不用固定值） | ⭐⭐⭐ | 防止误删 |
| 6.1.2.5 | 锁过期时间设置多少合适？自动续期 | ⭐⭐⭐ | |
| 6.1.2.6 | **Redisson 的看门狗（Watchdog）机制**：默认 30s 过期，每 10s 续期 | 🛠️ ⭐⭐⭐ | 解决业务超时锁释放问题 |
| 6.1.2.7 | Redisson 看门狗的 netty 时间轮实现 | ○ | |
| 6.1.2.8 | **Redis 主从切换导致锁丢失**问题 | 🛠️ ⭐⭐⭐ | 主节点宕机 → 从节点未同步锁数据 → 新主没有锁 |
| 6.1.2.9 | **RedLock 算法**：N 个独立 Redis（N/2+1 投票） | ◈◈ | Redisson 实现 RedissonRedLock |
| 6.1.2.10 | RedLock 争议（Martin Kleppmann 的批评） | ◈◈ | 时钟跳跃/GC 停顿等问题 |
| 6.1.2.11 | RedLock 的适用场景与局限性 | ◈◈ | |

#### 6.1.3 ZooKeeper 实现

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.1.3.1 | 临时顺序节点 + Watcher 机制 | ◈◈ | |
| 6.1.3.2 | ZK 分布式锁的加锁流程 | ◈◈ | 创建临时顺序节点 → 排序 → 判断最小 → watch 前一个 |
| 6.1.3.3 | ZK 锁的自动释放（Session 断开 → 临时节点删除） | ◈◈ | 天然防死锁 |
| 6.1.3.4 | ZK 锁的羊群效应（Herd Effect）与优化 | ○ | |
| 6.1.3.5 | Curator 框架的 InterProcessMutex | ○ | |
| 6.1.3.6 | ZK vs Redis 分布式锁全面对比 | ◈◈ | |

#### 6.1.4 etcd 实现

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.1.4.1 | etcd 的 Lease + Transaction 实现的分布式锁 | ○ | K8s 生态首选 |
| 6.1.4.2 | 与 ZK / Redis 的差异 | ○ | |

#### 6.1.5 分布式锁选型决策

| 场景 | 推荐方案 | 优先级 |
|---|---|---|
| 互联网主流应用（容忍小概率并发冲突） | Redis（单实例） | ⭐⭐⭐ |
| 对锁安全性要求较高 | Redis（RedLock）或 ZK | ⭐⭐⭐ |
| 强一致性要求（金融/支付） | ZooKeeper / etcd | ◈◈ |
| 低频操作，已有 DB | 数据库唯一索引 | ◈◈ |

### 6.2 分布式 ID 生成

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.2.1 | UUID：36 字符长，无序，B+树索引性能差 | ⭐⭐⭐ | 知道为什么不推荐 |
| 6.2.2 | 自增 ID：单点瓶颈 + 暴露业务量 | ◈◈ | |
| 6.2.3 | **雪花算法（Snowflake）详解** | 🛠️ ⭐⭐⭐ | |
| 6.2.4 | Snowflake 的 64 位结构：1 + 41 + 10 + 12 | 🛠️ ⭐⭐⭐ | 能手绘位结构图 |
| 6.2.5 | 41 位时间戳：可用 69 年（从自定义起始时间算） | ◈◈ | |
| 6.2.6 | 10 位机器 ID：1024 台机器 | ◈◈ | |
| 6.2.7 | 12 位序列号：单机每毫秒 4096 个 ID | ◈◈ | |
| 6.2.8 | **时钟回拨问题**及三种解决方案 | 🛠️ ⭐⭐⭐ | 等待/拒绝/备用 ID |
| 6.2.9 | 百度 UidGenerator：缓存 RingBuffer + 未来时间 | ○ | |
| 6.2.10 | **美团 Leaf**：号段模式（DB 预分配号段）+ Snowflake 模式 | ◈◈ | 两种模式双保险 |
| 6.2.11 | Leaf 号段模式的双 Buffer 优化 | ○ | |

### 6.3 分布式事务基础

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.3.1 | **CAP 定理**：C（一致性）/ A（可用性）/ P（分区容错性） | ⭐⭐⭐ | 为什么 P 必须选 |
| 6.3.2 | **BASE 理论**：基本可用 + 软状态 + 最终一致性 | ⭐⭐⭐ | |
| 6.3.3 | 2PC（两阶段提交）：XA 协议，强一致性 | ◈◈ | |
| 6.3.4 | 3PC（三阶段提交）：超时机制 + 预提交 | ○ | |
| 6.3.5 | **TCC（Try-Confirm-Cancel）**：业务层补偿 | ◈◈ | 灵活但实现复杂 |
| 6.3.6 | **Saga 模式**：长事务拆分 + 补偿链 | ◈◈ | 适合微服务 |
| 6.3.7 | 本地消息表 + MQ 的最终一致性方案 | ◈◈ | 高可用 |
| 6.3.8 | Seata AT 模式简介 | ○ | 无侵入的分布式事务 |
| 6.3.9 | 各方案对比与选型 | ◈◈ | |

### 6.4 分布式场景下的乐观锁与悲观锁

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.4.1 | **数据库乐观锁（版本号）**：`UPDATE ... WHERE version = ?` | 🛠️ ⭐⭐⭐ | |
| 6.4.2 | 乐观锁的 CAS 语义在 SQL 层的体现 | ⭐⭐⭐ | |
| 6.4.3 | 乐观锁失败后的重试策略（自旋 vs 抛异常 vs 降级） | 🛠️ ⭐⭐⭐ | |
| 6.4.4 | **数据库悲观锁**：`SELECT ... FOR UPDATE` | ◈◈ | |
| 6.4.5 | 乐观锁 vs 悲观锁的选择依据（冲突概率/响应时间/复杂度） | 🛠️ ⭐⭐⭐ | |

### 6.5 高并发架构概览（拓宽视野）

| 序号 | 知识点 | 优先级 | 说明 |
|---|---|---|---|
| 6.5.1 | 流量治理：限流 / 降级 / 熔断 / 隔离 | ◈◈ | Sentinel / Resilience4j |
| 6.5.2 | 消息队列削峰填谷：RocketMQ / Kafka | ◈◈ | |
| 6.5.3 | 缓存穿透（布隆过滤器）/ 击穿（分布式锁）/ 雪崩（随机过期） | ◈◈ | |
| 6.5.4 | 读写分离与分库分表下的数据一致性问题 | ○ | |

### 📖 阶段六推荐源码阅读

| 组件 | 重点 | 优先级 |
|---|---|---|
| Redisson `RedissonLock` | lock/unlock/tryLock + renewExpiration（看门狗） | ◈◈ |
| 雪花算法实现 | 时间戳/机器ID/序列号的位运算 | ⭐⭐⭐ |
| Curator `InterProcessMutex` | ZK 临时顺序节点 + Watcher | ○ |

### 🧪 阶段六必做练习

1. ⭐⭐⭐ 基于 Jedis/Lettuce 实现 Redis 分布式锁（lock + unlock Lua 脚本）+ 多线程并发测试
2. ⭐⭐⭐ 手写 Snowflake ID 生成器 + 处理时钟回拨 + 验证唯一性与趋势递增
3. ⭐⭐⭐ 数据库乐观锁库存扣减 + 模拟并发扣减 + 对比无锁/悲观锁/乐观锁

### ✅ 阶段六自测题

1. Redis 释放锁为何必须用 Lua 脚本？如果先 get 再判断 value 再 del 会有什么问题？
2. 雪花算法的 41 位时间戳能用多少年？怎么算？
3. CAP 理论中 P（分区容错性）为什么是必须选的？
4. 乐观锁和悲观锁分别在什么场景下更优？给出具体的业务例子。

---

## 📊 全景优先级统计

| 阶段 | 🛠️ 日常高频 | ⭐⭐⭐ 核心必学 | ◈◈ 重点掌握 | ○ 了解即可 | 🔖 选学 |
|---|---|---|---|---|---|
| 阶段一 | 5 个 | 13 个 | 4 个 | 8 个 | 2 个 |
| 阶段二 | 9 个 | 14 个 | 13 个 | 6 个 | 0 个 |
| 阶段三 | 22 个 | 38 个 | 31 个 | 14 个 | 0 个 |
| 阶段四 | 18 个 | 22 个 | 22 个 | 5 个 | 0 个 |
| 阶段五 | 13 个 | 18 个 | 19 个 | 4 个 | 0 个 |
| 阶段六 | 10 个 | 12 个 | 17 个 | 8 个 | 0 个 |
| **合计** | **77** | **117** | **106** | **45** | **2** |

---

## 🎯 第一轮冲刺路径（只学 ⭐⭐⭐，4~6 周）

> 如果你时间紧张，第一轮只学 ⭐⭐⭐ 标记的 117 个知识点，覆盖 80% 的面试和生产需求

| 周 | 内容 | 关键词 |
|---|---|---|
| 第 1 周 | 阶段一 + 阶段二（前半） | JMM、volatile、Synchronized 使用、Mark Word、锁升级 |
| 第 2 周 | 阶段二（后半）+ 阶段三 AQS | wait/notify、死锁、AQS acquire/release、ReentrantLock |
| 第 3 周 | 阶段三 并发容器 + Atomic | ConcurrentHashMap、CopyOnWriteArrayList、BlockingQueue 选型、LongAdder |
| 第 4 周 | 阶段三 同步工具 + 阶段四 线程池 | CountDownLatch/CyclicBarrier/Semaphore、ThreadPoolExecutor 全流程 |
| 第 5 周 | 阶段四 CompletableFuture + 阶段五 ThreadLocal | 异步编排、ThreadLocal 内存泄漏、死锁排查 |

---

## 📚 推荐书单（按阅读顺序）

| 序号 | 书名 | 作者 | 重点章节 | 对应阶段 |
|---|---|---|---|---|
| 1 | 《Java并发编程实战》 | Brian Goetz | 第 2-8 章、第 10-11 章 | 阶段一、二 |
| 2 | 《Java并发编程的艺术》 | 方腾飞 | 第 2 章（JMM）、第 3 章（内存模型）、第 5 章（AQS） | 阶段一、三 |
| 3 | 《实战Java高并发程序设计》 | 葛一鸣 | 第 4 章（锁优化）、第 5 章（线程池）、第 6 章（并发模式） | 阶段四、五 |
| 4 | 《深入理解Java虚拟机》 | 周志明 | 第 12 章（Java内存模型）、第 13 章（线程安全与锁优化） | 阶段一、二 |
| 5 | 《凤凰架构》 | 周志明 | 第 4 章（分布式事务） | 阶段六 |

---

## 🛠 工具速查

| 工具 | 用途 | 何时用 |
|---|---|---|
| `javap -v` | 查看字节码 | 阶段二：分析 Synchronized |
| JOL | 查看对象内存布局 | 阶段二：验证 Mark Word |
| JMH | 微基准测试 | 阶段三/五：压测并发组件 |
| jstack | 线程堆栈 | 阶段二/五：死锁排查 |
| jmap / jstat | 堆内存 / GC 统计 | 阶段五：内存问题 |
| Arthas | 线上诊断 | 阶段五：CPU/线程/锁诊断 |
| JFR + JMC | 低开销性能剖析 | 阶段五：生产级 profiling |
| JITWatch | JIT 编译分析 | 阶段一：volatile 汇编 |

---

## 📂 项目代码分包建议

```
src/main/java/com/sw/yang/concurrent/
├── jmm/            # 阶段一：可见性/原子性/有序性/CAS 验证
├── sync/           # 阶段二：Mark Word/锁升级/wait-notify/死锁
├── juc/
│   ├── aqs/        # 阶段三：手写 AQS 锁/Mutex/Semaphore
│   ├── container/  # 阶段三：ConcurrentHashMap/CopyOnWrite/BlockingQueue
│   ├── atomic/     # 阶段三：AtomicInteger/LongAdder 压测
│   └── tools/      # 阶段三：CountDownLatch/CyclicBarrier/Semaphore
├── pool/           # 阶段四：线程池/CompletableFuture/ForkJoin
├── pattern/        # 阶段五：单例/生产者消费者/ThreadLocal/限流/死锁排查
└── distributed/    # 阶段六：Redis 分布式锁/雪花算法/乐观锁扣库存
```

---

> 📬 **这套全景地图涵盖了 Java 并发编程的完整知识体系。建议你先通读一遍建立全局认知，然后按 ⭐⭐⭐ → ◈◈ → ○ 的顺序分层深入。后续我可以为每个阶段展开成详细的学习文档（含完整代码示例、源码逐行解析、面试要点），随时告诉我从哪个阶段开始。**

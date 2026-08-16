# 03-02-01 ReentrantLock 实例篇（5 个场景走完源码）

> **阶段三·第 2 篇配套实例** | 前置：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)、[03-02-ReentrantLock源码全解析](./03-02-ReentrantLock源码全解析.md) | 配套代码：`jdk8-lab/src/test/java/com/sw/yang/concurrent/juc/aqs/AqsWalkthroughTest.java`（独立子工程，跑 JDK 8 经典版）  
> **建议时长**：2~3 小时（每个场景 20~30 分钟：跑实验 → 对源码 → 记疑问）  
> 🧪 本篇不引入新机制，只做一件事：把 03-01/03-02 的抽象机制，用**可运行的例子 + 可观察的输出**落成行为

---

## 📌 优先级导航

| 场景 | 知识点 | 策略 |
|---|---|---|
| ⭐⭐⭐ 场景 2 | 竞争排队全链路（addWaiter/enq/acquireQueued/SIGNAL 预约/park/unparkSuccessor） | **能对着源码讲出每一步** |
| ⭐⭐⭐ 场景 5 | Condition 两队切换（await 四步、signal 只搬家不唤醒、SIGNAL 接力） | **用"观察 b"输出验证文档时序** |
| ◈◈ 场景 1 | 无竞争快路径、state 与 owner 的 volatile 差异 | 理解设计动机 |
| ◈◈ 场景 3/4 | 可重入计数、中断与超时语义 | 会对比两段源码 |

---

## 0. 使用方法

```bash
# 本子工程是 JDK 8 实验区：源码与文档（经典版）逐行对应
# （MAVEN_OPTS 修正 JDK 8 下 maven 转发 GBK 解码导致的输出乱码；fork 侧 UTF-8 由 pom 的 surefire argLine 保证）
JAVA_HOME=/d/Learn/soft/jdk/jdk8 MAVEN_OPTS="-Dfile.encoding=UTF-8" mvn -f jdk8-lab/pom.xml test
```

- **每个场景的观察点**：代码注释里标注了 `观察 a / b / c`，对应你要在源码里确认的结论
- **调试建议**：在 `ReentrantLock.lock()`、`AQS.addWaiter`、`AQS.acquireQueued`、`AQS.unparkSuccessor` 下断点，配合 IntelliJ 的"线程 dump"看 T2 停在哪个 park
- **版本说明**：本文按**经典版（JDK 8~13）**讲解（与 03-01/03-02 一致）；`jdk8-lab` 子工程跑的就是 JDK 8，**断点跟读与文档逐行一致**。若在主工程（JDK 17 重构版）下运行，行为语义不变，差异对照 [03-02 §7.11](./03-02-ReentrantLock源码全解析.md)

---

## 1. 场景 1：无竞争获取 —— 一行 `lock.lock()` 的完整旅程

### 1.1 例子与运行输出

```java
ReentrantLock lock = new ReentrantLock();   // 默认非公平（NonfairSync）
lock.lock();
System.out.println(lock.getHoldCount());    // 1
lock.unlock();
```

```
【1】lock() 入口：NonfairSync.lock() → CAS(state, 0, 1)
【2】已持有：可重入次数 = 1
【3】unlock() 入口：release(1) → tryRelease
【4】已释放
```

### 1.2 调用链与源码逐步

```
lock.lock()
  → ReentrantLock.lock()          // sync 多态 → NonfairSync
  → NonfairSync.lock():
      if (compareAndSetState(0, 1))      // ★ 直接 CAS：state 0→1
          setExclusiveOwnerThread(Thread.currentThread());
      else
          acquire(1);                    // 抢不到才走排队（场景 2）
```

| 步骤 | 源码 | 说明 |
|---|---|---|
| CAS | `unsafe.compareAndSwapInt(this, stateOffset, 0, 1)` | 一条 CPU 原子指令（CMPXCHG）：当前是 0 才写 1。`stateOffset` 在 AQS 静态块里用 `unsafe.objectFieldOffset` 取得 |
| 记 owner | `exclusiveOwnerThread = thread` | 非 volatile（见 1.3） |
| 释放 | `release(1)` → `tryRelease` → `setState(0)` | 无竞争时队列不存在（`head == null`），`release` 里 `h != null` 短路，直接返回 |

### 1.3 第一性原理：state 与 owner 的 volatile 差异

> **问题：它们在压缩哪个代价、守住哪个正确性？（docs/00 的审查问题）**

| 字段 | 为什么 volatile | 守住的正确性 |
|---|---|---|
| `state` | 被所有争锁线程读写，必须人人立刻看到最新值 | **互斥**：读错旧值 → 两人同时通过检查 → 锁被破坏 |
| `owner` | **故意不用** volatile（省一个内存屏障） | 什么都不用它守 |

owner 不 volatile 的论证（因果链）：
1. 写它的人只有持锁者；读它的人只有持锁者自己（重入判断）或想抢锁的新线程
2. 新线程读 owner 只是为了判"我是不是持有者"——读到过期值只会得到"我不是"→ 去排队
3. **读错 owner 是安全失败**（最多多等一轮），读错 state 是灾难 → volatile 用在刀刃上

> 无竞争获取的极致开销 = **1 次 CAS + 1 次普通字段写，0 次阻塞**。队列、park/unpark 全部推迟到竞争出现时——AQS 的懒惰设计。

### 1.4 追问：owner 会不会读到"过期的我"？

（把"读错 owner 只会得到'我不是'"推到底的完整论证）

**结论：读到"过期的我是"在数学上不存在。** 三层论证：

1. **读只能读到"曾经写入过的值"**——如果 T2 从未持有过锁，owner 从未被写成 T2，T2 永远读不到 T2
2. **持有过又释放的线程，程序顺序（happens-before）把"我是"排除**：
   T2 释放时写 `owner = null`，在程序顺序上晚于持有时写的 `owner = T2`、早于 T2 之后的读
   → 读至少看到 null 或 null 之后的值，不可能跳回自己更早写下的 T2
3. **穷举三种情况**：

| T2 的状态 | owner 可能读到 | 结果 |
|---|---|---|
| 从未持有过 | null / 旧持有者 | "我不是" → 排队 ✅ |
| 持有过、已释放 | null（自己的最后写）| "我不是" → 排队 ✅ |
| **正持有**（重入）| T2（**最新值**）| "我是" ✅ 且这不是过期值 |

唯一微妙处：`c != 0`（volatile 读，必为最新值）才进重入分支读 owner——此刻锁确实被持有，owner 读错的方向只有"把 T1 读成 null"或"把 T1 读成 T2"，**方向永远是"我不是"**。

### 1.5 追问：state 为什么不能学 owner"多排一次队"？

**关键认知：state 必须 volatile 守的不是互斥，是"临界区的可见性传递"。互斥本身 CAS 就够了**（CAS 比较的是内存真实值，即使 state 非 volatile 也原子）。

但锁的完整语义 = 互斥 + 可见性，JMM 的 happens-before 链：

```
T1 写共享变量 ──hb── T1 写 state（volatile 写）
                        ──hb── T2 读 state（CAS / volatile 读）
                                      ──hb── T2 读共享变量
```

**链的枢纽就是 state 的 volatile**。若 state 是普通字段：
- 互斥照常（CAS 保证）
- 但 T2 拿锁后**不保证看到 T1 临界区的写** → 锁在数据层面失效（比如消费者读不到生产者放进队列的 item）

owner 不参与这条链——它只在持锁者自己的线程内读写，"把我的临界区写传给下一个持锁者"走的是 state，不是 owner。

| 字段 | 读错的后果 | 可接受？ |
|---|---|---|
| owner | "不是我" → 多排一次队（方向受限，见 1.4）| ✅ 安全失败 |
| state（若普通字段）| hb 链断裂，临界区数据撕裂 | ❌ 正确性崩坏 |

一句话收尾：**互斥靠 CAS，可见性靠 state 的 volatile，owner 什么都不靠**——一个字段的三种命运。

---

## 2. 场景 2：竞争排队 —— AQS 最核心的一段（⭐⭐⭐）

### 2.1 例子与运行输出

```java
ReentrantLock lock = new ReentrantLock();
lock.lock();                                   // 主线程先持锁（扮演 T1）
Thread t2 = new Thread(() -> {
    lock.lock();                               // CAS 失败 → 入队 → park
    lock.unlock();
}, "T2");
t2.start();
Thread.sleep(200);                             // 等 T2 完成入队
System.out.println(t2.getState());             // 观察 a
lock.unlock();                                 // T1 释放 → 唤醒 T2
```

```
T2: 开始 lock()（将发生：CAS 失败 → addWaiter 入队 → park）   ← 预告
观察 a：T2 线程状态（应为 WAITING）: WAITING      ← park 的实证（主线程打）
T1(主): unlock() → release → unparkSuccessor 唤醒 T2
T2: lock() 返回，已拿到锁（setHead 晋升完成）      ← 记录
观察 b：T2 已结束（TERMINATED）
```

**`WAITING` 就是 `LockSupport.park` 留下的状态**——这就是"阻塞在锁上"的可观察形态。

> 📌 **日志语义：预告 vs 记录**。T2 的第一条日志打印时"入队、park"还没发生，它是**预告**（"将发生"）；第二条是**记录**（lock() 返回 = 拿到锁）。因为 `lock()` 是阻塞调用，println 放在它后面就失去"已入队已 park"的时间锚点——中间状态由主线程的观察 a（WAITING）实证。

### 2.2 调用链总览

```
T2: lock()  CAS 失败
  → acquire(1)
  → tryAcquire 失败（state=1，owner≠T2）
  → acquireQueued( addWaiter(EXCLUSIVE), 1 )     ← addWaiter 先执行
  → addWaiter：队列空 → enq 初始化（建 head 哑节点）→ T2 入队尾
  → acquireQueued 循环：
      前驱是 head？→ 是 → tryAcquire → 失败
      → shouldParkAfterFailedAcquire：把前驱(head)设为 SIGNAL → 返回 false
      → 再转一圈 → tryAcquire 失败 → 前驱已是 SIGNAL → 返回 true
      → parkAndCheckInterrupt → park（T2 睡）

T1: unlock() → release(1)
  → tryRelease：state 1→0，owner=null，返回 true
  → head.waitStatus == SIGNAL → unparkSuccessor(head)
  → unpark(T2)

T2: 醒来 → 循环 → 前驱是 head → tryAcquire 成功
  → setHead(T2 节点) → 旧 head.next = null（脱离队列）
```

### 2.3 源码逐步（带读）

**① 第二抢**：`NonfairSync.lock()` 的 CAS 失败后进 `acquire(1)`，内部 `tryAcquire` 还会再抢一次——所以非公平锁有"两次抢锁机会"（见 03-02 §2）。

**② addWaiter 入队**：

```java
private Node addWaiter(Node mode) {
    Node node = new Node(Thread.currentThread(), mode);   // 包装 T2
    Node pred = tail;
    if (pred != null) {                     // 快速路径：队列已存在
        node.prev = pred;
        if (compareAndSetTail(pred, node)) {  // CAS 把 tail 移到自己
            pred.next = node;                 // 连上旧 tail 的 next
            return node;
        }
    }
    enq(node);                              // 完整路径：CAS 失败或队列空
    return node;
}
```

首次竞争时队列为空（`tail == null`），走 `enq` 的初始化：**先建 head 哑节点**，再自旋入队。head 永远是"已获锁的占位符"（不存线程）——为什么必须哑节点，见 03-01 §3.5 的因果链。

**③ acquireQueued 核心循环**：

```java
for (;;) {
    final Node p = node.predecessor();           // 取前驱
    if (p == head && tryAcquire(arg)) {          // 只有 head.next 有资格抢
        setHead(node);                           // 自己成为新 head（thread 清空）
        p.next = null;                           // 旧 head 脱离（GC 回收）
        return interrupted;
    }
    if (shouldParkAfterFailedAcquire(p, node) && // park 前准备
        parkAndCheckInterrupt())                 // park + 检查中断
        interrupted = true;
}
```

**④ shouldParkAfterFailedAcquire —— 两次循环才 park 的因果链**：

```java
if (ws == Node.SIGNAL)  return true;      // 前驱已答应唤醒我 → 放心睡
if (ws > 0)             { 跳过取消节点 }
else                    { CAS 前驱 → SIGNAL; return false; }   // 预约唤醒
```

为什么不能第一次直接 park？**park 前必须保证前驱的 SIGNAL 已可见**，否则：
T2 检查前驱（还是 0）→ 恰逢 T1 release 看到 `head.waitStatus == 0` → 不 unpark → T2 才 park → **永远睡死（丢失唤醒）**。
所以：先 CAS 设 SIGNAL → 转一圈重试（顺带再试一次获取，锁若刚好空了就直接拿到，省一次 park/unpark）。

**⑤ T1 释放**：

```java
public final boolean release(int arg) {
    if (tryRelease(arg)) {                          // state 1→0
        Node h = head;
        if (h != null && h.waitStatus != 0)         // 有人预约了唤醒才行动
            unparkSuccessor(h);                     // 唤醒 head.next（T2）
        return true;
    }
    return false;
}
```

`h.waitStatus != 0` 的含义：head 是 SIGNAL = 有后继预约了唤醒；head 是 0 = 没人排队 → 什么都不做。这就是场景 1 疑问 2 的答案。

**⑥ T2 醒来**：回到循环头 → 前驱（head）是 head → tryAcquire 成功 → `setHead` 自己当哑节点 → 断开旧 head。**出队不是 T1 做的，是 T2 自己拿到锁时做的**——出队零竞争。

### 2.4 心智模型

```
SIGNAL = "预约唤醒"：我睡之前告诉前驱"你释放时记得叫我"
       → 用一次 CAS 把"唤醒义务"记账，把丢失唤醒从根上排除
非公平的"插队"只发生在 lock()/tryAcquire 的 CAS——
  T2 一旦入队，队列内部严格 FIFO（03-02 §3）
```

### 2.5 追问：tryAcquire 到底是什么

**一句话定义**：一次"非阻塞的获取尝试"——立即返回 boolean：`true` = 拿到了（state 已被自己改掉），`false` = 没拿到（别人拿着），**绝不排队、绝不阻塞**。

**三个时机（同一个方法，三种用途）**：

```
时机①  acquire 入口：tryAcquire → 成功则整个流程结束（快路径）
时机②  acquireQueued 循环：前驱是 head 时再试 → 成功 setHead 晋升；失败 → 睡
时机③  被唤醒后：回到循环 → 又是 tryAcquire → 确认"现在轮到我了"
```

**"try" 的威力**：同一个无副作用尝试，既当快路径、又当唤醒后的确认——试试不行再睡，睡得心安理得。

**契约（硬要求）**：

| 契约 | 含义 | 违反的后果 |
|---|---|---|
| 快速返回 | 不许阻塞、不许长自旋 | 队列 + park 机制白设计 |
| 无副作用 | 失败不改任何状态、不入队 | 调用方无法安全重试 |
| 自己改 state | CAS/重入在内部完成 | AQS 不知道"什么是锁" |

**JDK 8 三分支（RL:129，互斥锁的语义全集）**：

```java
if (c == 0) {                                    // ① 锁空闲 → 抢
    if (compareAndSetState(0, acquires)) { setExclusiveOwnerThread(current); return true; }
} else if (current == getExclusiveOwnerThread()) { // ② 自己持有 → 重入计数（无竞争，普通写）
    setState(c + acquires); return true;
}
return false;                                    // ③ 别人持有 → 失败
```

**模板方法的第一性原理**：同一个 AQS 要支撑 ReentrantLock（state=重入次数）、Semaphore（state=许可数）、CountDownLatch（state=计数）——"什么算获取成功"各不相同。所以把**获取规则**抽象成模板方法留给子类，AQS 只写死一件事：**失败 → 排队 → park → 唤醒 → 再试**。队列机制写死（正确性统一守），获取规则放开（语义各取所需）。

**两个对照**：
- `tryAcquire` vs `tryLock`：`tryLock()` 就是把 tryAcquire 直接暴露给你（`Sync.tryLock` → `nonfairTryAcquire`），所以它才能"立即返回"
- `tryAcquire` vs `tryAcquireShared`：独占返回 boolean（能不能），共享返回 int（能，还剩多少）——`<0` 失败、`≥0` 成功且值本身是剩余资源数

### 2.6 追问：addWaiter 到底是什么

**一句话定义**：把当前线程包装成 Node，无锁（CAS）挂到队列队尾。它回答"抢不到的话先把我排上"——**必然成功**（enq 自旋保证），只是抢锁不一定。

**与 tryAcquire 的契约差异**：

| | tryAcquire | addWaiter |
|---|---|---|
| 问的问题 | "现在能归我吗？" | "能把我排进去吗？" |
| 结果 | **可能失败**（锁被占）| **必然成功**（自旋到成功）|
| 受什么约束 | 锁的状态（state）| 只有 CAS 竞争 |
| 副作用 | 无 | 队列结构变化 |

**源码四件事（AQS:605）**：包装（线程 + mode）→ 读 tail → 快速路径 → enq 兜底。首次竞争时队列为空走 `enq` 初始化（AQS:583）：**先建 head 哑节点，再自旋入队**——队列是懒惰创建的，无竞争的场景 1 整个运行期间队列不存在。

**三个设计点**：

```
① 为什么入队也 CAS 而不锁队列？
   排队的人本来就抢锁失败，入队加锁 = "锁上再排队"的递归矛盾；
   锁是阻塞式的，入队还要睡荒谬；CAS 乐观（冲突窗口纳秒级），失败自旋几乎必成功。

② 为什么"prev 先设、next 后设"？
   prev = 资格证明（从 tail 往前遍历能发现我），入队第一步就可靠；
   next = 通知关系（前驱释放能顺着找到我），CAS 成功后才建立。
   两步间有窗口 → next 是"延迟建立的" → unparkSuccessor 必须从 tail 往前找。

③ 为什么节点带 mode（EXCLUSIVE/SHARED）？
   共享模式获取成功后要传播唤醒（Semaphore/CountDownLatch），队列需要知道节点类型；
   EXCLUSIVE = null 是巧妙编码（独占标记直接复用 null，省一个对象）。
```

### 2.7 追问：入队为什么必须用 CAS（不能 `tail = node`）

**反证：T2、T3 同时入队，普通赋值会丢节点**：

```
T2: node2.prev = 哑节点
T3: node3.prev = 哑节点       ← 都读到同一个旧 tail
T2: tail = node2
T3: tail = node3              ← 覆盖！node2 从队列丢失
→ 丢失 = 没人唤醒 node2 的线程 = 永久阻塞（挂死）
```

问题本质：**读 tail → 改 tail 两步之间有窗口，"最后写者胜"覆盖了前面的入队**。

**CAS 解决**：`compareAndSetTail(pred, node)` 一条原子指令（CMPXCHG）——"如果 tail 此刻还是 pred，就改成 node；否则失败重试"。T3 CAS 失败 → enq 重读 tail=node2 → `node3.prev=node2` → CAS 成功 → 链表完整。**"比较-交换"的意义：我排队依据的队尾必须是真实的，不能是过期的。**

**为什么不用锁（三个理由，每个都致命）**：

| 理由 | 展开 |
|---|---|
| 递归矛盾 | 入队的人本来就因抢锁失败而来，入队再加锁 = 锁上排队的循环悖论 |
| 阻塞不可接受 | 锁是阻塞式的，入队还要 park？连队都没排上先睡在锁上 |
| 杀鸡用牛刀 | 冲突窗口纳秒级，CAS 自旋几乎必成功；锁引入内核级唤醒/上下文切换 |

**为什么 `node.prev = pred` 可以先普通写？**——prev 是"预备"，CAS 才是"定案"：CAS 失败时 prev 指向过期队尾，enq 重试会覆盖它。失败的普通写无副作用 → 原子性只花在"决定队列结构的那一下"。

> 一句话：**tail 是 volatile 多写者共享字段（写它必须原子 + 期望值校验 → CAS）；node.prev 是单写者私有字段（写错可覆盖 → 普通写）**。这与 1.3 的 state/owner 分工是同一个思想在队列上的复现。

### 2.8 追问：为什么设计快速路径？这是非公平锁的特性吗？

先拆一个概念：**"快速路径"有两个层面，一个和非公平有关，一个和公平性无关**。

**层面 A：锁获取的快速路径——非公平锁独有（两处不查队列的 CAS）**

```java
// 非公平锁（RL:205）
final void lock() {
    if (compareAndSetState(0, 1))    // ★ 入口直接抢——不管队列里有没有人
        setExclusiveOwnerThread(Thread.currentThread());
    else
        acquire(1);
}
// 公平锁
final void lock() { acquire(1); }    // ★ 没有入口抢
```

非公平锁的特权是 `lock()` 入口 + `tryAcquire` **两处不查队列的 CAS**——只要锁空闲（state==0），哪怕队列里有人排着也能插队成功（插队窗口 = 锁空闲的瞬间）。

**层面 B：数据结构操作的快速路径——所有锁都有，与公平性无关**

`addWaiter` 的"一次 CAS 尝试，失败走 enq"（2.6）是乐观并发的通用代码模式，公平锁、共享模式、Condition 全用同一套。

**关键认知：公平锁也有快速路径，只是多两次 volatile 读**

```java
// 公平锁 tryAcquire
if (c == 0) {
    if (!hasQueuedPredecessors() && compareAndSetState(0, acquires))  // ★ 先查队列
        ...
}
```

无竞争时公平锁一样是"一次 CAS 拿锁"——CAS 前多一次 `hasQueuedPredecessors()`（两次 volatile 读 + 一次比较，纳秒级）。**两个锁无竞争时都是快路径，差别只在竞争时让不让插队。**

**为什么设计快速路径（第一性原理）**：

```
① 常态覆盖：锁的常态是"无竞争/低竞争、短临界区"
   一次 CAS ≈ 纳秒级；节点分配 + 入队 ≈ 几十纳秒；park/unpark + 上下文切换 ≈ 微秒级
   → 快路径决定大盘性能，慢路径只是兜底（差两到三个数量级）
② 懒惰：队列（Node 分配、CAS）只在首次竞争时创建——场景 1 全程无队列
③ 乐观：先试最便宜的操作，失败才升级到昂贵的
   升级链：CAS 一次 → tryAcquire 再试 → 入队 + park（纳秒 → 微秒）
```

**收尾：快速路径是"优化"，插队是"策略"，两者独立**

```
快速路径（所有锁）  = 无竞争时一步拿锁 —— 性能优化，公平锁也享受
插队（非公平锁独有）= 竞争时允许抢在队首前 —— 公平策略，用吞吐换等待公平
```

> 由此也解开一个调试疑问：观察"T2 直接拿到锁、没入队"有两种可能——要么无竞争（快路径生效），要么竞争窗口被插队（非公平策略生效）。场景 2 里 T2 被主线程持锁挡住才入队，恰好证明了慢路径的完整旅程。

### 2.9 追问：acquire 语义是什么

**一句话定义**：获取锁的总入口——**抢不到就一直等（park 阻塞），直到拿到锁才返回**。它保证"最终一定拿到"，不保证等待时间，也不因中断而放弃。

**源码五行（AQS:1197）——它自己不做任何事，只是编排**：

```java
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&                             // ① 抢（子类规则）
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))  // ② 失败 → 入队 → 排队等待
        selfInterrupt();                                // ③ 等待期间被中断过 → 补标志
}
```

求值顺序（嵌套决定执行顺序）：

```
① tryAcquire 先执行：成功 → 短路（&& 左边 false，右边不执行）→ 返回，锁已到手
② 失败 → addWaiter 先执行完（入队必然成功）→ acquireQueued 拿 node 开始循环
③ acquireQueued 返回 true（= 等待中被中断过）→ selfInterrupt() 把标志还给你
```

acquire 自己不碰 state、不碰队列——只把四个组件按正确顺序串起来，这就是模板方法里 AQS"写死"的骨架。

**灵魂：中断语义——"响应但不放弃"**：

| 阶段 | 发生什么 |
|---|---|
| park 等待中被打断 | park 立即返回（**响应了**），线程醒来 |
| 醒来后 | 循环继续，**不抛异常、不放弃等待** |
| 拿到锁后 | `acquireQueued` 返回 interrupted=true → `selfInterrupt()` 补回中断标志 |

中断状态全程保留、只是不中断等待——这就是 `lock()` 与 `lockInterruptibly()` 的分水岭（后者把"记标志"换成"抛异常"，见场景 4）。

**参数 `arg`：获取"多少个单位"**——`lock()` 传 1（每次 lock 让 state += 1）；Semaphore 传许可数。arg 原样传给 tryAcquire/acquireQueued，由子类解释——AQS 只保证"arg 被一致地传递"。

**家族对照：四种获取方式 = 同一骨架的变体**：

```java
acquire(arg)                 // 不可中断：中断只记标志
acquireInterruptibly(arg)    // 可中断：中断 → 抛 InterruptedException
tryAcquireNanos(arg, ns)     // 可中断 + 超时：deadline 到期 → 取消出队返回 false
tryAcquire()                 // 不等待：一次尝试立即返回
```

变体差异全部浓缩在 acquireQueued 的循环里：中断版把 `parkAndCheckInterrupt()` 的 true 从"记标志"改成"抛异常"；超时版把 `park` 换成 `parkNanos(deadline - now)`，到期 `cancelAcquire` 退出。**一个骨架，四种语义。**

**为什么 acquire 是 `final`**——骨架不可覆盖：子类能改写它就能绕过"失败→排队"的编排，队列机制的正确性（丢失唤醒防护、FIFO）全被绕开。AQS 只把钩子（tryAcquire/tryRelease 等"规则"）留给子类，骨架写死是"正确性统一守"的语法保证。

**契约清单**：

```
必然性：返回时当前线程必持有锁（无取消路径，异常才 cancel）
阻塞性：拿不到就 park，不烧 CPU（与自旋对比）
可见性：返回时 state 已 +arg、owner 已设
无超时、不可中断：语义上"死等"——这就是生产规范偏爱 tryLock(timeout) 的原因
```

---

## 3. 场景 3：可重入 —— state 计数的威力

### 3.1 例子与运行输出

```java
lock.lock();  lock.lock();     // state 0→1→2
lock.unlock();                 // state 2→1，锁还在
lock.unlock();                 // state 1→0，真正释放
```

```
【1】重入 2 次后 state = 2（expect 2）
【2】unlock 1 次后 state = 1（expect 1）
【3】unlock 2 次后 state = 0（expect 0）
```

### 3.2 源码：重入分支为什么不需要 CAS

```java
} else if (current == getExclusiveOwnerThread()) {   // 只有持有者能进这个分支
    int nextc = c + acquires;
    if (nextc < 0) throw new Error("Maximum lock count exceeded");
    setState(nextc);                                  // ★ 直接写，不 CAS
    return true;
}
```

第一性原理：**重入 = 没有并发**。能进这个分支的只有持锁者自己——不存在第二个线程同时改 state（锁还被自己拿着）。没有竞争 → 不需要 CAS → 普通写 + volatile 可见性足够。

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();     // 不是持有者不能释放
    boolean free = false;
    if (c == 0) { free = true; setExclusiveOwnerThread(null); }   // 归零才真正释放
    setState(c);
    return free;      // false = 只是少了一层，锁还在
}
```

> **为什么释放也不用 CAS？** 只有持锁者能走到这里（非法调用直接抛异常）——又是"无竞争 → 普通写"。**CAS 只用在真正可能并发的地方**：获取锁（多人抢）、入队（多人抢 tail）。

---

## 4. 场景 4：可中断与超时 —— 语义差异的源码落点

### 4.1 例子与运行输出

```java
// 主线程持锁，T 调 lockInterruptibly() 排队，然后 t.interrupt()
```

```
主线程: t.interrupt()
T: 等待中被中断 → InterruptedException（可中断语义）
```

### 4.2 三种获取方式的中断语义对比

| 方式 | 中断时行为 | 源码落点 |
|---|---|---|
| `lock()` | 记录标志，继续等，拿到锁后 `selfInterrupt()` 补回 | `acquireQueued` 返回 interrupted |
| `lockInterruptibly()` | **立即抛 InterruptedException** | `doAcquireInterruptibly`：`throw new InterruptedException()` |
| `tryLock(timeout)` | 抛 InterruptedException（若等待中被打断） | `doAcquireNanos` 同样抛 |

```java
// 同一处 park，两种命运 —— 唯一的差别是中断后做什么：
if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
    interrupted = true;                       // acquire：记标志，继续等
    throw new InterruptedException();         // acquireInterruptibly：立刻退出
```

### 4.3 tryLock(timeout)：deadline + 有限 park

```java
long deadline = System.nanoTime() + nanosTimeout;
for (;;) {
    ...
    nanosTimeout = deadline - System.nanoTime();   // 剩余时间
    if (nanosTimeout <= 0L) { cancelAcquire(node); return false; }   // 超时 → 取消出队
    if (shouldParkAfterFailedAcquire(p, node) &&
        nanosTimeout > spinForTimeoutThreshold)    // 剩余 < 1000ns 不再 park
        LockSupport.parkNanos(this, nanosTimeout); // 有限时间阻塞
}
```

两个设计动机：
1. **deadline 而不是累减**：用绝对截止时间算剩余，避免每次 park 的误差累积
2. **`spinForTimeoutThreshold = 1000ns`**：park/unpark 本身有开销（系统调用级），剩余时间小于它时直接自旋等——"park 的成本比等的时间还贵，就别 park"

超时/中断后的 `cancelAcquire(node)`：节点标 CANCELLED → 出队时被前后绕过；如果是队尾，还要负责唤醒下一个（防止唤醒丢失）——完整逻辑见 03-01 §5.3。

---

## 5. 场景 5：Condition —— 两队切换 + SIGNAL 接力（⭐⭐⭐）

### 5.1 例子与运行输出

```java
ReentrantLock lock = new ReentrantLock();
Condition notEmpty = lock.newCondition();
Thread consumer = new Thread(() -> {
    lock.lock();
    notEmpty.await();          // 条件不满足 → 入条件队列 → 释放锁 → park
    lock.unlock();
}, "C");
consumer.start();
Thread.sleep(200);             // C 已 await
lock.lock();                   // 主线程能拿到锁 → 证明 await 释放了锁
notEmpty.signal();             // 只搬家，不唤醒
lock.unlock();                 // 这里才真正唤醒 C
```

```
C: 持锁，条件不满足 → notEmpty.await()
主线程: lock() 成功 —— 证明 await 已释放锁
观察 a：C 状态（应为 WAITING）: WAITING
观察 b：signal 之后 C 状态（应仍是 WAITING，信号接力中）: WAITING   ← ★
C: await 返回（已重新抢到锁），条件满足
观察 c：C 已结束（unlock 才真正唤醒它）
```

### 5.2 观察 b 验证了什么

**signal 之后 C 仍是 WAITING —— signal 根本不 unpark**。它只做一件事：把 C 从条件队列搬进同步队列（`transferForSignal → enq`），并在前驱上设好 SIGNAL 预约。C 的唤醒由**之后的 unlock → release → unparkSuccessor** 接力兑现（与场景 2 完全同一条路径）。

这是 03-02 §7.4 时序的正确版本：**signal = 搬家，唤醒 = 接力**。绝大多数讲解（和早期文档）把 signal 说成"唤醒线程"——用观察 b 的输出就能证伪。

### 5.3 await 四步（注意顺序是因果，不是实现细节）

```
await() 底层做了四件事（顺序不可换）：
  ① 挂进 notFull 条件队列      ← 持锁状态下尾插，条件链表的安全由锁保证
  ② 完全释放锁（state 归零）    ← 抱着锁等 = 死锁（见 03-02 §7.3 因果链）
  ③ park 挂起；被 signal 后 → 搬到同步队列排队等锁
  ④ 抢到锁 → await 返回，此时锁又在自己手里
```

为什么②要"全量释放"？`lock()` × 3 后 await 若只 release(1)，state 还剩 2 → owner 不清 → 其他线程永远进不来。所以 `fullyRelease` 记住欠的层数（savedState），醒来 `acquireQueued(node, savedState)` **如数还债**——可重入在 await 里的落点。

### 5.4 中断与 signal 的竞速（同一张身份牌）

```
await 中被打断 → transferAfterCancelledWait 与 signal 的 transferForSignal
  抢同一个 CAS(CONDITION → 0)，只能赢一次：
  中断赢 → THROW_IE（取消等待，拿回锁后抛异常）
  signal 赢 → REINTERRUPT（合法走完，中断只补记标志）
→ 与场景 4 的 lock()/lockInterruptibly() 哲学同构（03-02 §7.9 ⑤）
```

---

## 6. 疑问清单（两遍法）

学完先自问，再对答案：

<details><summary>① 无竞争释放（1→0）为什么不用 CAS？</summary>

只有持锁者能走到 tryRelease（他人调用直接抛 IllegalMonitorStateException）——无并发 → 普通写即可。CAS 只用在"多人竞争同一处"：抢锁、入队抢 tail。
</details>

<details><summary>② release 里为什么检查 head.waitStatus != 0 而不是"队列非空"？</summary>

waitStatus == 0 表示"没有后继预约唤醒"（没人在等），== SIGNAL 才有活要干。用状态判断比遍历队列便宜，且正好对应 SIGNAL 的记账语义。
</details>

<details><summary>③ 非公平锁的两次抢锁机会分别在哪？</summary>

第一次：NonfairSync.lock() 入口的 CAS；第二次：acquire → tryAcquire（nonfairTryAcquire 里 c==0 时的 CAS）。两次都发生在入队之前。
</details>

<details><summary>④ signal 到底唤不唤醒线程？</summary>

不直接唤醒。signal = 搬进同步队列 + 预约 SIGNAL；唤醒由后续 unlock → unparkSuccessor 接力完成（场景 5 观察 b 的实证）。
</details>

<details><summary>⑤ owner 字段为什么敢不用 volatile？</summary>

读错 owner 是安全失败：新线程最多误判"我不是持有者"而多排一次队；互斥由 state 的 CAS 守住，owner 只是身份牌。
</details>

---

## 7. 自测题

1. **无竞争 lock() 的完整开销是哪几步？** 哪一步是队列被真正创建的分水岭？
2. **为什么"park 前必须把前驱设为 SIGNAL"？画出丢失唤醒的时间线。**
3. **重入为什么不需要 CAS？释放为什么不需要 CAS？**
4. **acquire 与 acquireInterruptibly 对中断的处理差在哪一行代码？**
5. **tryLock(timeout) 为什么用 deadline 而不是递减计数？spinForTimeoutThreshold 解决什么问题？**
6. **await 四步的顺序为什么是"入队→释放→park"而不是"释放→入队→park"？**
7. **用场景 5 的实验结果说明：signal 和唤醒之间隔着什么？**

---

> 📬 **5 个场景走完，你已经能对着 jdk8-lab 里的 `AqsWalkthroughTest.java` 讲出 ReentrantLock 的每一段源码。继续 [03-03-ReentrantReadWriteLock与StampedLock](./03-03-ReentrantReadWriteLock与StampedLock.md) —— 看 state 高低位拆分如何在同一个 AQS 上叠加读写两套语义**

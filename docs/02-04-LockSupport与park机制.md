# 02-04 LockSupport 与 park/unpark 线程阻塞原语

> **阶段二·第 4 篇** | 前置：[02-03-wait-notify与ObjectMonitor](./02-03-wait-notify与ObjectMonitor.md) | 后续：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)  
> **建议时长**：2~3 小时（permit 机制 1h + 对比/中断/伪唤醒 1h + 练习 1h）  
> 🔑 **AQS 的地基**：AQS 阻塞线程的唯一工具是 park/unpark——`acquireQueued` 里 park、`await` 里 park、`unparkSuccessor` 里 unpark。不学透 permit 机制，AQS 里每个"唤醒设计"都是悬空的

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| ⭐⭐⭐ | **permit（许可）机制**、park/unpark 语义、**unpark 可提前**（不丢唤醒的第一性原理） | **必须能画 permit 状态机 + 推演时序** |
| ◈◈ | 与 wait/notify 的系统对比、中断响应差异、伪唤醒 | 理解差异 + 能讲清因果 |
| ○ | Unsafe.park 底层、parkNanos/parkUntil | 知道概念 |

---

## 1. park 在 JUC 里的位置

```
JUC 的锁/同步器（ReentrantLock、Semaphore、CountDownLatch、Condition...）
        │
        ▼ 排队后怎么睡？醒来靠谁？
   LockSupport.park / unpark        ← AQS 阻塞线程的唯一工具
        │
        ▼
     Unsafe.park / unpark           ← JVM 内建线程挂起原语
```

- 02-03 的 `wait/notify` 属于**内置锁**（JVM ObjectMonitor），必须先持有监视器
- `park/unpark` 属于 **JUC 层**，不依赖任何锁——**任意代码位置都能调用**

**一句话定义**：

> **park = 挂起当前线程（让出 CPU）；unpark(thread) = 唤醒指定线程。它们通过线程的"许可（permit）"交互——这是与 wait/notify 最本质的区别。**

---

## 2. permit（许可）机制 —— park 的灵魂（⭐⭐⭐）

### 2.1 核心模型：每个线程有一个 0/1 的许可槽

```
permit 槽（初始 = 0）
    ├─ park()    ：permit == 1 → 消费掉（置 0），立即返回
    │             permit == 0 → 阻塞，直到 permit 变 1
    └─ unpark(t) ：permit 置 1；若 t 正阻塞 → 唤醒它
```

三个推论：
1. **许可不累积**：连续两次 unpark 后 park 一次，permit 回到 0——第二次 unpark 被"浪费"
2. **unpark 可以无人消费**：permit 一直保存着，等将来的 park 来消费
3. **unpark 唤醒的是"人"不是"队列"**：参数是线程，不是对象监视器

**为什么是 0/1 槽、而不是计数信号量（设计动机）**：

> unpark 的职责是"唤醒一个线程"，不是"投递 N 个信号"。一个线程不可能被打醒两次，计数累积的信号量在这里没有消费者——0/1 是能实现"不丢唤醒"的最小记账。HotSpot 注释引用过 Plan 9 的信号量设计（Mullender & Cox），unpark 强制把许可归 1 还有一层用意：让"先 unpark 后 park"必然空转返回一次，帮助暴露"不用条件变量裸用 park"的代码 bug。

### 2.2 两个关键推演

**推演 ①：先 park 后 unpark（标准流程）**

```
T2: park()      → permit=0 → 阻塞
T1: unpark(T2)  → permit=1 → 唤醒 T2
T2: park() 返回  → permit 被消费（置 0）
```

**推演 ②：先 unpark 后 park（⭐ 唤醒信号可提前保存）**

```
T1: unpark(T2)  → permit=1（T2 还没 park，信号被保存）
T2: park()      → permit=1 → 直接消费返回，不阻塞！
```

推演 ② 就是 **"不丢唤醒"的第一性原理**：唤醒信号在等待者到来**之前**就已就绪，等待者到来时直接消费，不需要"等待者先就位"。

### 2.3 与 wait/notify 的本质差异：唤醒信号能否提前

| | Object.wait/notify | LockSupport.park/unpark |
|---|---|---|
| 信号保存 | **不能**：wait 之前 notify → 信号丢失 | **能**：unpark 提前 → permit 保存 |
| 丢失唤醒 | 需要"检查 + wait 原子"来防御（02-03） | 机制上就不会丢（permit 记账） |

> ⚠️ 对比 02-03 的结论：wait/notify 的"不丢唤醒"靠**调用原子性**（条件检查与 wait 融合）；park/unpark 的"不丢唤醒"靠 **permit 记账**（信号保存到消费为止）——两条路，同一个正确性。

**这个机制在 AQS 里的落点**：`transferForSignal`（Condition 搬家）里"前驱已取消就直接 unpark"——唤醒可以发生在等待者真正 park 之前，因为 permit 会兜住它。03-01/03-02 里所有"提前唤醒"的设计，根都在这一节。

---

## 3. 与 wait/notify 的系统对比（◈◈）

| 维度 | Object.wait/notify | LockSupport.park/unpark |
|---|---|---|
| 依赖 | 必须持有监视器（synchronized 内）| 任意位置 |
| 唤醒信号 | notify 不可提前（wait 前 notify 丢失）| **unpark 可提前（permit 保存）** |
| 中断 | 抛 InterruptedException（标志清除）| **返回但不抛异常（标志保留）** |
| 超时 | wait(timeout) | parkNanos / parkUntil |
| 伪唤醒 | 有 | 有 |
| 与锁的关系 | wait 释放监视器 | 不涉及锁（释放与否由调用方决定）|
| 等待队列 | ObjectMonitor 的 _WaitSet | 无（permit 机制，不排队）|

---

## 4. 中断响应：返回但不抛异常（⭐）

```java
// 验证：park 中的线程被 interrupt 会怎样？
Thread t = new Thread(() -> {
    LockSupport.park();
    System.out.println("park 返回，中断标志 = " + Thread.currentThread().isInterrupted());
});
t.start();
Thread.sleep(100);
t.interrupt();          // 中断 → park 立即返回
t.join();
// 输出：park 返回，中断标志 = true   ← 不抛异常！标志保留！
```

与 wait 对比（02-03）：

| | wait | park |
|---|---|---|
| 中断后 | 抛 InterruptedException，标志**清除** | **不抛异常**，标志**保留** |
| 语义 | "中断即退出" | "中断只是唤醒，是否退出由你决定" |

**对 AQS 的意义（因果链）**——这是 03-02-01 2.9 的 `parkAndCheckInterrupt` 空转问题的根源：

```
① 中断到来 → park 返回（标志还在）
② acquire 语义 = 不可中断 → 循环继续
③ 不清除标志 → 下次 park 立即返回 → 忙循环空转！
④ 所以 parkAndCheckInterrupt 用 Thread.interrupted() 消费标志（清除），
   把"被中断过"记进局部变量，拿到锁后 selfInterrupt() 补回
```

**一句话：park 把"中断是否等于退出"的决定权完全交给调用方——这正是 AQS 能实现"不可中断获取"的基础。**

### ⚠️ JDK 8 Windows 平台差异：interrupt 的"幽灵信号"（探针实证）

本机（JDK 8.0_441 + Windows）上用 [ParkPermitTest](../jdk8-lab/src/test/java/com/sw/yang/concurrent/juc/locks/ParkPermitTest.java) 逐条验证时，发现两个上面模型没覆盖的机制级现象：

1. **interrupt() 会留下"幽灵信号"**：`os::interrupt` 调 `parker()->unpark()`（SetEvent），即使线程没在 park，信号也挂在事件对象上。带标志的 `park()` 会消费它；但 **`Thread.interrupted()` 只清标志、不碰事件**——所以"interrupt → interrupted() → park"序列里，若中间没有 park() 垫底，清除标志后的第一个 park 仍会立即返回一次（实证：全新线程上 `interrupt(); interrupted(); parkNanos(300)` = 0ms）。
2. **控制台输出会"复活"幽灵**：`park()` 消费幽灵之后，若在中断相关时序段内发生任何 **stdout/stderr 实际写入**（`System.out.print` 等；**普通文件写不会**），幽灵会重新出现，导致后续 `parkNanos` 提前返回（实证：同样的序列，插入 `print("")` = 0ms，不插入 = ~300ms；synchronized/分配/yield/flush 均不触发）。

> 对 AQS 的影响：不影响语义正确性——`acquireQueued` 的 `parkAndCheckInterrupt` 本来就是"醒来重查 + 消费标志"的循环，多一次幽灵返回只是多一次循环迭代。但对**验证实验**影响很大：时序敏感段（interrupt → park → interrupted → parkNanos）内不能穿插任何控制台 I/O，否则断言会假失败。ParkPermitTest 最初的两次构建失败正是测试自身的 println 插在了时序段里（已在测试内注明并修正）。

**机制归属**：现象 1 是"SetEvent 信号残留 + interrupted() 不消费"的组合；现象 2 的具体 VM 内部路径未进一步定位（指向 Windows 控制台 I/O 的 JVM 特殊处理）。Linux/macOS 上 parker 用 `_counter` + `pthread_cond`（`pthread_cond_signal` 无等待者即丢失），不存在信号残留——**幽灵信号是 JDK 8 Windows 特有的实现细节**。

---

## 5. 伪唤醒（Spurious Wakeup）（◈◈）

**park 可能无缘无故返回**（JVM/OS 层的未定义行为，现实中罕见但被规范允许）。

应对：**循环重查条件**——这是 AQS 所有循环的由来：

```java
// acquireQueued 的循环：醒来必须重新确认"前驱是 head 且抢到锁"
for (;;) {
    if (p == head && tryAcquire(arg)) { ... return; }   // 醒来重查
    if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
        ...
}

// await 的循环：醒来必须确认"真的被 signal 了"
while (!isOnSyncQueue(node)) {
    LockSupport.park(this);
}
```

> 与 02-03 的假唤醒防御（while + 条件检查）完全同构——**park 也一样必须 while**。

---

## 6. 常用 API 一览（○）

| API | 语义 | 场景 |
|---|---|---|
| `park()` | 无限期挂起（permit=0 时）| AQS 排队等待 |
| `parkNanos(ns)` | 限时挂起 | `doAcquireNanos` 超时获取 |
| `parkUntil(deadlineMs)` | 挂起到绝对时刻 | 定时任务 |
| `unpark(thread)` | 唤醒指定线程 | `unparkSuccessor` |

与 `Thread.sleep` 对比：

| | sleep | park |
|---|---|---|
| 谁唤醒 | 只能等超时 | unpark 可提前唤醒 |
| 中断 | 抛 InterruptedException | 返回不抛（见 §4）|
| permit | 不涉及 | 消费 permit |
| 锁 | 不释放（如果持有）| 不涉及 |

---

## 7. 底层：Unsafe.park（○）

```java
// JDK 8 的 LockSupport 就是对 Unsafe 的薄封装
public static void park() {
    UNSAFE.park(false, 0L);
}
public static void unpark(Thread thread) {
    if (thread != null)
        UNSAFE.unpark(thread);
}
```

`Unsafe.park(boolean isAbsolute, long time)` 是 JVM 内建原语（hotspot 的 `Unsafe_Park`）：
- Windows：Win32 **事件对象**——`WaitForSingleObject` 阻塞 + `SetEvent` 唤醒，配一个原子状态变量 `_Event`（不是旧时代 JVM 的 `SuspendThread`/`ResumeThread`）
- Linux/macOS：`pthread_cond_wait`/`pthread_cond_signal`
- permit 的 0/1 语义 + "线程是否正阻塞"的编码 = `_Event` 计数器的三态：**1=已发信号 / 0=中性 / -1=正在阻塞**

一个关键细节（permit 记账的物理实现）：`unpark()` 用 `Atomic::xchg(1, &_Event)`，**只有交换前的值是 -1（线程确实在阻塞）才调 `SetEvent`**——没有等待者时只改状态、不发内核信号，信号存在线程自己的状态里而不是内核事件里。park 返回时把 `_Event` 清回 0（源码注释："just in case multiple unpark() operations drove _Event up to 1"），这正是 §2.1 推论 1"许可不累积"的落地。

**不需要深挖平台实现——只要记住：permit 是跨平台一致的语义抽象，AQS 只依赖这个抽象。**

---

## 8. 常见坑点

### 🕳️ 坑 1：忘记 while 循环处理伪唤醒

```java
// ❌
if (condition) { LockSupport.park(); }   // 伪唤醒 → 直接执行，条件可能未满足
// ✅
while (!condition) { LockSupport.park(); }   // 醒来重查
```

### 🕳️ 坑 2：把 park 当 sleep 用

`park` 不是定时器——它随时可能被 unpark 唤醒，且唤醒后不保证"睡够"。定时需求用 `parkNanos`/`parkUntil`，但**必须先想清楚谁负责唤醒你**。

### 🕳️ 坑 3：中断标志不清除导致 park 失效（空转）

线程带中断标志调用 park → 立即返回 → 忙循环。若代码消费了标志（`Thread.interrupted()`），**必须在退出前恢复或抛异常**（03-02 §8 坑 4）。

---

## 9. 自测题

1. **先 unpark 再 park，会发生什么？为什么这实现了"不丢唤醒"？**
   <details><summary>答案</summary>

   park 直接返回不阻塞——unpark 把 permit 置 1 保存，park 到来时消费掉。唤醒信号可提前保存，等待者无需先就位。
   </details>

2. **连续调用两次 unpark，再调用一次 park，permit 是多少？**
   <details><summary>答案</summary>

   第一次 unpark 置 1，第二次无效（许可不累积，0/1 槽），park 消费后归 0。
   </details>

3. **park 被中断后会发生什么？和 wait 被中断有什么区别？**
   <details><summary>答案</summary>

   park 立即返回、不抛异常、中断标志保留；wait 抛 InterruptedException 且标志清除。park 把"是否退出"的决定权交给调用方。
   </details>

4. **为什么 parkAndCheckInterrupt 必须用 Thread.interrupted()（清除标志）而不是 isInterrupted()？**
   <details><summary>答案</summary>

   不清除标志，acquireQueued 循环下次 park 立即返回 → 忙循环空转。消费标志后把"被中断过"记进局部变量，拿到锁后 selfInterrupt() 归还。
   </details>

5. **为什么 AQS 的唤醒设计（如 transferForSignal 提前 unpark）依赖 permit 机制？**
   <details><summary>答案</summary>

   唤醒可以发生在等待者 park 之前——unpark 提前置 permit，等待者到来时直接消费，不会丢。这是"不丢唤醒"的机制保障。
   </details>

---

> 📬 **学完本篇，去 03-01 看 AQS 时，把每个 park/unpark 调用都对照 §2 的 permit 模型过一遍。配套实验：[ParkPermitTest](../jdk8-lab/src/test/java/com/sw/yang/concurrent/juc/locks/ParkPermitTest.java) 逐条验证 §2/§4 的 permit 结论（8 个场景对应推演①②、推论 1/3、中断四态）；[03-02-01 §2 场景 2](./03-02-01-ReentrantLock-实例.md) 里 T2 的 WAITING 状态是 park 在 AQS 里的直接观察**

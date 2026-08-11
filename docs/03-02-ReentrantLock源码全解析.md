# 03-02 ReentrantLock 源码全解析

> **阶段三·第 2 篇** | 前置：[02-03-wait/notify与ObjectMonitor](./02-03-wait-notify与ObjectMonitor.md)、[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md) | 后续：[03-03-ReentrantReadWriteLock与StampedLock](./03-03-ReentrantReadWriteLock与StampedLock.md)（待发布）  
> **建议时长**：5~6 小时（类结构 1h + 公平/非公平 2h + 可重入/中断/超时 1.5h + 练习 1.5h）  
> 🛠️ **日常高频**：ReentrantLock 是显式锁的代表，理解它 = 理解 JDK 如何应用 AQS

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | 公平锁 vs 非公平锁（tryAcquire 差异）、可重入实现、lock/lockInterruptibly/tryLock 四方式对比 | **能对比两段源码 + 能说出适用场景** |
| ◈◈ | 两次抢锁机会的时序、hasQueuedPredecessors、非公平锁为何吞吐高 | **理解设计意图** |
| ○ | 类结构的继承体系细节 | **了解即可** |

---

## 1. 类结构总览

```
ReentrantLock
├── implements Lock, java.io.Serializable
├── Sync（内部抽象类，继承 AQS）      ← 公共逻辑
│   ├── FairSync（公平锁）            ← tryAcquire 先检查排队
│   └── NonfairSync（非公平锁）       ← tryAcquire 直接抢
│
└── lock() 默认用 NonfairSync
    new ReentrantLock()          → 非公平锁（默认）
    new ReentrantLock(true)      → 公平锁
```

```java
public class ReentrantLock implements Lock, Serializable {
    private final Sync sync;

    // 默认非公平锁
    public ReentrantLock() { sync = new NonfairSync(); }
    // 可选公平锁
    public ReentrantLock(boolean fair) { sync = fair ? new FairSync() : new NonfairSync(); }

    public void lock()     { sync.lock(); }
    public void unlock()   { sync.release(1); }
    public Condition newCondition() { return sync.newCondition(); }
}
```

---

## 2. 非公平锁源码解析

### 2.1 NonfairSync.lock() —— 第一次抢锁机会

```java
static final class NonfairSync extends Sync {
    final void lock() {
        if (compareAndSetState(0, 1))          // ★ 直接 CAS 抢锁（不排队！）
            setExclusiveOwnerThread(Thread.currentThread());
        else
            acquire(1);                        // 抢不到 → 走 AQS 流程
    }
}
```

```
关键点：
- 非公平锁在 lock() 里就先 CAS 一次（此时完全不管队列里有没有人排队）
- 这叫"插队"：新来的线程有机会插到排队线程前面
```

### 2.2 NonfairSync.tryAcquire —— 第二次抢锁机会

```java
protected final boolean tryAcquire(int acquires) {
    return nonfairTryAcquire(acquires);
}

// 实现位于父类 Sync
final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();

    if (c == 0) {                                  // ① 锁空闲
        if (compareAndSetState(0, acquires)) {     // ② 直接 CAS 抢
            setExclusiveOwnerThread(current);
            return true;
        }
    } else if (current == getExclusiveOwnerThread()) {  // ③ 已持有（重入）
        int nextc = c + acquires;                  // ④ 计数 +1
        if (nextc < 0) throw new Error("Maximum lock count exceeded");
        setState(nextc);                           // 重入不需要 CAS（只有持有者能进）
        return true;
    }
    return false;                                  // ⑤ 失败 → AQS 排队
}
```

```
非公平锁的两次抢锁机会：
  第 1 次：lock() 方法入口直接 CAS（完全不排队）
  第 2 次：acquire → tryAcquire 又 CAS 一次
  → 只要锁空闲，新来的线程就有两次"插队"机会
```

---

## 3. 公平锁源码解析

### 3.1 FairSync.lock() —— 没有抢先逻辑

```java
static final class FairSync extends Sync {
    final void lock() {
        acquire(1);   // ★ 直接走 AQS，不在 lock() 里抢
    }
}
```

### 3.2 FairSync.tryAcquire —— 先检查有没有人排队

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();

    if (c == 0) {                                       // ① 锁空闲
        if (!hasQueuedPredecessors() &&                 // ★ 检查队列中是否有人在等待
            compareAndSetState(0, acquires)) {          // 没人排队才抢
            setExclusiveOwnerThread(current);
            return true;
        }
    } else if (current == getExclusiveOwnerThread()) {  // ② 重入（和公平锁一样）
        int nextc = c + acquires;
        if (nextc < 0) throw new Error("Maximum lock count exceeded");
        setState(nextc);
        return true;
    }
    return false;
}
```

### 3.3 核心差异：hasQueuedPredecessors()

```java
// 公平锁的关键方法：判断队列中是否有"比我排得早"的线程
public final boolean hasQueuedPredecessors() {
    Node t = tail;
    Node h = head;
    Node s;
    return h != t &&                              // 队列非空
        ((s = h.next) == null ||                 // head.next 为空
         s.thread != Thread.currentThread());    // 或下一个等待者不是我
}
```

```
逻辑解读：
  h == t：队列空或只有一个哑节点 → 无人排队 → 可以抢
  h != t：有人排队 → 检查 head.next 是不是自己
    是自己 → 该我抢（我排在最前面）
    不是自己 → 有人排在我前面 → 不抢，继续等

h != t 但 head.next == null 是什么情况？（竞态窗口，必须保守返回 true）
  addWaiter 入队分两步：① CAS 把 tail 指向新节点 → ② 把旧 tail 的 next 链到新节点
  两步之间有窗口：tail 已移走、新节点还没从 head 链过来 → 此刻 h != t 且 h.next == null
  那个正在入队的线程马上就会排到 head.next 的位置
  若此时返回 false 去抢锁 → 新来的插队 → 该线程被永久跳过 → 公平性被破坏
  → 公平锁宁可多等一轮，也不能在这个窗口插队
```

### 3.4 公平 vs 非公平代码对比

```java
// ──────────────────────────────
// 非公平锁 NonfairSync
// ──────────────────────────────
final void lock() {
    if (compareAndSetState(0, 1))    // ★ 抢！（不检查队列）
        setExclusiveOwnerThread(Thread.currentThread());
    else
        acquire(1);
}

final boolean nonfairTryAcquire(int acquires) {
    ...
    if (c == 0) {
        if (compareAndSetState(0, acquires))   // ★ 又抢！（不检查队列）
        ...
    }
}

// ──────────────────────────────
// 公平锁 FairSync
// ──────────────────────────────
final void lock() {
    acquire(1);    // 直接排队（没有抢先）
}

protected final boolean tryAcquire(int acquires) {
    ...
    if (c == 0) {
        if (!hasQueuedPredecessors() &&   // ★ 先检查队列！
            compareAndSetState(0, acquires))
        ...
    }
}
```

---

## 4. 为什么非公平锁吞吐量更高？（面试重点）

### 4.1 对比分析

```
公平锁的问题：
  每次获取锁都要检查队列（hasQueuedPredecessors）
  严格的 FIFO → 等待线程被唤醒后一定能拿到锁（没人能插队）
  → 但代价是：唤醒（unpark）+ 上下文切换的开销，每次锁交接都躲不掉

  公平锁的场景：线程 A 持有锁，线程 B 排队等待
  A 释放锁 → 唤醒 B → B 开始执行
  → 每次切换都伴随线程唤醒（开销大）

非公平锁的场景：
  A 释放锁 → B 还没被唤醒（唤醒有延迟）→ 新线程 C 插队抢到锁！
  → C 直接执行（省了唤醒 B 的等待时间）
  → B 醒来后发现锁又被占了 → 继续等

非公平锁的优势：
  1. 减少了线程唤醒的等待（"唤醒间隙"被插队利用）
  2. 减少了线程切换次数（C 不用睡也不用醒）
  3. 吞吐量更高（每秒能处理更多任务）

非公平锁的代价：
  可能"饿死"长时间等待的线程（极端情况下）
```

### 4.2 为什么"插队"反而更快

```
核心洞察：唤醒一个线程是有延迟的！

线程 A 释放锁的时刻线：
  时刻 0: A 释放锁（state = 0）
  时刻 1: 系统开始唤醒 B（park 恢复有延迟，微秒级）
  时刻 2: B 真正开始跑

非公平锁在时刻 0~2 之间允许 C 插队：
  C 在时刻 0 抢到锁 → 立刻执行 → 时刻 3 完成 → 释放
  → C 完全利用了这个"唤醒间隙"！

公平锁则浪费了这个间隙：
  时刻 0: 锁空闲
  时刻 1: 等待唤醒 B（锁空转！）
  时刻 2: B 开始执行
  → 时刻 0~2 之间锁是空闲的，没有线程在用它
```

### 4.3 什么场景该用公平锁

```
公平锁适用场景：
  1. 对"等待时间"有严格公平性要求（如排队叫号）
  2. 锁被持有时长远大于唤醒开销（竞争激烈且持锁时间长）
  3. 不能容忍线程饥饿（虽然非公平锁饥饿概率很低）

实际生产：绝大多数场景用默认的非公平锁
  - Synchronized 也是非公平的
  - 非公平锁吞吐高，饥饿几乎不出现
```

---

## 5. 可重入实现

```java
// 重入的关键：记录"持有者线程" + "重入次数"
// 持有者：exclusiveOwnerThread（AQS 提供）
// 次数：state（AQS 提供）

// 重入时（非公平锁版本）：
else if (current == getExclusiveOwnerThread()) {
    int nextc = c + acquires;    // state + 1
    setState(nextc);             // 不用 CAS！
    return true;
}

// 释放时：
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;      // state - 1
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();   // 不是持有者不能释放
    boolean free = false;
    if (c == 0) {                     // state 归零 → 真正释放
        free = true;
        setExclusiveOwnerThread(null);
    }
    setState(c);
    return free;                      // false 表示还没完全释放（只是少了一层）
}
```

```
可重入的完整流程：
  lock() × 3        → state = 3，owner = T
  unlock() × 1      → state = 2，free = false（锁还在）
  unlock() × 1      → state = 1，free = false（锁还在）
  unlock() × 1      → state = 0，owner = null，free = true（真正释放）

→ 每个 lock 必须配对 unlock！漏一个锁就永远不会释放
```

---

## 6. 四种获取锁方式对比（🛠️ 面试必考）

```java
lock.lock();                        // ① 不可中断：拿不到就一直等（不响应中断）
lock.lockInterruptibly();           // ② 可中断：等待时被 interrupt → 抛异常
lock.tryLock();                     // ③ 立即返回：拿不到就 false（不等待）
lock.tryLock(3, TimeUnit.SECONDS);  // ④ 限时等待：3 秒内拿到 true，否则 false
```

| 方式 | 等待行为 | 中断响应 | 超时 | 适用场景 |
|---|---|---|---|---|
| `lock()` | 无限等待 | ❌ 不响应（记录标志） | ❌ | 最简单，但可能死等 |
| `lockInterruptibly()` | 无限等待 | ✅ 抛 InterruptedException | ❌ | 需要响应取消（如关闭时） |
| `tryLock()` | 不等待 | — | 立即 | 抢不到就做别的（乐观） |
| `tryLock(timeout)` | 限时等待 | ✅ | ✅ | **防死锁首选** |

### 6.1 为什么 tryLock(timeout) 能防死锁

```java
// ❌ 死锁场景：两个线程互相等对方释放锁
// 线程 1：lock.lock() 等 A，等 B
// 线程 2：lock.lock() 等 B，等 A
// → 都无限等待 → 死锁

// ✅ 防死锁：加超时，拿不到就放弃重试
public boolean tryAcquireBothLocks(ReentrantLock lockA, ReentrantLock lockB) throws InterruptedException {
    if (lockA.tryLock(3, TimeUnit.SECONDS)) {   // 3 秒拿不到 A 就放弃
        boolean gotB = false;
        try {
            gotB = lockB.tryLock(3, TimeUnit.SECONDS);  // 3 秒拿不到 B 就放弃
        } finally {
            if (!gotB) lockA.unlock();  // 没凑齐 → 释放 A（A 是自己持有的，才能安全释放）
        }
        return gotB;   // 两把都拿到 → true，由调用方释放；否则 false
    }
    return false;      // 拿不到 A → 让调用方决定重试或降级
}

// ⚠️ 为什么不能写成"try 里 return true + finally 无条件解锁 B"？
//   ① B 拿到时：return true 会先执行 finally → B 刚拿到就被释放 → 调用方以为持有 B，实际没有 → 临界区失控
//   ② B 没拿到时：lockB.unlock() → 当前线程不持有 B → 抛 IllegalMonitorStateException，方法不会返回 false
//   ③ A 没拿到时：lockA.unlock() → 不持有 A → 同样抛异常
//   → 三条路径全错。正确做法：用布尔标志记录"B 是否拿到"，finally 只兜底"没凑齐"的情况
```

### 6.2 lockInterruptibly 的使用场景

```java
// 场景：应用关闭时，需要打断所有正在等待锁的线程
// 线程阻塞在 lock() 上无法响应 interrupt
// 线程阻塞在 lockInterruptibly() 上 → interrupt → 抛异常 → 线程退出

try {
    lock.lockInterruptibly();   // 等待时可以被 interrupt 打断
    // 临界区
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();  // 恢复中断标志
    // 处理关闭逻辑
} finally {
    if (isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

> 💡 **生产实践**：`lock()` 无限等待是最危险的使用方式，一旦逻辑 bug 就永久卡死。很多团队规范要求：优先 `tryLock(timeout)`，其次 `lockInterruptibly()`。

---

## 7. Condition 在 ReentrantLock 中的使用

### 7.1 场景与契约

```
两个角色共享一个 LinkedList：生产者 put 往里加，消费者 take 往外拿。两条约束：
  ① 队列满（size == CAPACITY）→ 生产者必须停下来等，直到有人拿走一个
  ② 队列空 → 消费者必须停下来等，直到有人放进一个

lock 解决互斥（同一时刻只有一个角色在碰 queue）
Condition 解决等待与唤醒（怎么停下来、怎么被叫醒、醒来的时机）
→ 这是两个独立的问题：互斥不保证"满了怎么办"
```

### 7.2 代码

```java
public class ConditionDemo {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();   // 队列不满
    private final Condition notEmpty = lock.newCondition();  // 队列非空
    private final java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
    private static final int CAPACITY = 10;

    public void put(Object item) throws InterruptedException {
        lock.lock();                                  // 1. 获取锁
        try {
            while (queue.size() == CAPACITY) {
                notFull.await();                      // 2. 满 → 等"不满"
            }
            queue.add(item);
            notEmpty.signal();                        // 3. 唤醒一个消费者
        } finally {
            lock.unlock();                            // 4. 释放锁（必须！）
        }
    }

    public Object take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            Object item = queue.removeFirst();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

### 7.3 核心机制：await 不是"暂停"，是一次四步旅程

```java
notFull.await()  这一行，底层实际做了四件事：

  ① 释放锁（state 归零，owner 置 null）       ← 关键！await 会放掉锁
  ② 把自己挂进 notFull 的等待队列，park 挂起
  ③ 被 signal 后：从条件队列搬到 AQS 同步队列，排队等锁
  ④ 抢到锁 → await() 返回，此时锁又在自己手里
```

```
为什么①必须释放锁？（因果链）
  生产者因"队列满"而等 → 队列要变不满，只能靠消费者拿走 item
  → 消费者要拿走 item，必须进入临界区 → 必须拿到锁
  → 若生产者抱着锁等：锁永远在自己手里，消费者永远进不来 → 死锁
  → 所以 await 释放锁是机制必然，不是实现细节

await 与 sleep/自旋的本质区别：
  sleep：我停，锁也停（占着锁睡）
  await：我停，锁继续流动（放掉锁睡）

第④步同样关键：await 返回时锁被重新持有，状态与 await 前一致
  → 调用者视角："执行到这里暂停一下，然后继续"
  → 底层已完成：释放锁 → 挂起 → 转移 → 重新抢锁 的完整循环
```

### 7.4 时序推演：空队列时 take 走一遍

```
消费者 C: lock()                        → 拿到锁
C: queue.isEmpty() 为 true              → 临界区内检查条件
C: notEmpty.await()：
     ① 释放锁 (state 1→0, owner=null)
     ② C 进入 notEmpty 条件队列，park（此刻 C 完全不占锁）
─────────────────────────────── 锁空闲
生产者 P: lock()                        → 拿到锁
P: queue.add(item)                      → 队列非空
P: notEmpty.signal()                    → 把 C 从条件队列搬到同步队列 + unpark C
P: finally { lock.unlock() }            → 锁再空闲
───────────────────────────────
C: 在同步队列排队 → 抢到锁             → 非公平锁下可能被插队（见 §4）
C: await() 返回（锁已重新持有）
C: while 重新检查 queue.isEmpty() → false → removeFirst
C: finally { lock.unlock() }
```

```
关键点：signal 只是把 C 从"等条件"变成"等锁"
  → C 此刻还没拿到锁，await 还没返回
  → C 要等 P 释放锁、自己排队抢到锁，才能继续走
  → "被唤醒" 和 "真正执行" 之间隔了一段不定时间
```

### 7.5 为什么是 while 而不是 if —— 被唤醒 ≠ 条件满足

```
signal 的语义：不是"条件满足了，去干活"，而是"去重新检查一下"

场景：C1、C2 都在等 notEmpty，生产者 add 一个 item 后 signal（唤醒 C1）
  C1 被搬到同步队列抢锁，但队列里还排着另一个消费者 C0（之前就在等锁）
  若 C0 先拿到锁：removeFirst 拿走 item → 队列又空了
  C1 拿到锁、从 await 返回后重新检查 → 空 → 必须继续等

if (queue.isEmpty()) { notEmpty.await(); }    // ❌ 唤醒后直接 removeFirst → 可能 NoSuchElementException
while (queue.isEmpty()) { notEmpty.await(); } // ✅ 唤醒后重新检查

两层循环：
  外层：业务代码的 while —— 重新检查条件（本节的论证）
  内层：await 源码里 while (!isOnSyncQueue(node)) park —— 处理伪唤醒
    LockSupport.park 可能无缘无故返回 → 醒来后必须确认自己真的被 signal 了
```

### 7.6 为什么是两个 Condition（与 wait/notify 对比）

```
wait/notify 的问题：
  一个对象只有一个 wait set → notifyAll 唤醒所有人
  → 生产者醒来看队列满（白醒）、消费者醒来看队列空（白醒）→ 大部分又睡回去
  → 惊群效应（Thundering Herd）

Condition 的增量：一个锁可以挂多个独立的等待队列
  notEmpty 里只可能有消费者，notFull 里只可能有生产者
  → signal 精确唤醒"正确的那一类"线程，不是全部
  → 队列 FIFO，唤醒等待最久的那个
  → 同类线程，signal 一个就够（一个 item 只够一个消费者；一个空位只够一个生产者）

对应关系：
  put 结束时 signal notEmpty  → 告诉消费者"来拿"
  take 结束时 signal notFull  → 告诉生产者"来放"
  → 交叉配对，各自只通知对方
```

### 7.7 与 02-03 的 ObjectMonitor 双队列模型对照

```
02-03 学过的模型：monitor 有 entry set（等锁）+ wait set（等条件）
  wait 释放 monitor 进 wait set，notify 把线程从 wait set 移回 entry set
AQS 的 Condition 完全同构：

| 02-03 ObjectMonitor | 03-02 AQS/Condition | 职责             |
|---|---|---|
| entry set / cxq     | 同步队列（sync queue）    | 等锁的人         |
| wait set            | 条件队列（condition queue）| 等条件的人       |
| wait()              | await()                  | 释放锁 + 进条件队列 |
| notify()            | signal()                 | 条件队列头 → 搬到同步队列 |
| 拿到锁后 wait 返回   | 抢到锁后 await 返回       | 恢复执行         |

唯一区别：ObjectMonitor 是"一个" wait set；Condition 支持"多个"（§7.6 的增量）
→ 02-03 建立的双队列心智模型，原封不动搬过来就是
```

### 7.8 三个高频坑

```
① take() 的 return 在 try 里，finally 还会执行吗？
   会。Java 语义：return 先求值 → 执行 finally → 再真正返回 → 锁一定被释放

② await 抛 InterruptedException 时锁谁拿着？
   AQS 实现里中断路径也要先重新拿到锁才抛异常 → finally 的 unlock 天然正确
   （"await 返回时锁必然在自己手里"的另一面）

③ signal 也必须持有锁
   AQS 的 signal() 开头检查 isHeldExclusively()，不持有直接抛 IllegalMonitorStateException
   → 与 ObjectMonitor 的 notify 规范一致（API 契约）
   → 效果：状态变更（add/remove）与发信号在同一临界区内、顺序稳定
     被唤醒的线程抢到锁后看到的队列状态，一定反映了 signal 线程的修改
```

### 7.9 源码走读：await()（经典版 JDK 8~20，与 03-01 同版本）

```java
public final void await() throws InterruptedException {
    if (Thread.interrupted())                          // ① 中断前置检查
        throw new InterruptedException();
    Node node = addConditionWaiter();                  // ② 尾插条件队列
    int savedState = fullyRelease(node);               // ③ 释放全部锁
    int interruptMode = 0;
    while (!isOnSyncQueue(node)) {                     // ④ 挂起循环
        LockSupport.park(this);
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;                                     // ⑤ 中断两模式
    }
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;                   // ⑥ 抢锁 + 恢复重入层数
    if (node.nextWaiter != null)
        unlinkCancelledWaiters();                      // ⑦ 清理取消节点
    if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode);       // ⑧ 报告中断
}
```

```
① 为什么在入队前检查？Thread.interrupted()（静态方法，会清除中断标志）
   已中断 → 直接抛异常 → 省掉"入队再拆队"的无效旅程（fail-fast）
```

#### ② addConditionWaiter —— 条件队列是单向链表

```java
private Node addConditionWaiter() {
    Node t = lastWaiter;
    if (t != null && t.waitStatus != Node.CONDITION) {  // 队尾是取消节点则先清
        unlinkCancelledWaiters();
        t = lastWaiter;
    }
    Node node = new Node(Thread.currentThread(), Node.CONDITION);
    if (t == null) firstWaiter = node;
    else t.nextWaiter = node;      // ★ 只连 nextWaiter
    lastWaiter = node;
    return node;
}
```

```
为什么条件队列单向、同步队列双向？
  条件队列只有两种操作：队尾插入（await）、队头取走（signal）→ 单向够用
  同步队列要沿 prev 回溯（改前驱状态、取消摘除）→ 必须双向
  → 数据结构形态由操作需求决定

CONDITION(-2) = "还在等条件"的身份牌：signal 与取消都靠抢这张牌定胜负（见 ⑤ 与 7.10 ④）
```

#### ③ fullyRelease —— 为什么是"全量"释放

```java
final int fullyRelease(Node node) {
    boolean failed = true;
    try {
        int savedState = getState();    // 记住 state（欠了多少层）
        if (release(savedState)) {      // ★ 一次释放全部层数
            failed = false;
            return savedState;          // 返回欠的层数
        }
        throw new IllegalMonitorStateException();
    } finally {
        if (failed) node.waitStatus = Node.CANCELLED;  // 防止孤儿节点被误转移
    }
}
```

```
因果链：lock() × 3 后 await，若只 release(1)：
  state = 2 ≠ 0 → owner 不清 → 其他线程永远进不来 → 死锁
  → 必须把 state 全部归零，欠的层数记在 savedState，醒来如数偿还（⑥）
  → 可重入（§5）在 await 里的落点
```

#### ④ 挂起循环 —— §7.5 的"内层循环"

```java
while (!isOnSyncQueue(node)) {
    LockSupport.park(this);   // 伪唤醒可能无缘无故返回
    ...
}

final boolean isOnSyncQueue(Node node) {
    if (node.waitStatus == Node.CONDITION || node.prev == null)
        return false;             // 还在条件队列
    if (node.next != null)        // 有后继 → 一定在同步队列
        return true;
    return findNodeFromTail(node);// 边界：可能是队尾，从尾遍历确认
}
```

```
每次醒来都确认"真的被 signal 了吗"——看链接不看状态（避免与入队的竞态）
```

#### ⑤ 中断两模式 —— 与 §6 中断哲学同构

```java
private int checkInterruptWhileWaiting(Node node) {
    return Thread.interrupted() ?
        (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) : 0;
}

final boolean transferAfterCancelledWait(Node node) {
    if (node.compareAndSetWaitStatus(node, Node.CONDITION, 0)) {  // 中断抢赢 signal
        enq(node);           // 自己转移自己
        return true;         // THROW_IE
    }
    while (!isOnSyncQueue(node))
        Thread.yield();      // signal 先赢 → 等转移完成
    return false;            // REINTERRUPT
}
```

```
中断与 signal 的竞速：同一个 CAS(CONDITION → 0) 只能赢一次
  中断赢 → THROW_IE：取消等待，拿回锁后抛 InterruptedException
  signal 赢 → REINTERRUPT：合法走完，中断只补记标志
  → 与 §6 lock()/lockInterruptibly() 同构：THROW_IE = 可中断语义，REINTERRUPT = 不可中断语义
```

#### ⑥⑧ 抢锁与报告中断

```
⑥ acquireQueued(node, savedState)：03-01 学过的同步队列抢锁
   参数是 savedState 而不是 1 → 抢回欠的层数（可重入恢复）
   返回 true = 抢锁期间又被中断 → 升级为 REINTERRUPT
⑧ reportInterruptAfterWait：THROW_IE → 抛异常；REINTERRUPT → 只重设标志
   ★ 抛异常发生在重新拿到锁之后 → §7.8 坑②的源码依据：
     await 抛异常时锁必然在自己手里，finally 的 unlock 天然正确
```

### 7.10 源码走读：signal()（经典版）

```java
public final void signal() {
    if (!isHeldExclusively())                        // ① 必须持有锁
        throw new IllegalMonitorStateException();
    Node first = firstWaiter;
    if (first != null)
        doSignal(first);                             // ② 只动队头
}

private void doSignal(Node first) {
    do {
        if ((firstWaiter = first.nextWaiter) == null)
            lastWaiter = null;
        first.nextWaiter = null;                     // 摘出队头
    } while (!transferForSignal(first) &&            // ③ 失败则顺延下一个
             (first = firstWaiter) != null);
}

final boolean transferForSignal(Node node) {
    if (!node.compareAndSetWaitStatus(node, Node.CONDITION, 0))  // ④ 与取消竞速
        return false;
    Node p = enq(node);                              // ⑤ 接入同步队列队尾
    int ws = p.waitStatus;
    if (ws > 0 || !p.compareAndSetWaitStatus(ws, Node.SIGNAL))   // ⑥ 前驱取消/设 SIGNAL 失败
        LockSupport.unpark(node.thread);             //    → 立即 unpark，不能等接力
    return true;
}
```

```
① isHeldExclusively = getExclusiveOwnerThread() == current → §7.8 坑③的源码依据
② FIFO：唤醒等待最久的那个（一个 item 只够一个消费者，§7.6）
③ 队头已取消 → 转移失败 → 顺延下一个 → signal 不空转
④ 与 ⑤ transferAfterCancelledWait 抢同一张身份牌（CAS CONDITION→0）——对称竞速
⑤ enq 到同步队列队尾 = §7.4 的"从等条件变成等锁"
⑥ 前驱取消（ws>0，接力链断）或设 SIGNAL 失败 → 主动 unpark，否则可能永远醒不了

signalAll = doSignal(first, true)，整链全部转移
```

### 7.11 本机 JDK 版本差异（JDK 21 重构版）

```
注意：03-01 与 7.9/7.10 对应经典版（JDK 8~20）
JDK 21 重写了 AQS（本机若用 21，实际跑的是新版）：四步旅程语义不变，骨架换新
```

| 经典版（JDK 8~20） | JDK 21 | 语义 |
|---|---|---|
| addConditionWaiter + fullyRelease | enableWait（先查持有锁，一次完成） | ②③ |
| isOnSyncQueue | canReacquire（沿 prev 查双向链接） | ④ |
| LockSupport.park(this) | node.block() / ForkJoinPool.managedBlock | ④ |
| transferForSignal（CAS + enq + 条件 unpark） | getAndUnsetStatus(COND) + enqueue | ③④ |
| acquireQueued(node, savedState) | acquire(node, savedState, false, false, false, 0L) | ⑥ |
| 状态：CANCELLED=1, SIGNAL=-1, CONDITION=-2, PROPAGATE=-3 | 位域：WAITING=1, CANCELLED=负数位, COND=2（COND\|WAITING 组合） | — |
| 统一 Node | Node + ExclusiveNode/SharedNode/ConditionNode | — |

```
JDK 21 三个设计变更（"为什么"层面）：
  1. 状态改位域，SIGNAL 握手删除：信号/取消竞速从两步 CAS 简化为一次原子位清
  2. ConditionNode implements ForkJoinPool.ManagedBlocker：
     FJP worker await 时 managedBlock 让池子补派线程，避免固定并行度的池被饿死
  3. Thread.onSpinWait()：signal 已清 COND 但入队未完成时醒来 → 自旋等入队
     （替代经典版 transferAfterCancelledWait 里的 Thread.yield()）
```

---

## 8. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：忘写 finally 中的 unlock —— 死锁！

```java
// ❌ 致命错误：中间抛异常，锁永远不释放
lock.lock();
doSomething();      // 如果这里抛异常 → unlock 永远不会执行 → 死锁
lock.unlock();

// ✅ 铁律：lock 和 unlock 之间必须 try-finally
lock.lock();
try {
    doSomething();
} finally {
    lock.unlock();
}
```

### 🕳️ 坑 2：lock() 不响应中断，可能导致线程无法停止

```java
// 应用关闭时：
// 线程阻塞在 lock() → 无法响应 interrupt → 关不掉
// 线程阻塞在 lockInterruptibly() → 响应 interrupt → 正常退出

// ✅ 规范：长时间等待锁的场景用 lockInterruptibly 或 tryLock(timeout)
```

### 🕳️ 坑 3：重入计数不一致（lock 次数 ≠ unlock 次数）

```java
// ❌ 重入了 2 次只释放 1 次 → 锁永远不会释放
lock.lock();            // 第 1 次 → state = 1
lock.lock();            // 重入 → state = 2
doSomething();
lock.unlock();          // 只释放 1 次 → state = 1 ≠ 0 → 锁还在！
// → state 永远到不了 0 → 其他线程永远拿不到锁 → 死锁

// ✅ 铁律：lock() 和 unlock() 严格配对，且用 try-finally 兜底
lock.lock();
lock.lock();
try {
    doSomething();
} finally {
    lock.unlock();      // state: 2 → 1
    lock.unlock();      // state: 1 → 0 → 真正释放
}
```

### 🕳️ 坑 4：interrupt 异常处理

```java
// ❌ 吞掉中断
try {
    lock.lockInterruptibly();
} catch (InterruptedException e) {
    // 什么都不做 → 中断标志被清除，上层无法感知
}

// ✅ 恢复中断标志
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("Interrupted", e);  // 或重新抛出
}
```

### 🕳️ 坑 5：把锁对象设为 null

```java
private ReentrantLock lock = new ReentrantLock();
// ❌ 不要这样做：lock = null; 或重新 new → 锁的语义被破坏
```

---

## 9. 面试高频考点

1. **公平锁和非公平锁的实现差异？**
   → 非公平：lock() 里先 CAS 抢一次 + tryAcquire 再抢一次；公平：tryAcquire 里先 `hasQueuedPredecessors()` 检查队列。

2. **为什么非公平锁吞吐量更高？**
   → 唤醒线程有延迟（微秒级），非公平锁利用"唤醒间隙"让新线程插队执行，减少锁空闲时间 + 减少线程切换。

3. **可重入是怎么实现的？**
   → exclusiveOwnerThread 记录持有者 + state 记录重入次数。持有时 state+1，释放时 state-1，归零才真正释放。

4. **lock / lockInterruptibly / tryLock / tryLock(timeout) 的区别？**
   → 无限等待（不响应中断）/ 无限等待（响应中断）/ 立即返回 / 限时等待。防死锁用 tryLock(timeout)。

5. **ReentrantLock 和 synchronized 怎么选？**
   → 简单互斥用 synchronized；需要 tryLock 超时/可中断/多 Condition/公平锁时用 ReentrantLock。

---

## 10. 实战练习

### 练习 1：公平锁 vs 非公平锁对比实验（45 分钟）

```java
package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 1：观察公平锁与非公平锁的线程获取顺序
 *
 * 实验设计：
 * 1. 一个线程持锁 100ms（制造排队）
 * 2. 10 个线程排队
 * 3. 观察获取锁的顺序是"先来先得"还是"可能插队"
 */
public class FairnessTest {

    @Test
    public void testFairness() throws InterruptedException {
        testLock(new ReentrantLock(true), "公平锁");
        testLock(new ReentrantLock(false), "非公平锁");
    }

    private void testLock(ReentrantLock lock, String name) throws InterruptedException {
        System.out.println("\n=== " + name + " ===");
        final int[] acquireOrder = new int[10];
        final int[] counter = {0};

        // 占锁线程：持有 100ms 再释放，制造排队
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "holder");
        holder.start();
        Thread.sleep(10); // 确保 holder 已持锁

        // 10 个等待线程同时启动（排队）
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                lock.lock();
                try {
                    acquireOrder[counter[0]++] = id;  // 记录获取顺序
                } finally {
                    lock.unlock();
                }
            }, "T" + i);
            threads[i].start();
        }

        holder.join();
        for (Thread t : threads) t.join();

        System.out.print("获取顺序: ");
        for (int i = 0; i < 10; i++) {
            System.out.print("T" + acquireOrder[i] + " ");
        }
        System.out.println();
        System.out.println(name + "下获取顺序" + (name.equals("公平锁") ? "严格按编号" : "可能乱序（插队）"));
    }
}
```

### 练习 2：可中断锁实验（30 分钟）

```java
package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 2：lock() 与 lockInterruptibly() 对中断的响应差异
 */
public class InterruptTest {

    @Test
    public void testLockNotInterruptible() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();  // 主线程先持锁

        Thread t = new Thread(() -> {
            System.out.println("子线程开始 lock()...");
            lock.lock();  // 拿不到锁，无限等待
            System.out.println("子线程获得了锁（不应该到这里）");
            lock.unlock();
        }, "waiter");
        t.start();

        Thread.sleep(500);
        t.interrupt();  // 中断子线程
        Thread.sleep(500);
        System.out.println("子线程是否还活着（lock() 不响应中断）: " + t.isAlive());
        lock.unlock();  // 释放锁，让子线程结束
        t.join();
    }

    @Test
    public void testLockInterruptibly() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();  // 主线程先持锁

        Thread t = new Thread(() -> {
            System.out.println("子线程开始 lockInterruptibly()...");
            try {
                lock.lockInterruptibly();
                System.out.println("子线程获得了锁");
                lock.unlock();
            } catch (InterruptedException e) {
                System.out.println("子线程收到中断，抛 InterruptedException → 退出等待");
                Thread.currentThread().interrupt();  // 恢复中断标志
            }
        }, "waiter");
        t.start();

        Thread.sleep(500);
        t.interrupt();  // 中断子线程
        t.join(2000);
        System.out.println("子线程是否还活着: " + t.isAlive());
        lock.unlock();
    }
}
```

### 练习 3：tryLock 防死锁（30 分钟）

```java
package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 3：用 tryLock(timeout) 避免死锁
 *
 * 场景：两个线程需要同时持有 lockA 和 lockB
 * 死锁版本：lock() 无限等待 → 互相等 → 死锁
 * 防死锁版：tryLock(2s) → 拿不到就释放已持有的 → 重试
 */
public class TryLockDeadlockTest {

    private final ReentrantLock lockA = new ReentrantLock();
    private final ReentrantLock lockB = new ReentrantLock();

    @Disabled("死锁演示永久阻塞非 daemon 线程，会把整个构建卡死；观察时临时启用单独运行")
    @Test
    public void testDeadlockVersion() throws InterruptedException {
        // 经典死锁：线程 1 拿 A 等 B，线程 2 拿 B 等 A
        Thread t1 = new Thread(() -> {
            lockA.lock();
            try {
                Thread.sleep(100);
                lockB.lock();   // 等 B（B 被 t2 拿着）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lockB.isHeldByCurrentThread()) lockB.unlock();
                lockA.unlock();
            }
        }, "T1");

        Thread t2 = new Thread(() -> {
            lockB.lock();
            try {
                Thread.sleep(100);
                lockA.lock();   // 等 A（A 被 t1 拿着）→ 死锁！
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lockA.isHeldByCurrentThread()) lockA.unlock();
                lockB.unlock();
            }
        }, "T2");

        t1.start(); t2.start();
        t1.join(2000); t2.join(2000);
        System.out.println("T1 alive: " + t1.isAlive() + ", T2 alive: " + t2.isAlive());
        System.out.println("两个线程都活着 → 死锁了（lock() 无限等待）");
        System.out.println("（本方法默认 @Disabled，观察时临时启用并单独运行）");
    }

    @Test
    public void testTryLockVersion() throws InterruptedException {
        // 防死锁：tryLock(2s)，拿不到就释放已持有的，让出机会
        Thread t1 = new Thread(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (lockA.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            if (lockB.tryLock(2, TimeUnit.SECONDS)) {
                                System.out.println("T1 同时拿到 A+B，执行成功");
                                lockB.unlock();
                                return;
                            }
                        } finally {
                            lockA.unlock();  // 拿不到 B → 释放 A
                        }
                    }
                    Thread.sleep(10);  // 随机退避后重试（中断 → 走 catch 退出）
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("T1 重试 5 次仍未成功，放弃");
        }, "T1");

        Thread t2 = new Thread(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (lockB.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            if (lockA.tryLock(2, TimeUnit.SECONDS)) {
                                System.out.println("T2 同时拿到 B+A，执行成功");
                                lockA.unlock();
                                return;
                            }
                        } finally {
                            lockB.unlock();
                        }
                    }
                    Thread.sleep(10);  // 中断 → 走 catch 退出
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("T2 重试 5 次仍未成功，放弃");
        }, "T2");

        t1.start(); t2.start();
        t1.join(); t2.join();
        System.out.println("✅ tryLock(2s) 版本：双方最终都完成（无死锁）");
    }
}
```

---

## 11. 自测题

1. **公平锁和非公平锁的代码差异在哪两处？**
   <details><summary>答案</summary>

   ① lock() 方法：非公平锁先 CAS 抢一次，公平锁直接 acquire；
   ② tryAcquire：非公平锁空闲就 CAS，公平锁先 hasQueuedPredecessors() 检查队列。
   </details>

2. **为什么非公平锁吞吐量更高？有什么代价？**
   <details><summary>答案</summary>

   唤醒线程有延迟（唤醒间隙），非公平锁让新线程在间隙中插队，减少锁空闲时间和线程切换次数。代价：可能饿死排队中的线程（概率低）。
   </details>

3. **可重入的 state 为什么重入时不需要 CAS？**
   <details><summary>答案</summary>

   重入的前提是 `current == getExclusiveOwnerThread()`，只有持有者线程自己才能重入，没有并发竞争，所以 setState 直接赋值即可。
   </details>

4. **lock() 和 lockInterruptibly() 对中断的响应有什么区别？**
   <details><summary>答案</summary>

   lock() 不响应中断（中断标志被记录，等待继续）；lockInterruptibly() 响应中断（抛 InterruptedException，等待结束）。
   </details>

5. **什么场景用 tryLock(timeout)？**
   <details><summary>答案</summary>

   ① 需要防死锁（拿不到就放弃，不无限等）；
   ② 抢不到锁可以做别的事（乐观尝试）；
   ③ 需要限时等待的业务（如最多等 3 秒）。
   </details>

---

> 📬 **完成练习后，进入下一篇 [03-03-ReentrantReadWriteLock与StampedLock](./03-03-ReentrantReadWriteLock与StampedLock.md)（待发布）—— 读写锁的 state 高低位拆分与乐观锁**

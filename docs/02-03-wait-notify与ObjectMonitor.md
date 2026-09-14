# 02-03 wait / notify 与 ObjectMonitor

> **阶段二·第 3 篇** | 前置：[02-02-对象头与锁升级全链路](./02-02-对象头与锁升级全链路.md) | 后续：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)（待发布）  
> **建议时长**：5~6 小时（设计因果链 0.5h + ObjectMonitor 1h + 正确范式 2h + 生产者消费者 0.5h + 练习 1.5h）  
> 🛠️ **日常高频**：wait/notify 是 Java 最底层的线程协作机制，写生产者-消费者、线程协作必用

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | wait/notify 正确范式（while + 条件检查）、wait 释放锁、notify vs notifyAll、假唤醒的防御 | **深入理解 + 能手写正确代码** |
| ◈◈ | ObjectMonitor 的三个队列（`_cxq` / `_EntryList` / `_WaitSet`）各管什么、假唤醒的四层产生机制、wait(timeout) 超时语义、生产者-消费者两种实现 | **知道原理 + 能画出流转图** |
| ○ | wait 的 Javadoc 原文 | **知道概念即可** |
| ○ | LockSupport park/unpark 的 permit 机制 | 见 [02-04-LockSupport与park机制](./02-04-LockSupport与park机制.md) |

---

## 1. wait / notify 是什么

### 1.1 三个方法

```
Object.wait()        当前线程释放锁，进入该对象的 WaitSet（等待集合），挂起
Object.wait(timeout) 带超时的等待，超时自动唤醒
Object.notify()      从 WaitSet 中随机唤醒一个线程（无法指定谁）
Object.notifyAll()   唤醒 WaitSet 中所有线程
```

### 1.2 与线程状态的关系

```
调用 wait() 的线程状态：WAITING（或 TIMED_WAITING）

线程状态流转：
  RUNNABLE → WAITING（调用 wait()）→ 被唤醒 → BLOCKED（回 _EntryList 重新抢锁）→ RUNNABLE（抢到锁）
```

---

## 2. 为什么需要 wait/notify，为什么它必须在 synchronized 内

> ⭐⭐⭐ **面试必问，理解这个是理解 wait/notify 的关键**
> 本节回答两个递进的问题：**① 这个机制为什么存在？② 为什么必须在锁内？**

### 2.1 为什么需要 wait/notify 机制（完整因果链）

**第一性原理：临界区内条件不满足，怎么等才不死锁、不浪费 CPU、不错过通知？**

```
① 协作需要锁：共享状态必须互斥访问
② 锁内可能"无法继续"：消费者拿到锁，队列是空的 —— 条件不满足是业务常态
③ 此时的三条路全堵死（三条路的完整论证见 2.2 第一层）：
   持锁自旋 → 死锁；退出轮询 → 低效且竞态还在；无锁检查 → 丢唤醒
④ 需求：一个"交锁 + 挂起 + 不丢唤醒"的原语 → wait()
   （释放锁与挂起融合成一步原子操作，中间插不进 notify）
⑤ 但 wait 只是机制的一半，协议必须闭合：
   wait 交出的锁会被某个线程抢到（可能是"对面"，也可能是同类）
   → 抢到锁的人只有两类结局：
     改了共享状态 → 干活，且若可能让别的等待者条件满足，就该 notify
     条件仍不满足 → 继续 wait
   → notify 的义务归属：谁改了共享状态、可能让别的等待者条件满足，谁调
     （生产者 add 后 notify 消费者；消费者 poll 后也要 notify 生产者）
   → 三个队列由此而生（详见第 3 节）：
     _cxq：抢锁失败的人（BLOCKED，投递口）   _EntryList：被挑中、待进入的人（BLOCKED，重排台）
     _WaitSet：等条件的人（WAITING）
     notify 干的事只是"移队"：_WaitSet → _EntryList → 抢锁 → 重查条件
⑥ 原子性闭环：检查条件、wait、改状态、notify 全在同一把锁内
   → 谁也没法插队 → 不丢唤醒 → 整个协议自洽
```

**wait 和 notify 是两条腿，缺一条就死**

```
只有 wait 没有 notify → 等条件的人永远等不到（除非超时/中断）→ 协作挂死
只有 notify 没有 wait → 通知发出去了，但没有等待者 → 通知无效

完整协作时序（两段式）：
消费者：wait() = 交锁 + 挂起进 WaitSet
  ↓ 交出的锁
生产者：抢到锁 → 添加元素 → notifyAll()（把消费者移回 EntryList）
  ↓ 唤醒事件
消费者：重新抢锁 → while 重查条件 → 消费
```

**本质：事件驱动 vs 轮询**

| | 轮询（主动查） | wait/notify（被动等） |
|---|---|---|
| 类比 | 每隔几秒问"快递到了没" | 留电话，到了打给你 |
| CPU | 空转消耗 | 等待期间 0 CPU |
| 响应延迟 | 至少一个轮询间隔 | 条件一变立刻唤醒，≈0 |
| 正确性 | 最后一步仍要加锁防竞态 | 协议内建原子性 |

**一句话总结**

```
wait/notify = "临界区内等条件"这个普遍需求，在管程模型下的标准答案：
互斥（synchronized）+ 条件变量（wait/notify）绑定在同一把锁上，
原子性、不丢唤醒都是绑定带来的。
生产者-消费者、线程池、AQS 的 Condition，全是这一套骨架的变体。
```

### 2.2 为什么必须在 synchronized 内（三层因果链）

> ⭐⭐⭐ **面试必问**。完整因果链有三层：**设计动机 → API 语义 → 正确性问题**。
> 别只背"检查与等待必须原子"——原子性是"手段"，不是"根因"。
> 本节论证"为什么必须在锁内"，机制为何存在的完整推导见 2.1。

**第一层（设计动机）：临界区内条件不满足时，必须交锁等待**

```
为什么需要 wait？设想消费者拿到锁进入临界区，发现队列是空的、无法继续：
  ① 持锁自旋等？ → 别人永远进不来改条件 → 死锁 ❌
  ② 退出临界区再轮询？ → 能用但低效：忙等耗 CPU、响应有延迟
     （sleep 间隔越小越费 CPU，越大反应越慢，二者不可兼得），
     而且最后拿数据依然要加锁 —— 竞态问题一点没少 ⚠️
  ③ 交锁 + 挂起，条件满足再回来 ← 优雅的方案
     （挂起 = 线程从运行队列移除、不占 CPU；唤醒靠事件：notify / 超时 / 中断）

→ 轮询是"主动查"，wait 是"被动等"：
   事件机制 = 等待期间 0 CPU + 条件一变立刻唤醒（延迟≈0）
   轮询只在"条件马上满足"（自旋几圈）或"没有事件源"时才划算
→ synchronized 块内只有两条出路：
    执行完 / 抛异常 —— 永久放弃 monitor
    wait()           —— 暂时放弃 monitor，之后还能回来抢锁
→ wait() 就是为"临界区内交锁"专门设计的唯一原语

（这两条出路的**代码位置视角**——为什么 wait 不是"出口"而只是一次普通方法调用
 —— 见 §3.4，那里是理解 while 必要性的机制根源）
```

**第二层（API 语义）：wait() 的定义就是"释放 monitor"，所以必须先持有锁**

```
wait() 的动作 = 释放当前线程持有的 monitor → 挂起 → 被唤醒后重新抢锁
→ 要释放锁，必须先持有锁 → 必须在 synchronized 内调用

反证：单线程、零并发时，不在锁内调 wait() 照样抛 IllegalMonitorStateException
—— 这跟"防竞态"无关，纯粹是 API 语义

违反规则的报错现象：

public void wrong() {
    obj.wait();  // ❌ IllegalMonitorStateException!
    // 必须持有 obj 的 monitor 才能调用 wait()
}

补充：wait() 把"释放锁 + 挂起"融合成一步原子操作，中间插不进 notify；
LockSupport.park/unpark 则用 permit 机制实现同样的"不丢唤醒"（详见 [02-04](./02-04-LockSupport与park机制.md)）
```

**第三层（正确性问题）：丢唤醒（lost wakeup）才是"病"，"检查 + wait 原子"是"药"**

```java
// 假设 wait 不需要锁（第一、二层都不存在），下面的逻辑是错的：

// 线程 A                             线程 B
if (!queue.isEmpty()) {               queue.add(item);
    // 此刻被切换走！                 notify();
    // B 添加元素并 notify             
    // A 恢复，执行 wait()
    obj.wait();  // 永远等不到 notify！ 因为 notify 已经发生了
}

// → "检查条件"与"等待"之间存在竞态窗口：notify 恰好落在窗口里就丢失
// → 药方：用锁把"检查条件 + wait"包成原子操作，notify 也必须在同一把锁内
//   （检查、wait、修改条件、notify 四方形成一个原子整体，谁也没法插队）
// → 再次强调：原子性是"手段"不是"根因"——用 CAS + LockSupport 或信号量
//   也能实现同样的原子性，锁只是最直观的一种
```

---

## 3. ObjectMonitor：三个队列

> 本节是**「ObjectMonitor 内部长什么样」的唯一讲解处**。锁的状态机（Mark Word / 锁升级）在 [02-02](./02-02-对象头与锁升级全链路.md)，这里只管「等锁的人和等条件的人分别待在哪」。

### 3.1 结构

```
ObjectMonitor（HotSpot 内部结构，JDK 8~21 骨架一致）：
┌────────────────────────────────┐
│ _owner: Thread*                │ ← 持有锁的线程（NULL = 无人持有）
│ _recursions: int               │ ← 重入计数
│ _cxq: ObjectWaiter*            │ ← 竞争队列（投递口）：抢锁失败的线程在这里 CAS 头插（LIFO 栈）
│ _EntryList: ObjectWaiter*      │ ← 待唤醒队列（重排台）：owner 从这里挑继任者（双向链表）
│ _WaitSet: ObjectWaiter*        │ ← 等待集（候车室）：调了 wait() 等条件的线程（双向循环链表）
│ _succ: Thread*                 │ ← 内定的"继任者"，owner 退出前指定
│ _contentions / _count: int     │ ← 活跃竞争数 / 引用计数
│ _WaitSetLock                   │ ← 只保护 _WaitSet 的短自旋锁（cxq 不用锁！）
└────────────────────────────────┘
```

> 字段定义在 `objectMonitor.hpp`，全部逻辑在同目录 `objectMonitor.cpp` 的
> `enter()` / `EnterI()` / `exit()` / `TrySpin()` / `AddWaiter()` 里。
> 几个字段的源码注释（原文，以你手上的 hpp 为准）：
> `_cxq` = *LL of recently-arrived threads blocked on entry.*；
> `_EntryList` = *Threads blocked on entry or reentry.*；
> `_WaitSet` = *LL of threads wait()ing on the monitor*；
> `_succ` = *Heir presumptive thread - used for futile wakeup throttling*。

**三个队列不是冗余，是分工**：

| | `_cxq`<br>**竞争队列**（投递口） | `_EntryList`<br>**待唤醒队列**（重排台） | `_WaitSet`<br>**等待集**（候车室） |
|---|---|---|---|
| 谁写 | 任何抢锁失败的线程 | **只有 owner** | 调了 `wait()` 的线程 |
| 同步手段 | 无锁 CAS | 不需要（owner 独占） | `_WaitSetLock` 短自旋锁 |
| 结构 | 单向链表（栈，LIFO） | 双向链表 | 双向循环链表 |
| 语义 | 抢锁失败、未被挑中 | 已被挑中 / 待唤醒 | 等**条件**（wait） |
| 写入者的时间预算 | "下一秒可能就拿到锁" | — | 已经决定要睡很久 |
| 存在理由 | 入队必须无锁 ⇒ 只能是栈 | 出队需要有序 | 条件等待语义不同 |

> 📌 **三个中文名的选词有讲究**：
> - **`_cxq` = 竞争队列**：HotSpot 注释里 *contention queue* 的直译。叫「投递口」是因为**谁来了都先塞这儿**
> - **`_EntryList` = 待唤醒队列**：**故意不叫"入口队列"**——新线程其实先进 `_cxq`，叫"入口"会直接把人带偏（本文档旧版就踩过这个坑）。它是 owner **从 `_cxq` 里挑出继任者之后**待的地方，等的是"被 unpark"
> - **`_WaitSet` = 等待集**：JLS 里 *wait set* 的标准中文译法（同义说法「等待集合」）。它和锁无关，等的是**条件**

### 3.2 为什么是三个队列？（因果链）

**根问题**：一次只有一个线程能持有锁，抢不到的线程得"记个名"等下次机会。记在哪？

**关键矛盾**：记名这个动作由**失败者自己**做，而且多个失败者会**同时**做。若用"队列 + 一把内部锁"保护，就撞上**自举悖论**——你用一把锁去保护"大家排队等着拿的那把锁"的队列，谁来保护这把锁？

```
自举悖论（为什么 cxq 不能用锁保护）
  ↓
入队必须无锁 → 只能 CAS
  ↓
无锁单链表上，头插最便宜（一次 cmpxchg）
尾插要 CAS 尾指针 + 处理中断/ABA，复杂度陡增
  ↓
头插 ⇒ _cxq 是 LIFO 栈
  ↓
新问题：LIFO 对锁是灾难 —— 最后到的先拿到锁，先到的被持续插队 → 饿死
  ↓
需要有人把顺序重排回来。谁有资格？只有持有锁的线程
（它是唯一有权改锁状态的人，且临界区内天然单线程）
  ↓
于是 owner 在 exit() 时做一次"搬家"：_cxq → _EntryList，顺手重排
_EntryList 用双向链表，因为只有一个操作者，负担得起
```

> 💡 **顺带回答一个隐藏问题**：为什么 `_WaitSet` 就能用真锁（`_WaitSetLock`）？
> 因为写它的线程刚调完 `wait()`、下一秒就要 park 睡很久，多花几十纳秒无所谓；
> 而写 `_cxq` 的线程是"owner 随时可能 release，我下一秒就可能拿到锁"，
> 这里多花的每一纳秒都直接变成延迟。**成本预算不同 ⇒ 允许的同步手段不同。**
> 这不是"哪个结构更先进"，是"谁在用、用得多急"。

### 3.3 一个线程的完整轨迹

**线程流转图**（⚠️ 注意新线程的落脚点）：

```
                    ┌──────────────┐
                    │  新线程进入   │
                    └──────┬───────┘
                           ▼
              ┌────────────────────────┐
              │  TryLock CAS _owner    │──成功──▶ 直接执行临界区（不碰任何队列）
              └────────┬───────────────┘
                       │ 失败
                       ▼
              ┌────────────────────────┐
              │  TrySpin 自适应自旋     │──抢到──▶ 直接执行临界区
              └────────┬───────────────┘
                       │ 仍失败
                       ▼
              ┌────────────────────────┐
              │  _cxq（投递口，LIFO）   │  ← 新线程先到这里，不是 _EntryList！
              └────────┬───────────────┘
                       │ owner 退出时挑人
                       ▼
              ┌────────────────────────┐
              │  _EntryList（重排台）   │  等 owner 挑中并 unpark
              └────────┬───────────────┘
                       ▼ 被唤醒后【重新 CAS 竞争】，赢了才算数
              ┌────────────────────────┐
              │  执行临界区代码         │
              └──┬──────────────────┬──┘
                 │                  │
     调用 wait() │                  │ 执行完毕
                 ▼                  ▼
        ┌─────────────┐    ┌──────────────┐
        │ _WaitSet    │    │ 释放锁，退出  │
        │ 等待被唤醒   │    └──────────────┘
        └──────┬──────┘
               │ notify() / notifyAll()
               ▼
        ┌─────────────────┐
        │ 回到 _EntryList  │  ← 注意：不是直接执行！
        │ 重新排队抢锁      │     要先抢到锁才能从 wait() 返回
        └─────────────────┘
```

**同一件事的时序视角**：

```
T: synchronized(obj)
   ├─ TryLock  CAS(_owner, NULL → T) ──成功──▶ 直接成为 owner（压根没碰任何队列）
   │                                           已是 owner → _recursions++
   ├─ 失败 → TrySpin 自适应自旋              ← 目的是省掉一次 park/unpark（1~10μs）
   │                                          （_succ 被指定时，其他线程会提前放弃自旋）
   └─ 仍失败 → EnterI：
         ① new ObjectWaiter，CAS 头插进 _cxq     ← LIFO
         ② 循环里继续 TryLock（在 cxq 里也能截胡），拿不到才 park()
        ⚠ cxq 是投递口，不是终点站

owner exit():
   _recursions--；若仍 > 0 直接返回（重入）
   否则：挑继任者 w → 置 _succ = w._thread → unpark(w)   ← 不是把锁"交给"它

T 被唤醒 ──▶ 回到 TryLock 重新 CAS 竞争
             ├─ 抢到 → 成为 owner，把自己从 cxq/_EntryList 摘掉
             └─ 没抢到（被刚到的线程 barge 截胡）→ 回去继续 park

wait()/notify() 走的是另一条线：
   owner：wait()    → 自己入 _WaitSet + 释放锁 + park
   其他：notify()   → 从 _WaitSet 摘一个 → 移进 _EntryList → 它还得重新抢锁
```

**关键点**：

```
1. notify() 只是"移队"，不是"直接唤醒执行"
   WaitSet → EntryList → 抢锁 → 才能继续

2. 被唤醒的线程抢到锁后，从 wait() 返回，继续执行 wait() 之后的代码

3. 队列分工：_cxq 是投递口（谁来都先塞这）、_EntryList 是 owner 的重排台、
   _WaitSet 是等条件的候车室。三者不是"三个并列的等待区"，
   而是一个线程在生命周期里经过的三个位置

4. wait 释放锁的意义：不给别人释放，别人无法进入临界区去 notify
```

### 3.4 被唤醒的线程如何恢复执行（代码位置模型）

> 3.3 说"从 wait() 返回，继续执行 wait() 之后的代码"——这句话的机制是什么？

**关键观察：线程从未离开过 synchronized 块**

```
synchronized (lock) {
    while (queue.isEmpty()) {   // ① 条件检查
        lock.wait();            // ② PC 停在这一行，阻塞
    }                           // ③ 恢复后从这里继续
    process(queue.poll());      // ④
}

synchronized 块只有两个出口：执行完 / 抛异常。wait() 不是出口！
→ wait() 只是块内的一条普通方法调用：
   调用时 PC 停在 ②，返回后 PC 指向 ② 的下一条指令（回到 ① 重查）
→ "重新抢到锁"不是"重新进入临界区"，而是 wait() 返回的前置条件：
   JVM 保证 wait() 只有在重新获得 monitor 后才会返回，这步对调用者不可见
```

**两种视角对照**

| 视角 | 看到的 |
|---|---|
| 锁的视角 | 释放 monitor → 挂起 → 被唤醒 → 重新抢 monitor（= 重新进入临界区） |
| 代码的视角 | 执行到 wait() → 方法调用阻塞 → wait() 返回 → 原地继续下一条指令 |

**机制实现（HotSpot 简化）**

```
wait() 内部：
  1. 释放 monitor，把自己加进 _WaitSet
  2. park（线程挂起，PC 保留在调用 wait() 的栈帧上）

被 notify 后：
  3. 移回 _EntryList，参与抢锁（monitor enter）
     ⚠️ 注意是【重新竞争】，不是直接拿到——详见 §3.5「错误说法 1」
  4. 抢到锁 → unpark → park 返回 → wait() 返回
  5. JVM 从栈帧取出返回地址 → 继续执行下一条字节码
```

**与 while 的关系（为什么 if 必错）**

```
恢复点在 wait() 之后，所以恢复后第一个执行的是"wait() 后面的代码"：
  用 if：if (isEmpty()) wait(); 后面直接是 process(poll()) → 醒来不重查 ❌
  用 while：while (isEmpty()) { wait(); } 后面是回到循环条件 → 先重查 ✅
→ "从 wait() 返回处继续"正是 while 循环必要性的机制根源（详见 4.2）
```

### 3.5 两个常见错误说法

> ⚠️ **错误说法 1：「被唤醒后就直接变成 `_owner`」**
> **错**。被唤醒只是获得**重新竞争的资格**，还要 CAS 赢一次。这正是 `synchronized` **非公平**的根源：刚到的线程可以直接插队（barging），被唤醒的线程可能白醒一场，回去接着 park。
> HotSpot 用 `_succ` 缓解——它是 owner 指定的"内定继任者"（源码注释：*used for futile wakeup throttling*，抑制无效唤醒）：继任者已内定时，其他线程少自旋、早点去 park，避免"醒了一群、只跑一个"。

> ⚠️ **错误说法 2：「`_owner = null` → 唤醒一个线程」**
> **不完整**。`exit()` 里 owner 还要短暂地**重新 `TryLock`** 才能安全改队列（改队列需要 owner 身份），挑完继任者才真正放手。

> 📌 **`_cxq → _EntryList` 的搬法是有开关的**：HotSpot 用 `Knob_QMode` 控制
> （`-XX:+UnlockDiagnosticVMOptions -XX:QMode=N`，0/1/2/3 对应不同的
> "谁优先 / 头插还是尾插 / 搬不搬"策略）。本文**不写死**默认走哪条——
> 要进结论，就打开 `objectMonitor.cpp` 的 `exit()` 逐行确认。
> 骨架记住即可：**`_cxq` 是投递口，`_EntryList` 是 owner 的重排台。**

### 3.6 验证：三个队列能被 `Thread.getState()` 看见

> 代码：`src/test/java/com/sw/yang/concurrent/sync/MonitorQueueStateTest.java`

JDK 里「队列 ↔ 线程状态」是**文档化**的对应关系，不是巧合：

| 队列 | `Thread.State` | 为什么是这个状态 |
|---|---|---|
| `_cxq` / `_EntryList` | `BLOCKED` | `ObjectMonitor::enter` 里 `JavaThreadBlockedOnMonitorEnterState` 会把线程状态置为 BLOCKED 再 park |
| `_WaitSet` | `WAITING` / `TIMED_WAITING` | 调 `wait()` / `wait(ms)` 后 park，等的是**条件**不是锁 |

四段验证：

1. **`testContentionIsBlocked`** —— 抢锁失败者 = `BLOCKED`（在 `_cxq`/`_EntryList` 里）
2. **`testWaitIsWaiting`** —— 调了 `wait()` 者 = `WAITING`（在 `_WaitSet` 里）
3. **`testNotifyOnlyMovesQueue`** —— 核心。`notify()` 只是"移队"，**不是"给锁"**：通知者仍持锁期间，被唤醒线程**不可能**越过 `wait()` 的返回点
4. **`testBargingObservation`** —— 非确定性观察（只打印不断言）：通知者退出临界区后立刻重新抢锁，被唤醒的线程往往还在排队 → 这就是**非公平 / barging**

> ⚠️ 第 3 条的断言写法有讲究：**不断言"被唤醒后是 BLOCKED"**（`WAITING → BLOCKED` 的具体翻转时刻是实现细节，跨版本可能变），只断言**不变量**——"锁还被别人拿着时，它绝不可能从 `wait()` 返回"。这样测试不会因 JVM 实现细节而 flaky。

**本机实测**（Temurin JDK 21，4 个用例 0.29s 全绿）：

```
✅ 抢锁失败（_cxq/_EntryList）→ BLOCKED
✅ 调了 wait()（_WaitSet）→ WAITING
notify() 之后、main 仍持锁时，waiter = BLOCKED（= 已在 _EntryList 里重新抢锁）
✅ main 退出临界区后，waiter 才真正从 wait() 返回

=== barging 观察（100 轮）===
通知者退出后立刻插队成功：100 次
waiter 先抢到：0 次
```

> 📌 100/100 值得琢磨：**通知者从没下过 CPU**，而被唤醒者要走 unpark → 等调度 → 上 CPU 一整条路，所以次次插队成功。这不是"JVM 实现得不好"，是**非公平锁的必然**——公平要额外代价（AQS 的 `hasQueuedPredecessors` 就是为它付的账，见 03-01）。

---

## 4. wait / notify 正确范式（核心）

### 4.1 必须记住的四条规则

```
规则 1：wait 必须在 synchronized 块内（否则 IllegalMonitorStateException）
规则 2：条件检查必须用 while 循环（不能用 if！）
规则 3：notify 也必须在 synchronized 块内
规则 4：尽量用 notifyAll 而不是 notify
```

### 4.2 为什么条件检查必须用 while（竞争 + 假唤醒）

> while 必须重查，原因有三，缺一不可：
> ① 恢复点在 wait() 之后 —— if 醒来直接干活（机制见 3.4）
> ② 竞争 —— 即使真被 notify，抢到锁前条件可能已被别的线程改掉（本节示例）
> ③ 假唤醒 —— wait() 可能无缘无故返回（机制见 4.3）

```java
// ❌ 错误写法：if 只检查一次
synchronized (lock) {
    if (queue.isEmpty()) {      // ① 检查
        lock.wait();            // ② 等待
    }
    process(queue.poll());      // ③ 处理 ← 醒来后从 wait() 返回处继续执行，不再检查！（机制见 3.4）
}

// 问题场景（两个消费者 + 一个生产者）：
// 1. 队列空，消费者A 和 消费者B 都进入 wait（WaitSet 有 2 个）
// 2. 生产者添加 1 个元素，notifyAll()
// 3. A 和 B 都被移到 EntryList
// 4. A 抢到锁，poll 走元素，释放锁
// 5. B 抢到锁，poll() 返回 null！→ NPE 或者处理 null
//    ← 因为 B 在 wait 前只检查了一次，醒来后没有重新检查！
```

```java
// ✅ 正确写法：while 循环重新检查
synchronized (lock) {
    while (queue.isEmpty()) {   // ① 醒来后重新检查条件！
        lock.wait();
    }
    process(queue.poll());      // ② 条件满足才继续
}
```

### 4.3 假唤醒（Spurious Wakeup）

```
官方文档（Object.wait 的 Javadoc）原文：

"A thread can also wake up without being notified, interrupted, or timing out,
 a so-called spurious wakeup. ... applications must guard against it by
 testing for the condition that should have caused the thread to be awakened."

（线程可能在没有被 notify、中断或超时的情况下被唤醒 —— 这就是假唤醒）
```

**产生链路（四层，从 OS 到应用）**

```
① OS 层设计取舍：park 底层是 pthread_cond_wait（Linux）/ SleepConditionVariableCS（Windows），
   这些原语被设计成"允许假醒"——为了绝不丢唤醒（宁多醒，勿漏醒）：
   signal 时实现可能无法精确定位该唤醒哪个等待者（等待者在组间迁移、序列号竞态），
   保守策略是唤醒一组甚至全部 → 多醒的线程醒来一查谓词没变 = 假醒

② 触发源：
   - 保守唤醒：signal 时多唤醒线程（① 的机制，POSIX 标准明确允许）
   - 信号：进程收到信号（jstack 的 SIGQUIT、JFR、kill 等），futex_wait 返回 EINTR 被晃醒
   - 内核事件：线程调度迁移等其他唤醒路径

③ JVM 层"安检"：park() 内部有 permit（许可）计数器
   → 返回后检查 permit：是 1 → 真实唤醒，继续
                             否 → 假醒！→ 重新 park（内部吸收，应用看不见）

④ 规范放行：但"内部吸收"不是承诺——JVM 规范明确允许 wait() 假唤醒，
   不同 JVM/平台行为可能不同 → 应用必须按最坏情况写
```

**两条路径的汇合点**

```
真唤醒：被 notify → 移 _EntryList → 重新抢到锁 → unpark → park 返回 → wait() 返回
假唤醒：park 无故返回（没有 unpark）→ permit 检查 → 假醒 → 再睡（或放行）

wait() 返回前必须重新持有 monitor —— 两条路径都满足（见 3.4）
→ 返回条件保证"锁"，但不保证"条件"，条件必须 while 重查

→ 防御手段：while 循环重新检查条件（而不是 if）
```

### 4.4 为什么推荐 notifyAll 而不是 notify

**前提：WaitSet 里可能同时躺着两类等待者**

```
这个锁上其实有两个条件：
  生产者的条件：队列不满（满了 → wait）
  消费者的条件：队列不空（空了 → wait）
两者共用同一个 WaitSet，而且可以同时存在——因为每个人是在
不同时刻、不同队列状态下睡着的（见下方 t2）
```

**错误唤醒如何导致死锁（容量 1：1 生产者 P + 2 消费者 C1/C2）**

```
t0  队列空。C1、C2 发现空 → wait()               WaitSet = {C1, C2}
t1  P 生产，队列满。P notify() → 唤醒 C1          WaitSet = {C2}
t2  P 循环抢锁：队列满 → P 也 wait()               WaitSet = {C2, P} ← 两类共存！
t3  C1 抢到锁，消费，队列空。C1 notify()
    → 唤醒 P  → P 条件满足（不满）→ 生产 → 正常 ✅
    → 唤醒 C2 → C2 重查：队列空 → 不满足 → 又 wait 回去 ❌

错误唤醒的后果链（t3 的第二种分支）：
  唤醒 C2 → 这次 notify 被浪费（C2 睡回去，白喊）
  → 真正条件满足的 P 还睡着（它该起来生产！）
  → 队列永远空，状态再也不变
  → 再也不会有人 notify —— 能改状态的人正是没被唤醒的 P
  → 所有线程挂死 ← 死锁
```

**notifyAll 为什么安全**

```
notifyAll → C1、C2、P 全部醒来 → 各自 while 重查
→ P 条件满足 → 生产 → 状态继续变化 → notifyAll → 协议继续 ✅
代价：C1/C2 白醒一次（短暂抢锁竞争），无害
```

**什么时候 notify 够用**

```
单生产者单消费者：WaitSet 里永远只有一类等待者，唤醒谁都是对的 → notify 安全
多生产者多消费者：两类共存是常态 → 必须 notifyAll
```

```java
// ❌ notify() 的核心问题：唤醒"错误"的线程，浪费唤醒名额（机制见上方时间线）
// ✅ notifyAll() 的好处：全部醒来 → 各自重查 → 条件满足的自然接活，绝对安全
// ⚠️ 注意：notifyAll 唤醒的是 WaitSet 的全部，
//    但只有抢到锁的线程才能真正从 wait 返回（移入 EntryList 排队）
```

### 4.5 完整正确范式模板

```java
public class WaitNotifyTemplate {

    private final Object lock = new Object();
    private final java.util.Queue<String> queue = new java.util.LinkedList<>();
    private static final int CAPACITY = 10;

    // 生产者
    public void produce(String item) throws InterruptedException {
        synchronized (lock) {
            while (queue.size() >= CAPACITY) {   // ① while 检查条件
                lock.wait();                      // ② 不满才能继续
            }
            queue.add(item);
            lock.notifyAll();                     // ③ notifyAll
        }
    }

    // 消费者
    public String consume() throws InterruptedException {
        synchronized (lock) {
            while (queue.isEmpty()) {             // ① while 检查条件
                lock.wait();                      // ② 不空才能继续
            }
            String item = queue.poll();
            lock.notifyAll();                     // ③ notifyAll
            return item;
        }
    }
}
```

---

## 5. wait(timeout) 超时语义

```
wait(0)      = 无限等待，等价于 wait()
wait(1000)   = 最多等 1 秒，超时自动醒来（WAITING → BLOCKED 抢锁 → RUNNABLE）

超时醒来的线程：
  1. 自动从 WaitSet 移到 EntryList
  2. 抢到锁后从 wait() 返回
  3. 但条件不一定满足！→ 依然要用 while 循环重新检查！

四种唤醒途径：
  notify / notifyAll  → 手动唤醒
  wait(timeout) 超时  → 自动唤醒
  线程被 interrupt()  → 抛 InterruptedException
  假唤醒（spurious）  → 无原因唤醒（产生机制见 4.3）
```

```java
// 超时等待的典型用法：最多等 3 秒，等不到就算了
synchronized (lock) {
    while (queue.isEmpty()) {
        lock.wait(3000);   // 最多等 3 秒
        if (queue.isEmpty()) {
            System.out.println("3 秒超时，队列仍为空，降级处理");
            return null;   // 降级
        }
    }
    return queue.poll();
}
```

---

## 6. wait vs sleep 对比

> ⭐⭐⭐ **面试必考**

| 维度 | wait() | sleep() |
|---|---|---|
| 归属 | Object 的方法 | Thread 的静态方法 |
| 释放锁 | ✅ 释放 | ❌ 不释放 |
| 调用前提 | 必须在 synchronized 块内 | 无要求 |
| 线程状态 | WAITING / TIMED_WAITING | TIMED_WAITING |
| 唤醒方式 | notify / notifyAll / 超时 | 时间到自动醒来 |
| 异常 | InterruptedException | InterruptedException |
| 用途 | 线程间协作（生产者-消费者） | 让出 CPU 一段时间 |

```java
// sleep 不释放锁的验证
synchronized (lock) {
    Thread.sleep(1000);  // 持有锁睡 1 秒 → 其他线程进不来
}

// wait 释放锁的验证
synchronized (lock) {
    lock.wait(1000);     // 释放锁等 1 秒 → 其他线程能进来
}
```

---

## 7. 生产者-消费者实战

### 7.1 基于 wait/notifyAll（最底层）

```java
public class ProducerConsumerDemo {

    private final Object lock = new Object();
    private final java.util.LinkedList<Integer> buffer = new java.util.LinkedList<>();
    private static final int CAPACITY = 10;
    private volatile boolean running = true;

    class Producer implements Runnable {
        @Override
        public void run() {
            int i = 0;
            while (running) {
                synchronized (lock) {
                    while (buffer.size() >= CAPACITY) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    buffer.add(i++);
                    System.out.println("生产: " + (i - 1) + ", 队列: " + buffer.size());
                    lock.notifyAll();
                }
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }
    }

    class Consumer implements Runnable {
        @Override
        public void run() {
            while (running) {
                synchronized (lock) {
                    while (buffer.isEmpty()) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    int item = buffer.removeFirst();
                    System.out.println("消费: " + item + ", 队列: " + buffer.size());
                    lock.notifyAll();
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        }
    }
}
```

### 7.2 生产者-消费者三种实现对比

| 实现方式 | 优点 | 缺点 | 适用场景 |
|---|---|---|---|
| wait/notifyAll | 最底层，理解原理 | 代码复杂，易出错 | 学习原理 |
| Lock + Condition | 精确唤醒（不浪费） | 代码较长 | 需要多条件时 |
| BlockingQueue | 最简单 | 不透明（封装了细节） | 生产首选 |

> 生产环境首选 BlockingQueue（阶段三讲），wait/notify 的价值在于理解底层原理。

---

## 8. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：在循环外 wait

```java
// ❌ 错误：wait 在 while 外面
synchronized (lock) {
    if (queue.isEmpty()) {
        lock.wait();   // 被唤醒后不再检查，直接往下走
    }
    process(queue.poll());  // 可能拿到 null
}

// ✅ 正确：wait 在 while 里面
synchronized (lock) {
    while (queue.isEmpty()) {
        lock.wait();
    }
    process(queue.poll());
}
```

### 🕳️ 坑 2：忘记 notify

```java
// ❌ 生产者添加元素后忘了 notify → 消费者永远等不到 → 死锁
synchronized (lock) {
    queue.add(item);
    // 缺少 lock.notifyAll()!
}

// ✅ 每次修改共享状态后都要 notifyAll
```

### 🕳️ 坑 3：notify 唤醒错误的线程

```java
// ❌ 一个生产者一个消费者时用 notify 没问题
// ❌ 多个生产者多个消费者时用 notify 有风险：
//    唤醒的可能是"不满足条件"的线程 → 它又 wait → 真正能处理的还在睡 → 死锁

// ✅ 多生产者多消费者场景：永远用 notifyAll
```

### 🕳️ 坑 4：在持有锁时调用 sleep

```java
// ❌ sleep 不释放锁，其他线程饿死
synchronized (lock) {
    Thread.sleep(1000);   // 持有锁睡觉 → 其他人进不来
}

// ✅ 需要"让出锁等一会"时用 wait(timeout)
synchronized (lock) {
    lock.wait(1000);      // 释放锁等待，1 秒后自动醒来
}
```

### 🕳️ 坑 5：中断异常处理不当

```java
// ❌ 吞掉中断异常
synchronized (lock) {
    try {
        lock.wait();
    } catch (InterruptedException e) {
        // 什么都不做 → 线程中断标志被清除 → 其他代码无法感知中断
    }
}

// ✅ 正确：重新设置中断标志
synchronized (lock) {
    try {
        lock.wait();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // 恢复中断标志
        // 或直接返回/抛出，让上层处理
    }
}
```

---

## 9. 面试高频考点

1. **wait 和 sleep 的区别？**
   → wait 释放锁（Object 方法，需要 monitor），sleep 不释放锁（Thread 静态方法）。wait 需要 notify 唤醒，sleep 自动醒。

2. **为什么 wait 必须放在 synchronized 里？**
   → 三层：① 设计动机——synchronized 块内唯一的交锁方式是 wait()；② API 语义——wait() 要释放 monitor，必须先持有它；③ 正确性——把"检查 + wait + notify"放同一把锁内，才能防止竞态窗口丢唤醒。

3. **为什么条件检查要用 while 而不是 if？**
   → 假唤醒 + 多线程竞争：被唤醒的线程抢到锁后，条件可能已经不满足（如队列又空了），必须重新检查。

4. **notify 和 notifyAll 的区别？什么时候用哪个？**
   → notify 随机唤醒一个，notifyAll 唤醒全部。多消费者场景用 notifyAll（防止唤醒错误的线程导致死锁）。

5. **被 notify 唤醒的线程能立刻执行吗？**
   → 不能。被唤醒的线程从 WaitSet 移到 EntryList，需要重新抢锁，抢到锁才能从 wait() 返回。

---

## 10. 实战练习

### 练习 1：正确的生产者-消费者（60 分钟）

```java
package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：基于 wait/notifyAll 的生产者-消费者
 *
 * 目标：
 * 1. 2 个生产者 + 3 个消费者，缓冲区容量 10
 * 2. 验证数据不丢失、不重复
 * 3. 刻意改成 if 条件检查，观察 bug
 */
public class ProducerConsumerTest {

    private final Object lock = new Object();
    private final LinkedList<Integer> buffer = new LinkedList<>();
    private static final int CAPACITY = 10;

    private volatile boolean running = true;
    private final AtomicInteger produced = new AtomicInteger(0);
    private final AtomicInteger consumed = new AtomicInteger(0);

    class Producer implements Runnable {
        @Override
        public void run() {
            while (running) {
                synchronized (lock) {
                    // TODO: 改成 if (buffer.size() >= CAPACITY) 试试
                    //      多生产者时会发生什么？
                    while (buffer.size() >= CAPACITY) {
                        try { lock.wait(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    int item = produced.incrementAndGet();
                    buffer.add(item);
                    lock.notifyAll();  // TODO: 改成 notify() 试试多消费者场景
                }
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    class Consumer implements Runnable {
        @Override
        public void run() {
            while (running) {
                synchronized (lock) {
                    while (buffer.isEmpty()) {
                        try { lock.wait(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    int item = buffer.removeFirst();
                    consumed.incrementAndGet();
                    lock.notifyAll();
                }
                try { Thread.sleep(2); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Test
    public void testProducerConsumer() throws InterruptedException {
        Thread[] producers = new Thread[2];
        Thread[] consumers = new Thread[3];

        for (int i = 0; i < producers.length; i++) {
            producers[i] = new Thread(new Producer(), "producer-" + i);
            producers[i].start();
        }
        for (int i = 0; i < consumers.length; i++) {
            consumers[i] = new Thread(new Consumer(), "consumer-" + i);
            consumers[i].start();
        }

        Thread.sleep(2000); // 运行 2 秒
        running = false;

        // 唤醒所有可能阻塞的线程（优雅停止）
        synchronized (lock) {
            lock.notifyAll();
        }

        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("生产总数: " + produced.get());
        System.out.println("消费总数: " + consumed.get());
        System.out.println("缓冲区残留: " + buffer.size());
        System.out.println(produced.get() == consumed.get() + buffer.size()
                ? "✅ 数据一致（不丢失不重复）" : "❌ 数据不一致！");
    }
}
```

### 练习 2：验证 wait 释放锁、sleep 不释放锁（20 分钟）

```java
package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 wait 释放锁 / sleep 不释放锁
 */
public class WaitVsSleepTest {

    @Test
    public void testWaitReleasesLock() throws InterruptedException {
        Object lock = new Object();
        long[] waitTime = new long[1];

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                System.out.println("Holder 获取锁，调用 wait(2000)...");
                try {
                    lock.wait(2000);  // 释放锁
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Holder wait 结束");
            }
        }, "holder");
        holder.start();

        Thread.sleep(500); // 确保 holder 先拿到锁

        Thread taker = new Thread(() -> {
            long start = System.currentTimeMillis();
            synchronized (lock) {
                waitTime[0] = System.currentTimeMillis() - start;
                System.out.println("Taker 获取锁，等待耗时: " + waitTime[0] + "ms");
            }
        }, "taker");
        taker.start();
        taker.join();

        System.out.println(waitTime[0] < 2000
                ? "✅ wait 释放了锁（taker 在 holder 等待期间就进来了）"
                : "❌ wait 没有释放锁？");
        holder.join();
    }

    @Test
    public void testSleepHoldsLock() throws InterruptedException {
        Object lock = new Object();
        long[] sleepTime = new long[1];

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                System.out.println("Holder 获取锁，sleep(2000)...");
                try {
                    Thread.sleep(2000);  // 不释放锁
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Holder sleep 结束");
            }
        }, "holder");
        holder.start();

        Thread.sleep(500);

        Thread taker = new Thread(() -> {
            long start = System.currentTimeMillis();
            synchronized (lock) {
                sleepTime[0] = System.currentTimeMillis() - start;
                System.out.println("Taker 获取锁，等待耗时: " + sleepTime[0] + "ms");
            }
        }, "taker");
        taker.start();
        taker.join();

        System.out.println(sleepTime[0] >= 2000
                ? "✅ sleep 持有锁（taker 等到 sleep 结束才进来）"
                : "❌ sleep 释放了锁？");
        holder.join();
    }
}
```

### 练习 3：验证假唤醒 + 修复（30 分钟，可选）

```java
// 用 3 个消费者 + 1 个生产者验证：
// if 条件 → 可能 NPE 或数据不一致
// while 条件 → 永远安全

// 代码结构参考练习 1，修改点：
// 1. Consumer 的 if (buffer.isEmpty()) 换成 if（错误示范）
// 2. 运行多次观察异常
// 3. 改回 while，验证稳定
```

---

## 11. 自测题

1. **为什么 wait() 必须在 synchronized 块内调用？不这样做会怎样？**
   <details><summary>答案</summary>

   三层：① 设计动机——临界区内条件不满足时只能交锁等待，synchronized 块内唯一的交锁方式是 wait()；② API 语义——wait() 的定义就是"释放当前线程持有的 monitor"，要释放锁必须先持有锁；③ 正确性——把"检查条件 + wait + notify"放进同一把锁内，notify 才不会落在竞态窗口里导致丢唤醒。不这样做直接调用会抛 IllegalMonitorStateException（单线程也一样抛——这不是防竞态，是 API 语义）。
   </details>

2. **notify() 唤醒的线程是立刻执行吗？它会经历什么？**
   <details><summary>答案</summary>

   不会立刻执行。被唤醒的线程从 WaitSet 移到 EntryList（等待获取锁的队列），必须重新抢锁，抢到锁后才能从 wait() 返回继续执行。
   </details>

3. **什么是假唤醒（Spurious Wakeup）？如何防御？**
   <details><summary>答案</summary>

   线程在没被 notify、中断、超时的情况下被唤醒。产生链路：OS 原语（pthread_cond_wait 等）为了不丢唤醒被设计成"允许假醒"（宁多醒勿漏醒），信号、保守唤醒会触发；JVM 的 park 内部有 permit 安检能吸收大部分，但规范明确允许 wait() 假唤醒（机制见 4.3）。防御：用 while 循环重新检查条件，而不是 if 只检查一次。
   </details>

4. **多生产者多消费者场景为什么推荐 notifyAll？**
   <details><summary>答案</summary>

   notify 是随机唤醒一个线程，可能唤醒"不满足条件"的线程（比如队列满时唤醒生产者），它检查条件后又 wait 回去，而真正能处理的线程还在睡 → 可能死锁（完整时间线机制见 4.4）。notifyAll 唤醒所有线程，各自重新检查条件，绝对安全。
   </details>

5. **wait(1000) 超时后，条件一定满足吗？**
   <details><summary>答案</summary>

   不一定。超时只是"自动醒来"，线程还是要重新抢锁、重新检查条件。所以超时醒来后依然要走 while 循环检查。
   </details>

---

> 📬 **阶段二三篇全部完成！你现在掌握的：Synchronized 使用 → 字节码 → 可重入 → 对象头 Mark Word → 锁升级 → ObjectMonitor → wait/notify。这是面试中"锁"专题的完整闭环。**
>
> 下一篇：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)（待发布）—— 阶段三，JUC 的灵魂，全路线最重要的部分

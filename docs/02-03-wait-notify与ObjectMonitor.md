# 02-03 wait / notify 与 ObjectMonitor

> **阶段二·第 3 篇** | 前置：[02-02-对象头与锁升级全链路](./02-02-对象头与锁升级全链路.md) | 后续：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)（待发布）  
> **建议时长**：4~5 小时（ObjectMonitor 1.5h + 正确范式 1.5h + 生产者消费者 1h + 练习 1.5h）  
> 🛠️ **日常高频**：wait/notify 是 Java 最底层的线程协作机制，写生产者-消费者、线程协作必用

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | wait/notify 正确范式（while + 条件检查）、wait 释放锁、notify vs notifyAll、假唤醒 | **深入理解 + 能手写正确代码** |
| ◈◈ | ObjectMonitor 的 _WaitSet/_EntryList 流转、wait(timeout) 超时语义、生产者-消费者两种实现 | **知道原理 + 能画出流转图** |
| ○ | wait 的 spurious wakeup 官方文档细节 | **知道概念即可** |

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
  RUNNABLE → WAITING（调用 wait()）→ 被 notify/notifyAll 唤醒 → RUNNABLE
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
③ 此时的三条路全堵死（三条路的完整论证见 2.3 第一层）：
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
   → 两个队列由此而生（详见第 3 节）：
     EntryList：等锁的人（BLOCKED）     WaitSet：等条件的人（WAITING）
     notify 干的事只是"移队"：WaitSet → EntryList → 抢锁 → 重查条件
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

### 2.2 源码层面的报错

```java
public void wrong() {
    obj.wait();  // ❌ IllegalMonitorStateException!
    // 必须持有 obj 的 monitor 才能调用 wait()
}
```

### 2.3 根本原因：临界区内必须能"交锁等待"，而 wait 是唯一出路

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
```

**第二层（API 语义）：wait() 的定义就是"释放 monitor"，所以必须先持有锁**

```
wait() 的动作 = 释放当前线程持有的 monitor → 挂起 → 被唤醒后重新抢锁
→ 要释放锁，必须先持有锁 → 必须在 synchronized 内调用

反证：单线程、零并发时，不在锁内调 wait() 照样抛 IllegalMonitorStateException
—— 这跟"防竞态"无关，纯粹是 API 语义

补充：wait() 把"释放锁 + 挂起"融合成一步原子操作，中间插不进 notify；
LockSupport.park/unpark 则用 permit 机制实现同样的"不丢唤醒"（03-01 AQS 里细讲）
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

### 2.4 正确理解：wait/notify 与锁的关系

```
wait() 做了什么：
  1. 释放当前线程持有的锁（monitor）
  2. 加入该对象的 WaitSet
  3. 挂起（park）

notify() 做了什么：
  1. 从 WaitSet 中挑一个线程
  2. 把该线程移到 EntryList（等待获取锁的队列）
  3. 该线程重新竞争锁，抢到后从 wait() 返回

→ 唤醒的线程不能立刻执行，必须先抢到锁！
→ 这就是"wait 释放锁"的意义：不给别人释放，别人无法进入临界区去 notify
```

---

## 3. ObjectMonitor 中的两个队列

### 3.1 结构图

```
ObjectMonitor：
┌─────────────────────────────────────────────┐
│  _owner（当前持有锁的线程）                    │
│  _recursions（重入计数）                      │
│                                             │
│  _EntryList：等待获取锁的线程队列               │
│    [Thread1] → [Thread2] → [Thread3]        │
│                                             │
│  _WaitSet：调用了 wait() 的线程队列             │
│    [ThreadA] → [ThreadB]                    │
└─────────────────────────────────────────────┘
```

### 3.2 线程流转图

```
                    ┌──────────────┐
                    │  新线程进入   │
                    └──────┬───────┘
                           ▼
                 ┌─────────────────┐
                 │  _EntryList     │  等待获取锁
                 └────────┬────────┘
                          ▼ 抢到锁
                 ┌─────────────────┐
                 │  执行临界区代码   │
                 └──┬───────┬──────┘
                    │       │
        调用 wait()  │       │ 执行完毕
                    ▼       ▼
           ┌─────────────┐  ┌──────────────┐
           │ _WaitSet    │  │ 释放锁，退出  │
           │ 等待被唤醒   │  └──────────────┘
           └──────┬──────┘
                  │ notify() / notifyAll()
                  ▼
           ┌─────────────────┐
           │ 回到 _EntryList  │  ← 注意：不是直接执行！
           │ 重新排队抢锁      │     要先抢到锁才能从 wait() 返回
           └─────────────────┘
```

### 3.3 关键点

```
1. notify() 只是"移队"，不是"直接唤醒执行"
   WaitSet → EntryList → 抢锁 → 才能继续

2. 被唤醒的线程抢到锁后，从 wait() 返回，继续执行 wait() 之后的代码

3. 两个队列互不干扰：EntryList 是抢锁的，WaitSet 是等通知的
```

---

## 4. wait / notify 正确范式（核心）

### 4.1 必须记住的四条规则

```
规则 1：wait 必须在 synchronized 块内（否则 IllegalMonitorStateException）
规则 2：条件检查必须用 while 循环（不能用 if！）
规则 3：notify 也必须在 synchronized 块内
规则 4：尽量用 notifyAll 而不是 notify
```

### 4.2 为什么条件检查必须用 while（假唤醒）

```java
// ❌ 错误写法：if 只检查一次
synchronized (lock) {
    if (queue.isEmpty()) {      // ① 检查
        lock.wait();            // ② 等待
    }
    process(queue.poll());      // ③ 处理 ← 醒来后不再检查！
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

原因：操作系统层面的限制，JVM 无法完全控制线程唤醒的时机

→ 防御手段：while 循环重新检查条件（而不是 if）
```

### 4.4 为什么推荐 notifyAll 而不是 notify

```java
// ❌ notify() 的问题：
// 1. 只唤醒一个线程，而且是"随机"的 —— 无法控制唤醒谁
// 2. 如果唤醒的线程不满足条件（比如生产者被唤醒但队列已满）→ 它又 wait 回去
//    而真正能处理的线程还在睡 → 死锁！
// 3. 多个消费者场景下，可能唤醒一个"错误"的线程

// ✅ notifyAll() 的好处：
// 所有线程都醒来 → 重新检查条件 → 满足条件的线程继续
// 虽然浪费一点（多唤醒几个），但绝对安全

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
wait(1000)   = 最多等 1 秒，超时自动醒来（从 WAITING 变为 RUNNABLE）

超时醒来的线程：
  1. 自动从 WaitSet 移到 EntryList
  2. 抢到锁后从 wait() 返回
  3. 但条件不一定满足！→ 依然要用 while 循环重新检查！

三种唤醒途径：
  notify / notifyAll  → 手动唤醒
  wait(timeout) 超时  → 自动唤醒
  线程被 interrupt()  → 抛 InterruptedException
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

   线程在没被 notify、中断、超时的情况下被唤醒（操作系统层面的限制）。防御：用 while 循环重新检查条件，而不是 if 只检查一次。
   </details>

4. **多生产者多消费者场景为什么推荐 notifyAll？**
   <details><summary>答案</summary>

   notify 是随机唤醒一个线程，可能唤醒"不满足条件"的线程（比如队列满时唤醒生产者），它检查条件后又 wait 回去，而真正能处理的线程还在睡 → 可能死锁。notifyAll 唤醒所有线程，各自重新检查条件，绝对安全。
   </details>

5. **wait(1000) 超时后，条件一定满足吗？**
   <details><summary>答案</summary>

   不一定。超时只是"自动醒来"，线程还是要重新抢锁、重新检查条件。所以超时醒来后依然要走 while 循环检查。
   </details>

---

> 📬 **阶段二三篇全部完成！你现在掌握的：Synchronized 使用 → 字节码 → 可重入 → 对象头 Mark Word → 锁升级 → ObjectMonitor → wait/notify。这是面试中"锁"专题的完整闭环。**
>
> 下一篇：[03-01-AQS框架源码解析](./03-01-AQS框架源码解析.md)（待发布）—— 阶段三，JUC 的灵魂，全路线最重要的部分

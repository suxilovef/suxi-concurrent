# 01-02 volatile 关键字深度解析

> **阶段一·第 2 篇** | 前置：[01-01-JMM内存模型与三大特性](./01-01-JMM内存模型与三大特性.md) | 后续：[01-03-CAS原理与Unsafe入门](./01-03-CAS原理与Unsafe入门.md)  
> **建议时长**：4~5 小时（原理 1.5h + 源码分析 1h + 动手练习 1.5h）  
> 🛠️ **日常高频**：状态标志位、DCL 单例，几乎每个项目都会用到

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | volatile 内存语义、happens-before 规则、禁止重排序原理、i++ 不保证原子性、4 个适用场景 | **深入理解 + 能写对 + 知道为什么错** |
| ◈◈ | volatile 的屏障插入策略细节、volatile vs final 对比、JDK 源码中的 volatile 应用 | **知道原理 + 能看源码识别** |
| ○ | JMM 8 种原子操作（01-01 已讲）、x86 汇编验证（JITWatch） | **用到时回头看** |

---

## 1. volatile 是什么

一句话定义：

> **volatile 是 Java 提供的一种轻量级同步机制，保证共享变量的"可见性"和"有序性"，但不保证"原子性"。**

```java
// 最简单的使用
private volatile boolean shutdown = false;

// 线程 1                          // 线程 2
shutdown = true;                   while (!shutdown) {
                                       // 一定能看到 shutdown = true
                                   }
```

对比表：

| 特性 | volatile | synchronized | Lock | AtomicInteger |
|---|---|---|---|---|
| 可见性 | ✅ | ✅ | ✅ | ✅ |
| 有序性 | ✅（部分） | ✅ | ✅ | ✅ |
| 原子性 | ❌ | ✅ | ✅ | ✅（单变量） |
| 阻塞 | 无 | 有 | 有 | 无 |
| 性能开销 | 极低 | 中~高 | 中 | 极低 |

---

## 2. volatile 的内存语义

### 2.1 写 volatile 的语义

```
当线程 A 写一个 volatile 变量时，JMM 会：
┌─────────────────────────────────────────┐
│  在 volatile 写之前，线程 A 工作内存中     │
│  所有被修改过的共享变量（不一定 volatile）  │
│  → 全部刷新到主内存                       │
│                                          │
│  效果：写 volatile = 顺便把所有"脏数据"    │
│        也一起写回主内存                    │
└─────────────────────────────────────────┘
```

```java
// volatile 写不仅保证 volatile 变量本身写回主内存，
// 还保证写之前修改的普通变量也写回主内存
int a = 1;              // ① 普通写
volatileFlag = true;    // ② volatile 写
// ② 执行后，a 和 volatileFlag 的最新值都在主内存中对其他线程可见
```

### 2.2 读 volatile 的语义

```
当线程 B 读一个 volatile 变量时，JMM 会：
┌─────────────────────────────────────────┐
│  将线程 B 的工作内存置为无效               │
│  → 强制从主内存重新读取所有共享变量        │
│                                          │
│  效果：读 volatile = 顺便把工作内存"刷新"  │
│        一次，后续读取都从主内存拿           │
└─────────────────────────────────────────┘
```

```java
// volatile 读不仅保证读到 volatile 变量的最新值，
// 还能保证读到 volatile 读之后访问的普通变量的最新值
if (volatileFlag) {     // ③ volatile 读 → 强制刷新工作内存
    int b = a;          // ④ a 一定读到主内存中最新的 a = 1
}
```

### 2.3 经典场景 + happens-before 推导

```java
// 线程 A                            // 线程 B
sharedVar = 42;        // ①           if (flag) {              // ③ volatile 读
flag = true;           // ② volatile 写    int local = sharedVar; // ④
                                          System.out.println(local);
                                      }

// happens-before 推导：
// ① hb ②  （程序次序规则）
// ② hb ③  （volatile 变量规则：volatile 写 hb volatile 读）
// ③ hb ④  （程序次序规则）
// → ① hb ④  （传递性）
// 结论：local 一定是 42
```

> ⚠️ **关键限制**：上述推导只有在**单线程顺序写 → volatile 写**时才成立。如果 A 线程先写 `flag` 再写 `sharedVar`，B 线程不一定能看到 `sharedVar` 的修改。

---

## 3. volatile 禁止指令重排序

### 3.1 为什么需要禁止重排序

回顾 01-01 中 DCL 的 `new` 操作：

```
memory = allocate();      // 1. 分配内存
ctor(memory);             // 2. 初始化对象
instance = memory;        // 3. 引用赋值

如果 2 和 3 被重排序：
instance = memory;        // 3. 先赋值引用（instance 非 null！）
ctor(memory);             // 2. 后初始化对象
→ 另一个线程看到 instance != null → 拿到半初始化对象 → 崩溃
```

### 3.2 volatile 的屏障插入策略

JMM 对 volatile 变量在编译器层面插入了内存屏障：

```
volatile 写：
    普通写
    ────── [StoreStore 屏障] ──────  禁止上面的普通写和下面的 volatile 写重排
    volatile 写
    ────── [StoreLoad 屏障]  ──────  禁止 volatile 写与下面可能有的 volatile 读重排
    后续操作

volatile 读：
    前序操作
    ────── [LoadLoad 屏障]  ──────  禁止 volatile 读与下面的普通读重排
    volatile 读
    ────── [LoadStore 屏障] ──────  禁止 volatile 读与下面的普通写重排
    后续操作
```

**图解**：

```
volatile 写前：StoreStore
volatile 写后：StoreLoad
volatile 读后：LoadLoad + LoadStore

┌──────────────────────────────────────┐
│ volatile 写 = 释放语义（release）      │
│   → 写之前的所有操作不能重排到写之后      │
│                                      │
│ volatile 读 = 获取语义（acquire）      │
│   → 读之后的所有操作不能重排到读之前      │
└──────────────────────────────────────┘
```

### 3.3 x86 平台的实际实现

> ○ **x86 是强内存模型（TSO）**，只允许 Store-Load 重排序。所以 volatile 在 x86 上的实现很简单：

```
x86 上：
  volatile 读 = 普通读（x86 读本身有序）
  volatile 写 = 普通写 + lock 前缀指令（相当于 StoreLoad 屏障）

lock 前缀做了什么：
  - 锁定总线/缓存行，保证写操作的独占
  - 使其他 CPU 缓存行失效（相当于 MESI → Invalidate）
  - 禁止 Store-Load 重排序
```

> 你可以用 JITWatch + hsdis 插件观察有/无 volatile 时生成的实际汇编指令。无 volatile 时是普通 `mov` 指令，有 volatile 时前面有 `lock` 前缀。

---

## 4. volatile 不保证原子性（核心陷阱）

### 4.1 `i++` 问题回顾

```java
volatile int count = 0;

// 10 个线程各执行 10000 次 count++
// 预期：100000
// 实际：通常 20000 ~ 40000（丢失率 60%~80%）
```

原因已经在 01-01 讲过，这里**聚焦于 volatile 的本质局限**：

```
volatile 的保证范围：
  ✅ 单次读：读到的值一定是最新的
  ✅ 单次写：写完后一定对所有线程可见
  ❌ 复合操作（读→改→写）：volatile 不保护中间态

count++ 被拆成三步：
  getstatic (读) → iadd (加1) → putstatic (写)
            ↑ volatile 的可见性只保证这一步               ↑ 和这一步
            但不保证"读-加-写"这个整体不被打断
```

### 4.2 什么操作 volatile 能保证原子性

```java
// ✅ 能保证：单次读或单次写
volatile int a = 1;        // 写
int b = a;                 // 读

// ✅ 能保证：boolean/byte/short/char/float/int/引用 的单次读写
volatile boolean flag = true;

// ❌ 不能保证：任何"读-改-写"的复合操作
volatile int count;
count++;                   // ❌ 不是原子的
count = count + 1;         // ❌ 不是原子的
count += 1;                // ❌ 不是原子的

// ❌ 不能保证：多变量联动
volatile int x, y;
void setBoth(int a, int b) {
    x = a;  // ①
    y = b;  // ② ← ①② 之间没有原子性保证
}

// ✅ volatile long/double 的读写是原子的 —— JMM 强制保证，不会出现"半个值"
// ❌ 非 volatile long/double 的读写 —— JMM 不保证原子性
//    在 32 位 JVM 上可能被拆成两个 32 位操作，读到脏数据
```

### 4.3 需要原子性时用什么

| 场景 | 方案 |
|---|---|
| 简单的计数/自增 | `AtomicInteger` / `LongAdder` |
| 单变量原子操作 | `AtomicReference` / `AtomicBoolean` |
| 多个变量联动 | `synchronized` / `ReentrantLock` |
| 复杂的读-改-写 | `synchronized` / `ReentrantLock` |
| 读多写少的标志位 | `volatile` 就够了 |

---

## 5. volatile 的适用场景（🛠️ 日常高频）

### 场景 1：状态标志位（Shutdown Flag）

```java
public class Worker implements Runnable {
    private volatile boolean running = true;  // 🛠️ 最常见的用法

    @Override
    public void run() {
        while (running) {
            // 处理任务
        }
        // 退出前的清理工作
    }

    public void shutdown() {
        running = false;  // volatile 写 → 工作线程立即可见
    }
}
```

**为什么不直接用普通 `boolean`？**

```java
// ❌ 错误原因 1：JIT 可能将 running 缓存到寄存器
// → while 循环永远退不出
private boolean running = true;  // 没有 volatile！

// ❌ 错误原因 2：即使用 volatile，也要配合中断来处理阻塞操作
// 如果 run() 里有 blockingQueue.take() — 它在阻塞，while (!running) 永远执行不到
```

### 场景 2：双重检查锁定（DCL）

```java
public class Singleton {
    // 🛠️ volatile 防止 new 操作的重排序（半初始化对象）
    private static volatile Singleton instance;

    private Singleton() {}

    public static Singleton getInstance() {
        if (instance == null) {                    // ① 第一次检查（不加锁，快速路径）
            synchronized (Singleton.class) {
                if (instance == null) {            // ② 第二次检查（加锁，安全路径）
                    instance = new Singleton();    // ③ 创建对象
                }
            }
        }
        return instance;
    }
}
```

**每条语句的必要性分析**：

```
① 第一次检查：性能优化，大多数时候 instance 已存在，无需加锁
② 第二次检查：防止重复创建
   线程 A 和 线程 B 同时通过 ①
   → 线程 A 获得锁，创建 instance，释放锁
   → 线程 B 获得锁，如果不再检查 ②，会再次创建 instance
③ volatile：禁止指令重排
   new Singleton() = 分配内存 → 初始化 → 赋值引用
   如果 初始化 和 赋值引用 被重排 → 线程 B 在 ① 处看到半初始化对象
```

> 📝 **生产最佳实践**：枚举单例 > 静态内部类 > DCL。DCL 只有在需要延迟加载且无法用静态内部类时才用。

### 场景 3：CAS 操作中的 value 字段

```java
// AtomicInteger 内部
public class AtomicInteger extends Number {
    private volatile int value;  // 🛠️ CAS 的底层依赖 volatile

    public final int incrementAndGet() {
        return U.getAndAddInt(this, VALUE, 1) + 1;
    }
}

// AQS 内部
public abstract class AbstractQueuedSynchronizer {
    private volatile int state;  // 🛠️ 同步状态，CAS + volatile 的组合
}
```

> 为什么 CAS + volatile 是黄金搭档？  
> CAS 保证"读-改-写"的原子性，volatile 保证 CAS 读到的值是最新的 → 两者配合实现无锁线程安全。

### 场景 4：开关/配置热更新

```java
public class ConfigManager {
    private volatile int maxConnections = 100;    // 🛠️ 单变量配置
    private volatile boolean debugMode = false;
    private volatile String apiEndpoint;

    // 管理线程修改配置 → 业务线程立即可见，无需重启
    public void updateConfig(int maxConn, boolean debug, String endpoint) {
        this.maxConnections = maxConn;
        this.debugMode = debug;
        this.apiEndpoint = endpoint;
        // 注意：这三个变量之间没有 happens-before 关系
        // 如果需要整体一起生效，应该用一个 volatile 的 Config 对象引用
    }
}
```

> ⚠️ **坑**：如果多个配置变量需要"同时生效"，应该包装成一个不可变对象，然后 volatile 引用那个对象。

### 场景 5：单次发布-订阅

```java
public class Publisher {
    // 🛠️ 发布者只写一次，订阅者反复读
    private volatile Subscriber subscriber;

    public void register(Subscriber s) {
        this.subscriber = s;  // volatile 写：新订阅者对所有线程可见
    }

    public void publish(Message msg) {
        Subscriber s = subscriber;
        if (s != null) {
            s.onMessage(msg);
        }
    }
}
```

---

## 6. volatile 的**不适用**场景（知道什么时候别用）

| 场景 | 原因 | 正确替代 |
|---|---|---|
| 计数器（i++） | 复合操作不原子 | `AtomicInteger` / `LongAdder` |
| 多个变量联动修改 | 没有整体的 atomicity | `synchronized` / `Lock` |
| 需要互斥访问（临界区） | volatile 不阻塞 | `synchronized` / `ReentrantLock` |
| 读写锁场景 | volatile 不支持 | `ReadWriteLock` / `StampedLock` |
| 循环依赖当前值（`count = count * 2`） | 不保证原子 | `AtomicInteger.updateAndGet()` |

---

## 7. volatile vs final 对比

> ◈◈ 两者都能提供某种程度的"安全发布"保证，但机制完全不同。

| | volatile | final |
|---|---|---|
| 保证什么 | 可见性 + 有序性（部分） | 构造完成后初始值的可见性 |
| 何时生效 | 每次读写 | 构造方法完成后 |
| 能否修改 | 可以反复修改 | 初始化后不可变 |
| 底层机制 | 内存屏障 | 构造方法结束时的 StoreStore 屏障 |
| 典型场景 | 状态标志、DCL | 不可变对象、配置常量 |
| JMM 规则 | volatile 写 hb volatile 读 | this 逃逸安全规则 |

**final 的安全发布**：只要对象的构造函数中没有让 `this` 逸出，所有 final 字段在构造函数结束后，对其他线程一定可见。

```java
// ✅ 安全的 final 发布
public class ImmutableConfig {
    private final int maxConnections;
    private final String endpoint;

    public ImmutableConfig(int mc, String ep) {
        this.maxConnections = mc;
        this.endpoint = ep;
        // 构造函数正确结束 → final 字段对所有线程可见
    }
}
```

---

## 8. JDK 源码中的 volatile 应用（学会读源码时识别）

| 类 | 字段 | 作用 |
|---|---|---|
| `Thread` | `volatile String name` | 线程名修改后对其他线程可见 |
| `Thread` | `volatile int threadStatus` | 线程状态（6 种） |
| `ThreadPoolExecutor` | `volatile int runState`（在 ctl 中） | 运行状态 + 线程数 |
| `AbstractQueuedSynchronizer` | `volatile int state` | 同步状态位 |
| `ConcurrentHashMap` | `volatile Node<K,V>[] table` | 桶数组的引用 |
| `FutureTask` | `volatile int state` | 任务状态 |
| `CopyOnWriteArrayList` | `volatile Object[] array` | 底层数组引用 |

> 🔑 **规律**：凡是用 volatile 的字段，几乎都是"一个线程写，多个线程读"的**状态/引用控制**模式。

---

## 9. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：循环内的 volatile 可能被"意外修复"

```java
// 理论上这段代码可能因为无 volatile 而退不出循环
while (!flag) {
    // 但如果这里面有 System.out.println（含 synchronized）
    // 或任何内存屏障操作 → 会强制刷新缓存 → "碰巧正确"
    // 这非常危险！不要依赖副作用来保证正确性！
}
```

### 🕳️ 坑 2：赋值给 volatile 数组 ≠ 数组元素 volatile

```java
volatile int[] arr = new int[10];
arr[0] = 42;     // ❌ 这个写不保证对所有线程可见！
arr = new int[5]; // ✅ 这个写保证对所有线程可见（修改的是引用）

// volatile 只保护引用的可见性，不保护数组内容的可见性
// 要保护数组内容，用 AtomicIntegerArray
```

### 🕳️ 坑 3：volatile 和 synchronized 可以"混用"

```java
// ✅ 可以：volatile 变量 + synchronized 块配合使用
volatile boolean flag;

// 线程 A：synchronized 块（锁 lock） + volatile 写
synchronized (lock) {
    prepareData();
    flag = true;  // volatile 写
}

// 线程 B：volatile 读 + synchronized 块（同一个锁）
if (flag) {  // volatile 读
    synchronized (lock) {
        // 这里能看到 prepareData() 的效果吗？
        // 能！因为 volatile 写 hb volatile 读，再通过锁的规则传递
    }
}
```

### 🕳️ 坑 4：DCL 中漏掉 volatile 极难发现问题

```java
// ❌ 去掉 volatile 的 DCL
private static Singleton instance;  // 没有 volatile

// 问题：new 重排序导致其他线程看到半初始化对象
// 几率极低，但在高并发场景下偶发 → 最难排查的那种 bug
// 测试环境可能永远碰不到，一到生产就偶发崩溃
```

---

## 10. 面试高频考点

1. **volatile 保证什么？不保证什么？**
   → 保证可见性和有序性（禁止重排），不保证原子性。i++ 是经典反例。

2. **写 volatile 变量时，JMM 具体做了什么？**
   → volatile 写之前的所有修改一起刷新到主内存。这是 **"附带刷新"** 效果。

3. **DCL 单例中 volatile 的作用是什么？**
   → 禁止 `new` 操作中的分配内存和初始化重排序，防止其他线程拿到半初始化对象。

4. **volatile 和 synchronized 的区别？**
   → 可见性都保证。volatile：不阻塞、不保证原子性、只能修饰变量。synchronized：阻塞、保证原子性、可修饰方法和代码块。

5. **volatile 修饰数组时，对数组元素的修改对其他线程可见吗？**
   → 不可见。volatile 只修饰引用，不修饰内容。用 AtomicIntegerArray。

---

## 11. 实战练习

### 练习 1：状态标志位 —— 优雅停机（30 分钟）

在 `src/test/java/com/sw/yang/concurrent/jmm/` 下创建：

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：用 volatile 实现优雅停机
 *
 * 模拟：Worker 线程在处理任务，需要能随时安全停止
 */
public class VolatilePracticeTest {

    // TODO: 分别尝试有 volatile 和无 volatile，观察 shutdown 的效果
    private volatile boolean running = true;
    private final AtomicInteger processedCount = new AtomicInteger(0);

    @Test
    public void testGracefulShutdown() throws InterruptedException {
        Thread worker = new Thread(() -> {
            while (running) {
                // 模拟处理任务
                processedCount.incrementAndGet();
                try {
                    Thread.sleep(10); // 模拟 IO 操作
                } catch (InterruptedException e) {
                    // 中断不是用来停止的，而是用来唤醒的
                    // 还是要通过 running 标志位来判断
                    System.out.println("Worker 被中断唤醒");
                }
            }
            System.out.println("Worker 检测到 running=false，开始清理...");
            System.out.println("Worker 安全退出，共处理: " + processedCount.get());
        }, "worker");
        worker.start();

        // 让 worker 跑一会儿
        Thread.sleep(2000);
        System.out.println("已处理 " + processedCount.get() + " 个任务");

        // 优雅停机
        System.out.println("发送停机信号...");
        running = false;
        worker.interrupt();  // 如果 worker 在 sleep 里，需要中断唤醒

        worker.join(3000);
        System.out.println("停机后任务数: " + processedCount.get());
        System.out.println(worker.isAlive() ? "❌ Worker 未能退出" : "✅ Worker 已安全退出");
    }
}
```

### 练习 2：volatile 写"附带刷新"验证（30 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 volatile 写的"附带刷新"效果
 *
 * 线程 A：先写普通变量 → 再写 volatile 变量
 * 线程 B：先读 volatile 变量 → 再读普通变量
 * → 线程 B 应该能看到线程 A 写的普通变量值
 */
public class VolatilePropagationTest {

    private int normalValue = 0;       // 普通变量
    private volatile boolean signal = false; // volatile 变量

    @Test
    public void testVolatilePropagation() throws InterruptedException {
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100); // 确保 reader 先开始等待
            } catch (InterruptedException e) { /* ignore */ }
            normalValue = 42;    // ① 先写普通变量
            signal = true;       // ② 再写 volatile → 附带刷新 normalValue 到主内存
            System.out.println("Writer: normalValue=" + normalValue + ", signal=true");
        }, "writer");

        Thread reader = new Thread(() -> {
            int attempts = 0;
            while (!signal) {   // ③ 读 volatile → 附带刷新工作内存
                attempts++;
            }
            // ④ 由于 ① hb ② hb ③ hb ④ → normalValue 一定为 42
            int value = normalValue;
            System.out.println("Reader: signal=true, normalValue=" + value +
                    " (attempts=" + attempts + ")");
            if (value == 42) {
                System.out.println("✅ volatile 附带刷新生效");
            } else {
                System.out.println("❌ 不应该出现在这里");
            }
        }, "reader");

        reader.start();
        writer.start();

        writer.join();
        reader.join(5000);
    }
}
```

### 练习 3：画出 volatile 的屏障插入位置（20 分钟）

> 手写以下代码的屏障位置图：

```java
int a = 0;
volatile boolean flag = false;

// 线程 A
a = 1;           // 普通写
flag = true;     // volatile 写 — 画出前后各插入什么屏障？

// 线程 B
if (flag) {      // volatile 读 — 画出前后各插入什么屏障？
    int b = a;   // b 一定等于 1 吗？
}
```

---

## 12. 自测题

1. **volatile 修饰的 `long` 变量，在 32 位 JVM 上读写是原子的吗？非 volatile 的 `long` 呢？**
   <details><summary>答案</summary>

   volatile 修饰的 long/double 读写是原子的（JMM 强制保证）。非 volatile 的 long/double 在 32 位 JVM 上可能被拆分为两个 32 位操作，读到"半个"值（脏数据）。64 位 JVM 上通常也是原子的，但 JMM 不强制保证。
   </details>

2. **以下写法哪个线程安全，哪个不安全？**

   ```java
   // A
   volatile int count = 0;
   count = count + 1;

   // B
   volatile boolean flag = false;
   flag = true;

   // C
   volatile int[] arr;
   arr = new int[]{1, 2, 3};

   // D: 还是 arr
   arr[0] = 99;
   ```
   <details><summary>答案</summary>

   - A：❌ 不安全，count+1 是读-改-写
   - B：✅ 安全，单次写
   - C：✅ 安全，修改的是引用（arr 变量本身）
   - D：❌ 不安全，修改的是数组元素，volatile 不保护数组内容
   </details>

3. **volatile 为什么比 synchronized 性能好？**
   <details><summary>答案</summary>

   volatile 是**无锁**的：
   - 不涉及线程上下文切换（没有内核态切换）
   - 不涉及阻塞/唤醒（无 wait/park 开销）
   - 仅仅是一个 CPU 缓存刷新操作（x86 上 `lock` 前缀指令）
   - 开销 ≈ 一个 CPU 周期级别的内存屏障

   synchronized 有锁升级过程，最坏情况下需要：
   - 线程挂起 → 内核态 → 上下文切换 → 线程恢复
   - 开销可能是微秒到毫秒级
   </details>

4. **什么时候用 volatile，什么时候用 AtomicInteger？**
   <details><summary>答案</summary>

   | 场景 | 用 volatile | 用 AtomicInteger |
   |---|---|---|
   | 简单读写（set/get） | ✅ | ❌ 大材小用 |
   | 自增/自减 | ❌ 不安全 | ✅ CAS 保证 |
   | 状态标志位 | ✅ 最佳 | ❌ 大材小用 |
   | CAS 操作 | ❌ 做不到 | ✅ |
   </details>

---

> 📬 **完成自测题 + 3 个练习后，进入下一篇 [01-03-CAS原理与Unsafe入门](./01-03-CAS原理与Unsafe入门.md)**

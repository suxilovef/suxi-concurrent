# 01-03 CAS 原理与 Unsafe 入门

> **阶段一·第 3 篇** | 前置：[01-02-volatile关键字深度解析](./01-02-volatile关键字深度解析.md) | 后续：[02-01-Synchronized使用与字节码](../02-01-Synchronized使用与字节码.md)（待发布）  
> **建议时长**：3~4 小时（原理 1h + 源码阅读 1h + 动手练习 1.5h）  
> **角色**：承上启下 — 衔接 volatile 的可见性 + 为阶段三 AQS 做铺垫

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| ⭐⭐⭐ | CAS 三个操作数、Unsafe 的核心 CAS 方法、CAS 自旋模式、CAS + volatile 的黄金组合 | **理解原理 + 能写简化版自旋代码** |
| ◈◈ | ABA 问题与 AtomicStampedReference、CAS 的 CPU 开销分析、CAS 在 JDK 中的分布 | **知道为什么需要、怎么解决** |
| ○ | Unsafe 的全貌（内存操作/对象操作/数组操作/线程操作）、Unsafe 的获取方式 | **知道有这个能力，用到时查文档** |

---

## 1. CAS 是什么

### 1.1 一句话定义

> **CAS（Compare And Swap）是一条 CPU 原子指令，包含三个操作数：内存地址 V、期望值 A、新值 B。只有当 V 的值等于 A 时，才把 V 更新为 B。整个过程由 CPU 硬件保证原子性。**

```
CAS(V, A, B) 的语义：

if (V.get() == A) {
    V.set(B);
    return true;   // 交换成功
} else {
    return false;  // 交换失败，V 的实际值 ≠ 预期值 A
}
```

### 1.2 为什么需要 CAS

回顾前两篇的核心矛盾：

```
volatile：保证可见性，不保证原子性
synchronized：保证原子性，但涉及线程阻塞（重量级）

问题：有没有一种方式，既保证原子性、又不阻塞线程？

答案：CAS → 无锁并发（Lock-Free）的基石
```

### 1.3 三个操作数的含义

```
CAS(内存地址 V, 期望值 A, 新值 B)

    V — 要更新的变量在内存中的地址
    A — 你认为 V 的当前值应该是多少（预期值）
    B — 如果 V 确实是 A，就把 V 更新为 B

工作流程：
┌─────────────────────────────────────────────────┐
│ 1. 读取 V 的当前值                                │
│ 2. 检查 V == A ?                                │
│    ├─ 是 → 原子地将 V 更新为 B → 返回 true         │
│    └─ 否 → 什么也不做 → 返回 false（说明有并发修改） │
│ 3. 如果返回 false，通常重新读取 V，再次尝试 CAS    │
│    → 这就是 "CAS 自旋"                            │
└─────────────────────────────────────────────────┘
```

**一句话说清楚**：CAS = "我以为你是 A，如果是就改成 B，如果不是说明别人改过了，我重来。"

---

## 2. JUC 的基石：CAS + volatile

### 2.1 黄金组合

```
CAS 负责：保证"比较并交换"这个操作的原子性
volatile 负责：保证 CAS 读到的值是最新的（可见性）

两者配合 → 实现无锁的线程安全操作
```

### 2.2 以 AtomicInteger.incrementAndGet() 为例

```java
// AtomicInteger 源码（简化版）
public class AtomicInteger {
    private volatile int value;  // ← volatile 保证可见性
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE; // value 字段的内存偏移量

    public final int incrementAndGet() {
        // CAS 自旋
        for (;;) {
            int current = value;              // ① volatile 读，拿到最新值
            int next = current + 1;           // ② 计算新值
            if (U.compareAndSwapInt(this, VALUE, current, next)) {
                return next;                  // ③ CAS 成功，返回
            }
            // ④ CAS 失败 → 说明有别的线程改了 value → 回到 ① 重试
        }
    }
}
```

**流程图**：

```
                  ┌─────────────┐
                  │  开始自增    │
                  └──────┬──────┘
                         ▼
              ┌─────────────────────┐
              │ volatile 读 value   │  ← 拿到最新值 current
              └────────┬────────────┘
                       ▼
              ┌─────────────────────┐
              │ next = current + 1  │  ← 计算新值
              └────────┬────────────┘
                       ▼
         ┌─────────────────────────────┐
         │ CAS(value, current, next)   │  ← 原子比较并交换
         └──────────────┬──────────────┘
                  ┌─────┴─────┐
                  │           │
              成功 ▼       失败 ▼
         ┌──────────┐  ┌──────────────┐
         │ 返回 next │  │ 有并发修改   │
         └──────────┘  │ 重新读 value  │
                       └──────┬───────┘
                              │
                              └──→ 回到 volatile 读
```

### 2.3 为什么 CAS 不需要加锁

```
传统锁（synchronized / ReentrantLock）：
  线程获取不到锁 → 操作系统将其挂起 → 上下文切换 → 等待唤醒
  → 每次挂起/唤醒涉及用户态↔内核态切换，开销大

CAS：
  线程 CAS 失败 → 重新读取值 → 再次 CAS
  → 整个过程在用户态完成，无上下文切换
  → 这就是 "乐观锁" 的核心思想：我先试试，不行再重试
```

---

## 3. Unsafe 类

### 3.1 Unsafe 是什么

```
Unsafe = sun.misc.Unsafe
      = JDK 内部的"后门"类
      = 提供直接操作内存、CAS、线程调度等底层能力
      = 不开放给外部使用（类名就叫 Unsafe）
      = 只能通过反射获取单例
```

### 3.2 Unsafe 的核心 CAS 方法

```java
// Unsafe 中三个核心 CAS 方法（都是 native 方法，对应 CPU 的 CAS 指令）

// 对 int 类型字段的 CAS
public final native boolean compareAndSwapInt(Object o, long offset,
                                               int expected, int x);

// 对 long 类型字段的 CAS
public final native boolean compareAndSwapLong(Object o, long offset,
                                                long expected, long x);

// 对 Object 类型字段的 CAS
public final native boolean compareAndSwapObject(Object o, long offset,
                                                  Object expected, Object x);

// 参数含义：
// o       — 要操作的对象（哪个对象）
// offset  — 字段在对象中的内存偏移量（哪个字段）
// expected — 期望值
// x       — 新值
```

**参数中的 `offset` 是什么**：

```
一个 Java 对象在内存中是这样布局的：

┌──────────────┬──────────────┬──────────────────┐
│  Mark Word   │  Klass Pointer│  实例数据（字段）  │
│  (8/12 bytes)│  (4/8 bytes)  │  offset 偏移量   │
└──────────────┴──────────────┴──────────────────┘
                                     ↑
                              offset 就是字段相对于对象起始位置的偏移量
                              通过 Unsafe.objectFieldOffset() 获取
```

### 3.3 Unsafe 的其他能力（知道即可，○）

> ○ Unsafe 远比 CAS 强大，但不建议在生产代码中直接使用（Java 9+ 已有 VarHandle 替代）。

| 能力分类 | 方法示例 | 用途 |
|---|---|---|
| CAS 操作 | `compareAndSwap{Int/Long/Object}` | JUC 所有原子类的基础 |
| 内存操作 | `allocateMemory` / `freeMemory` / `putInt` / `getInt` | 直接内存操作（类似 C 语言） |
| 对象操作 | `allocateInstance` 不调构造直接创建对象 | 反序列化框架用 |
| 数组操作 | `arrayBaseOffset` / `arrayIndexScale` | 计算数组元素偏移 |
| 线程操作 | `park` / `unpark` | LockSupport 的底层实现 |
| 内存屏障 | `loadFence` / `storeFence` / `fullFence` | 直接插入屏障指令 |
| 类操作 | `defineClass` / `defineAnonymousClass` | 动态生成类 |

### 3.4 获取 Unsafe 实例

```java
// 方式 1：JDK 内部直接调用（JUC 的类就是这样做的）
private static final Unsafe U = Unsafe.getUnsafe();
// ⚠️ 限制：只有 BootstrapClassLoader 加载的类才能调用，普通应用代码调用会抛 SecurityException

// 方式 2：通过反射（普通应用代码使用）
public static Unsafe getUnsafe() {
    try {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

---

## 4. CAS 自旋的代码模式

### 4.1 基础自旋模式

```java
// 标准的 CAS 自旋模式（AtomicInteger 的精简版）
public final int getAndAddInt(Object o, long offset, int delta) {
    int v;
    do {
        v = getIntVolatile(o, offset);  // ① volatile 读，取当前值
    } while (!compareAndSwapInt(o, offset, v, v + delta));  // ② CAS，失败则重试
    return v;
}
```

### 4.2 手写一个 CAS 驱动的线程安全计数器

```java
public class CasCounter {
    private volatile int value;  // volatile 保证可见性

    // 需要 Unsafe + 字段偏移量（初始化时通过反射获取）
    private static final Unsafe U;
    private static final long VALUE_OFFSET;

    static {
        try {
            // 通过反射获取 Unsafe 实例（见 §3.4）
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
            VALUE_OFFSET = U.objectFieldOffset(CasCounter.class.getDeclaredField("value"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public int incrementAndGet() {
        int current, next;
        do {
            current = value;     // volatile 读
            next = current + 1;  // 计算新值
        } while (!U.compareAndSwapInt(this, VALUE_OFFSET, current, next));
        // CAS 失败 → 说明 value 变了 → 重新读取 current → 重新计算 next → 再次 CAS
        return next;
    }
}
```

---

## 5. ABA 问题

### 5.1 什么是 ABA

```
ABA 问题：一个值从 A 变成 B，又变回 A，
        CAS 只看"当前值是不是 A"，看不出中间发生过变化

时间线：
  时刻 1: value = A
  时刻 2: 线程1 把 value 改成 B
  时刻 3: 线程2 把 value 改回 A
  时刻 4: 线程3 CAS → 看到 value = A，以为没变过 → CAS 成功

  CAS 的缺陷：只检查"值"，不检查"有没有人改过历史"
```

### 5.2 ABA 什么时候有危害

**经典场景：无锁栈的 pop 操作**：

```
栈中元素：A → B → C

线程 1 要 pop A：
  ① 读取栈顶 = A
  ② CAS(栈顶, A, B)  ← 此时被调度走，暂停执行

线程 2 连续执行：
  pop A → pop B → push A
  栈变成：A → C
  （线程 2 把 A 放回来了）

线程 1 恢复：
  CAS(栈顶, A, B)  ← 成功！因为栈顶现在是 A
  但实际上 B 已经不在栈里了 → 出栈出错了

  栈应该变成：C
  栈实际变成：B（错的！B 早就被 pop 走了，链向了野指针）
```

### 5.3 如何解决

**方案一：版本号（AtomicStampedReference）**

```java
// 不是只比较值，还比较版本号
AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 0);

int[] stamp = new int[1];
String value = ref.get(stamp);  // 值和当前版本号一起读出来

// CAS 同时校验值和版本号
boolean ok = ref.compareAndSet(value, "B", stamp[0], stamp[0] + 1);
//               ──────┬────── ────┬──── ──────┬────── ─────┬──────
//                期望值     新值   期望版本号  新版本号
```

**方案二：布尔标记（AtomicMarkableReference）**

```java
// 只关心"有没有被改过"，不关心改了几次
AtomicMarkableReference<String> ref = new AtomicMarkableReference<>("A", false);

ref.compareAndSet("A", "B", false, true);
// 适合"我只想知道有没有被碰过"的场景
```

**方案三：大部分场景不需要处理 ABA**

> 大多数使用 CAS 的场景（如 AtomicInteger 自增、LongAdder 计数），ABA 不产生实质性危害，不需要额外处理。只有**引用类型的 CAS**（链表、栈等数据结构）才需要关注。

---

## 6. CAS 的开销与局限

### 6.1 CPU 开销

```
CAS 的开销来源：

1. CPU 锁总线/缓存行：lock 前缀会锁定缓存行（或总线，取决于实现）
   → 一个核心在执行 CAS，其他核心不能同时操作同一个缓存行

2. 自旋重试：竞争激烈时，CAS 会反复失败 → 反复自旋 → CPU 空转
   线程1: CAS成功 → 继续执行
   线程2: CAS失败 → 重试 → CAS失败 → 重试 → ... → CPU 空转
   线程3: CAS失败 → 重试 → CAS失败 → 重试 → ...

3. 伪共享放大：多个变量在同一个缓存行 → 一个 CAS 导致整个缓存行失效
   → 其他 CPU 上的变量也都失效 → 雪崩效应
```

### 6.2 CAS vs 锁的选型决策

```
CAS 适合：竞争不激烈、临界区极短（几条指令）
  → AtomicInteger 自增、LongAdder 计数
  → 失败就重试，代价比线程切换小

锁适合：竞争激烈、临界区较长（几十条指令以上）
  → 竞争激烈时 CAS 反复失败 → CPU 空转 → 不如阻塞等待
  → synchronized 在 JDK 8+ 下经过大量优化，短临界区性能接近 CAS
```

### 6.3 破除迷信：无锁不一定比有锁快

> 很多人认为"无锁一定比有锁快"，这是错误的。在高竞争场景下，CAS 的自旋重试会导致 CPU 空转（忙等），而 synchronized 会将失败的线程挂起（让出 CPU 给其他线程用）。**正确的做法是：用 JMH 压测，让数据说话，而不是凭感觉选型。**（JMH 会在阶段五详细讲解）

---

## 7. CAS 在 JDK 中的分布（阅读源码时的导航）

| 所在类 | 使用方式 | 具体用途 |
|---|---|---|
| `AtomicInteger` / `AtomicLong` | CAS 自旋 | 原子自增/自减 |
| `LongAdder` | CAS + Cell 分段 | 高并发计数 |
| `AbstractQueuedSynchronizer` | CAS state | 同步状态的获取与释放 |
| `ReentrantLock` | AQS → CAS | 锁的获取 |
| `ConcurrentHashMap` | CAS table 桶 | 初始化表、插入空桶 |
| `CopyOnWriteArrayList` | —（用锁） | COW 用 ReentrantLock，不用 CAS |
| `FutureTask` | CAS state | 任务状态切换 |
| `SynchronousQueue` | CAS 栈/队列 | TransferStack/TransferQueue |
| `CountDownLatch` | AQS → CAS | countDown |

> 🔑 规律：凡是状态位（state/status）的切换，几乎都用 CAS + volatile。

---

## 8. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：普通应用代码不能直接 `Unsafe.getUnsafe()`

```java
// ❌ 会抛 SecurityException
Unsafe unsafe = Unsafe.getUnsafe();

// ✅ 必须通过反射
Field f = Unsafe.class.getDeclaredField("theUnsafe");
f.setAccessible(true);
Unsafe unsafe = (Unsafe) f.get(null);
```

### 🕳️ 坑 2：CAS 失败的次数不被感知

```java
//❌ 这个循环可能 run 了几十万次才成功，但你完全不知道
while (!U.compareAndSwapInt(this, OFFSET, current, next)) {
    current = get();  // 重试
    next = current + 1;
}

// ✅ 生产代码应该加上自旋次数监控
int retries = 0;
while (!U.compareAndSwapInt(this, OFFSET, current, next)) {
    current = get();
    next = current + 1;
    if (++retries > 1000) {
        // 告警：竞争太激烈，考虑换方案
    }
}
```

### 🕳️ 坑 3：CAS 只保护一个变量

```java
// ❌ CAS 不能保护多变量联动
// 比如同时修改 account1 和 account2，CAS 无能为力
// 需要用悲观锁或分布式事务
```

### 🕳️ 坑 4：忽略 ABA 在引用场景的危害

```java
// ❌ 使用 AtomicReference 实现无锁栈时，必须处理 ABA
// 否则在高并发下会出现元素丢失或野指针
```

---

## 9. 面试高频考点

1. **什么是 CAS？三个参数分别是什么？**
   → CAS = Compare And Swap。内存地址 V、期望值 A、新值 B。V == A 时原子地更新为 B。

2. **CAS 的底层是什么？**
   → CPU 的 `cmpxchg` 指令（x86）。Java 通过 Unsafe 的 native 方法调用。`lock cmpxchg` 保证多核原子性。

3. **什么是 ABA 问题？怎么解决？**
   → 值从 A→B→A，CAS 看不出变化。AtomicStampedReference（版本号）或 AtomicMarkableReference（布尔标记）解决。

4. **CAS 和 synchronized 各适用什么场景？**
   → CAS：短临界区、低竞争，如计数器。synchronized：长临界区、高竞争，如复杂的业务逻辑。

5. **为什么 AtomicInteger 的 value 必须是 volatile？**
   → 保证 CAS 读取的 current 是最新值。没有 volatile，CAS 可能基于过期数据做判断。

---

## 10. 实战练习

### 练习 1：用 CAS 实现线程安全的计数器（45 分钟）

在 `src/test/java/com/sw/yang/concurrent/jmm/` 下创建：

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * 练习 1：用 CAS + volatile 手写一个线程安全的计数器
 *
 * 目标：
 * 1. 理解 CAS 自旋的基本模式
 * 2. 理解反射获取 Unsafe 的必要性
 * 3. 验证多线程自增的正确性
 */
public class CasCounterTest {

    @Test
    public void testMyCasCounter() throws InterruptedException {
        MyCasCounter counter = new MyCasCounter();

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    counter.increment();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("预期: 100000");
        System.out.println("实际: " + counter.get());
        System.out.println(counter.get() == 100000 ? "✅ CAS 计数器正确" : "❌ 异常");
    }
}

class MyCasCounter {
    private volatile int value;  // volatile 保证可见性

    private static final Unsafe U;
    private static final long VALUE_OFFSET;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
            // 获取 value 字段在 MyCasCounter 对象中的内存偏移量
            VALUE_OFFSET = U.objectFieldOffset(
                    MyCasCounter.class.getDeclaredField("value"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public void increment() {
        int current, next;
        do {
            current = value;     // volatile 读
            next = current + 1;
        } while (!U.compareAndSwapInt(this, VALUE_OFFSET, current, next));
    }

    public int get() {
        return value;           // volatile 读
    }
}
```

### 练习 2：对比 CAS vs synchronized 的性能差异（30 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：简单对比 CAS 自旋 vs synchronized 在不同竞争强度下的表现
 *
 * 结论预览（你需要实际运行验证）：
 *  - 低竞争时：CAS 略快
 *  - 高竞争时：synchronized 更稳定（CAS 自旋大量失败重试）
 *  - 更精确的对比需要用 JMH（阶段五会学）
 */
public class CasVsSyncTest {

    private static final int THREADS = 10;
    private static final int ITERATIONS = 100_000;

    @Test
    public void testCasPerformance() throws InterruptedException {
        AtomicInteger casCounter = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    casCounter.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("CAS AtomicInteger: " + (System.currentTimeMillis() - start) +
                "ms, 结果=" + casCounter.get());
    }

    @Test
    public void testSyncPerformance() throws InterruptedException {
        Object lock = new Object();
        int[] counter = {0};
        long start = System.currentTimeMillis();

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    synchronized (lock) {
                        counter[0]++;
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("synchronized: " + (System.currentTimeMillis() - start) +
                "ms, 结果=" + counter[0]);
    }
}
```

### 练习 3：ABA 问题模拟（可选，15 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 练习 3：模拟 ABA 问题 + AtomicStampedReference 修复
 *
 * 场景简化版：
 * 1. 线程 1 读到一个值 A，准备 CAS 成 C，但被暂停
 * 2. 线程 2 把 A 改成 B，又改回 A
 * 3. 线程 1 恢复，CAS(A→C) 成功 —— 但中间已经发生过 A→B→A 的变化！
 *
 * AtomicStampedReference 用版本号避免了这个问题
 */
public class AbaProblemTest {

    @Test
    public void testAbaProblem() throws InterruptedException {
        // 使用 AtomicStampedReference，初始值 "A"，版本号 0
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 0);

        System.out.println("初始值: " + ref.getReference() + ", 版本: " + ref.getStamp());

        // 模拟线程 2：A → B → A（版本号从 0 → 1 → 2）
        int[] stampHolder = new int[1];
        String current = ref.get(stampHolder);
        int stamp = stampHolder[0];

        boolean ok1 = ref.compareAndSet(current, "B", stamp, stamp + 1);
        System.out.println("A→B: " + (ok1 ? "成功" : "失败") + ", 当前值: " +
                ref.getReference() + ", 版本: " + ref.getStamp());

        current = ref.get(stampHolder);
        stamp = stampHolder[0];
        boolean ok2 = ref.compareAndSet(current, "A", stamp, stamp + 1);
        System.out.println("B→A: " + (ok2 ? "成功" : "失败") + ", 当前值: " +
                ref.getReference() + ", 版本: " + ref.getStamp());

        // 模拟线程 1 用旧版本号 0 尝试 CAS → 失败！
        boolean ok3 = ref.compareAndSet("A", "C", 0, 1);
        System.out.println("用旧版本号 CAS A→C: " + (ok3 ? "成功" : "失败（ABA 被阻止！）"));
        System.out.println("最终值: " + ref.getReference() + ", 版本: " + ref.getStamp());

        // 正确做法：用当前版本号
        current = ref.get(stampHolder);
        stamp = stampHolder[0];
        boolean ok4 = ref.compareAndSet(current, "C", stamp, stamp + 1);
        System.out.println("用当前版本号 CAS A→C: " + (ok4 ? "成功" : "失败") +
                ", 最终值: " + ref.getReference() + ", 版本: " + ref.getStamp());
    }
}
```

---

## 11. 自测题

1. **CAS 的三个参数分别代表什么？CAS 的底层对应 x86 的什么指令？**
   <details><summary>答案</summary>

   三个参数：内存地址 V（对象 + 字段偏移量）、期望值 A（旧值）、新值 B。
   x86 对应 `lock cmpxchg` 指令，`lock` 前缀保证多核原子性。
   </details>

2. **`AtomicInteger.incrementAndGet()` 为什么用 CAS 自旋而不用 synchronized？**
   <details><summary>答案</summary>

   自增操作临界区极短（只有一次读和一次加），CAS 失败重试的开销远小于线程挂起/唤醒的开销。这就是"轻量级操作用无锁，重量级操作用加锁"的语义。
   </details>

3. **ABA 问题在计数场景下（AtomicInteger）有危害吗？在链表场景下呢？**
   <details><summary>答案</summary>

   - 计数场景无危害：从 5→6→5，CAS 之后变成 6，最终计数正确
   - 链表场景有危害：pop A → pop B → push A，栈结构被破坏，B 节点丢失
   - 区别：计数值的"含义"不依赖历史状态，链表的指针依赖
   </details>

4. **为什么普通应用代码不能直接调用 `Unsafe.getUnsafe()`？**
   <details><summary>答案</summary>

   `Unsafe.getUnsafe()` 内部有安全检查：`if (caller.getClassLoader() != null) throw SecurityException` — 只有 BootstrapClassLoader 加载的类（`ClassLoader == null`）才能调用。这是 JDK 故意设置的安全限制，防止开发者滥用 Unsafe。

   Java 9+ 推荐使用 `VarHandle` 替代 Unsafe 的 CAS 操作。
   </details>

5. **CAS + volatile 为什么是无锁并发的黄金组合？各自负责什么？**
   <details><summary>答案</summary>

   - CAS：负责"比较并交换"的**原子性**——保证没有线程能在比较和交换之间插入
   - volatile：负责**可见性**——保证 CAS 每次读到的 current 值都是最新的
   - 两者缺一不可：没有 volatile，CAS 基于过期数据；没有 CAS，volatile 不保证复合操作
   </details>

---

> 📬 **阶段一三篇全部完成！回顾一下你学到的：JMM 模型 → happens-before → volatile → CAS → 它们如何组成无锁并发的基石。建议花 30 分钟回顾三篇文档和自测题，确认都通了再进入阶段二。**
>
> 下一篇：[02-01-Synchronized使用与字节码](../02-01-Synchronized使用与字节码.md)（待发布）

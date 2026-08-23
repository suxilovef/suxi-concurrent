# 01-03 CAS 原理与 Unsafe 入门

> **阶段一·第 3 篇** | 前置：[01-02-volatile关键字深度解析](./01-02-volatile关键字深度解析.md) | 后续：[02-01-Synchronized使用与字节码](../02-01-Synchronized使用与字节码.md)（待发布）  
> **建议时长**：3~4 小时（原理 1h + 源码阅读 1h + 动手练习 1.5h）  
> **源码口径**：JDK 17
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
synchronized：保证原子性，但竞争激烈时涉及线程阻塞（重量级）

问题：有没有一种方式，既保证原子性、又不阻塞线程？

答案：CAS → 无锁并发（Lock-Free）的基石
```

**但先把这个"免费答案"拆穿——CAS 不是白赚，是两个维度的代价迁移：**

| 维度 | synchronized | CAS | CAS 付出的代价 |
|---|---|---|---|
| 原子性范围 | 任意代码区间（临界区） | **单变量的读-比较-写** | 范围收窄：只解决"一次读-改-写" |
| 等待方式 | 挂起线程、让出 CPU（贵在切换） | **自旋重试**（贵在 CPU 空转） | 阻塞换成忙等：竞争激烈时可能更糟 |

所以 CAS 的完整表述是：

> **把原子性从"临界区互斥"降级为"单变量原子更新"、把等待从"线程切换"换成"自旋重试"的有条件赢家。**

适用条件藏在代价里：临界区极短（重试成本 < 切换成本）+ 竞争不激烈（自旋不会空转太久）。条件不满足时（长临界区、高竞争），锁反而更优（见 6.2）。

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

> 📌 **与 JDK 真实实现的关系**：JDK 8+ 的 `incrementAndGet()` 是一行 `U.getAndAddInt(this, VALUE, 1) + 1`，自旋在 `getAndAddInt` 内部（模式见 4.1）。本处拆成 current/next 三步是为了展示"读-算-写"语义——两种写法等价，真实实现是 4.1 的单变量模式。

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

### 2.3 CAS 与内存屏障：为什么 value 必须是 volatile

承接 01-02 的屏障知识，把"黄金组合"的最后一环补上。

**x86 上 `lock cmpxchg` = 原子操作 + 全屏障（full fence）**：

```
lock 前缀在 CAS 上做了三件事：
  ① 原子：比较-交换一气呵成（缓存行锁，防并发插入）
  ② 顺序：禁止 Store-Load 重排（01-02 讲过的 TSO 唯一漏洞）
  ③ 传播：冲刷 store buffer，写回立刻对其他核可见
```

所以 **CAS 成功的写回自带 volatile 写的传播效果**——那 `value` 为什么还必须 volatile？两层原因：

1. **自旋的读需要 acquire**：`current = value` 每次循环都要看到最新值，volatile 读保证"读之后的操作不越过读"。但注意：**去掉 volatile 不会让这个循环死循环**——CAS 自旋与 01-02 场景 1（状态标志位）是两种循环形状，结论不可互套：
   - **CAS 自旋循环（本场景）**：循环体内每轮迭代都对 `value` 做条件写（`lock cmpxchg`）——读同一地址的 load 被循环体内的写 kill 掉，JIT 不可能把它提升出循环（实证：练习一去掉 volatile，结果仍为 100000）
   - **纯读循环（01-02 场景 1）**：体内无任何写，load 是循环不变量，C2 的 LICM 会把它提升出循环 → 死循环（实证：`VolatilePracticeTest#testNoVolatileInfiniteLoopRepro`，复现"running=false 后线程仍不退"）
   - 那 volatile 对自旋读到底保什么：stale 读在 CAS 自旋里只多付重试次数（正确性由 CAS 的原子比较兜底），但"读到最新值"是 JMM 对 volatile 读的承诺；去掉后行为不再被语言保证，循环形状稍变（如消费者线程纯读轮询 `while (counter.get() < N);`）立即显形
   > 🔍 实证细节：循环体内若出现 volatile 读（曾用 `AtomicBoolean` 做"逃生门"），其 acquire 屏障同样抑制提升，复现测试立刻失效——"循环形状决定行为"再次应验

2. **其他线程不经 CAS 的普通读需要可见性**：`get()` 就是普通读——CAS 的屏障只约束执行 CAS 的线程，管不了别的线程读 `value`。字段不是 volatile，`get()` 在轮询循环里可能被 JIT 缓存成永远读到旧值。练习一主线程的最终断言另由 `join()` 的 happens-before 兜底（JLS 17.4.5：线程的所有动作先于 join 返回），所以去掉 volatile 断言仍过——那是 join 的功劳，不是 `value` 不需要 volatile

> 💡 成本结构（复用 01-02 的结论）：x86 上 volatile 读免费、写要 `lock`。所以 CAS 自旋每次迭代 = 免费读 + 贵的 `lock cmpxchg`——开销大头在 CAS 指令本身，竞争越激烈重试越多、越贵（见 6.1）。

### 2.4 为什么 CAS 不需要加锁

```
传统锁（synchronized / ReentrantLock）：
  锁膨胀后（真竞争）→ 线程获取不到锁 → 操作系统将其挂起 → 上下文切换 → 等待唤醒
  → 每次挂起/唤醒涉及用户态↔内核态切换，开销大
  （注：未膨胀时 synchronized 有偏向锁/轻量级锁，先在用户态自旋，见 6.2）

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

> 📌 JDK 9+ 新增 `weakCompareAndSet{Int/Long/Object}` 弱化版（允许意外失败、不保证顺序语义），JUC 内部的自旋用的是弱化版；`compareAndSwap*` 是经典 API，语义最强。见 4.1。

**参数中的 `offset` 是什么**：

```
一个 Java 对象在内存中是这样布局的：

┌──────────────┬──────────────┬──────────────────┐
│  Mark Word   │  Klass Pointer│  实例数据（字段）  │
│  (8 bytes)   │  (4/8 bytes)  │  offset 偏移量   │
└──────────────┴──────────────┴──────────────────┘
                                     ↑
                              offset 就是字段相对于对象起始位置的偏移量
                              通过 Unsafe.objectFieldOffset() 获取
                              （64 位 JVM 对象头共 12~16 字节；32 位 JVM 是 4+4=8 字节）
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
// JDK 8+ 的真实自旋模式（getAndAddInt 的语义）
// JDK 9+ 内部实际用 getIntVolatile + weakCompareAndSetInt（弱化版，见 3.2 说明）
public final int getAndAddInt(Object o, long offset, int delta) {
    int v;
    do {
        v = getIntVolatile(o, offset);  // ① volatile 读，取当前值
    } while (!weakCompareAndSetInt(o, offset, v, v + delta));  // ② CAS，失败则重试
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

**这里的 CAS 具体指什么？**

上面 `CAS(栈顶, A, B)` 操作的对象是**栈顶指针 `top`**（一个 `AtomicReference<Node>`），不是某个节点的值。对应到无锁栈的 pop 实现：

```java
public T pop() {
    Node oldTop, newTop;
    do {
        oldTop = top.get();              // ① 读当前栈顶 = A
        if (oldTop == null) return null;
        newTop = oldTop.next;            // ② 记下 A 的下一个节点 = B
    } while (!top.compareAndSet(oldTop, newTop));  // ③ CAS(栈顶, A, B)
    return oldTop.value;
}
```

三个操作数对应关系：

```
V（内存地址） = 栈顶字段 top（栈对象里的 AtomicReference<Node>）
A（期望值）   = 线程 1 读到的栈顶节点 A
B（新值）     = A.next，即节点 B
语义：如果栈顶还是 A 这个对象，就把栈顶原子地换成 A.next（B）
     —— 相当于把 A 从栈里摘下来
```

**为什么正常情况下 CAS 是安全的**：CAS 比较的是**对象引用**（内部就是 `oldTop == top`）。线程 1 暂停期间如果有人动过栈顶，恢复后 `top` 不再是 A，CAS 失败，回到 ① 重新读取栈顶再试——这就是无锁栈靠 CAS 自旋保证正确性的机制。

**ABA 是怎么坑到它的**：线程 2 的 `push A` 把**同一个 A 对象**放回栈顶（`top` 又指向 A），线程 1 恢复后引用比较仍然相等，以为没人动过，于是 CAS 成功把栈顶换成了 B。但：

1. 线程 2 push 时执行了 `A.next = 当前栈顶(C)`，**A.next 已被改成 C，不再是 B**
2. 线程 1 用的是自己之前读到的旧 `newTop = B` 去替换
3. 结果栈顶变成 **B**——而 B 早就被线程 2 pop 走了，是已经「出栈交付给调用方」的节点

> 所以正确的栈应该是 `A → C`（pop 后栈顶是 C），实际却变成了 `B → C`。上面图示里说「链向了野指针」是 C/C++ 指针的类比——在 Java 里更准确的说法是：**栈顶指向了一个已经不在栈里、内容可能已被改写的节点**。

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

> 🎯 **引用 CAS 的边界：比较的是引用，不是内容**（和 01-02 场景 4 的"统一原则"是同一件事）
>
> `AtomicReference` 的 CAS 比较的是引用那 8 个字节的位模式——它回答的是**"引用没被换过"**，不是**"对象状态没变过"**。对象内部字段被就地修改，引用不变，CAS 照样成功：
>
> ```java
> box.setValue(42);                 // 改内容，没换引用
> ref.compareAndSet(box, newBox);   // ✅ 成功——CAS 对内容变化无感
> ```
>
> 想保护内容，要么**不可变对象 + 整体换引用**（内容变 → 引用必变 → CAS 能识别），要么内容字段用原子类/锁。5.2 栈例子里线程 2 改 `A.next` 就是这个边界踩坑的实际案例——"引用没换、内容已变"，CAS 认不出来。这也和 ABA 同源：CAS 的"相等"是引用位模式相等，想让它识别什么变化，就得把变化编码进引用或版本号里。

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

> 🔍 **失败的 CAS 也付全价：失效是"锁"的代价，不是"写"的代价**
>
> 你可能以为"比较不等就不写，那失败应该很便宜？"——不是。x86 的 `lock cmpxchg` 执行顺序是**先独占、再读、再比较、再条件写**：
>
> ```
> lock cmpxchg [行], B：
>   ① 获取该缓存行的独占权    ← 向其他核广播 Invalidate，等 ACK（失效在此刻发生）
>   ② 读行里的当前值，与 A 比较
>   ③ 相等 → 写入 B；不等 → 不写（但 ① 的失效不会撤销）
> ```
>
> CPU 在指令开头**无法预知比较结果**，原子性要求"读-比较-条件写"全程独占——所以**无论成败，锁定、失效广播、等 ACK 都已发生**。
> 高竞争下 CAS 反复失败 = **白锁 + 白算**双重代价：每转一圈都触发一轮跨核失效（伪共享场景下邻居变量照旧连坐），不只是"空转"而已。
> 对比：普通读（volatile 读）是 Shared 友好操作，从不失效别人的行——这就是 2.3 说的"volatile 读免费、`lock cmpxchg` 全价"的硬件原因。

### 6.2 CAS vs 锁的选型决策

```
CAS 适合：竞争不激烈、临界区极短（几条指令）
  → AtomicInteger 自增、LongAdder 计数
  → 失败就重试，代价比线程切换小

锁适合：竞争激烈、临界区较长（几十条指令以上）
  → 竞争激烈时 CAS 反复失败 → CPU 空转 → 不如阻塞等待
  → synchronized 有锁升级优化（偏向锁/轻量级锁，JDK 6+ 起），短临界区性能接近 CAS
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

// ✅ 可以考虑加自旋次数监控（仅用于诊断）
// 注意：AtomicInteger 的生产实现并不这么做；告警阈值按压测定，别拍脑袋
int retries = 0;
while (!U.compareAndSwapInt(this, OFFSET, current, next)) {
    current = get();
    next = current + 1;
    if (++retries > 1000) {
        // 告警：竞争太激烈，考虑换方案（LongAdder / 锁）
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

### 练习 2：对比 CAS vs synchronized 在不同竞争强度下的表现（30 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：对比 CAS 自旋 vs synchronized 在不同竞争强度下的表现
 *
 * 竞争强度 = 并发线程数（1 个线程 ≈ 无竞争，16 个线程 ≈ 高竞争）
 * 结论预览（你需要实际运行验证，单次噪声大，多跑几次看趋势）：
 *  - 低竞争（1~2 线程）：CAS 略快
 *  - 高竞争（8+ 线程）：synchronized 更稳定（CAS 自旋大量失败重试）
 *  - 更精确的对比需要用 JMH（阶段五会学）
 */
public class CasVsSyncTest {

    private static final int ITERATIONS = 100_000;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16};

    @Test
    public void testCasVsSync() throws InterruptedException {
        for (int threads : THREAD_COUNTS) {
            long casMs = runCas(threads);
            long syncMs = runSync(threads);
            System.out.println("线程数=" + threads
                    + "  CAS: " + casMs + "ms  synchronized: " + syncMs + "ms"
                    + (casMs < syncMs ? "  → CAS 快" : "  → synchronized 快"));
        }
    }

    private long runCas(int threadCount) throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        runAll(threadCount, () -> counter.incrementAndGet());
        long cost = System.currentTimeMillis() - start;
        check(counter.get(), threadCount);
        return cost;
    }

    private long runSync(int threadCount) throws InterruptedException {
        Object lock = new Object();
        int[] counter = {0};
        long start = System.currentTimeMillis();
        runAll(threadCount, () -> {
            synchronized (lock) {
                counter[0]++;
            }
        });
        long cost = System.currentTimeMillis() - start;
        check(counter[0], threadCount);
        return cost;
    }

    private void runAll(int threadCount, Runnable task) throws InterruptedException {
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    task.run();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
    }

    private void check(int actual, int threadCount) {
        int expected = threadCount * ITERATIONS;
        if (actual != expected) {
            System.out.println("❌ 结果错误: 实际=" + actual + ", 预期=" + expected);
        }
    }
}
```

### 练习 3：AtomicStampedReference 机制演示（可选，15 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 练习 3：演示 AtomicStampedReference 的版本号机制
 *
 * ⚠️ 注意：本练习是"机制演示"（单线程顺序调用），不是 ABA 危害的模拟——
 *    ABA 的危害需要并发 + 引用复用（无锁栈被破坏，见 5.2），单线程模拟不出来
 *
 * 演示内容：
 * 1. 每次 CAS 都带版本号，值每变一次版本号 +1
 * 2. 用"旧版本号"做 CAS → 失败（ABA 被阻止）
 * 3. 用"当前版本号"做 CAS → 成功
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

# 03-06 原子类与 LongAdder

> **阶段三·第 6 篇** | 前置：[03-05-并发容器之其他容器](./03-05-并发容器之其他容器.md) | 后续：[03-07-同步工具类](./03-07-同步工具类.md)（待发布）  
> **建议时长**：5~6 小时（Atomic 系列 2h + 伪共享 1.5h + LongAdder 2h + 练习 1.5h）  
> 🛠️ **日常高频**：计数器、统计、累加是日常开发最常用的并发操作

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | AtomicInteger 核心方法、CAS 自旋源码、LongAdder 分段累加、AtomicLong vs LongAdder 选型 | **理解原理 + 能选型** |
| ◈◈ | 伪共享（False Sharing）+ @Contended、AtomicReference、AtomicStampedReference | **知道原理 + 能解决** |
| ○ | AtomicIntegerArray/FieldUpdater/LongAccumulator | **知道有** |

---

## 1. Atomic 系列总览

```
java.util.concurrent.atomic 包（JDK 8 共 16 个类）：

基础类型：
  AtomicBoolean / AtomicInteger / AtomicLong

数组类型：
  AtomicIntegerArray / AtomicLongArray / AtomicReferenceArray

引用类型：
  AtomicReference / AtomicStampedReference / AtomicMarkableReference

字段更新器：
  AtomicIntegerFieldUpdater / AtomicLongFieldUpdater / AtomicReferenceFieldUpdater

累加器（JDK 8 新增）：
  LongAdder / LongAccumulator / DoubleAdder / DoubleAccumulator
```

### 1.1 核心机制回顾

```
所有原子类 = 同一个套路：

private volatile int value;                    ← volatile 保证可见性
private static final Unsafe U;                 ← CAS 工具
private static final long VALUE_OFFSET;        ← 字段偏移量

public final int incrementAndGet() {
    for (;;) {
        int current = value;                   ← 读
        int next = current + 1;                ← 算
        if (U.compareAndSwapInt(this, VALUE_OFFSET, current, next))  ← CAS
            return next;
    }                                          ← 失败自旋重试
}
```

> ⚠️ 以上是 **JDK 8 经典版源码**。JDK 9 起（JEP 193）AtomicInteger 等改用 **VarHandle** 实现（语义相同，仍是 volatile + CAS 自旋，只是没有 Unsafe 字段）；有意思的是 LongAdder 的父类 **Striped64 在 JDK 17 仍是 Unsafe 实现**——两代实现并存。主工程跑 JDK 17（重构版），jdk8-lab 跑经典版，与 03-01/03-02 的双轨惯例一致。

---

## 2. AtomicInteger 核心方法（🛠️ 必会）

### 2.1 方法全家桶

| 方法 | 语义 | 等价操作 |
|---|---|---|
| `get()` | 读取 | volatile 读 |
| `set()` | 写入 | volatile 写 |
| `getAndSet(v)` | 读取并设置 | `old = x; x = v; return old` |
| `getAndIncrement()` | 自增并返回旧值 | `return x++` |
| `incrementAndGet()` | 自增并返回新值 | `return ++x` |
| `getAndAdd(delta)` | 加并返回旧值 | `old = x; x += delta; return old` |
| `addAndGet(delta)` | 加并返回新值 | `return x += delta` |
| `compareAndSet(exp, upd)` | CAS | 成功 true 失败 false |
| `updateAndGet(fn)` | 函数式更新 | `x = fn(x)` |
| `accumulateAndGet(x, fn)` | 函数式累积 | `x = fn(x, param)` |

### 2.2 方法语义图

```
AtomicInteger a = new AtomicInteger(10);

a.get()                → 10
a.getAndIncrement()    → 返回 10，a 变成 11
a.incrementAndGet()    → a 变成 12，返回 12
a.getAndAdd(5)         → 返回 12，a 变成 17
a.addAndGet(5)         → a 变成 22，返回 22
a.compareAndSet(22, 30) → true（当前是 22）
a.compareAndSet(22, 99) → false（当前已是 30）
```

### 2.3 常用场景

```java
// 场景 1：计数器
AtomicInteger count = new AtomicInteger(0);
count.incrementAndGet();   // 并发安全自增

// 场景 2：序列号生成
AtomicInteger seq = new AtomicInteger(0);
int id = seq.incrementAndGet();  // 1, 2, 3...

// 场景 3：乐观 CAS 更新（不满足条件不更新）
AtomicInteger state = new AtomicInteger(0);
// 只有 state 是 0 时才更新为 1（防止重复初始化）
if (state.compareAndSet(0, 1)) {
    init();
}
```

---

## 3. AtomicReference（◈◈）

### 3.1 基本使用

```java
// 原子引用：整个对象引用可以原子替换
AtomicReference<String> ref = new AtomicReference<>("hello");

// 乐观更新：仅当当前值是我预期的才替换
String expected = ref.get();
boolean ok = ref.compareAndSet(expected, "world");
```

> ⚠️ **CAS 比较的是引用，不是 equals()**：`compareAndSet(expected, new)` 用 `==` 比较引用同一性。因果：CAS 最终是硬件指令（`CMPXCHG`），它只能比较内存位置上存的 64 位值（即指针本身），没有能力执行 Java 的 `equals()` → 两个内容相同的对象是不同引用 → CAS 失败。这是**保守的正确**：宁可失败重试，也不基于错误前提写入。
>
> 推论（第一性原理）：指针就是 CAS 眼中的全部状态 → 使用纪律是**“换而不改”**——指向的对象要么不可变，要么每次更新都构造新对象再替换指针。CAS 只保证“换指针”这个动作原子，不保护对象内部字段（`get()` 拿到引用后直接改内部 → 并发不安全）。
> 引用比较的两个固有弱点见下：ABA 见 §3.2，equals 语义见上方说明。

### 3.2 经典应用：无锁栈（ABA 场景）

```java
// 用 AtomicReference 实现无锁栈
public class LockFreeStack<T> {
    private static class Node<T> {
        final T value;
        volatile Node<T> next;
        Node(T value) { this.value = value; }
    }

    private final AtomicReference<Node<T>> head = new AtomicReference<>();

    public void push(T value) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> oldHead = head.get();
            newNode.next = oldHead;
            if (head.compareAndSet(oldHead, newNode)) {
                return;   // CAS 成功
            }
            // CAS 失败 → 有人抢先 push 了 → 重试
        }
    }

    public T pop() {
        while (true) {
            Node<T> oldHead = head.get();
            if (oldHead == null) return null;
            if (head.compareAndSet(oldHead, oldHead.next)) {
                return oldHead.value;
            }
        }
    }
}
```

> ⚠️ 上面的栈有 **ABA 问题**（见 01-03）。一条具体的破坏时间线：
>
> ```
> T1 读 head = A，随后被调度走
> T2 完成：pop A → pop B → push 新 A'（栈顶又是 A'，但 B 已被弹出）
> T1 醒来 CAS(head, A → B)：值匹配 → 成功！但 B 早被 T2 弹出了，栈结构已坏
> ```
>
> 生产用 `AtomicStampedReference`（值 + 版本号一起 CAS）或 `ConcurrentLinkedQueue`。

### 3.3 正确用法：写时复制（Copy-on-Write）

```java
// 场景：AtomicReference<Map> 做并发缓存
// ❌ 错误：cache.get().put(...) —— 绕过 CAS 直接改对象内部，并发不安全
// ✅ 正确：每次更新构造新快照，CAS 原子换指针

AtomicReference<Map<String, Integer>> cache = new AtomicReference<>(new HashMap<>());

void update(String key, int val) {
    Map<String, Integer> snapshot;
    do {
        snapshot = cache.get();                              // 读到当前版本
        Map<String, Integer> copy = new HashMap<>(snapshot); // 线程私有副本
        copy.put(key, val);                                  // 只在副本上改
    } while (!cache.compareAndSet(snapshot, copy));          // 原子换指针
}

// 读线程：get() 永远拿到一个完整快照，绝不看到“改了一半”的状态
```

> 因果链：所有修改先落在线程私有副本（没有并发写共享数据）→ 唯一被并发修改的点收敛到指针这一个字段 → CAS 原子换指针 → 读线程要么旧快照、要么新快照，永远一致完整。旧快照不再被修改（视为不可变），也就没有“改到已废弃对象”的问题。

---

## 4. 伪共享（False Sharing）⭐

### 4.1 什么是缓存行

```
CPU 读内存的最小单位不是 1 字节，而是一个缓存行（Cache Line）：

缓存行 = 64 字节（现代 x86）

┌────────────────────────── 64 字节 ──────────────────────────┐
│  data1 (8B) │ data2 (8B) │ data3 (8B) │ ... │ data8 (8B)   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 什么是伪共享

```
两个线程修改"不同的变量"，但这两个变量在同一个缓存行里：

线程 1 修改变量 A ──→ 整个缓存行失效（包括 B！）
线程 2 修改变量 B ──→ 整个缓存行失效（包括 A！）

→ 两个线程互相"踢走"对方的缓存行 → 反复从内存加载
→ 性能断崖式下降！

名字的由来："伪"共享 —— 明明没有共享变量，却被缓存行"强行共享"
```

```
┌────────────────────────── 64 字节缓存行 ──────────────────────────┐
│  thread1 的 count（8B）│ thread2 的 count（8B）│ 其他（48B）      │
└──────────────────────────────────────────────────────────────────┘

线程 1: count++ → 修改自己那份 → 但整个缓存行被标记为"脏"
线程 2: count++ → 也要加载这个缓存行 → 重新从内存读 → 性能暴跌
```

### 4.3 伪共享的典型场景

```java
// 经典伪共享例子：两个线程各自更新的计数器放在一起
public class FalseSharingDemo {
    // ❌ 两个 count 在同一缓存行 → 互相踢
    long count1;
    long count2;   // 与 count1 相邻（< 64 字节）→ 伪共享！

    // ✅ 填充 7 个 long（7 × 8 = 56 字节），保证两个 count 不在同一缓存行
    long p1, p2, p3, p4, p5, p6, p7;  // 填充（padding）
    long countA;
    long q1, q2, q3, q4, q5, q6, q7;
    long countB;
}
```

### 4.4 解决伪共享：@Contended 注解

```java
// JDK 8 提供 @Contended 注解（需要 JVM 参数开启 -XX:-RestrictContended）
// ⚠️ 版本差异：sun.misc.Contended 在 JDK 9 已被移除，
//    JDK 9+ 换成 jdk.internal.vm.annotation.Contended（需 --add-exports
//    java.base/jdk.internal.vm.annotation=ALL-UNNAMED 才能编译）
//    主工程（JDK 17）下本示例编译不过 → 请在 jdk8-lab 子工程验证

import sun.misc.Contended;

public class Counter {
    @Contended          // 告诉 JVM：这个字段单独占一个缓存行
    volatile long count;
}
```

```java
// LongAdder 的 Cell 就用了 @Contended！
@sun.misc.Contended
static final class Cell {
    volatile long value;
    Cell(long x) { value = x; }
}

// 每个 Cell 独立缓存行 → 多个线程累加到不同 Cell 时不互相踢
```

### 4.5 伪共享的验证实验

```
实验思路：
  两个线程各自对独立的 long 自增 1 亿次
  对比：相邻声明（伪共享） vs 填充隔离（无伪共享）
  结果：伪共享版本慢 2~10 倍
```

### 4.6 两个变量什么时候会进同一个缓存行

字段地址 = 对象基地址 + 字段偏移，两截的决定者完全不同：

```
字段偏移：JVM 对象布局算法决定（对象头 12B + 按类型大小排序）→ 编译后固定不变
对象基地址：运行时由 GC/分配器决定（TLAB bump-pointer 分配，同一线程连续分配的
  对象紧挨着；GC 移动后还会变）→ 每次运行都不同
```

所以“两个变量是否同缓存行”分两种情况：

```
同一对象的两个字段：接近 100% 必在同一行（确定性）——小对象总大小 < 64 字节，
  整个对象就装在一个缓存行里；唯一例外是对象恰好横跨缓存行边界
  （起始地址 mod 64 把字段劈开），即 §4.5 实验注释里的“对齐运气”。
不同对象的两个字段：看分配运气——同一线程连续分配的小对象容易相邻；
  不同线程的对象通常在各自 TLAB，但 GC 复制后可能被压到一起。
  → 框架层防不胜防，只能主动填充隔离。
```

> ⚠️ 澄清一个常见误解：**伪共享与值无关**。机制是“写操作”让缓存行在别的核失效——只要两个变量同缓存行、且不同核高频写，**不管写什么值、值变不变**，都会互相踢。不需要“变量地址固定”之类的特殊前提。

### 4.7 主战场在基础设施层，不在业务层

伪共享的伤害 = 写失效次数 × 单次代价，要变得可见必须写频率够高。按“被谁使用”分层：

```
业务层：订单 / 用户 / 库存等具体功能
  → 计数器只有本功能的流量在写 → 频率低，分摊进毫秒级请求里无感
基础设施层：日志、指标、连接池、线程池、队列、JDK 内部（LongAdder / CHM 等）
  → 所有业务、所有请求的总流量都在写 → 频率高几个数量级
```

因果链：**业务层写频率是“局部流量”，基础设施层写频率是“全系统流量”** → 后者每秒百万~千万次写 → 缓存行乒乓挤满一致性总线 → 性能掉 2~10 倍。

| 组件 | 为什么写频率极高 |
|---|---|
| LongAdder 的 Cell | 每个请求自增一次 → 全系统请求数 |
| ConcurrentHashMap 的 CounterCell | 每次 put/remove 都要更新 size 计数 |
| Disruptor 序号 | 每个事件进出队列都写一次游标 |
| 日志 / 指标管道 | 每个请求采样多个指标 |

> 结论：伪共享不是业务开发的日常敌人，而是写框架/中间件的人必须背的债。业务层使用者只需做到两件事：知道它存在（本节）+ 遇到诡异性能问题时能想到排查（§7 坑 4）。

---

## 5. LongAdder（JDK 8 新增，🛠️ 高频）

### 5.1 为什么需要 LongAdder

```
AtomicLong 的问题（高并发下）——两重根因：
  ① CAS 自旋空转：所有线程竞争同一个 value → CAS 频繁失败 → 自旋重试 → CPU 空转
     10 个线程竞争 1 个变量 → 9 个在自旋
  ② 缓存行乒乓：每次 CAS 都是一次写操作 → 需要 RFO（read-for-ownership）→
     其他核持有的缓存行全部失效 → 核间一致性流量占满内存总线
     → 第 ② 重不止浪费自旋线程的 CPU，还会拖慢真正持锁的线程
     （第一性原理：AtomicLong 把正确性代价压在了缓存一致性总线上）

LongAdder 的思路（分治）：
  把 1 个变量拆成 N 个 Cell
  每个线程按自身 probe 哈希"确定性"定位一个 Cell —— 不是随机抢！
    （同一线程反复命中同一 Cell → 缓存友好；冲突时才换哈希重试）
  需要总数时，把 baseCount + 所有 Cell 相加

  类似"分段计数"（ConcurrentHashMap 的 CounterCell 就是抄它）

为什么用 Cell 数组，而不是 ThreadLocal 每线程一个计数器？
  ThreadLocal 无法做全局 sum：JVM 不维护可遍历的"活线程注册表"
  （ThreadLocalMap 是弱引用挂在 Thread 上的）→ 没法优雅地遍历所有线程求和
  Cell 数组 = 固定数组 + probe 定位 → 天然可遍历、可 O(NCPU) 求和
```

### 5.2 结构

```java
// 注：base/cells/cellsBusy 三个字段实际定义在父类 Striped64 中，
//     LongAdder 继承使用（这里合并展示）
public class LongAdder extends Striped64 {
    // base：无竞争时的基础计数
    transient volatile long base;

    // Cell[]：竞争时的分段计数（每个 Cell 是 @Contended 的）
    transient volatile Cell[] cells;

    // cellsBusy：扩容/初始化 Cell[] 的锁标记（CAS 0→1）
    transient volatile int cellsBusy;
}
```

### 5.3 add() 核心流程

```java
public void add(long x) {
    Cell[] as;
    long b, v;
    int m;
    Cell a;

    // ① 快速路径：直接 CAS base
    if ((as = cells) != null ||
        !casBase(b = base, b + x)) {
        // ② CAS base 失败 → 走 Cell 路径
        boolean uncontended = true;
        if (as == null || (m = as.length - 1) < 0 ||
            (a = as[getProbe() & m]) == null ||   // ③ 按线程 hash 定位 Cell
            !(uncontended = a.cas(v = a.value, v + x))) {
            longAccumulate(x, null, uncontended);  // ④ 初始化/扩容/重试
        }
    }
}
```

```
add 的四个层次（逐级降级）：
  ① cells == null 且无竞争 → CAS base（一个变量就够）
     ⚠️ 边界：只有 cells 未初始化才尝试 base；一旦有过竞争、cells 已建，
     之后即使无竞争也直接走 Cell 路径，不会回落
  ② 有竞争 → 用线程 hash 定位自己的 Cell → CAS 自己的 Cell
  ③ 自己的 Cell 也冲突 → longAccumulate：扩容 Cell[] / 换 Cell / 重试
  ④ 极端情况 → 全忙 → 无限重试（性能瓶颈是理论上的）

为什么扩容上限是 NCPU（不无限扩）？
  同一时刻活跃写者 ≤ CPU 核数 → Cell 数超过 NCPU 后必有线程共用 Cell
  → 摊薄无收益，只浪费内存（每个 Cell 是 @Contended 类，至少占一个缓存行）
  → Striped64 源码里正是 n >= NCPU 后停止扩容，改为换哈希重试
```

### 5.4 sum() —— 合并所有 Cell

```java
public long sum() {
    Cell[] as = cells;
    long sum = base;
    if (as != null) {
        for (Cell a : as) {
            if (a != null)
                sum += a.value;   // 遍历所有 Cell 求和
        }
    }
    return sum;
}
```

```
⚠️ sum() 不是原子操作！
  求和过程中可能有线程正在 add → 结果可能略小于实际值
  方向性：只会偏小、不会偏大 —— sum 逐 cell 做 volatile 读，
    若某线程的 add 发生在"该 cell 被读过之后"，这次累加就不会被计入；
    而所有读到的都是已提交的值，不可能多算
  另一个误差来源：sum 持有的 cells 引用是快照，扩容期间可能读到旧数组
  → LongAdder 适合"最终一致"的计数（如统计），不适合"必须精确"的计数
  → 需要精确时：同步外部手段或换 AtomicLong
```

### 5.5 AtomicLong vs LongAdder 选型（🛠️ 面试常考）

| 维度 | AtomicLong | LongAdder |
|---|---|---|
| 原理 | CAS 自旋（单变量） | 分段累加（多 Cell） |
| 低竞争 | ✅ 快 | 略慢（add 路径更长：probe 哈希 + cells 检查，与 sum 无关） |
| 高竞争 | ❌ 自旋浪费 CPU | ✅ 竞争摊薄 |
| sum() 一致性 | ✅ 强一致 | ❌ 弱一致（近似值） |
| 内存 | 8 字节 | N × 64 字节（Cell 带填充） |
| 适用 | 低竞争、需要精确值 | 高竞争、统计类 |

```
选型建议：
  计数器（需精确读取）→ AtomicLong
  统计类（QPS、请求数、流量，允许近似）→ LongAdder
  同一时间大量线程自增 → LongAdder
  sum 频率很低、add 频率极高 → LongAdder（典型统计场景）
```

---

## 6. LongAccumulator（○ 了解）

```java
// LongAdder 的通用化版本：支持自定义累加函数
LongAccumulator accumulator = new LongAccumulator(Long::max, Long.MIN_VALUE);
accumulator.accumulate(10);
accumulator.accumulate(50);
accumulator.accumulate(30);
System.out.println(accumulator.get());  // 50（最大值）

// LongAdder = LongAccumulator 的"加法特化版"
```

---

## 7. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：高并发计数用 AtomicLong 导致 CPU 飙高

```java
// ❌ 高并发（如每秒百万次）用 AtomicLong
// → CAS 大量失败 → 自旋 → CPU 空转 → 线上 CPU 飙高

// ✅ 高并发统计改用 LongAdder
LongAdder qps = new LongAdder();
qps.increment();
```

### 🕳️ 坑 2：LongAdder.sum() 的值"不太对"

```java
// ❌ 把 LongAdder 当精确计数器
long total = adder.sum();
if (total == expected) { ... }   // 可能不相等（并发累加中）

// ✅ 用于监控指标（允许近似）
// ✅ 需要精确 → AtomicLong 或外部同步
```

### 🕳️ 坑 3：AtomicReference 的 ABA（无锁栈场景）

```java
// ❌ 无锁栈直接用 AtomicReference → ABA 问题（见 01-03）
// ✅ 用 AtomicStampedReference（版本号）兜底
```

### 🕳️ 坑 4：伪共享导致的诡异性能问题

```java
// 现象：加了一个"无关"字段，性能暴跌 10 倍
// 原因：新字段和热字段在同一缓存行 → 伪共享
// 排查：JFR/Perf 观察缓存行 miss
// 解决：@Contended 或字段填充
```

### 🕳️ 坑 5：FieldUpdater 的字段必须是 volatile

```java
// ❌ 不是 volatile → 更新不生效/不一致
class Account {
    int balance;  // 忘记加 volatile！
}
AtomicIntegerFieldUpdater<Account> updater =
        AtomicIntegerFieldUpdater.newUpdater(Account.class, "balance");

// ✅ 必须：
class Account {
    volatile int balance;   // ← 必须 volatile！
}
```

---

## 8. 面试高频考点

1. **AtomicInteger 的 incrementAndGet 原理？**
   → volatile value + CAS 自旋（for(;;) + compareAndSwapInt），失败重试。

2. **什么是伪共享？怎么解决？**
   → 不同变量在同一缓存行，一个被修改导致整个缓存行失效，互相拖累。填充（padding）或 @Contended。

3. **LongAdder 为什么比 AtomicLong 快？**
   → 分段累加：竞争被摊薄到多个 Cell，减少 CAS 失败；代价是 sum() 弱一致。

4. **AtomicLong 和 LongAdder 怎么选？**
   → 低竞争/需要精确值 → AtomicLong；高竞争/统计类（允许近似）→ LongAdder。

5. **LongAdder 的 sum() 为什么不是原子操作？**
   → 求和遍历过程中可能有线程在 add，读到的是"中途"的值。

---

## 9. 实战练习

### 练习 1：AtomicLong vs LongAdder 压测对比（60 分钟）★必做

```java
package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 练习 1：对比 AtomicLong vs LongAdder 在不同竞争强度下的性能
 *
 * 预期结果：
 * - 线程少（竞争低）：AtomicLong 略快
 * - 线程多（竞争高）：LongAdder 明显更快
 */
public class AtomicVsAdderTest {

    private static final int ITERATIONS = 10_000_000;

    @Test
    public void testCompare() throws InterruptedException {
        warmup();   // JIT 预热：编译期间第一个被测方法会系统性偏慢，先热一轮
        testAtomicLong(2);
        testLongAdder(2);
        testAtomicLong(8);
        testLongAdder(8);
        testAtomicLong(32);
        testLongAdder(32);
    }

    /** 预热：触发 AtomicLong/LongAdder 相关方法的 JIT 编译，避免污染正式结果 */
    private void warmup() {
        AtomicLong c = new AtomicLong(0);
        LongAdder a = new LongAdder();
        for (int i = 0; i < 1_000_000; i++) {
            c.incrementAndGet();
            a.increment();
        }
    }

    private void testAtomicLong(int threads) throws InterruptedException {
        AtomicLong counter = new AtomicLong(0);
        long start = System.currentTimeMillis();

        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS / threads; j++) {
                    counter.incrementAndGet();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("AtomicLong " + threads + " 线程: " + elapsed + "ms, 结果=" + counter.get());
    }

    private void testLongAdder(int threads) throws InterruptedException {
        LongAdder counter = new LongAdder();
        long start = System.currentTimeMillis();

        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS / threads; j++) {
                    counter.increment();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("LongAdder " + threads + " 线程: " + elapsed + "ms, 结果=" + counter.sum());
    }
}
```

### 练习 2：伪共享实验（45 分钟）

```java
package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证伪共享对性能的影响
 *
 * 对比：两个线程各自累加相邻变量（伪共享）vs 填充隔离（无伪共享）
 */
public class FalseSharingTest {

    private static final int ITERATIONS = 100_000_000;

    // ❌ 伪共享：count1 和 count2 相邻（同一缓存行）
    // ⚠️ 必须加 volatile —— 否则 JIT 可能把字段提升到寄存器，
    //    两个线程各自用寄存器累加，反而"更快"，结论会被反转！
    // ⚠️ 是否真在同一缓存行取决于对象起始地址的对齐（12B 对象头 + 8B 对齐下
    //    通常成立；若实例恰好落在缓存行尾部，两字段跨行 → 实验显示 ~1x，
    //    结论被"对齐运气"反转）→ 建议多跑几次看稳定性。
    //    PaddedCounters 因两 count 间距恰 64B，恒在不同行，不受对齐影响。
    static class AdjacentCounters {
        volatile long count1;
        volatile long count2;
    }

    // ✅ 无伪共享：填充隔离（每个 count 独占缓存行）
    static class PaddedCounters {
        long p1, p2, p3, p4, p5, p6, p7;  // 填充 56 字节
        volatile long count1;
        long q1, q2, q3, q4, q5, q6, q7;  // 填充 56 字节
        volatile long count2;
    }

    @Test
    public void testFalseSharing() throws InterruptedException {
        AdjacentCounters adjacent = new AdjacentCounters();
        PaddedCounters padded = new PaddedCounters();

        // 伪共享版本
        long start = System.currentTimeMillis();
        Thread t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) adjacent.count1++; });
        Thread t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) adjacent.count2++; });
        t1.start(); t2.start();
        t1.join(); t2.join();
        long adjacentTime = System.currentTimeMillis() - start;

        // 无伪共享版本
        start = System.currentTimeMillis();
        Thread t3 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) padded.count1++; });
        Thread t4 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) padded.count2++; });
        t3.start(); t4.start();
        t3.join(); t4.join();
        long paddedTime = System.currentTimeMillis() - start;

        System.out.println("伪共享: " + adjacentTime + "ms");
        System.out.println("无伪共享: " + paddedTime + "ms");
        System.out.println("加速比: " + (double) adjacentTime / paddedTime + "x");
    }
}
```

### 练习 3：AtomicReference 无锁栈 + ABA 修复（45 分钟，选做）

```java
package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 练习 3：用 AtomicStampedReference 实现无 ABA 的无锁栈
 *
 * 对比 03-06 §3.2 的 AtomicReference 版本：
 * 每次 push/pop 都递增版本号 → CAS 时同时校验值 + 版本号
 */
public class LockFreeStackTest {

    static class Stack<T> {
        private static class Node<T> {
            final T value;
            Node<T> next;
            Node(T value) { this.value = value; }
        }

        // 值 + 版本号（stamp）一起 CAS
        private final AtomicStampedReference<Node<T>> head =
                new AtomicStampedReference<>(null, 0);

        public void push(T value) {
            Node<T> newNode = new Node<>(value);
            while (true) {
                Node<T> oldHead = head.getReference();
                int stamp = head.getStamp();
                newNode.next = oldHead;
                if (head.compareAndSet(oldHead, newNode, stamp, stamp + 1)) {
                    return;
                }
            }
        }

        public T pop() {
            while (true) {
                Node<T> oldHead = head.getReference();
                int stamp = head.getStamp();
                if (oldHead == null) return null;
                if (head.compareAndSet(oldHead, oldHead.next, stamp, stamp + 1)) {
                    return oldHead.value;
                }
            }
        }
    }

    @Test
    public void testStack() throws InterruptedException {
        Stack<Integer> stack = new Stack<>();

        // 两个生产者 + 两个消费者
        Thread[] producers = new Thread[2];
        Thread[] consumers = new Thread[2];

        for (int i = 0; i < 2; i++) {
            producers[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) stack.push(j);
            }, "producer-" + i);
            consumers[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) stack.pop();
            }, "consumer-" + i);
        }

        for (Thread t : producers) t.start();
        for (Thread t : consumers) t.start();
        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("✅ AtomicStampedReference 无锁栈运行完成（ABA 已防御）");
    }
}
```

---

## 10. 自测题

1. **AtomicInteger 的 CAS 自旋为什么会"空转"？**
   <details><summary>答案</summary>

   竞争激烈时，多个线程同时 CAS 一个 value，只有一个成功，其他失败后立刻重试（不阻塞），反复失败反复重试 → CPU 时间片被空转消耗。
   </details>

2. **伪共享的根因是什么？为什么"没有共享变量"也会互相拖累？**
   <details><summary>答案</summary>

   根因是缓存行（64 字节）的粒度大于变量粒度：两个不同变量落在同一缓存行，一个变量的修改会让整个缓存行失效，另一个变量的持有者被迫重新加载。
   </details>

3. **LongAdder 在什么场景下优于 AtomicLong？为什么 sum() 弱一致？**
   <details><summary>答案</summary>

   高并发累加场景（竞争被摊薄到 Cell）。sum() 遍历 Cell 求和的过程中可能有线程在 add，读到的是中间状态。
   </details>

4. **@Contended 是干什么的？为什么需要 JVM 参数开启？**
   <details><summary>答案</summary>

   让标注的字段独占一个缓存行（前后自动填充）。JDK 8 默认只允许 JDK 内部类使用，应用代码需要 `-XX:-RestrictContended` 开启。
   </details>

5. **AtomicStampedReference 如何解决 ABA？**
   <details><summary>答案</summary>

   在值之外加一个版本号（stamp），每次修改版本号 +1。CAS 时同时比较值和版本号 → 值变回 A 但版本号已经不同 → CAS 失败 → ABA 被识别。
   </details>

---

> 📬 **阶段三第 6 篇完成！还剩最后一篇 [03-07-同步工具类](./03-07-同步工具类.md)（待发布）—— CountDownLatch / CyclicBarrier / Semaphore / Exchanger / Phaser，学完阶段三就收官了**

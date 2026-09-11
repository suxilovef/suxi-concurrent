# 01-02 volatile 关键字深度解析

> **阶段一·第 2 篇** | 前置：[01-01-JMM内存模型与三大特性](./01-01-JMM内存模型与三大特性.md) | 后续：[01-03-CAS原理与Unsafe入门](./01-03-CAS原理与Unsafe入门.md)  
> **建议时长**：4~5 小时（原理 1.5h + 源码分析 1h + 动手练习 1.5h）  
> **源码口径**：JDK 17
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
当线程 A 写一个 volatile 变量时，这次写具有 release 语义：
┌─────────────────────────────────────────┐
│  volatile 写之前的普通写                 │
│  不能被重排序到 volatile 写之后            │
│                                          │
│  其他线程一旦读到这次 volatile 写          │
│  就能通过 happens-before 看到前面的普通写  │
│                                          │
│  效果：写 volatile = 发布前面已完成的修改   │
└─────────────────────────────────────────┘
```

> 💡 **一句话版**：写 volatile = 按下"发布"按钮，把前面干完的活（普通写）一次性发布出去；其他线程读到这次写 = 按下"接收"按钮，把发布的内容全部收进来。**完整因果链（为什么这句话成立）见 2.4。**

```java
// volatile 写不仅发布 volatile 变量本身，
// 还通过 happens-before 发布它之前的普通写
int a = 1;              // ① 普通写
volatileFlag = true;    // ② volatile 写
// 如果其他线程读到 volatileFlag=true，就一定能看到 ① 的结果
```

### 2.2 读 volatile 的语义

```
当线程 B 读一个 volatile 变量时，这次读具有 acquire 语义：
┌─────────────────────────────────────────┐
│  volatile 读之后的普通读写                │
│  不能被重排序到 volatile 读之前            │
│                                          │
│  如果读到的是某次 volatile 写的值          │
│  就能看到那次 volatile 写之前发布的修改     │
│                                          │
│  效果：读 volatile = 获取发布方的修改       │
└─────────────────────────────────────────┘
```

```java
// volatile 读不仅保证读到 volatile 变量的最新值，
// 还会获取该 volatile 写之前发布的普通变量修改
if (volatileFlag) {     // ③ volatile 读
    int b = a;          // ④ 如果 ③ 读到 ② 的 true，a 一定是 1
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

> ⚠️ **关键限制**：上述推导要求"普通写与 volatile 写在**同一线程内、普通写在前**"（程序次序）。如果 A 线程先写 `flag` 再写 `sharedVar`，`sharedVar` 的写在 volatile 写**之后**，B 线程不一定能看到它。多写者场景同理：B 读到哪次发布，就看到那次发布携带的完整快照，但不同写者的普通写之间互不保证。

### 2.4 完整因果链：一条链讲透 volatile 可见性

> 2.1/2.2 是"结论"，2.3 是"推导"。如果还是觉得跳，把下面这条因果链从硬件到效果完整走一遍——每一环都回答一个"为什么"。全链 5 环，环环相扣，没有缺口。

**第 1 环：问题从哪来（为什么必须有这套规矩）**

两个独立的物理事实，叠加出"看不到 / 看乱"：

**事实一：写不会立刻被所有人看到（缓存 + 写缓冲）**
- 多核 CPU，每个核有自己的缓存（L1/L2），线程 A 跑在核 1、B 跑在核 2
- A 的写先落在自己的写缓冲/缓存里，刷到共享内存是异步的；B 读自己那份缓存，可能是旧的
- 即使写已刷出，其他核的缓存行失效（Invalidate）也有传播延迟——B 依然可能读到旧值

**事实二：指令执行顺序会变（重排序）**
- CPU 为了效率会乱序执行（把无依赖的指令对调），编译器（JIT）也会调序
- 它们凭什么敢调？判断标准叫 **as-if-serial**：只要**单线程可观察结果**不变，顺序就是自由的；判断抓手是**数据依赖**（写后读 / 读后写 / 写后写）——没有依赖的指令 = 机器眼中的"无关指令"，随便排
- 但"无关"是**本线程视角**的无关：`a = 1; flag = true;` 对 A 自己确实无所谓先后，可 B 能撞上"换序后的中间状态"——你写在代码里的发布关系（flag 是 a 的哨兵），机器看不到（完整机制清单见 3.2）

结果：没有规则时，B 可能看到——**旧值**（写没传播）、**半成品**（发布与内容乱序）、**乱序值**（读写乱序）

所以必须有一份规范，把"谁先谁后、谁看得见谁"的行为**钉死**。这就是 JMM（Java 内存模型，JLS §17.4）。

**第 2 环：JMM 的答案（happens-before 规则）**

> 若 A happens-before B，则 A 的结果对 B 可见，且 A 不会被重排到 B 之后。

哪些情况算 happens-before（阶段一先记这 5 行；完整的还有中断、finalizer 两条，用到时再补）：

| 规则 | 条件 |
|---|---|
| 程序顺序 | 同一线程内，按代码顺序 |
| volatile | 对同一 volatile 变量，写 → 后来的读 |
| 锁 | unlock → 之后同一个锁的 lock |
| 线程 | start() → 新线程内一切；线程内一切 → join() 返回 |
| 传递性 | A→B 且 B→C ⇒ A→C |

**为什么用"规则"而不是"要求硬件别乱"？** 缓存、乱序是物理现实，规范只能规定**行为效果**，实现交给 JVM 各显神通（这就是第 4 环"不同 CPU 插不同屏障"的原因）。

**第 3 环：volatile 的定义（2.1/2.2 那句话的真身）**

JMM 给 volatile 定的条款：对 volatile 变量 v，**写 v happens-before 之后任意线程对 v 的读**。翻译成动作约束：

- **volatile 写 = release**：写之前的所有普通写，不许被挪到写之后 → "发布"
- **volatile 读 = acquire**：读之后的所有普通读写，不许被挪到读之前 → "收获"

> ⚠️ 注意：这是**定义**，不是实现。2.1 里"volatile 写之前的普通写不能被重排序到 volatile 写之后"这句话**本身就是 volatile 的语义内容**，不是"为了实现可见性而附加的限制"——禁止跨边界 = 语义 = 同一件事。

**第 4 环：JVM 落实（重排序有两处，屏障管两处）**

重排序发生在两个地方，得两头堵（具体机制清单见 3.2）：

1. **编译时**（JIT 编译换序）→ 决策：JIT 生成机器码时决定"这段代码要不要防重排"，决定的方式是**在生成的指令里放屏障指令**
2. **运行时**（CPU 乱序执行）→ 执行：屏障指令在 CPU 上生效——约束乱序引擎、冲刷写缓冲、触发缓存失效

> 🔍 澄清一个常见误解：**"编译期屏障"和"硬件屏障"不是两种东西**。JIT 的屏障标记最终也是生成机器码里的屏障指令（x86 的 `lock` 前缀 / `mfence`）。区别只在决策时机——JIT 换序发生在生成代码时（静态），CPU 换序发生在执行时（动态），两处都要拦，但拦的工具是同一条指令链。

JVM 按 JSR-133 cookbook 的标准插屏障表（详见 3.3）：

| 位置 | 插什么 | 管什么 |
|---|---|---|
| volatile 写**前** | StoreStore | 前面的普通写不许越过写跑后面 |
| volatile 写**后** | StoreLoad | 后面的 volatile 读不许越过写跑前面（普通读越过 volatile 写是 release 语义允许的） |
| volatile 读**后** | LoadLoad + LoadStore | 后面的读写不许越过读跑前面 |

屏障是 CPU 的一条特殊指令（如 `mfence`/`dmb`），作用：① 前后指令不许跨越；② 强制把写缓冲冲刷出去、让写传播给其他核（配合缓存一致性协议）。

> 同一规定，不同 CPU 执法力度不同：x86 本身序强（TSO），volatile 写 = 普通写 + `lock` 前缀指令，读甚至不用插屏障；ARM 弱序，屏障插得多。JMM 承诺的效果一样，手段不同（为什么 x86 读免费、写要付钱，见 3.4）。

**第 5 环：闭环（为什么 B 最终一定看到完整数据）**

```java
// 线程 A
x = 1;                  // ① 普通写
// ── JVM 插入 StoreStore 屏障 ──
ready = true;           // ② volatile 写 = 发布 + 冲刷缓存
// ── JVM 插入 StoreLoad 屏障 ──

// 线程 B
if (ready) {            // ③ volatile 读 = 收获
    // ── JVM 插入 LoadLoad + LoadStore 屏障 ──
    use(x);             // ④ 普通读
}
```

推理走一遍：

1. **顺序保证**（第 4 环的屏障）：① 一定在 ② 之前完成 → **发布时 x 一定写完了**；③ 一定在 ④ 之前完成 → **没收到发布就绝不碰 x**
2. **可见性保证**（第 4 环的冲刷）：② 执行时 A 的写传播出去；③ 读到的 `ready=true` 一定是 ② 写的那次 → **发布被接收了**
3. **关系保证**（第 2 环的规则）：①→②（程序顺序）、②→③（volatile 条款）、③→④（程序顺序）、①→④（传递性）→ **JMM 承诺 x 对 B 完整可见**

三股力量各管一段，没有缺口。

**对照表：本节 ↔ 2.1 原文**

| 2.1 原文那句话 | 对应环节 |
|---|---|
| "volatile 写之前的普通写不能被重排序到写之后" | 第 3 环（定义）+ 第 4 环（落实） |
| "其他线程一旦读到这次 volatile 写" | 第 5 环的 ③ |
| "就能通过 happens-before 看到前面的普通写" | 第 2 环规则 + 第 5 环传递性 |
| "效果：写 volatile = 发布前面的修改" | 整个链条的结论 |

> 📌 2.1 的原文 = 第 3 环 + 第 2 环 + 第 5 环的**压缩包**；第 1、4 环（硬件背景和屏障实现）原文默认读者已知、没写。刚接触时觉得不清晰是正常的——按本节链条从头读一遍即可。

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

**重排后的完整效果走一遍（为什么半初始化对象会崩）：**

**第 1 步：第 1 行一结束，`instance` 就非 null 了**

内存地址已经拿到，重排只是把"初始化"挪到"赋值"之后：

```
线程 A 执行 new Singleton()：
  memory = allocate();    // 1. 分配内存 —— 拿到一块地址（非 null）
  instance = memory;      // 3. 先赋值引用 ← 重排到这里了！
  ctor(memory);           // 2. 后初始化对象
```

**第 2 步：线程 B 在锁外就能拿到半成品**

```java
public static Singleton getInstance() {
    if (instance == null) {          // ① 第一次检查（无锁快速路径）
        synchronized (Singleton.class) {
            if (instance == null) {
                instance = new Singleton();  // ③
            }
        }
    }
    return instance;                 // ← B 在这里直接拿走
}
```

时间线：

```
线程 A:  分配内存 → 赋值引用（instance 非 null！）
                        ↑
线程 B:  ① 检查：instance != null → 不进锁 → return instance
                        ↑ 此时 A 的 ②（初始化）还没执行！
```

**第 3 步：半成品对象长什么样**

内存分好了（引用非 null），但字段还是默认值（null / 0），构造方法里的逻辑一行都没跑：

```java
instance.cache.add("x");   // 💥 NPE —— cache 还是 null
instance.getName();        // 💥 返回 null，逻辑错乱
```

> 🏠 **类比**：交房（分配内存）→ 装修（初始化）→ 挂牌出售（赋值引用）。重排成"交房 → 挂牌出售 → 装修"：B 看到"出售中"就拎包入住，结果墙是毛坯、水管没接——房子（引用）是真的，但住不了（字段全废）。

> ⚠️ **为什么 synchronized 救不了 DCL？** 因为 B 的**第一次检查在锁外面**——看到非 null 就直接 return，根本不会进临界区。synchronized 只保护临界区内部，管不了这个无锁快速路径。只有 volatile 能保证"B 在锁外看到非 null 引用"这个时刻对象已初始化完（StoreStore 屏障：② 必须完成，③ 才允许发生，见 2.4 第 4 环）。所以 volatile 不是替代 synchronized，而是**补它管不到的那段缝隙**。

> 🖥️ **平台差异：x86 上其实"碰巧不崩"，但 volatile 仍是必须的**（与 3.4 的 TSO 结论接上）
>
> 上面的重排是 **Store→Store**（初始化写 ↔ 引用赋值写，都是写）。3.4 会说 x86 是 TSO、**只允许 Store-Load 重排**——即 x86 硬件层面**禁止**这种换序，HotSpot 的 JIT 也不会主动把引用发布提前。所以 x86 + HotSpot 上 DCL 漏掉 volatile **实测不会崩**。
>
> 那 volatile 为什么还是必须的？
> - **JMM 不保证**：规范钉的是"行为效果"，不是某个 CPU 的脾气。x86 今天禁止 Store-Store 重排，不等于规范承诺了它——正确性必须建立在规范上，才能在所有平台一致成立
> - **换平台就崩**：ARM / POWER 是弱序模型，硬件真的会换这两个 store 的序——同一份代码 x86 上跑十年没事，部署到 ARM（手机端/部分云主机）就偶发半初始化崩溃
>
> 这也解释了坑 4 的"测试环境可能永远碰不到"：**"碰不到"不是因为概率低，而是 x86 根本不产生这种重排**——坑是埋给可移植性的。volatile 对应的 StoreStore 屏障在 x86 上是 no-op（TSO 天然有序），在 ARM 上才真正干活。

### 3.2 重排序全景：到底发生在哪些地方

重排不是一次性的——从源码到执行，每层都按**自己的规则**独立决定要不要换序：javac 不动、JIT 敢动、CPU 再动、缓存传播延迟再补一刀。按层数一遍：

**第 1 层：javac（源码 → 字节码）——几乎不重排**

字节码序列的顺序是源码顺序（`a = 1; flag = true;` 编译成两条顺序固定的 store）。DCL 的问题不是 javac 干的，是下一层。

**第 2 层：JIT 编译（字节码 → 机器码）——重排重灾区**

JIT 在生成机器码时做指令调度，以"单线程结果不变"（as-if-serial）为唯一约束。具体机制：

| 优化 | 干什么 | 并发下的后果 |
|---|---|---|
| 循环不变量提升 | 把循环里不变的 load 提出去 | `while(!running)` 的 running 只读一次 → 死循环（场景 1 的 bug） |
| 指令调度 | 无依赖指令换序，隐藏延迟 | `a = 1; flag = true;` 两条独立 store 被换序 |
| 冗余 load 消除 | 同一字段的多次 load 合并 | 每次循环重读变成一次读 |
| store 合并/延迟 | 连续写合并；store 后无读则推迟 | `a = 1` 的写晚于 `flag = true` 生效 |
| 屏障删减 | 证明 volatile 字段单线程访问就删屏障 | 合法——JIT 有权删它证明"不需要"的屏障 |

> ⚠️ 解释执行阶段不重排。同一方法：解释执行（顺序）→ JIT 编译后（可能重排）→ 逆优化后回解释器（顺序）。并发 bug"跑热了才出现、换个参数就消失"，多半是这个阶段切换。

**第 3 层：CPU 执行（机器码 → 内存系统）**

机器码的顺序是"死"的，CPU 执行是"活"的。四个机制：

| 机制 | 干什么 | 后果 |
|---|---|---|
| 乱序执行引擎 | 操作数就绪即执行，不管程序顺序 | 主要影响执行时间；内存指令提交时按序 |
| **Store Buffer（写缓冲）** | store 先进缓冲、异步刷出 | **x86 上唯一的重排来源**：先发的 store 没刷出，后发的 load 绕过缓冲直接读 → 读到旧值 |
| Load 推测执行 | load 提前跑，读到旧值再验证 | 方向通常安全，x86 上不用管 |
| 缓存一致性传播 | 失效消息有延迟，写传播到其他核要时间 | 观察乱序的物理延迟（配合 MESI 失效） |

**弱序架构**：上面按 x86（TSO）说。ARM / POWER 全开——Store-Store、Load-Load、Load-Store、Store-Load **四种重排都可能**。这就是 DCL 在 ARM 上真崩、在 x86 上碰巧不崩的原因（见 3.1 平台差异）。

**为什么必须两头堵**：每层的合法性标准不同——javac 按 JLS 语义，JIT 按 as-if-serial + JMM 承诺，CPU 按架构内存模型（TSO/weak）。JMM 钉住**最终可观察行为**，各层在授权内自由。所以 JIT 换序发生在生成代码时（静态）、CPU 换序发生在执行时（动态），两道门都要拦：

| 重排发生在 | 防线 | 谁决策 |
|---|---|---|
| JIT 编译期（静态换序） | JIT 生成机器码时放屏障指令 | JIT（编译时） |
| CPU 运行期（动态乱序 / 写缓冲 / 缓存传播） | 屏障指令执行时约束乱序引擎、冲刷 store buffer、触发缓存失效 | CPU（运行时） |

落实下来就是 3.3 的那张屏障表。

### 3.3 屏障如何拦：插入策略与执行

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
    volatile 读
    ────── [LoadLoad 屏障]  ──────  禁止 volatile 读与下面的普通读重排
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

**为什么是这张表（而不是别的形状）——三步推导**

> 上面的表常被当口诀背。其实不用背：它可以由 volatile 的三条语义义务一步一步推出来。掌握推导，自然能回答三个"为什么"：为什么写前只有 StoreStore？为什么写后多一根 StoreLoad？为什么读前什么都没有？

**第 1 步｜先改一个认知：屏障名 = 被隔开的"操作对"，不是位置**

`StoreLoad` 不是"写后屏障"，`LoadLoad` 也不是"读后屏障"。四根屏障的名字格式是 `<先执行的那个><不许越过它的那个>`，禁的是"**后一个操作越过前一个、跑到前面去**"：

| 屏障 | 禁止的重排（后 → 前） | x86（TSO）上 |
|---|---|---|
| `LoadLoad` | 后面的**读**越过前面的**读** | 免费（硬件天然禁） |
| `LoadStore` | 后面的**写**越过前面的**读** | 免费（硬件天然禁） |
| `StoreStore` | 后面的**写**越过前面的**写** | 免费（硬件天然禁） |
| `StoreLoad` | 后面的**读**越过前面的**写** | **收费（唯一要真插的）** |

栅栏永远站在操作对**中间**——"前一个的后面"和"后一个的前面"是同一道门。所以表格里"写前 / 写后 / 读后"只是叙述视角，本质是**在需要禁止换序的操作对之间插栅栏**。

**第 2 步｜volatile 的三条义务 → 各自需要禁止哪些操作对**

volatile 相对普通变量多了三条排序义务，每条派生一根/一组屏障：

| 义务 | 语义 | 派生出的屏障 | 为什么是这一根 |
|---|---|---|---|
| 写 = release | **发布前面的活**：前面的普通写必须完整，才允许开闸 | 写**前** `StoreStore` | 防止前面的写沉到 volatile 写之后（发布半成品） |
| 读 = acquire | **接收后面要用**：后面的读写必须先确认收到发布 | 读**后** `LoadLoad` + `LoadStore` | 防止后面的读/写提前到 volatile 读之前 |
| volatile 之间 = SC | **写不能被后面的 volatile 读越过** | 写**后** `StoreLoad` | release/acquire 都管不到"写被自己后面的读越过"（x86 唯一放开的重排），必须补一根大棒 |

> 🔑 第 3 条义务就是 **Java volatile 与 C++11 的 release/acquire 的差别所在**：纯 release/acquire 没有它，所以写后不需要 `StoreLoad`。这根 `StoreLoad` 是 Java volatile 比"普通发布/接收"多付的全部成本——也是 x86 上唯一真正执行的屏障（见 3.4）。

**第 3 步｜那"读前"和"写后"为什么空着？——责任是分派的，不是每扇门开两遍**

把上面三条义务按**约束方向**摊开，没有一类操作需要两侧同时约束：

| 边界 | 管谁 | 落在哪 |
|---|---|---|
| 写 = release | 管**前面**（前面的写不许落后） | 写前 `StoreStore` |
| 写 = SC | 管后面跟的 volatile 读 | 写后 `StoreLoad` |
| 读 = acquire | 管**后面**（后面的读写不许提前） | 读后 `LoadLoad` + `LoadStore` |
| 读 | 管**前面**？（读不发布任何东西，无此义务） | **读前：空** |

一句话：**"管前面"归 release（写侧），"管后面"归 acquire（读侧）**——发布方管身前、接收方管身后、一对 volatile 由发布方兜 SC，三块互不重叠。任何一对跨 volatile 的操作要排序，都能在上面找到归属；找不到归属的那一对（如"操作 ↔ volatile 读"），就是**语义上不需要排序**，插了也是白插。

**读前为什么真的是空的——把站在读前面的候选逐一举证：**

| 读 R 前面站着什么 | 需要钉吗 | 为什么 |
|---|---|---|
| 前面的普通读 L | 不需要 | 两个都是输入操作、谁都不发布，换序无可观察变化 |
| 前面的普通写 S | 不需要 | S 要发布必须靠它**后面**某次 volatile 写，那次写自带 StoreStore 把 S 钉住；S 沉到 R 下面时，R 不发布任何东西、读的值也不依赖 S，无害 |
| 前面的 volatile 写 | 不需要（在 R 侧） | 这一对要 SC，但门开在**写侧**（那根 StoreLoad 站在它俩中间），不在读侧 |

> ⚠️ 由此澄清一个常见误解：x86 上 volatile 读是普通 load，**不是**因为 TSO 禁止了 store→load 重排——TSO 恰恰允许 store→load。真正原因是：读前面的 store→load 恰好是**无害重排**（上表第二行的论证），故意不拦。**"CPU 能干" ≠ "要插屏障"；插不插只看这个重排会不会掐断某条 happens-before 链。**

对偶检查（写后为什么也几乎为空）：release 不管身后的普通操作——volatile 写**后面**的普通读/写想提前到写之前是允许的（它们不是发布内容，提前无害）。所以写后只有那根为 SC 服务的 StoreLoad，没有别的。

### 3.4 x86 平台的实际实现：为什么读免费、写要付钱

> ○ **x86 是强内存模型（TSO）**，只允许 Store-Load 重排序。所以 volatile 在 x86 上的实现很简单：

```
x86 上：
  volatile 读 = 普通读（x86 读本身有序）
  volatile 写 = 普通写 + StoreLoad 屏障效果
  HotSpot 常见做法：在 volatile 写之后使用带 lock 前缀的指令充当屏障

lock 前缀做了什么：
  - 锁定缓存行相关操作（现代 CPU 通常不直接锁总线）
  - 使其他 CPU 缓存行失效（相当于 MESI → Invalidate）
  - 禁止 Store-Load 重排序
```

**为什么 x86 的 volatile 读免费、写要付钱？** 这是"每层合法性标准不同"（3.2 末尾）的直接推论：

- **读 = acquire**，需要"读之后的读写不许越过读"。TSO 只允许 Store-Load 一种重排——load 不会与其他 load/store 换序，acquire 需要的顺序**架构免费提供**，volatile 读自然不需要插屏障
- **写 = release**，需要"写之前的写不许越过写 + 写必须刷出写缓冲"。TSO 免费提供前者（Store-Store 天然有序），但**刷出 store buffer 必须显式做**——`lock` 前缀正好干这个，还顺带防了 x86 唯一的重排（Store-Load）
- ARM 弱序：四种重排都可能，acquire/release 需要的屏障架构**都不免费**——读要插 LoadLoad/LoadStore，写要插 StoreStore/StoreLoad。JMM 承诺的效果一样，手段随架构不同

> 你可以用 JITWatch + hsdis 插件观察有/无 volatile 时生成的实际汇编指令。无 volatile 时通常是普通 `mov` 指令，有 volatile 时 HotSpot 可能在写之后生成带 `lock` 前缀的屏障指令。具体形式与 JDK 版本、CPU 架构、JIT 编译阶段有关。

---

## 4. volatile 不保证原子性（核心陷阱）

### 4.1 `i++` 问题回顾

```java
volatile int count = 0;

// 10 个线程各执行 10000 次 count++
// 预期：100000
// 实际：远低于预期，一个常见的经验区间是 20000 ~ 40000（丢失率 60%~80%）
// 具体值随 JIT 编译状态和机器差异浮动，别当定值
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

> 🔍 **关键细节：加 1 这一步既不读也不写，它是纯计算**
>
> `iadd` 只是 CPU 在**寄存器**里做算术，全程不碰内存：
>
> ```
> getstatic → 读：内存 → 寄存器
> iadd      → 算：寄存器内 +1（内存不参与）
> putstatic → 写：寄存器 → 内存
> ```
>
> 所以"加 1"永远不会算错——错的是它**基于"读的那一刻"的值**。从读到写之间的缝隙里，内存的值可能已经被别的线程改过，而你的计算完全不知道，最后写回时把别人的结果覆盖掉——这就是"丢失更新"的本质。
>
> 这也解释了 `AtomicInteger` 的解决思路：要么用 CPU 单条"读-算-写"合并指令（`lock xadd`），要么用 CAS 发现值变了就重来——本质都是**把三步之间的缝隙焊死**（见 4.3）。

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

### 5.0 适用判据：四问体检法（判据从哪来）

> 下面 5 个场景不是靠背的，是靠一套"四问体检法"筛出来的。判据也不是拍脑袋定的——它是拿 volatile 的能力清单**倒推**出来的：场景需要的能力，volatile 必须给得起；volatile 的短板，场景必须用不到。

**volatile 的能力清单（来自 2.4 链条）：**

| 能力 | volatile | 代价/短板 |
|---|---|---|
| 可见性（发布/接收） | ✅ | — |
| 有序性（release/acquire） | ✅ | — |
| 原子性（复合操作） | ❌ | 没有锁 |
| 互斥（把别人挡在外面） | ❌ | 不阻塞 |
| 读写开销 | 读几乎免费 | **写比较贵**（冲刷缓存 / `lock` 前缀） |

**四问判据 ↔ 能力清单的对应关系：**

| 判据 | 对应能力/短板 | 违反了会怎样 |
|---|---|---|
| ① 一个线程写，其他线程读 | volatile 只能"发布+接收"，**协调不了多个写者** | 两个线程同时写 → 互相覆盖 → 需要原子性 |
| ② 单次读/单次写，无复合操作 | **没有原子性**，管不了读-改-写 | `count++` → 丢失更新（见 4.1 的缝隙） |
| ③ 不需要互斥 | **没有锁**，无法围成临界区 | 需要"检查-修改"一体 → 拦不住别人插队 |
| ④ 读多写少 | **写贵读便宜**，让贵的动作少发生 | 高频写 → 每次缓存失效，性能差 |

**四问体检法（遇到新场景先过一遍）：**

> ① 是单写多读吗？② 只是单次读写吗？③ 不需要互斥吗？④ 读多写少吗？
>
> **四问全过 → volatile 刚刚好；任一不过 → 升级工具。**

判据反过来用就是升级路线：多个写者 → `AtomicInteger`；复合操作 → 锁或 CAS；需要临界区 → `synchronized` / `Lock`；高频写 → 状态复杂，通常也不是简单标志位。一旦需要任何一个，volatile 就只配当配角（如 `AtomicInteger` 内部的 `value` 字段）。

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

> 🎯 **统一原则（和坑 2 是同一件事）**：volatile 只保护**引用本身**，不保护引用指向的内容。"合并成一个对象"能生效，前提是配套写法——**整体替换引用**，而不是修改对象内部：
>
> | 写法 | 动作性质 | volatile 管吗 |
> |---|---|---|
> | `config = new AppConfig(...)` | 整体替换引用 | ✅ 管 |
> | `config.setMaxConnections(200)` | 修改对象内部 | ❌ 不管（和坑 2 的 `arr[0] = 42` 一样） |
>
> 为什么"整体替换"后内容可见？因为对象内部字段的填充发生在 volatile 写**之前**（StoreStore 屏障保证），发布时一起带过去（见 2.4 第 4 环）：
>
> ```
> new AppConfig(...) 里的所有写（内部字段填充）
>      ↓ 在 volatile 写之前完成
> config = 新对象    ← 一次 volatile 写，整体发布
>      ↓
> 业务线程读到新 config → 内部字段全部可见
> ```
>
> 反过来说：如果写成 `config.setXxx(...)`（发布之后又改内部），没有新的发布动作，其他线程就停在旧状态——这和坑 2 的 `arr[0] = 42` 是完全一样的错误。**数组和对象一视同仁：volatile 管"换哪个对象"，不管"对象里面怎么动"。**

**真实使用场景（都是"单写多读 + 读多写少"，四问全过）：**

| 场景 | 具体长什么样 |
|---|---|
| 功能开关/灰度 | 新功能先对 1% 用户开，运营调后台数值 → 100% 放量，不用发版（feature flag 系统） |
| 日志级别热切换 | 线上出问题把 `debugMode` 切成 true，日志立刻打全，不用重启 |
| 限流/降级参数 | 流量突增调 `maxQps`；下游依赖挂了切"本地兜底"降级开关 |
| 资源参数 | 连接池大小、超时时间、重试次数 |
| 配置中心客户端 | Nacos / Apollo / Spring Cloud Config 推送新值 → 客户端监听器写入 volatile 字段 → 业务线程立刻读到 |

> 🤔 **常见误区："配置不是发布到缓存里吗？跟 volatile 有什么关系？"**
>
> 你的理解没错——现代配置中心确实是"改配置 → 推送 → 各处缓存"的模式。但"缓存"最终落在 JVM 里**就是一个 Java 字段**，配置中心客户端把它存成 volatile（或内部为 volatile 的 `AtomicReference`），业务线程直接从字段读：
>
> ```
> 远端配置中心/配置文件（存储层）
>    ↓ 拉取/推送
> JVM 里的 volatile 字段（缓存层）← "缓存"就是这里
>    ↓ volatile 保证可见
> 所有业务线程读到最新值
> ```
>
> 所以 volatile **不是缓存的替代品，而是让缓存方案真正生效的最后一跳**：如果那个字段不加 volatile，推送线程更新了字段，业务线程却还在读自己 CPU 缓存里的旧副本——配置"改了但没生效"。这跟场景 1 的死循环是同一个问题，只是表现从"循环不退"变成"配置不生效"。

**真实源码例子（Apollo 配置中心客户端，真实类简化）：**

```java
// com.ctrip.framework.apollo.internals.DefaultConfig（真实类）
public class DefaultConfig {
    // "缓存"就是这个字段：最新版用 AtomicReference，其内部就是 volatile V value
    private AtomicReference<Properties> m_configProperties;

    // 配置中心推送新配置时回调（配置写方，单线程）
    public synchronized void onRepositoryChange(String namespace, Properties newProperties) {
        Properties newConfigProperties = propertiesFactory.getPropertiesInstance();
        newConfigProperties.putAll(newProperties);
        // 计算变更 + 更新缓存：整体换一个新 Properties 引用
        updateAndCalcConfigChanges(newConfigProperties, sourceType);
        m_configProperties.set(newConfigProperties);   // 一次引用写，整体发布
        this.fireConfigChange(...);                     // 通知监听器
    }

    // 业务线程读配置（配置读方，多线程并发读）
    public String getProperty(String key, String defaultValue) {
        return m_configProperties.get().getProperty(key, defaultValue);
    }
}
```

注意它正是文档前面说的生产姿势：**不可变对象（新 Properties 整体换引用）+ volatile（`AtomicReference` 内部）**，一次发布全体生效，业务线程永远看到完整的新/旧配置，不会混搭。

> TODO: 对照 JDK 17 验证 `AtomicReference` 内部 `value` 字段的 volatile 定义；补充 Nacos 客户端（`NacosPropertySource` 的 `volatile Map data`）源码片段。参考：[Apollo 核心配置系统解析](https://deepwiki.com/apolloconfig/apollo-java/2.1-core-configuration-system)

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
| `ThreadPoolExecutor` | `AtomicInteger ctl` | 高位保存运行状态，低位保存线程数；内部依赖 volatile value + CAS |
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
        // 能！光 volatile 链就够：prepareData hb flag写 hb flag读（传递性）
        // 锁规则（unlock hb lock）是多余加成，不是必要条件
    }
}
```

### 🕳️ 坑 4：DCL 中漏掉 volatile 极难发现问题

```java
// ❌ 去掉 volatile 的 DCL
private static Singleton instance;  // 没有 volatile

// 问题：new 重排序导致其他线程看到半初始化对象
// 但注意：在 x86 上几乎不会发生（TSO 禁止 Store-Store 重排，见 3.1 平台差异）
// ——"偶发"的真实来源是 JMM 不保证 + 弱序平台（ARM/POWER）
// 测试环境可能永远碰不到，一到 ARM 服务器/其他 JVM 上就偶发崩溃
// → 最难排查的那种 bug：看起来没坏，但代码是错的
```

---

## 10. 面试高频考点

1. **volatile 保证什么？不保证什么？**
   → 保证可见性和有序性（禁止重排），不保证原子性。i++ 是经典反例。

2. **写 volatile 变量时，JMM 具体做了什么？**
   → volatile 写具有 release 语义：写之前的普通写不能重排到 volatile 写之后；其他线程读到这次 volatile 写后，可以通过 happens-before 看到这些普通写。

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
            // ⚠️ 循环体必须是"忙等"：不能有 Thread.sleep / println 等
            //    含内存屏障的操作（见坑 1）——否则无 volatile 时也会碰巧退出，
            //    观察不到差异
            while (running) {
                processedCount.incrementAndGet(); // 模拟处理任务（纯 CPU，无屏障）
            }
            System.out.println("Worker 检测到 running=false，开始清理...");
            System.out.println("Worker 安全退出，共处理: " + processedCount.get());
        }, "worker");
        worker.start();

        // 让 worker 跑一会儿，给 JIT 编译时间
        //（无 volatile 时 JIT 把 running 提升出循环 → 循环永不退出）
        Thread.sleep(2000);
        System.out.println("已处理 " + processedCount.get() + " 个任务");

        // 优雅停机
        System.out.println("发送停机信号...");
        running = false;
        // 忙等循环不会阻塞，不需要 interrupt 唤醒；
        // interrupt 只用于唤醒"阻塞中的任务"（如 sleep / wait / blockingQueue.take）

        worker.join(3000);
        System.out.println("停机后任务数: " + processedCount.get());
        System.out.println(worker.isAlive() ? "❌ Worker 未能退出" : "✅ Worker 已安全退出");
    }
}
```

### 练习 2：volatile 写的发布语义验证（30 分钟）

```java
package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 volatile 写的发布语义
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
            signal = true;       // ② 再写 volatile → 发布前面的普通写
            System.out.println("Writer: normalValue=" + normalValue + ", signal=true");
        }, "writer");

        Thread reader = new Thread(() -> {
            int attempts = 0;
            while (!signal) {   // ③ 读 volatile → 获取 writer 发布的修改
                attempts++;
            }
            // ④ 由于 ① hb ② hb ③ hb ④ → normalValue 一定为 42
            int value = normalValue;
            System.out.println("Reader: signal=true, normalValue=" + value +
                    " (attempts=" + attempts + ")");
            if (value == 42) {
                System.out.println("✅ volatile 发布语义生效");
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
   - 主要成本是内存屏障、缓存一致性通信和可能的缓存行失效
   - 在低竞争场景下通常比阻塞锁轻，但不是"一个 CPU 周期"级别

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

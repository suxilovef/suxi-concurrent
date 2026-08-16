# 03-04 并发容器之 ConcurrentHashMap

> **阶段三·第 4 篇** | 前置：[03-03-ReentrantReadWriteLock与StampedLock](./03-03-ReentrantReadWriteLock与StampedLock.md) | 后续：[03-05-并发容器之其他容器](./03-05-并发容器之其他容器.md)（待发布）  
> **建议时长**：7~8 小时（JDK7 vs JDK8 1h + putVal 2.5h + transfer 2h + sizeCtl 1h + 练习 1.5h）  
> 🛠️ **日常高频**：Java 并发容器中使用最频繁的一个，面试必考

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | putVal 全流程、get 无锁原理、sizeCtl 多重角色、CAS + synchronized 设计 | **能画流程图 + 能讲为什么这么设计** |
| ⭐⭐⭐ | 多线程协同扩容 transfer、ForwardingNode、helpTransfer | **理解扩容机制（面试高频）** |
| ◈◈ | 链表转红黑树阈值设计（8/6/64）、addCount + CounterCell 计数、key/value 为何不能为 null | **知道原因 + 会回答** |
| ○ | JDK 7 分段锁细节、红黑树内部结构 | **了解即可** |

---

## 1. 为什么需要 ConcurrentHashMap

```
HashMap 的问题：
  多线程并发 put → 数据覆盖、死循环（JDK 7 环形链表）、丢失数据
  → 线程不安全！

Hashtable 的问题：
  所有方法 synchronized → 整个表一把锁 → 并发度 0，性能极差

→ 需要：线程安全 + 高并发 → ConcurrentHashMap
```

### 1.1 JDK 7 vs JDK 8 对比总览

| 维度 | JDK 7 | JDK 8 |
|---|---|---|
| 数据结构 | Segment[] + HashEntry[] | Node[] + 链表/红黑树 |
| 锁粒度 | Segment（默认 16 段） | 单个桶（bin） |
| 锁机制 | ReentrantLock（继承自分段锁） | **CAS + synchronized** |
| 并发度 | 16（segment 数） | 表的长度（可达 2^30） |
| 扩容 | 每段独立扩容 | **多线程协同扩容** |
| 查询 | 无锁 | 无锁（volatile） |
| 复杂度 | 简单 | 复杂但高效 |

---

## 2. JDK 8 核心设计

### 2.1 数据结构

```
JDK 8 ConcurrentHashMap 结构：

┌────────────────────────────────────────────────────┐
│  Node<K,V>[] table（volatile 引用）                  │
│  ┌──────┬──────┬──────┬──────┬──────┬──────┐      │
│  │ bin0 │ bin1 │ bin2 │ bin3 │ bin4 │ bin5 │ ...  │
│  └──┬───┴──┬───┴──┬───┴──┬───┴──┬───┴──┬───┘      │
│     │      │      │      │      │      │          │
│   null   Node → Node → Node   null   null  TreeNode│
│          （链表，≤8 个）              （红黑树，≥8 个）│
└────────────────────────────────────────────────────┘

节点类型：
  Node        普通节点（链表）
  TreeNode    红黑树节点
  TreeBin     红黑树头节点（替代链表头）
  ForwardingNode  扩容中的转发节点（标记已迁移的桶）
  ReservationNode 占位节点（computeIfAbsent 用）
```

### 2.2 为什么 JDK 8 用 synchronized 而不是 ReentrantLock？

```
JDK 7 用 Segment（继承 ReentrantLock）：
  - Segment 的粒度是"一段"（16 段）
  - 锁对象是 Segment 实例

JDK 8 改用 synchronized 锁桶的头节点：
  - 粒度更细：每个桶独立加锁
  - 锁对象是 Node 本身（创建即固定：final hash/key，零额外分配）
  - 代码更简洁：不需要显式加锁/释放
  - 性能：临界区极小（单桶操作），低竞争下 synchronized 开销可忽略，
    且竞争远低于 JDK 7 的段锁（16 段共享一把锁）

为什么"锁头节点"就够？（第一性）
  对桶的一切修改都从头部进入：要么 CAS 替换头节点，要么从头节点开始遍历
  → 锁住头节点 = 锁住桶内所有修改的入口

为什么空桶不需要锁、非空桶必须锁？
  空桶：写入 = 一次 CAS 换头，单步原子操作 → 无锁
  非空桶：插入 = "找尾节点 + 改 pred.next"两步，读写位置分离，
     CAS 只能原子化其中一步 → 必须互斥

为什么需要双重检查（putVal 第 ⑦ 步）？
  锁的是"读到的头节点 f"，但 f 可能已被其他线程替换
   （并发 CAS 插入新头 / 树化换 TreeBin）→ 锁旧头 = 锁了个寂寞
  → 锁内再验 tabAt(tab, i) == f，不一致就重来

结论：synchronized 锁头节点 + CAS 空桶 = 更细粒度 + 更低成本
```

### 2.3 sizeCtl —— 一个变量五种角色（⭐）

```java
// 一个 volatile int 承担五种角色：
private transient volatile int sizeCtl;

// 角色 1：初始化标志（-1）
//   有线程正在初始化表（CAS 从 0 → -1）
// 角色 2：初始化/扩容的目标大小
//   n * 0.75（容量 × 负载因子）
// 角色 3：扩容进行中的低 16 位
//   正在扩容的线程数（低 16 位 = 参与线程总数 + 1；加入一个线程 +1，完成一个 -1）
// 角色 4：扩容标志（高 16 位）
//   扩容指纹 resizeStamp = numberOfLeadingZeros(容量) | 0x8000
//   （由当前表的容量推导，不是"第几次扩容"的序号）
//   作用：防止线程加入一个"旧表时代的扩容"（容量指纹对不上就拒绝）
// 角色 5：默认初始值（0）
//   未初始化时的初始状态
```

```
sizeCtl 取值与含义速查：
  0        → 表未初始化（默认）
  -1       → 有线程正在初始化
  < 0      → 正在扩容：低 16 位 = 参与线程总数 + 1（只有发起者时为 2）
              高 16 位 = 容量指纹（resizeStamp）
  正数     → 扩容阈值（容量 × 0.75）
```

---

## 3. 初始化：initTable()

```java
private final Node<K,V>[] initTable() {
    Node<K,V>[] tab;
    int sc;
    while ((tab = table) == null || tab.length == 0) {
        if ((sc = sizeCtl) < 0)              // ① 有线程在初始化 → 让出 CPU
            Thread.yield();
        else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {  // ② CAS 抢占初始化权
            try {
                if ((tab = table) == null || tab.length == 0) {
                    int n = (sc > 0) ? sc : DEFAULT_CAPACITY;    // ③ 容量
                    Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                    table = tab = nt;
                    sc = n - (n >>> 2);      // ④ 阈值 = n - n/4 = n * 0.75
                }
            } finally {
                sizeCtl = sc;                // ⑤ 更新阈值
            }
            break;
        }
    }
    return tab;
}
```

```
核心：CAS 竞争初始化权（sizeCtl: 0 → -1）
  谁 CAS 成功谁初始化，其他人 Thread.yield() 等待
```

---

## 4. putVal 全流程（⭐ 面试必考）

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();  // ① 禁止 null
    int hash = spread(key.hashCode());      // ② 散列（扰动函数）
    int binCount = 0;

    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f;
        int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();              // ③ 表未初始化 → 初始化

        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            // ④ 桶为空 → CAS 直接插入（无锁！）
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
                break;
        }
        else if ((fh = f.hash) == MOVED) {
            // ⑤ 桶正在扩容 → 帮忙扩容
            tab = helpTransfer(tab, f);
        }
        else {
            // ⑥ 桶非空 → synchronized 锁头节点
            V oldVal = null;
            synchronized (f) {
                if (tabAt(tab, i) == f) {   // ⑦ 双重检查（锁前锁后头节点一致）
                    if (fh >= 0) {          // ⑧ 链表
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            if (e.hash == hash &&
                                ((ek = e.key) == key || (ek != null && key.equals(ek)))) {
                                oldVal = e.val;      // 找到相同 key → 替换
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {  // 尾插法
                                pred.next = new Node<K,V>(hash, key, value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {  // ⑨ 红黑树
                        binCount = 2;
                        if ((oldVal = ((TreeBin<K,V>)f).putTreeVal(hash, key, value)) != null)
                            break;  // 已有 → 替换
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD)   // ⑩ 链表 ≥ 8 → 树化
                    treeifyBin(tab, i);
                if (oldVal != null)
                    return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);   // ⑪ 计数 +1（可能触发扩容）
    return null;
}
```

### 4.1 putVal 流程图（务必能画）

```
put(key, value)
   │
   ├─ key/value 为 null → NPE
   │
   ├─ 表未初始化 → initTable()
   │
   ├─ 桶为空 → CAS 插入（无锁）✅
   │
   ├─ 桶是 ForwardingNode → helpTransfer()（帮扩容）
   │
   └─ 桶非空 → synchronized(头节点)
        ├─ 链表 → 遍历：找到 key 替换 / 尾部插入
        ├─ 红黑树 → putTreeVal
        └─ 链表 ≥ 8 → treeifyBin 树化
   │
   └─ addCount() → 计数 + 可能扩容
```

### 4.2 spread 扰动函数

```java
// 让高位参与低位计算，减少哈希冲突
static final int spread(int h) {
    return (h ^ (h >>> 16)) & HASH_BITS;  // 高 16 位异或低 16 位
}
// HASH_BITS = 0x7fffffff（去掉符号位，保证 hash 非负）
```

### 4.3 为什么 key/value 不能为 null？

```
HashMap 允许 null：单线程场景，get(null) 返回 null 后能用 containsKey 兜底区分
ConcurrentHashMap 禁止 null：
  1. 并发歧义：get(key) 返回 null 时，无法区分"key 不存在"还是"key 的值是 null"
     → 为什么"再查一次"不可靠？（第一性）
        单线程：get 后用 containsKey 复核，两次调用之间没有其他线程改表 → 可靠
        并发：两次调用之间任何线程都能插入/删除 → 复核结果不可信
        → 注意不是"map 没有加锁"的问题：即使 get 内部加锁，锁也覆盖不了
          两次独立 API 调用之间的窗口（锁的粒度是"单方法"，歧义在"跨方法"）
        → 根因：任何单次 API 调用都无法原子地完成"读取 + 判定"
  2. 简化代码：Doug Lea 故意设计（ConcurrentHashMap 的 Javadoc 说明）

→ 结论：不允许 null 是为了消除并发下的二义性，是"故意"的设计
```

### 4.4 树化判断为什么在锁外？（⭐ 设计哲学）

```java
// putVal 第 ⑩ 步——这段在 synchronized 块外面，为什么？
if (binCount != 0) {
    if (binCount >= TREEIFY_THRESHOLD)   // ⑩ 链表 ≥ 8 → 树化
        treeifyBin(tab, i);
    if (oldVal != null)
        return oldVal;
    break;
}
```

```
原因 1：treeifyBin 是"自包含"操作，自己会重新加锁
  treeifyBin 内部：synchronized (b) + tabAt(tab, i) == b 双重检查
  → 重新读头、重新遍历当前链表，不依赖锁内看到的视图

原因 2：树化可能根本不做树化
  容量 < 64 时 treeifyBin 走 tryPresize 去扩容（建新表 + 迁移，长操作）
  → 绝不能持桶锁做扩容

原因 3：临界区最小化
  锁内只放"必须互斥的桶结构变更"（找尾节点 + 改 pred.next）
  建树是 O(链长) 的节点分配，放锁外 → 其他线程能更早进入该桶
```

```
锁外调用时桶的状态变化，treeifyBin 的应对：
  仍是链表           → 锁当前头 + 双检通过 → 树化
  头已被换（新插入）  → 锁新头，按当前链表重新树化
  已是 TreeBin / ForwardingNode → b.hash >= 0 不成立 → 跳过

设计哲学：判断用"触发条件"，执行以"当下状态"为准
  binCount >= 8 只是触发条件（锁内看到的近似长度）
  执行时以当下真实状态为准：状态变了就重新验证、按新的做
  → 判断与执行之间允许"状态漂移"，只要每次操作自身自洽
  与 §5 get 的弱一致性同源；同类应用：
    addCount 里 count >= sizeCtl 只是触发，CAS 抢到扩容权才是执行
```

```
统一模型：锁的本质 = 把"多步操作"原子化（串起 §2.2 / §4.4 / §5）

  操作能不能"自我原子化" → 决定要不要锁：
    空桶插入  单次 CAS 换槽（单步原子）         → 无锁
    链表插入  找尾 + 改 next（两步，读写分离）   → 必须锁
    树化      本地构建 + setTabAt（对外单步）    → 自持锁即可

  锁的两个旋钮：
    粒度（锁哪个对象）    → 桶级 vs 段级 vs 全局（§2.2）
    临界区（持锁多久）    → 只放必须互斥的多步变更（§4.4）

  自我原子化 = build-then-swap：
    本地构建 + 单次原子替换 → 操作自己变成"单步写"，不需要外部锁配合
    → 树化能脱掉上一把锁、重新自持锁执行；空桶 CAS 同理

  闭环：
    写路径每一步都被原子化兜住 → 读才敢不锁（§5 无锁读的地基）
    每个操作自我原子 → "状态漂移"无害 → "触发条件/当下状态"哲学合法
```

### 4.5 树化的单写点机制：为什么桶结构变更只有一个写点？（⭐）

```java
private final void treeifyBin(Node<K,V>[] tab, int index) {
    Node<K,V> b; int n, sc;
    if (tab != null) {
        if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
            tryPresize(n << 1);                       // ① 容量 < 64 → 去扩容，不做树化
        else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {  // ② 读当前头，必须还是链表
            synchronized (b) {                        // ③ 锁"当前头节点"
                if (tabAt(tab, index) == b) {         // ④ 双重检查：头没被换
                    TreeNode<K,V> hd = null, tl = null;
                    for (Node<K,V> e = b; e != null; e = e.next) {   // ⑤ 遍历链表
                        TreeNode<K,V> p = new TreeNode<>(e.hash, e.key, e.val, null, null);
                        if ((p.prev = tl) == null)    // ⑥ prev 指针串成双向链
                            hd = p;
                        else
                            tl.next = p;
                        tl = p;
                    }
                    setTabAt(tab, index, new TreeBin<K,V>(hd));   // ⑦ ★唯一写点★
                }
            }
        }
    }
}
```

```
"一个写点"的确切含义：对共享状态（桶槽位）的写只有 ⑦ 一次。
  ⑤⑥ 的 new TreeNode / p.prev / tl.next 写的是自己刚创建的对象
  → 此刻只有本线程持有引用（无别名），对其他线程不可见，不算共享写

为什么这样是安全的（四个保证）：
  1. 拷贝而非修改：旧链表一个节点都不动（只复制 key/val 引用）
     → 并发 get 若在 ⑦ 前读到旧头，走完的是完整旧链（有效旧状态）
  2. 构建线程私有：新节点只挂局部变量 hd/tl，写它们天然无竞争
  3. 发布唯一：setTabAt = 数组槽位 volatile 写（与 tabAt 对称）
     → 之前的一切写入通过 volatile 写-读 happens-before 整体可见
     → 读线程看到的必然是完整构建的树，绝无半初始化（安全发布）
  4. 持锁 + 双检防覆盖：任何改桶线程都必须锁"当前头"（putVal 第 ⑥ 步）
     → ③④ 验证通过 = 本线程独占桶 → ⑦ 覆盖式写不会抹掉并发插入
```

```
关键论证：为什么构建必须在锁内？（尾插不改头）

  双检（tabAt == b）只能验证"头没变"，验证不了"链没变"
  → 若先建树、再上锁验证、再换桶：
    T1 读头 b 开始建树
    T2 另一线程锁 b → 尾插新节点成功（头仍是 b！）
    T3 拿到锁 → 双检通过 → setTabAt 换桶 → T2 的插入被整个抹掉（丢失更新）

  → "读链 → 建树 → 换桶"必须处于同一持锁临界区
  → §4.4"临界区最小化"的精确含义：不是"尽量短"，而是"不多放一步"

三方并发交错，全部自洽：
  put（等锁中）     → 树化完成 → 双检失败 → 重走循环 → 见 TreeBin → putTreeVal 插树
  get（⑦ 前读旧头） → 走完旧链，读到树化前的完整状态（有效旧值）
  get（⑦ 后读）    → 从 TreeBin.find 读到新树里的值
  → 没有路径能观察到"半棵树"：结构变更的发布窗口只有一个点
```

---

## 5. get 无锁原理（⭐）

```java
public V get(Object key) {
    Node<K,V>[] tab;
    Node<K,V> e, p;
    int n, eh;
    K ek;

    int h = spread(key.hashCode());
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) {
        // ① 头节点就是目标
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                return e.val;
        }
        // ② 头节点 hash < 0：是 ForwardingNode 或 TreeBin
        else if (eh < 0)
            return (p = e.find(h, key)) != null ? p.val : null;
        // ③ 链表遍历
        while ((e = e.next) != null) {
            if (e.hash == h &&
                ((ek = e.key) == key || (ek != null && key.equals(ek))))
                return e.val;
        }
    }
    return null;
}
```

### 为什么 get 不需要加锁？

```
关键：Node 的 val 和 next 都是 volatile！

static class Node<K,V> {
    final int hash;
    final K key;
    volatile V val;        // ★ volatile
    volatile Node<K,V> next;  // ★ volatile
}

→ 读线程能"看见"写线程的最新 val 和 next（可见性保证）
→ 写线程对链表的修改通过 volatile next 发布
→ 即使读到"稍旧"的版本，也不会有线程安全问题（弱一致性的体现）

弱一致性（Weakly Consistent）：
  - get 可能读不到刚 put 的数据（tail 插入的最后一步 pred.next = newNode
    这个 volatile 写尚未发生 → 新节点还没"发布"）
  - 但读到的一定是"有效数据"：Node 通过 volatile next 发布（安全发布模式），
    读到节点就必然读到完整字段 → 无数据竞争，不会看到半初始化/脏数据
  - 精确表述：每个节点读取都保证是"某一时刻的最新值"，但一条链上的不同节点
    可能来自不同写时刻 → 读到的是"混合快照"
  - 适合"缓存"类场景，不适合"必须立即看到"的场景
```

---

## 6. 多线程协同扩容 transfer（⭐ 难点）

### 6.1 扩容触发

```
触发条件：addCount() 后 count >= sizeCtl（阈值）

扩容目标：容量 × 2
扩容过程：多个线程"分工"迁移桶
```

### 6.2 ForwardingNode

```java
// 扩容时，已迁移完的桶会被替换为 ForwardingNode
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;   // 指向新表

    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null);  // hash = MOVED (-1)
        this.nextTable = tab;
    }
}

// hash = MOVED = -1 → put/get 时发现是 ForwardingNode：
//   put：helpTransfer() 帮忙扩容
//   get：e.find() 到新表继续找
```

### 6.3 transfer 核心逻辑

```java
private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
    int n = tab.length, stride;

    // ① 计算每个线程负责的桶数（stride）
    //    单核 CPU：n（全部桶）
    //    多核：n / 8 / NCPU，最小 16
    if ((stride = (n > 8) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
        stride = MIN_TRANSFER_STRIDE;   // 最少 16 个桶

    // ② 初始化 nextTab（新表）
    if (nextTab == null) {
        // CAS 竞争创建新表（扩容发起者做）
        nextTab = new Node<?,?>[n << 1];   // 容量 × 2
        ...
    }

    // ③ 从后往前迁移桶
    for (int i = n - 1; i >= 0; i -= stride) {
        // ④ 领取任务：CAS 更新 transferIndex
        //    （transferIndex = 当前分配给线程的桶边界）
        ...
        for (; ;) {
            // ⑤ 处理桶 i：
            //    - 桶空 → CAS 放 ForwardingNode
            //    - 桶非空 → synchronized 头节点 → 迁移
            //    - 链表 → 拆成 low/high 两条链（按 hash & n）
            //    - 红黑树 → split
        }
        // ⑥ 每个线程完成自己的批次 → CAS sizeCtl - 1（收尾判定见 6.6）
    }

    // ⑦ 最后一个线程完成 → table = nextTab，sizeCtl 更新
}
```

### 6.4 链表拆分原理（hash & n）

```
扩容后容量 ×2，一个桶会被拆到两个新桶：

旧桶索引 i 的链表 → 新桶：
  hash & 旧容量 n == 0 → 留在原位 i
  hash & 旧容量 n != 0 → 移到 i + n

例子：
  旧容量 16（二进制 10000）
  hash = 5    → 5 & 16 = 0    → 留在桶 5
  hash = 21   → 21 & 16 = 16  → 移到桶 5+16=21

  旧桶 5：Node(hash=5) → Node(hash=21)
  扩容后：
    新桶 5：Node(hash=5)
    新桶 21：Node(hash=21)

→ 一次遍历，拆成两条链（不用重哈希！）

JDK 8 还有 lastRun 优化：
  从链表尾部往前找"最后一段同组（hash & n 相同）的连续节点"，
  整段 lastRun 直接搬到新桶复用（不用 new 新节点），只重建前半段
  → 减少节点分配（多数节点落在同一组时收益明显）
```

### 6.5 helpTransfer —— put 时帮忙

```java
final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
    // put 时发现头节点是 ForwardingNode → 加入扩容
    // 1. CAS 增加扩容线程计数（sizeCtl 低 16 位 +1）
    // 2. 调用 transfer 帮忙迁移
    // 3. 迁移完退出（自己该干嘛干嘛）
}
```

### 6.6 扩容总结

```
扩容设计要点：
1. 分工：stride 个桶一组，多线程并行迁移
2. 认领：CAS 更新 transferIndex，线程自己领任务
3. 标记：已迁移的桶放 ForwardingNode（MOVED）
4. 协作：put/get 遇到 ForwardingNode → 帮忙/查找新表
5. 收尾：每个线程完成批次后 CAS sizeCtl - 1；
   当低 16 位回到基数（sc - 2 == 指纹 << 16，只剩发起者）时该线程是最后一个
   → 替换 table 引用 + 重置 sizeCtl 为新阈值（新容量 × 0.75）

好处：扩容不再"卡住"所有线程，put 线程顺路帮忙
```

---

## 7. 计数机制：addCount + CounterCell

### 7.1 为什么要分段计数

```
高并发下每次 put 都 CAS 一个 baseCount：
  → 竞争激烈 → CAS 频繁失败 → 性能差

LongAdder 的思路（JDK 8 CHM 借鉴）：
  多个 Cell 分段累加，每个线程抢一个 Cell 累加
  计数时把 baseCount + 所有 Cell 的值相加
```

```java
// 结构
private transient volatile long baseCount;      // 基本计数
private transient volatile CounterCell[] counterCells;  // 分段计数

// addCount 逻辑
private final void addCount(long x, int check) {
    CounterCell[] as;
    long b, s;

    // ① 尝试 CAS baseCount + 1
    if ((as = counterCells) != null ||
        !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
        // ② CAS 失败 → 用 CounterCell 分段累加
        //    随机挑一个 cell，CAS 累加
        //    竞争激烈 → 扩容 counterCells
        ...
    }
    // ③ 检查是否需要扩容（check >= 0 是 put 传进来的）
    if (check >= 0) { ... }
}
```

### 7.2 size() 的实现

```java
public int size() {
    long n = sumCount();   // baseCount + 所有 CounterCell 之和
    // 注意：这是"近似值"，并发下可能不精确（弱一致性）
}
```

---

## 8. 树化阈值设计（为什么是 8 / 6 / 64）

```
TREEIFY_THRESHOLD = 8   链表 → 红黑树
UNTREEIFY_THRESHOLD = 6 红黑树 → 链表
MIN_TREEIFY_CAPACITY = 64  最小树化容量

为什么链表 ≥ 8 才树化？
  JDK 官方注释：哈希冲突符合泊松分布（Poisson Distribution）
  当负载因子 0.75 时：
    桶中 8 个节点的概率 ≈ 0.00000006（亿分之六，6×10⁻⁸）
  → 链表到 8 个几乎不可能（正常哈希下）
  → 一旦出现 → 说明哈希函数出问题或恶意攻击（哈希碰撞攻击）
  → 用红黑树兜底，避免退化为 O(n)

为什么退化阈值是 6 而不是 8？
  防止"抖振"（震荡）：如果阈值相同
  → 8 树化 → 删到 7 退化 → 加回 8 又树化 → 反复开销
  → 6 和 8 之间留出缓冲带（7 保持现状）

为什么树化要求容量 ≥ 64？
  如果容量小（如 16），表本身应该扩容而不是树化
  → 容量 < 64 时，链表 ≥ 8 → 先扩容（容量翻倍，链表自然分散）
```

---

## 9. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：复合操作不是原子的

```java
// ❌ 先检查后操作 → 两个操作之间有竞争窗口
if (!map.containsKey(key)) {
    map.put(key, value);   // 可能被其他线程插入
}

// ✅ 用原子方法
map.putIfAbsent(key, value);       // 原子：不存在才放
map.computeIfAbsent(key, k -> create(k));  // 原子：计算并放入
map.compute(key, (k, v) -> merge(k, v));   // 原子：合并
map.merge(key, value, (v1, v2) -> combine(v1, v2));  // 原子：合并
```

### 🕳️ 坑 2：key/value 不能为 null

```java
// ❌ NPE！CHM 不允许 null（所有公开方法，get 也不例外）
map.put(null, "v");    // NullPointerException
map.put("k", null);    // NullPointerException
map.get(null);         // NullPointerException（内部无条件调用 key.hashCode()）

// 与 HashMap 的区别：HashMap.get(null) 有 key==null 特判（hash 取 0），CHM 没有
// 原因：并发下 get 返回 null 无法区分"不存在"和"值为 null"（见 4.3）
// 验证：jdk8-lab/ChmVerifyTest（JDK 8）、主工程 ChmRecursiveUpdateTest（重构版）
```

### 🕳️ 坑 3：不要用 size() 做精确控制

```java
// ❌ size() 是弱一致性的近似值，并发下不精确
if (map.size() == 100) { ... }   // 可能不是 100

// ✅ 用累加器自己维护计数，或接受"近似"语义
```

### 🕳️ 坑 4：computeIfAbsent 里的递归调用

```java
// ❌ mapping 函数内回写同一个 key（不是"递归调用"，是"重入同一个桶"）
map.computeIfAbsent("k", k -> map.put("k", "v"));   // 危险：回写路径
map.computeIfAbsent("k", k -> map.get("k"));        // 也不对：两个版本都静默返回 null

// JDK 8：computeIfAbsent 用 ReservationNode 占位（hash = -3）
//   get 同一 key → 占位节点 find 返回 null → 函数返回 null → 不存入、不异常（静默错误）
//   put/compute 同一 key → putVal 只识别 MOVED，遇到占位节点无事发生 → 死循环挂起
// JDK 9+（含 JDK 17）：回写路径增加 RESERVED 检测 → 直接抛
//   IllegalStateException("Recursive update")；get 路径仍静默返回 null
// → 规则：mapping 函数内只能读外部数据，绝不能回写同一个 map
// → 验证：jdk8-lab/ChmVerifyTest（JDK 8）、主工程 ChmRecursiveUpdateTest（重构版）
```

### 🕳️ 坑 5：遍历时的弱一致性

```java
// CHM 的迭代器是弱一致的：
// 迭代期间 put 的数据可能看不到
// 迭代期间删除的数据可能还在
// 不抛 ConcurrentModificationException（与 HashMap 不同）
// → 不适合"必须看到最新数据"的场景
```

---

## 10. 面试高频考点

1. **JDK 8 CHM 为什么用 synchronized 替代 JDK 7 的 Segment？**
   → 锁粒度更细（桶级 vs 段级）、不需要额外锁对象、JDK 6+ 的 synchronized 已优化、代码更简单。

2. **putVal 全流程？**
   → null 检查 → spread → 初始化/空桶 CAS 插入/ForwardingNode 帮扩容/锁头节点链表或树插入 → 树化判断 → addCount。

3. **get 为什么不加锁？**
   → Node 的 val 和 next 是 volatile，读线程可见写线程的最新修改；弱一致性设计。

4. **sizeCtl 的五种角色？**
   → 0 未初始化 / -1 初始化中 / 负数低 16 位扩容线程数（= 参与线程总数 + 1，回到 2 时最后一线程收尾）/ 正数扩容阈值 / 负数高 16 位扩容指纹（由当前容量推导，防止加入旧扩容）。

5. **多线程扩容怎么协作？**
   → stride 分桶 → CAS 认领任务 → ForwardingNode 标记 → 其他线程 helpTransfer → 最后线程收尾。

6. **为什么树化阈值是 8？退化是 6？**
   → 泊松分布：正常哈希下桶中 8 节点的概率约亿分之六；8/6 留缓冲避免抖振。

---

## 11. 实战练习

### 练习 1：统计单词出现次数（45 分钟）

```java
package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 练习 1：多线程统计单词出现次数（经典场景）
 *
 * 目标：
 * 1. 使用 compute/merge 原子操作
 * 2. 对比错误写法（containsKey+put）与正确写法
 */
public class WordCountTest {

    private static final String[] WORDS =
            {"java", "concurrent", "hashmap", "java", "thread", "concurrent",
             "lock", "java", "aqs", "thread", "concurrent", "hashmap"};

    @Test
    public void testCorrectWay() throws InterruptedException {
        ConcurrentHashMap<String, Integer> count = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(WORDS.length);

        for (String word : WORDS) {
            new Thread(() -> {
                // ✅ 原子操作：存在则 +1，不存在则 1
                count.merge(word, 1, Integer::sum);
                done.countDown();
            }, "t-" + word).start();
        }
        done.await();

        System.out.println("统计结果（正确写法）: " + count);
        System.out.println("java=" + count.get("java") + ", concurrent=" + count.get("concurrent"));
        System.out.println(count.get("java") == 3 ? "✅ 统计正确" : "❌ 统计错误");
    }

    @Test
    public void testWrongWay() throws InterruptedException {
        ConcurrentHashMap<String, Integer> count = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(WORDS.length);

        for (String word : WORDS) {
            new Thread(() -> {
                // ❌ 非原子操作：containsKey + put 之间有竞争窗口
                if (count.containsKey(word)) {
                    count.put(word, count.get(word) + 1);   // 丢失更新！
                } else {
                    count.put(word, 1);
                }
                done.countDown();
            }, "t-" + word).start();
        }
        done.await();

        System.out.println("统计结果（错误写法）: " + count);
        System.out.println("java=" + count.get("java") + "（预期 3，经常 < 3）");
        System.out.println(count.get("java") == 3 ? "（这次碰巧正确）" : "❌ 数据丢失（并发写覆盖）");
    }
}
```

### 练习 2：本地缓存 + 原子读取（45 分钟）

```java
package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 练习 2：基于 CHM 的本地缓存（computeIfAbsent 懒加载）
 *
 * 目标：理解 computeIfAbsent 只执行一次（并发安全）
 */
public class LocalCacheTest {

    static class LocalCache {
        private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        private final AtomicLong loadCount = new AtomicLong(0);

        // 模拟昂贵的数据加载（只应该执行一次）
        private String loadFromDB(String key) {
            try {
                Thread.sleep(50); // 模拟 DB 查询
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loadCount.incrementAndGet();
            return "value-" + key + "-" + System.nanoTime();
        }

        public String get(String key) {
            // ✅ 原子懒加载：并发下 loadFromDB 只执行一次
            return cache.computeIfAbsent(key, this::loadFromDB);
        }
    }

    @Test
    public void testLazyLoad() throws InterruptedException {
        LocalCache cache = new LocalCache();

        // 10 个线程同时 get 同一个 key
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                String v = cache.get("key1");
                System.out.println(Thread.currentThread().getName() + " 得到: " + v);
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("实际加载次数: " + cache.loadCount.get());
        System.out.println(cache.loadCount.get() == 1
                ? "✅ computeIfAbsent 并发下只加载了一次"
                : "❌ 加载了多次（不应该）");
    }
}
```

### 练习 3：并发读写 + size 观察（30 分钟）

```java
package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 练习 3：高并发读写，观察 size() 的弱一致性
 */
public class ConcurrencyTest {

    @Test
    public void testConcurrentReadWrite() throws InterruptedException {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        final int total = 50000;

        // 写线程
        Thread writer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                map.put(i, i);
            }
            System.out.println("写入完成，共 " + total + " 个");
        }, "writer");

        // 读线程
        Thread reader = new Thread(() -> {
            long sum = 0;
            for (int i = 0; i < total; i++) {
                Integer v = map.get(i);
                if (v != null) sum += v;   // 可能读到"稍旧"的数据（弱一致性）
            }
            System.out.println("读线程看到的总和: " + sum);
        }, "reader");

        writer.start();
        Thread.sleep(100); // 写一半时开始读
        reader.start();

        writer.join();
        reader.join();
        System.out.println("最终 size: " + map.size());
        System.out.println("✅ 高并发读写无异常（弱一致性是设计特性）");
    }
}
```

---

## 12. 自测题

1. **JDK 8 CHM 的 put 在哪些情况下不加锁？为什么能不加锁？**
   <details><summary>答案</summary>

   桶为空时：CAS 直接插入（CAS 本身是原子的，不需要锁）。桶非空时锁头节点。因为"空桶 CAS 插入"这个操作天然无竞争冲突（CAS 失败就重试），不需要悲观锁。
   </details>

2. **get 为什么不加锁？读到旧数据怎么办？**
   <details><summary>答案</summary>

   Node.val 和 next 是 volatile，保证可见性。读到旧数据是弱一致性的表现，但读到的一定是有效数据。适合缓存场景，不适合强一致场景。
   </details>

3. **扩容时 put 一个元素到已迁移的桶会发生什么？**
   <details><summary>答案</summary>

   发现头节点是 ForwardingNode（hash = MOVED）→ 调用 helpTransfer() 加入扩容帮忙，而不是直接插入（旧桶正在迁移，直接插入会丢失）。
   </details>

4. **链表拆分时，为什么不需要重新计算 hash？**
   <details><summary>答案</summary>

   旧桶 i 中的节点在新表中只可能去两个位置：i（hash & n == 0）或 i+n（hash & n != 0）。一次遍历按 `hash & 旧容量 n` 分成两条链即可。
   </details>

5. **为什么并发场景不能用 HashMap？**
   <details><summary>答案</summary>

   ① 并发 put 会数据覆盖/丢失；② JDK 7 扩容时头插法可能形成环形链表 → 死循环；③ 快速失败迭代器抛 ConcurrentModificationException。
   </details>

---

> 📬 **完成练习后，进入下一篇 [03-05-并发容器之其他容器](./03-05-并发容器之其他容器.md)（待发布）—— CopyOnWriteArrayList、BlockingQueue 家族、ConcurrentLinkedQueue**

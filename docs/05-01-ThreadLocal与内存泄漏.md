# 05-01 ThreadLocal 与内存泄漏

> **阶段五·第 1 篇** | 前置：[04-04-ForkJoin与工作窃取](./04-04-ForkJoin与工作窃取.md) | 后续：[05-02-单例模式与并发设计模式](./05-02-单例模式与并发设计模式.md)（待发布）  
> **建议时长**：5~6 小时（内部结构 2h + 内存泄漏 2h + 线程池场景 1h + 练习 1.5h）  
> 🛠️ **日常高频**：TraceId 传递、用户上下文、数据库事务——几乎每个项目都用，**内存泄漏是生产事故高发区**

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | 内部结构（Thread → ThreadLocalMap → Entry）、内存泄漏全链路分析、remove 的必要性、阿里规范 | **能画引用链图 + 能讲清泄漏成因** |
| ⭐⭐⭐ | Tomcat 线程池复用陷阱、InheritableThreadLocal 局限、TTL 解决的问题 | **必须理解（生产场景）** |
| ◈◈ | expungeStaleEntry 清理机制、开放定址法、典型应用（TraceId/事务） | **知道原理 + 会用** |
| ○ | ThreadLocal 源码细节（哈希、扩容）、跨线程传递的高级方案 | **了解即可** |

---

## 1. ThreadLocal 是什么

### 1.1 一句话定义

> **ThreadLocal 提供线程局部变量：每个线程都有自己的独立副本，互不干扰。**

```java
// 基本使用
ThreadLocal<Integer> counter = new ThreadLocal<>();

// 线程 A：counter = 1
counter.set(1);

// 线程 B：counter = 2（与 A 互不干扰！）
counter.set(2);

// 线程 A：get() 返回 1（拿自己的副本）
```

### 1.2 核心 API

| 方法 | 作用 |
|---|---|
| `set(value)` | 设置当前线程的副本值 |
| `get()` | 获取当前线程的副本值 |
| `remove()` | 移除当前线程的副本（**重要！**） |
| `initialValue()` | 首次 get 时初始化的值（可重写） |

### 1.3 典型应用场景（🛠️ 日常）

```
① 链路追踪：TraceId 贯穿整个请求
② 用户上下文：登录用户信息（UserContext）
③ Spring 事务：DataSourceTransactionManager 绑定连接
④ PageHelper：分页参数传递
⑤ SimpleDateFormat 线程安全（每线程一个实例）
```

---

## 2. 内部结构（核心）⭐

### 2.1 一个常见的误解

```
❌ 误解：ThreadLocal 对象内部存了 value

ThreadLocal 对象本身只是一个"钥匙"（key）！
真正的存储位置在：当前线程的 ThreadLocalMap 里
```

### 2.2 数据结构（务必能画）

```
每个 Thread 内部有一个 ThreadLocalMap：

┌─────────────────────────────────────────────┐
│ Thread 对象                                  │
│   ├── threadLocals: ThreadLocalMap ←───────┐ │
│   └── inheritableThreadLocals: ThreadLocalMap│ │
└─────────────────────────────────────────────┘ │
                                                │
┌───────────────────────────────────────────────┘
│ ThreadLocalMap（就是一个数组，开放定址法）        │
│  ┌───────┬───────┬───────┬───────┬────────┐  │
│  │ Entry │ Entry │ Entry │ Entry │ Entry  │  │
│  └───┬───┴───┬───┴───┬───┴───┬───┴───┬────┘  │
│      │       │       │       │       │       │
│  key=TL1  key=TL2  key=TL3  key=null  ...    │
│  value=v1  value=v2  value=v3  value=v4       │
│  ↑↑↑↑      ↑↑↑↑    ↑↑↑↑     ↑↑↑↑             │
│  WeakReference 弱引用指向 ThreadLocal 对象     │
└──────────────────────────────────────────────┘

关键点：
  Thread → ThreadLocalMap（强引用，线程持有）
  Entry.key → ThreadLocal（弱引用！）
  Entry.value → 业务数据（强引用！）
```

### 2.3 Entry 为什么用弱引用

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;   // 强引用业务数据

    Entry(ThreadLocal<?> k, Object v) {
        super(k);   // key 是 WeakReference（弱引用）
        value = v;
    }
}
```

```
弱引用的意义：
  如果 ThreadLocal 对象不再被外部引用（如局部变量用完）
  → GC 时可以回收 ThreadLocal 对象本身（key 变 null）
  → 避免 ThreadLocal 对象本身泄漏

但如果 key 是强引用：
  ThreadLocal 对象被 Thread → ThreadLocalMap → Entry.key 强引用
  → 即使业务代码不用了，ThreadLocal 对象也无法回收 → 泄漏
```

> 弱引用解决了"ThreadLocal 对象本身"的泄漏，但**没有解决"value"的泄漏**（见 §4）！

---

## 3. get/set 流程

### 3.1 set 流程

```java
public void set(T value) {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);   // 获取当前线程的 map
    if (map != null)
        map.set(this, value);         // 自己作为 key 存进去
    else
        createMap(t, value);          // 第一次：创建 map
}
```

### 3.2 get 流程

```java
public T get() {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        ThreadLocalMap.Entry e = map.getEntry(this);
        if (e != null)
            return (T) e.value;       // 找到 → 返回 value
    }
    return setInitialValue();         // 没找到 → 初始化（initialValue）
}
```

### 3.3 ThreadLocalMap 的哈希

```
ThreadLocalMap 用"开放定址法"（线性探测）处理冲突：

  index = threadLocalHashCode & (table.length - 1)
  冲突时：index+1 继续找（线性探测）

对比 HashMap：
  HashMap 用"链地址法"（链表/红黑树）
  ThreadLocalMap 用"开放定址法"（线性探测）
  → 因为 ThreadLocalMap 的 Entry 数量少（每个线程只有少量 ThreadLocal）
  → 线性探测简单高效
```

### 3.4 expungeStaleEntry —— 惰性清理

```java
// 清理"过期 Entry"（key 已为 null 的）
// 在 get/set 过程中顺带清理（不是主动清理）

private int expungeStaleEntry(int staleSlot) {
    Entry[] tab = table;
    int len = tab.length;

    // ① 清空过期位置的 value（帮助 GC 回收）
    tab[staleSlot].value = null;
    tab[staleSlot] = null;

    // ② 线性探测：把后面被"挤偏"的 Entry 重新哈希
    //    （删除一个元素后，原本冲突后移的元素要回填）
    Entry e;
    int i;
    for (i = nextIndex(staleSlot, len);
         (e = tab[i]) != null;
         i = nextIndex(i, len)) {
        ThreadLocal<?> k = e.get();
        if (k == null) {
            e.value = null;   // 顺带清理其他过期 Entry
            tab[i] = null;
        } else {
            int h = k.threadLocalHashCode & (len - 1);
            if (h != i) {
                tab[i] = null;
                while (tab[h] != null)
                    h = nextIndex(h, len);
                tab[h] = e;
            }
        }
    }
    return i;
}
```

```
清理的时机（都是"惰性"的）：
  get() 时：找不到自己的 Entry → 触发清理
  set() 时：哈希冲突 → 触发清理
  remove() 时：主动清理（唯一主动的方式）

→ 不主动 remove() → 清理依赖后续 get/set 操作
→ 如果线程长期空闲 → 永远不清理 → 泄漏持续
```

---

## 4. 内存泄漏全链路分析（核心）⭐

### 4.1 泄漏场景

```
场景：线程池 + ThreadLocal（生产最常见的泄漏组合）

线程池中的线程是"长期存活"的（核心线程不回收）
  → Thread 对象一直存在（强引用链不断）

引用链（泄漏的根源）：
  Thread（存活，线程池持有）
    → ThreadLocalMap（强引用）
      → Entry.value（强引用 ← 泄漏点！）
        → 业务数据（如用户对象、大集合）永远无法回收！

key（ThreadLocal 对象）虽然是弱引用可能被回收
  但 value 是强引用 → value 不会回收
  → 每次请求都 set 一个新值 → 旧 value 残留
  → 内存持续增长 → OOM！
```

### 4.2 两种泄漏情况

```
情况 1：ThreadLocal 对象本身泄漏（弱引用已解决）
  ThreadLocal 不被外部引用 → key 变 null → 对象可回收 ✅

情况 2：value 泄漏（弱引用解决不了！）
  key 虽然变 null，但 value 被 Entry 强引用
  Thread → map → Entry → value 的强引用链不断
  → 在触发清理（get/set/remove 或线程结束）之前，value 一直残留 ❌
  → 残留的 value 只能靠 expungeStaleEntry 惰性清理，无法被 GC 主动回收

情况 3：ThreadLocal 对象一直被引用 + 线程长期存活
  ThreadLocal 是 static（全局引用，永不回收）
  → key 永远有效 → Entry 永远不被清理
  → 每次 set 新值 → 旧 value 残留（被覆盖才释放）
  → 线程池场景 → 线程不回收 → 每个线程残留一份
```

### 4.3 引用链图（务必能画）

```
✅ 正常场景（无线程池）：

  主线程（用完结束）
    └─ ThreadLocalMap → Entry(key, value)
  → 线程结束 → Thread 被回收 → 整个 map 一起回收 ✅

❌ 泄漏场景（线程池 + 不 remove）：

  线程池线程（长期存活）
    └─ ThreadLocalMap（强引用，随线程存活）
         └─ Entry
              ├─ key: ThreadLocal（弱引用，可能变 null）
              └─ value: 业务数据（强引用 ← 泄漏！）
  → 线程不回收 → value 永不回收 ❌

❌ 泄漏场景（static ThreadLocal + 线程池）：

  static ThreadLocal（类加载，永不回收）
    └─ 每个线程池线程的 map 里都有一个 Entry
         └─ value 强引用
  → N 个线程 = N 份残留数据 ❌
```

### 4.4 阿里规范（必背）

```
【强制】ThreadLocal 必须 remove：

  ThreadLocal<String> TL = new ThreadLocal<>();
  try {
      TL.set(value);      // 使用
      ...
  } finally {
      TL.remove();        // ★ 必须 finally 中 remove！
  }

原因：
  线程池线程复用 → 线程不销毁 → ThreadLocalMap 不清理
  → 不 remove → value 残留 → 内存泄漏
```

---

## 5. Tomcat 线程池陷阱（🛠️ 生产必知）

### 5.1 问题场景

```
Tomcat 处理 HTTP 请求用的也是线程池！

每个请求 → Tomcat 线程池分配一个线程处理

Web 应用里常见的 ThreadLocal 用法：
  请求开始时：UserContext.set(用户信息) / TraceId.set(请求ID)
  请求结束：忘记 remove！

→ 线程回到线程池复用
→ 下一个请求复用同一个线程 → 拿到上一个请求的残留数据！
→ 两个后果：
   ① 数据串号（A 用户看到 B 用户的数据！安全漏洞）
   ② 内存泄漏（每次请求残留一份）
```

### 5.2 数据串号（比泄漏更可怕）

```java
// 请求 1（线程 T1）：UserContext.set("张三")
// 请求 2（线程 T1 复用！）：UserContext.get() → "张三"？！
// → 请求 2 的代码没 set 或 set 晚于 get → 拿到请求 1 的残留

// 经典事故：
// 用户 A 请求 → 设置用户上下文 → 忘记 remove
// 用户 B 请求（同一线程）→ 某处 get() → 拿到用户 A 的信息 → 越权！
```

### 5.3 解决方案

```
① 规范：try-finally + remove（根本方案）
  每个使用 ThreadLocal 的地方都必须清理

② 框架级拦截器（Spring）：
  HandlerInterceptor 的 afterCompletion 中统一清理
  或 Filter 的 finally 中清理

③ 注意：Spring Security、Shiro 等框架内部也用 ThreadLocal
  它们的清理由框架负责，但我们自己 set 的要自己清
```

---

## 6. InheritableThreadLocal 与 TTL

### 6.1 InheritableThreadLocal —— 父子线程传递

```java
// 普通 ThreadLocal：子线程拿不到父线程的值
ThreadLocal<String> tl = new ThreadLocal<>();
tl.set("父线程值");
new Thread(() -> System.out.println(tl.get())).start();
// 输出：null（子线程拿不到！）

// InheritableThreadLocal：子线程可以继承父线程的值
InheritableThreadLocal<String> itl = new InheritableThreadLocal<>();
itl.set("父线程值");
new Thread(() -> System.out.println(itl.get())).start();
// 输出：父线程值（new Thread 时复制父线程的 map）
```

```
原理：new Thread() 时，把父线程的 inheritableThreadLocals 复制一份给子线程
→ 只在新线程创建的那一刻复制
→ 复制后父子线程各自独立（互不影响）
```

### 6.2 线程池场景失效

```java
// ❌ 线程池 + InheritableThreadLocal → 失效！
ThreadPoolExecutor pool = ...;
InheritableThreadLocal<String> itl = new InheritableThreadLocal<>();
itl.set("任务A的上下文");

pool.execute(() -> doWork());   // 第一次：线程池创建新线程 → 复制 ✅
pool.execute(() -> doWork2());  // 第二次：复用线程 → 不复制 ❌
// → 任务 A、B 用的是同一个线程 → B 拿到 A 的上下文（串号！）

// 原因：InheritableThreadLocal 只在"创建线程"时复制
//       线程池复用线程 → 不创建 → 不复制 → 失效
```

### 6.3 TTL（TransmittableThreadLocal）—— 阿里的解决

```java
// 阿里开源：解决线程池场景的上下文传递
// https://github.com/alibaba/transmittable-thread-local

// 用法：
TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
ttl.set("上下文");

// 提交任务时包装（推荐方式）：
TtlRunnable wrapped = TtlRunnable.get(() -> doWork());
executor.execute(wrapped);   // 任务执行时自动携带父线程的上下文

// 或使用 TtlExecutors 包装线程池（无侵入）：
ExecutorService ttlExecutor = TtlExecutors.getTtlExecutorService(executor);
ttlExecutor.execute(() -> doWork());
```

```
TTL 解决的问题：
  线程池复用 → InheritableThreadLocal 失效
  → TTL 在"任务提交时"捕获父线程的上下文
  → "任务执行前"恢复上下文 → "任务执行后"清理
  → 每个任务都有正确的上下文，不串号
```

---

## 7. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：忘记 remove（最常见）

```java
// ❌ 只 set 不 remove → 线程池场景必泄漏
public void handleRequest(Request req) {
    UserContext.set(req.getUser());   // 设置上下文
    doBusiness();
    // 忘了 remove！
}

// ✅ try-finally 铁律
public void handleRequest(Request req) {
    UserContext.set(req.getUser());
    try {
        doBusiness();
    } finally {
        UserContext.remove();   // ★ 必须
    }
}
```

### 🕳️ 坑 2：remove 放错位置

```java
// ❌ remove 在 finally 外面 → 异常时不执行
try {
    UserContext.set(user);
    doBusiness();
    UserContext.remove();   // doBusiness 抛异常 → 不执行！
} catch (Exception e) { ... }

// ✅ 必须 finally
try {
    UserContext.set(user);
    doBusiness();
} finally {
    UserContext.remove();
}
```

### 🕳️ 坑 3：static ThreadLocal 的误用

```java
// ❌ static 但用 set 存"每个请求不同"的数据 → 泄漏 + 串号
// static 只代表"这个 ThreadLocal 是全局的钥匙"（这是正常的）
// 关键：value 必须随请求清理！

// ✅ static ThreadLocal 本身没问题（作为 key）
//    问题只在 value 是否清理
private static ThreadLocal<UserContext> context = new ThreadLocal<>();  // OK
// 但使用后必须 remove
```

### 🕳️ 坑 4：在 finally 里 remove 后再使用

```java
// ❌ 先 remove 再 get → 返回 null/初始值
try {
    doBusiness();
} finally {
    UserContext.remove();
    UserContext.get();   // 已清除 → 拿不到
}
```

### 🕳️ 坑 5：存了线程不安全对象

```java
// ❌ 错误：把"共享的同一个实例"存进 ThreadLocal
// 所有线程 get 到的是同一个 SimpleDateFormat → 依然线程不安全！
private static final SimpleDateFormat SHARED_SDF = new SimpleDateFormat();
ThreadLocal<SimpleDateFormat> tl = new ThreadLocal<>();
tl.set(SHARED_SDF);   // 所有线程共享同一实例 → 不安全！

// ✅ 正确：withInitial 每个线程独立创建实例
ThreadLocal<SimpleDateFormat> tl = ThreadLocal.withInitial(SimpleDateFormat::new);
// 每个线程首次 get 时各创建一个实例 → 线程安全
```

### 🕳️ 坑 6：内存泄漏排查（jmap 实战）

```bash
# 排查思路：
# ① 堆内存持续增长
jmap -histo <pid> | head -30
# 观察是否有大量业务对象无法回收

# ② 定位 ThreadLocalMap 的持有者
jmap -dump:format=b,file=heap.bin <pid>
# 用 MAT 分析：找 ThreadLocalMap → 看 value 是什么 → 反查代码

# ③ 检查线程池线程数量
jstack <pid> | grep "pool-" | wc -l
```

---

## 8. 面试高频考点

1. **ThreadLocal 的内部结构？**
   → Thread 持有 ThreadLocalMap，ThreadLocal 只是 key（弱引用），value 存 map 里。key 弱引用 + value 强引用。

2. **ThreadLocal 为什么会内存泄漏？**
   → 线程池线程长期存活 → Thread → ThreadLocalMap → Entry.value 强引用链不断 → value 无法回收。key 弱引用解决不了 value 的泄漏。

3. **为什么 Entry 的 key 用弱引用？**
   → 让 ThreadLocal 对象本身在无外部引用时可回收，避免 ThreadLocal 对象泄漏。

4. **为什么要 remove？放在哪里？**
   → 线程池复用线程 → 线程不销毁 → map 不清理 → value 残留。必须放 finally。

5. **InheritableThreadLocal 为什么在线程池里失效？**
   → 它只在 new Thread() 时复制父线程的值，线程池复用线程不复制。

---

## 9. 实战练习

### 练习 1：验证线程池 + ThreadLocal 泄漏（60 分钟）★必做

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

/**
 * 练习 1：验证线程池场景下 ThreadLocal 的 value 残留（泄漏）
 *
 * 实验设计：
 * 1. 固定线程池（1 个线程）+ ThreadLocal
 * 2. 提交多个任务，每个任务 set 不同数据但不 remove
 * 3. 观察：第二个任务能读到第一个任务残留的 value（串号！）
 */
public class ThreadLocalLeakTest {

    private static final ThreadLocal<String> context = new ThreadLocal<>();

    @Test
    public void testLeakInPool() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> { Thread t = new Thread(r); t.setName("leak-worker"); return t; },
                new ThreadPoolExecutor.AbortPolicy());

        // 任务 1：设置上下文，但不 remove（模拟忘记）
        pool.execute(() -> {
            context.set("任务1的用户");
            System.out.println("任务 1 设置: " + context.get());
            // 忘记 remove！
        });

        Thread.sleep(200);

        // 任务 2：没有 set，直接 get
        pool.execute(() -> {
            String value = context.get();
            System.out.println("任务 2 读到: " + value);
            System.out.println("（任务 2 没有 set，却读到了任务 1 的值 → 串号！）");
            context.remove();   // 修复示范
        });

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 演示完成：不 remove 会导致数据串号 + 泄漏");
    }

    @Test
    public void testRemoveFix() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> { Thread t = new Thread(r); t.setName("fix-worker"); return t; },
                new ThreadPoolExecutor.AbortPolicy());

        // 正确写法：try-finally + remove
        pool.execute(() -> {
            context.set("任务A");
            try {
                System.out.println("任务 A: " + context.get());
            } finally {
                context.remove();
            }
        });

        Thread.sleep(200);

        pool.execute(() -> {
            String value = context.get();
            System.out.println("任务 B 读到: " + value + "（null = 没有串号 ✅）");
            if (value == null) {
                System.out.println("✅ remove 生效：任务 B 拿不到任务 A 的残留");
            }
        });

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

### 练习 2：内存泄漏观察（30 分钟）

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：观察 ThreadLocal value 无法被 GC 回收
 *
 * 实验：key 是弱引用可回收，但 value 被 ThreadLocalMap 强引用不可回收，
 *       直到 remove() 或线程结束
 */
public class ThreadLocalGCTest {

    @Test
    public void testValueNotCollected() throws InterruptedException {
        // 大对象（模拟泄漏的 value）
        byte[] bigData = new byte[1024 * 1024 * 10];   // 10MB

        ThreadLocal<byte[]> tl = new ThreadLocal<>();
        tl.set(bigData);
        bigData = null;    // 外部引用断开

        // 提示 GC
        System.gc();
        Thread.sleep(1000);

        // ThreadLocal 对象没有外部引用 → 弱引用 key 可回收
        // 但 value（10MB）被 ThreadLocalMap 强引用 → 无法回收
        // 直到调用 remove() 或线程结束

        tl.remove();   // 主动清理后 value 才可回收
        System.gc();
        Thread.sleep(1000);
        System.out.println("✅ remove 后 value 才被释放（观察内存变化）");
    }
}
```

### 练习 3：TraceId 链路传递实战（45 分钟）★必做

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * 练习 3：用 ThreadLocal 实现 TraceId 链路追踪（真实场景）
 *
 * 场景：请求进来 → 生成 TraceId → 后续所有日志带上 TraceId
 */
public class TraceIdTest {

    // TraceId 上下文（static 作为全局钥匙）
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    // 模拟：设置 TraceId（通常由 Filter/Interceptor 在请求入口调用）
    private static void setTraceId() {
        TRACE_ID.set(UUID.randomUUID().toString().substring(0, 8));
    }

    // 模拟：业务日志（带上当前线程的 TraceId）
    private static void log(String msg) {
        System.out.println("[" + TRACE_ID.get() + "] " + msg);
    }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> { Thread t = new Thread(r); t.setName("biz-worker"); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Test
    public void testTraceId() throws InterruptedException {
        // 模拟两个并发请求
        for (int i = 1; i <= 3; i++) {
            final int reqId = i;
            new Thread(() -> {
                setTraceId();          // ① 请求入口生成 TraceId
                log("请求 " + reqId + " 开始");

                // ② 异步任务（线程池）—— 这里 TraceId 会丢失！
                executor.execute(() -> {
                    // ❌ 线程池线程没有 TRACE_ID → 日志无 TraceId
                    log("异步处理请求 " + reqId + "（注意：TraceId 丢失了！）");
                    // ✅ 正确做法：任务提交前捕获，执行时恢复（TTL 的原理）
                });

                try { Thread.sleep(50); } catch (InterruptedException e) { }
                log("请求 " + reqId + " 结束");
                TRACE_ID.remove();     // ③ 请求结束清理
            }, "req-" + i).start();
        }

        Thread.sleep(1000);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 演示完成：线程池场景 TraceId 丢失（这就是 TTL 存在的意义）");
    }
}
```

---

## 10. 自测题

1. **ThreadLocal 的内部结构？value 存在哪里？**
   <details><summary>答案</summary>

   value 存在"当前线程"的 ThreadLocalMap 里（Thread → threadLocals）。ThreadLocal 对象只是 key（弱引用）。一个线程可以有多个 ThreadLocal，各自占 map 的一个 Entry。
   </details>

2. **内存泄漏的完整引用链？弱引用解决了什么？没解决什么？**
   <details><summary>答案</summary>

   链：Thread（存活）→ ThreadLocalMap → Entry → value（强引用）。弱引用解决了 ThreadLocal 对象本身的回收（key 变 null）；没解决 value 的回收（value 强引用，线程不销毁就不回收）。
   </details>

3. **为什么线程池场景泄漏最严重？**
   <details><summary>答案</summary>

   线程池核心线程长期存活 → Thread 对象永不销毁 → ThreadLocalMap 永不清理 → value 永不回收。普通线程用完就死，map 随线程一起回收。
   </details>

4. **InheritableThreadLocal 在线程池为什么失效？TTL 怎么解决？**
   <details><summary>答案</summary>

   它只在 new Thread() 时复制父线程值，线程池复用不创建线程。TTL 在任务提交时捕获上下文、执行前恢复、执行后清理。
   </details>

5. **数据串号是什么？怎么发生？**
   <details><summary>答案</summary>

   线程池线程复用时，下一个请求读到上一个请求残留的 ThreadLocal 值 → 用户数据串号（越权风险）。根源是不 remove。解决：try-finally 强制清理 + 框架层拦截器兜底。
   </details>

---

> 📬 **完成练习后，进入下一篇 [05-02-单例模式与并发设计模式](./05-02-单例模式与并发设计模式.md)（待发布）—— DCL、静态内部类、枚举单例、生产者消费者、限流器**

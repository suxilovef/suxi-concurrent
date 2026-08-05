# 03-03 ReentrantReadWriteLock 与 StampedLock

> **阶段三·第 3 篇** | 前置：[03-02-ReentrantLock源码全解析](./03-02-ReentrantLock源码全解析.md) | 后续：[03-04-并发容器之ConcurrentHashMap](./03-04-并发容器之ConcurrentHashMap.md)（待发布）  
> **建议时长**：5~6 小时（读写锁 2.5h + StampedLock 1.5h + 选型 1h + 练习 1h）  
> 🛠️ **日常高频**：读多写少场景的性能利器

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | 读写锁设计思想（读读不互斥）、state 高低位拆分、写锁降级、StampedLock 乐观读/validate | **理解 + 能手写正确用法** |
| ◈◈ | 写者饥饿、HoldCounter、StampedLock vs ReadWriteLock 性能对比、四种锁选型 | **知道原理 + 会选型** |
| ○ | 读写锁的公平模式细节、StampedLock 源码内部结构 | **知道存在** |

---

## 1. 为什么要读写锁

### 1.1 问题

```
缓存场景：读操作 90%，写操作 10%

用 synchronized / ReentrantLock：
  读读也互斥 → 10 个读线程排队执行 → 吞吐量极低
  但读操作之间互相不影响！锁住它们没有意义！

→ 需要：读读并行、读写互斥、写写互斥
```

### 1.2 读写锁的互斥矩阵

| | 读 | 写 |
|---|---|---|
| **读** | ✅ 不互斥 | ❌ 互斥 |
| **写** | ❌ 互斥 | ❌ 互斥 |

```
读锁（共享锁）：多个线程可以同时持有
写锁（独占锁）：只有一个线程能持有
```

```java
public class Cache {
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final Map<String, Object> cache = new HashMap<>();

    public Object get(String key) {
        readLock.lock();   // 多个读者可同时进入
        try {
            return cache.get(key);
        } finally {
            readLock.unlock();
        }
    }

    public void put(String key, Object value) {
        writeLock.lock();  // 写者独占
        try {
            cache.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }
}
```

---

## 2. state 高低位拆分（核心）

### 2.1 一个 state 存两把锁

```
ReentrantReadWriteLock 的 state 是一个 int（32 位）：

┌────────────────────┬────────────────────┐
│  高 16 位：读锁计数  │  低 16 位：写锁计数  │
│  (sharedCount)      │  (exclusiveCount)  │
└────────────────────┴────────────────────┘

state = 0x0000_0003_0000_0002
        └─读锁3次─┘ └─写锁2次─┘
        （读锁计数上限 65535，写锁计数上限 65535）
```

### 2.2 提取两个计数的源码

```java
// 读锁计数：无符号右移 16 位
static int sharedCount(int c)    { return c >>> 16; }

// 写锁计数：低 16 位（& 0xFFFF）
static int exclusiveCount(int c) { return c & 0xFFFF; }
```

```
例子：
  state = 0x00010002
  sharedCount    = 0x00010002 >>> 16 = 1     （1 个读锁）
  exclusiveCount = 0x00010002 & 0xFFFF = 2   （2 次写锁重入）

  → 一个 int 同时表达了"读锁持有数"和"写锁持有数"
```

---

## 3. 读锁获取：tryAcquireShared

```java
protected final int tryAcquireShared(int unused) {
    Thread current = Thread.currentThread();
    int c = getState();

    // ① 写锁被占用且持有者不是自己 → 读锁获取失败
    if (exclusiveCount(c) != 0 &&
        getExclusiveOwnerThread() != current)
        return -1;

    // ② 写锁空闲（或自己持有写锁 → 写锁降级读锁）
    int r = sharedCount(c);
    if (!readerShouldBlock() &&          // 是否需要阻塞读（公平锁/写者优先）
        r < MAX_COUNT &&
        compareAndSetState(c, c + SHARED_UNIT)) {   // 高 16 位 +1
        // ③ 首次读锁：记录持有者（firstReader 等）
        // ④ 重复读锁：HoldCounter 计数 +1
        return 1;   // 获取成功
    }
    // ⑤ CAS 失败 → 循环重试
    return fullTryAcquireShared(current);
}
```

### 3.1 HoldCounter —— 记录每个线程的读锁重入

```java
// 读锁是可重入的！每个线程的重入次数怎么记？
// 用一个计数器对象 HoldCounter + ThreadLocal

static final class HoldCounter {
    int count = 0;             // 该线程读锁重入次数
    long tid = getThreadId(Thread.currentThread());
}

// ThreadLocal 存每个线程自己的 HoldCounter
private transient ThreadLocalHoldCounter readHolds;
```

> 为什么不能像写锁那样用 state 计数？  
> 写锁只有 1 个持有者 → state 低 16 位计数即可；  
> 读锁可以**多个线程同时持有** → 每个线程的重入次数必须各自记录 → ThreadLocal + HoldCounter。

---

## 4. 写锁获取：tryAcquire

```java
protected final boolean tryAcquire(int acquires) {
    Thread current = Thread.currentThread();
    int c = getState();
    int w = exclusiveCount(c);

    if (c != 0) {                          // ① 锁已被占用（读或写）
        if (w == 0 ||                      // 被读锁占用 → 写锁获取失败
            current != getExclusiveOwnerThread())  // 被其他线程写锁占用 → 失败
            return false;
        // ② 自己持有写锁 → 重入
        if (w + exclusiveCount(acquires) > MAX_COUNT)
            throw new Error("Maximum lock count exceeded");
        setState(c + acquires);
        return true;
    }

    // ③ 锁空闲
    if (writerShouldBlock() ||             // 公平锁检查队列
        !compareAndSetState(c, c + acquires))
        return false;
    setExclusiveOwnerThread(current);
    return true;
}
```

### 4.1 写者饥饿问题

```
读锁是共享的：只要有读者，写锁就永远获取不到？

readerShouldBlock() 的设计（非公平模式）：
  当队列头是写锁等待者时 → 新来的读锁请求被阻塞（排队）
  → 避免"读者源源不断进来，写者永远等不到"

公平模式：
  任何线程来了都先检查队列（hasQueuedPredecessors）
  → 先来先得，写者不会被插队
```

### 4.2 写锁降级为读锁（🛠️ 重要技巧）

```java
// 场景：读到了数据，要更新，更新完还想继续读（不想释放锁间隙被插队）

public void process() {
    writeLock.lock();       // ① 先获取写锁
    try {
        // 更新缓存
        cache.put(key, newValue);
        // ... 还要读其他数据

        readLock.lock();    // ② 获取读锁（此时允许！因为持有写锁）
        // ③ 释放写锁，降级为读锁
    } finally {
        writeLock.unlock(); // ④ 释放写锁（降级完成）
    }

    // ⑤ 现在只持有读锁，可以安全读
    readLock.lock();
    try {
        Object v = cache.get(otherKey);  // 读期间写锁不会进来
    } finally {
        readLock.unlock();
    }
}
```

```
为什么需要降级：
  更新完数据后，如果不降级直接释放写锁：
    写锁释放 → 其他线程立刻抢到写锁修改数据 → 我再读就是脏数据
  降级后：
    我持有读锁 → 其他写者进不来 → 读到的数据是连续的

为什么不能升级（读锁 → 写锁）：
  读锁可以被多个线程持有 → 每个读者都想升级成写锁
  → 互相等待对方释放读锁 → 死锁！
  → JDK 明确不支持读锁升级为写锁
```

---

## 5. ReentrantReadWriteLock 的局限

| 局限 | 说明 |
|---|---|
| 写锁饥饿 | 读多写少场景，写锁可能长时间等不到（已通过 readerShouldBlock 缓解） |
| 读锁不可升级 | 升级会死锁，只能降级 |
| 双向互斥 | 读写互斥，无法"无锁读" |
| 计数上限 | 65535 次（读/写各 16 位），一般不会触顶 |

> → 这些局限正是 StampedLock 的优化动机！

---

## 6. StampedLock（JDK 8+）

### 6.1 三种模式

```
StampedLock 提供三种访问模式：

1. 写锁（writeLock）：独占，和 ReadWriteLock 写锁一样
2. 悲观读锁（readLock）：共享，和 ReadWriteLock 读锁一样
3. 乐观读（tryOptimisticRead）：★ 不锁！直接读！

核心卖点：乐观读 —— 读取期间不加锁，最后校验（validate）
```

### 6.2 乐观读的使用范式

```java
public class Point {
    private double x, y;
    private final StampedLock lock = new StampedLock();

    public double distanceFromOrigin() {
        // ① 乐观读：不锁，拿到一个"戳"
        long stamp = lock.tryOptimisticRead();

        // ② 直接读（不加锁！）
        double currentX = x;
        double currentY = y;

        // ③ 校验：读取期间有没有被写
        if (!lock.validate(stamp)) {
            // ④ 被写了 → 升级为悲观读锁重新读
            stamp = lock.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return Math.sqrt(currentX * currentX + currentY * currentY);
    }
}
```

```
乐观读的取舍：
  ✅ 无锁读：读期间完全没有锁开销（比读锁更快）
  ✅ 读多写少场景：绝大多数读取都是乐观读命中
  ❌ 代价：读到一半发现被写 → 需要重新读（validate 失败）
  ❌ 如果写入频繁 → 频繁重读 → 比普通读锁还慢
```

### 6.3 写锁使用

```java
public void move(double deltaX, double deltaY) {
    long stamp = lock.writeLock();
    try {
        x += deltaX;
        y += deltaY;
    } finally {
        lock.unlockWrite(stamp);
    }
}
```

### 6.4 锁升级：乐观读 → 读锁 / 写锁

```java
// 乐观读 → 悲观读（上面已展示）
// 乐观读 → 写锁（使用场景：读后需要修改）
long stamp = lock.tryOptimisticRead();
double currentX = x;
if (!lock.validate(stamp)) {
    stamp = lock.writeLock();   // 乐观读失败 → 直接升级为写锁
    try {
        currentX = x;
    } finally {
        lock.unlockWrite(stamp);
    }
}
```

### 6.5 StampedLock 的坑（⚠️ 重要）

```
坑 1：不可重入！
  StampedLock 不支持重入 → 同一线程不能重复获取 → 注意递归

坑 2：中断不友好！
  lock() / lockInterruptibly() 会抛 UnsupportedOperationException？
  不，stampedLock 没有 lockInterruptibly；
  中断时 park 被打断会报错（JDK 8 有 bug，JDK 9 修复）

坑 3：锁的"戳"（stamp）必须配对释放！
  unlockWrite(stamp) / unlockRead(stamp) 必须传回获取时的 stamp
  传错 stamp → IllegalMonitorStateException

坑 4：性能陷阱
  乐观读适用于"写很少"的场景
  写多场景 validate 频繁失败 → 重读 → 比 ReadWriteLock 更慢
```

### 6.6 性能对比

```
写多读少：synchronized / ReentrantLock 足够（写锁本身开销最小）
读多写少：
  ReadWriteLock：读写都加锁（读锁有锁开销）
  StampedLock：乐观读无锁（性能最佳）
  但 StampedLock 不可重入 + 不支持 Condition → 受限场景

结论（实测经验）：
  读占比 > 90% 且写不频繁 → StampedLock 乐观读胜出
  读写交替 → ReadWriteLock 更稳
  有重入需求 → 不能用 StampedLock
```

---

## 7. 四种锁选型决策（🛠️ 面试常考）

| 场景 | 推荐 | 原因 |
|---|---|---|
| 简单互斥，临界区短 | `synchronized` | 简单、自动释放、JIT 优化 |
| 需要 tryLock/中断/多 Condition | `ReentrantLock` | 功能最全 |
| 读多写少，需要公平性 | `ReentrantReadWriteLock` | 读读并行 |
| 读多写少，追求极致性能 | `StampedLock` | 乐观读无锁（JDK 8+） |
| 无并发（单线程） | 什么锁都不用 | 锁消除会帮你 |

```
选型决策树：

需要多线程协作（Condition）? ────是──→ ReentrantLock
        │否
需要读读并行? ───────────────────否──→ synchronized
        │是
有重入需求? ─────────────────────是──→ ReentrantReadWriteLock
        │否
写非常少 + 追求性能? ────────────是──→ StampedLock
        │否
─────────────→ ReentrantReadWriteLock
```

---

## 8. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：读锁忘记在 finally 释放

```java
// ❌ 读锁不释放 → 写锁永远获取不到 → 整个服务卡死
readLock.lock();
Object v = cache.get(key);
return v;   // 忘了解锁！

// ✅ 铁律：所有锁都必须 try-finally 释放
readLock.lock();
try {
    return cache.get(key);
} finally {
    readLock.unlock();
}
```

### 🕳️ 坑 2：锁降级顺序写错

```java
// ❌ 错误：先释放写锁再获取读锁（不是降级！中间有竞争窗口）
writeLock.lock();
try {
    update();
    writeLock.unlock();     // 先释放写锁
    readLock.lock();        // 再获取读锁 → 其他线程可能已写
} finally {
    readLock.unlock();
}

// ✅ 正确：先获取读锁，再释放写锁（保持连续性）
writeLock.lock();
try {
    update();
    readLock.lock();        // 先获取读锁
} finally {
    writeLock.unlock();     // 再释放写锁（降级完成）
}
```

### 🕳️ 坑 3：读锁升级为写锁 → 死锁

```java
// ❌ 不支持！两个线程都持有读锁都想升级 → 互相等待 → 死锁
readLock.lock();
try {
    // 需要写 → 获取写锁 → 死锁！
    writeLock.lock();
} finally {
    readLock.unlock();
}
```

### 🕳️ 坑 4：StampedLock 的 stamp 传错

```java
// ❌ stamp 不匹配 → IllegalMonitorStateException
long stamp = lock.writeLock();
try {
    doSomething();
} finally {
    lock.unlockRead(stamp);  // 错误！写锁必须用 unlockWrite
}
```

### 🕳️ 坑 5：读多写少 ≠ 一定要用读写锁

```java
// 如果临界区极短（几条指令），普通锁的开销可能比读写锁还小
// 因为读写锁的读写切换有额外开销（读锁也要 CAS）
// → 先用 JMH 压测再选型，别盲目"优化"
```

---

## 9. 面试高频考点

1. **读写锁的 state 怎么同时存读锁和写锁计数？**
   → 高 16 位读锁计数（sharedCount = c >>> 16），低 16 位写锁计数（exclusiveCount = c & 0xFFFF）。

2. **读锁为什么用 HoldCounter + ThreadLocal 记录重入，而不是 state？**
   → 写锁只有一个持有者，state 够用；读锁有多个线程同时持有，每个线程的重入次数必须分开记录。

3. **什么是写锁降级？为什么要降级？**
   → 持有写锁时获取读锁，再释放写锁。为了在读操作期间保持数据连续性（防止其他写者插队修改）。

4. **为什么读锁不能升级为写锁？**
   → 多个线程持有读锁时都想升级 → 互相等待对方释放读锁 → 死锁。

5. **StampedLock 乐观读的原理和适用场景？**
   → tryOptimisticRead 不锁直接读，结束时 validate 校验是否被写。读多写少场景性能最优，写多场景频繁重读反而更慢。

---

## 10. 实战练习

### 练习 1：读写锁实现缓存（45 分钟）

```java
package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 练习 1：用 ReentrantReadWriteLock 实现线程安全缓存
 *
 * 目标：
 * 1. 验证读读并行（多个读者同时进入）
 * 2. 验证读写互斥
 * 3. 验证读锁重入
 */
public class RwLockCacheTest {

    static class Cache {
        private final Map<String, String> data = new HashMap<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();

        public String get(String key) {
            readLock.lock();
            try {
                return data.get(key);
            } finally {
                readLock.unlock();
            }
        }

        public void put(String key, String value) {
            writeLock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 写入 " + key);
                Thread.sleep(100); // 放大写操作窗口
                data.put(key, value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeLock.unlock();
            }
        }
    }

    @Test
    public void testCache() throws InterruptedException {
        Cache cache = new Cache();

        // 3 个读者并发读
        Thread[] readers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            readers[i] = new Thread(() -> {
                for (int j = 0; j < 3; j++) {
                    String v = cache.get("key1");
                    System.out.println(Thread.currentThread().getName() + " 读到: " + v);
                }
            }, "reader-" + i);
            readers[i].start();
        }

        // 1 个写者
        Thread writer = new Thread(() -> {
            for (int j = 0; j < 3; j++) {
                cache.put("key1", "value-" + j);
            }
        }, "writer");
        writer.start();

        for (Thread t : readers) t.join();
        writer.join();
        System.out.println("✅ 读写锁缓存运行完成");
    }
}
```

### 练习 2：写锁降级验证（30 分钟）

```java
package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 练习 2：验证写锁降级为读锁的正确姿势
 */
public class LockDowngradeTest {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private volatile String value = "init";

    @Test
    public void testDowngrade() {
        writeLock.lock();
        try {
            value = "updated";
            System.out.println("写锁中更新 value = " + value);

            // 降级：先获取读锁
            readLock.lock();
            System.out.println("获取读锁成功（写锁降级）");
        } finally {
            writeLock.unlock(); // 释放写锁，此时只持有读锁
        }

        try {
            // 现在只持有读锁
            System.out.println("读锁中读取 value = " + value);
            // 验证此时不能获取写锁（会被其他线程占住）
        } finally {
            readLock.unlock();
        }
        System.out.println("✅ 写锁降级成功");
    }

    @Test
    public void testUpgradeNotAllowed() {
        readLock.lock();
        try {
            System.out.println("持有读锁，尝试升级为写锁...");
            // 如果在这里调 writeLock.lock() → 永远阻塞（死锁）
            // 所以注释掉，避免测试卡住
            System.out.println("读锁升级写锁不受支持（会死锁，已跳过演示）");
        } finally {
            readLock.unlock();
        }
    }
}
```

### 练习 3：StampedLock 乐观读实战（45 分钟）

```java
package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;
import java.util.concurrent.locks.StampedLock;

/**
 * 练习 3：StampedLock 乐观读 + 写锁
 */
public class StampedLockTest {

    static class Point {
        private double x, y;
        private final StampedLock lock = new StampedLock();

        // 写操作
        public void move(double dx, double dy) {
            long stamp = lock.writeLock();
            try {
                x += dx;
                y += dy;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        // 乐观读
        public double distanceFromOrigin() {
            long stamp = lock.tryOptimisticRead();   // ① 乐观读（不锁）
            double currentX = x;
            double currentY = y;

            if (!lock.validate(stamp)) {             // ② 校验
                stamp = lock.readLock();             // ③ 失败 → 悲观读
                try {
                    currentX = x;
                    currentY = y;
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return Math.sqrt(currentX * currentX + currentY * currentY);
        }
    }

    @Test
    public void testStampedLock() throws InterruptedException {
        Point point = new Point();

        // 写线程
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                point.move(1, 1);
            }
        }, "writer");
        writer.start();

        // 读线程（乐观读）
        Thread[] readers = new Thread[5];
        for (int i = 0; i < 5; i++) {
            readers[i] = new Thread(() -> {
                double sum = 0;
                for (int j = 0; j < 10000; j++) {
                    sum += point.distanceFromOrigin();
                }
                System.out.println(Thread.currentThread().getName() +
                        " 总距离: " + sum);
            }, "reader-" + i);
            readers[i].start();
        }

        for (Thread t : readers) t.join();
        writer.join();
        System.out.println("✅ StampedLock 乐观读运行完成（读无锁 + 写互斥）");
    }
}
```

---

## 11. 自测题

1. **state 高低位拆分：一个 int 怎么存两把锁的计数？**
   <details><summary>答案</summary>

   高 16 位 = 读锁计数（sharedCount = c >>> 16），低 16 位 = 写锁计数（exclusiveCount = c & 0xFFFF）。每次读锁 +0x00010000，写锁 +1。
   </details>

2. **读锁为什么需要 HoldCounter + ThreadLocal？**
   <details><summary>答案</summary>

   写锁只有一个持有者（state 记次数够了）；读锁可以多个线程同时持有，每个线程的重入次数必须各自记录，用 ThreadLocal 存每个线程的 HoldCounter。
   </details>

3. **写锁降级的正确顺序？错误顺序有什么问题？**
   <details><summary>答案</summary>

   正确：写锁内 → 获取读锁 → 释放写锁。错误：先释放写锁再获取读锁 → 中间有竞争窗口，其他写者可能插入修改数据。
   </details>

4. **为什么读锁不能升级为写锁？**
   <details><summary>答案</summary>

   多个线程持有读锁时，每个都想升级为写锁 → 都必须等别人先释放读锁 → 互相等待 → 死锁。JDK 设计为"支持降级，不支持升级"。
   </details>

5. **StampedLock 乐观读什么时候比 ReadWriteLock 快？什么时候更慢？**
   <details><summary>答案</summary>

   快：读多写少（validate 大多成功，全程无锁）；慢：写频繁（validate 频繁失败 → 重读 → 比读锁还慢）。
   </details>

---

> 📬 **完成练习后，进入下一篇 [03-04-并发容器之ConcurrentHashMap](./03-04-并发容器之ConcurrentHashMap.md)（待发布）—— JDK 8 的 CAS + synchronized 重构，面试最常考的容器**

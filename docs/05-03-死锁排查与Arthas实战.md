# 05-03 死锁排查与 Arthas 实战

> **阶段五·第 3 篇** | 前置：[05-02-单例模式与并发设计模式](./05-02-单例模式与并发设计模式.md) | 后续：[05-04-JMH性能压测](./05-04-JMH性能压测.md)（待发布）  
> **建议时长**：6~7 小时（死锁理论 2h + jstack 实操 1.5h + Arthas 1.5h + CPU 排查 1.5h）  
> 🛠️ **日常高频**：线上问题排查是后端核心能力，死锁和 CPU 飙高是最常见的两类故障

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | 死锁四条件、三种预防策略、jstack 死锁检测、CPU 飙高排查全流程 | **能构造 + 能排查 + 能预防** |
| ⭐⭐⭐ | Arthas 常用命令（thread/watch/trace/monitor）、ThreadMXBean 程序化检测 | **会实操** |
| ◈◈ | 活锁、饥饿、锁顺序、随机退避 | **知道概念 + 会处理** |
| ○ | jmap/jstat 深度用法、MAT 分析 | **知道有** |

---

## 1. 死锁理论

### 1.1 死锁的四个必要条件（⭐ 必背）

```
死锁发生必须同时满足四个条件：

① 互斥（Mutual Exclusion）
   资源同一时刻只能被一个线程使用（如锁）

② 持有并等待（Hold and Wait）
   持有资源 A 的同时，还去等待资源 B

③ 不可抢占（No Preemption）
   已持有的资源不能被其他线程强行抢走

④ 循环等待（Circular Wait）
   线程 1 等线程 2 的资源，线程 2 等线程 1 的资源
```

```
经典死锁场景：

线程 1：持有锁 A → 等待锁 B
线程 2：持有锁 B → 等待锁 A

  ┌────────────┐           ┌────────────┐
  │   线程 1    │──持有──→  │    锁 A    │
  │            │           └─────┬──────┘
  │ 等待 B     │                 │
  │            │           ┌─────▼──────┐
  │   线程 2    │◄──持有────│    锁 B    │
  │            │           └────────────┘
  └────────────┘

→ 双方都"持有并等待" → 谁也等不到 → 永久阻塞
```

### 1.2 死锁的代码构造

```java
public class DeadlockDemo {
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    public static void main(String[] args) {
        // 线程 1：先拿 A 再拿 B
        new Thread(() -> {
            synchronized (LOCK_A) {
                System.out.println("T1 持有 A，等待 B...");
                sleep(100);   // 确保 T2 先拿到 B
                synchronized (LOCK_B) {   // 永远等不到！
                    System.out.println("T1 拿到 B");
                }
            }
        }, "T1").start();

        // 线程 2：先拿 B 再拿 A
        new Thread(() -> {
            synchronized (LOCK_B) {
                System.out.println("T2 持有 B，等待 A...");
                sleep(100);
                synchronized (LOCK_A) {   // 永远等不到！
                    System.out.println("T2 拿到 A");
                }
            }
        }, "T2").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 1.3 预防死锁的三种策略（⭐ 必背）

```
策略 1：锁排序（Lock Ordering）—— 破解循环等待

  所有线程必须"按相同的顺序"获取锁：
    先拿 A → 再拿 B → 再拿 C（全局统一顺序）

  T1：lock A → lock B
  T2：lock A → lock B（和 T1 一样！）
  → 不会出现"T1 拿 A 等 B、T2 拿 B 等 A"

  实现：给锁编号，按编号顺序获取
  int hash1 = System.identityHashCode(LOCK_A);
  int hash2 = System.identityHashCode(LOCK_B);
  if (hash1 < hash2) { lock A; lock B; }
  else { lock B; lock A; }

策略 2：锁超时（tryLock）—— 破解持有并等待

  拿不到锁不无限等，超时放弃已持有的锁：
  if (lockA.tryLock(3s)) {
      try {
          if (lockB.tryLock(3s)) { ... }   // 拿不到 B
          else { /* 放弃，重试或降级 */ }
      } finally {
          lockA.unlock();
      }
  }

策略 3：死锁检测（ThreadMXBean）—— 事后发现

  定期检测 → 发现死锁 → 告警/干预（见 §4）
```

---

## 2. jstack 死锁排查（🛠️ 实操）

### 2.1 排查流程

```bash
# ① 找到 Java 进程 PID
jps -l
# 输出：12345 com.example.MyApp

# ② 抓线程栈（关键参数 -l：显示锁信息）
jstack -l 12345 > thread_dump.txt

# ③ 查看死锁报告
cat thread_dump.txt
```

### 2.2 jstack 死锁报告解读

```
Found one Java-level deadlock:
=============================
"T1":
  waiting to lock monitor 0x000000001a2f4f30 (object 0x000000076b9e7c58, a java.lang.Object),
  which is held by "T2"
  T1 的栈：
  - com.example.DeadlockDemo.lambda$main$0(DeadlockDemo.java:15)
  - 等待锁 B（被 T2 持有）

"T2":
  waiting to lock monitor 0x000000076b9e7d00 (object 0x000000076b9e7d10, a java.lang.Object),
  which is held by "T1"
  T2 的栈：
  - com.example.DeadlockDemo.lambda$main$1(DeadlockDemo.java:27)
  - 等待锁 A（被 T1 持有）

Found 1 deadlock.      ← JVM 自动检测到了！
```

```
解读要点：
  ① "Found one Java-level deadlock" → JVM 自动检测到死锁
  ② "waiting to lock ... held by" → 谁在等谁的锁
  ③ 看栈顶方法 → 定位死锁代码行

线程状态速查：
  RUNNABLE    → 正在执行（或等待 CPU）
  BLOCKED     → 阻塞等待锁（synchronized）
  WAITING     → 无限等待（wait/park）
  TIMED_WAITING → 限时等待（sleep/wait(timeout)/parkNanos）
```

### 2.3 线程状态判断示例

```
"T2" #12 prio=5 os_prio=0 cpu=0.00ms elapsed=8.00s tid=0x... nid=0x... waiting for monitor entry
  [0x00007f...]
  java.lang.Thread.State: BLOCKED (on object monitor)   ← 等锁！
        at com.example.DeadlockDemo.lambda$main$1(DeadlockDemo.java:27)
        - waiting to lock <0x000000076b9e7d10> (a java.lang.Object)  ← 等哪个锁
        - locked <0x000000076b9e7c58> (a java.lang.Object)  ← 持有哪个锁

关键：locked = 已持有，waiting to lock = 正在等待
```

---

## 3. 活锁与饥饿（◈◈）

### 3.1 活锁（Livelock）

```
定义：线程不断改变状态但没有真正进展（没有阻塞，但一直"空转"）

例子：两个人面对面让路，都往左闪 → 又撞 → 都往右闪 → 又撞...

代码场景：
  两个线程都持有"对方需要的资源"，
  检测到冲突 → 都释放 → 都重试 → 又同时拿到 → 又冲突 → 无限循环

与死锁的区别：
  死锁：线程阻塞不动（WAITING/BLOCKED）
  活锁：线程在运行（RUNNABLE），但一直在做无用功

解决：引入随机退避（Random Backoff）
  冲突后随机等待 0~N ms 再重试
  → 打破"同时重试"的同步节奏
```

```java
// 活锁解决：随机退避
public boolean acquireWithBackoff(Lock a, Lock b) {
    while (true) {
        if (a.tryLock()) {
            if (b.tryLock()) {
                return true;
            }
            a.unlock();
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(100));
            // ↑ 随机退避：让两个线程的重试节奏错开
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
```

### 3.2 饥饿（Starvation）

```
定义：低优先级/运气差的线程永远等不到资源

场景：
  ① 非公平锁：新来的线程总是插队 → 排队的线程饿死（理论场景）
  ② 线程优先级：低优先级线程永远不被调度
  ③ 读锁泛滥：读者源源不断 → 写者永远等不到（读写锁场景）

解决：
  ① 公平锁（FIFO）
  ② 读写锁的 writerShouldBlock 机制
  ③ 提高优先级（不推荐，优先级是提示不是保证）

生产实际：
  非公平锁的饥饿概率极低（JVM 的调度会尽量避免）
  真正要注意的是"读写锁写者饥饿"（读多写少 + 读者持续不断）
```

---

## 4. ThreadMXBean 程序化死锁检测（🛠️ 生产必备）

```java
// 自动化死锁检测：后台线程定期扫描，发现即告警
public class DeadlockDetector {
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    public void start() {
        scheduler.scheduleAtFixedRate(this::check, 0, 10, TimeUnit.SECONDS);
    }

    private void check() {
        // 找出处于死锁中的线程（返回死锁线程 ID 数组）
        long[] deadlockedThreadIds = threadMXBean.findDeadlockedThreads();
        if (deadlockedThreadIds != null) {
            ThreadInfo[] infos = threadMXBean.getThreadInfo(deadlockedThreadIds, true, true);
            System.out.println("⚠️ 检测到死锁！涉及 " + infos.length + " 个线程：");
            for (ThreadInfo info : infos) {
                System.out.println("  - " + info.getThreadName() + " (" + info.getThreadState() + ")");
                System.out.println("    栈顶: " + info.getStackTrace()[0]);
            }
            // TODO: 发送告警（钉钉/邮件/日志）
        }
    }
}

// 使用：
new DeadlockDetector().start();
// 死锁发生 → 10 秒内自动发现并告警
```

```
findDeadlockedThreads vs findMonitorDeadlockedThreads：
  findDeadlockedThreads：能检测所有死锁（含 AQS/Lock 等）← 用这个
  findMonitorDeadlockedThreads：只能检测 synchronized 死锁（已过时）
```

---

## 5. Arthas 实战（⭐ 线上诊断利器）

### 5.1 安装与启动

```bash
# 下载并启动（交互式命令行）
curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar

# 选择要 attach 的 Java 进程
# [1]: 12345 com.example.MyApp
# 输入 1 回车 → 进入 Arthas 交互终端
```

### 5.2 核心命令速查（🛠️ 必会）

| 命令 | 作用 | 示例 |
|---|---|---|
| `thread` | 查看所有线程状态 | `thread` |
| `thread -n 3` | CPU 占用最高的 3 个线程 | `thread -n 3` |
| `thread -b` | 找出阻塞其他线程的锁（死锁定位） | `thread -b` |
| `watch` | 观察方法入参/返回值/异常 | `watch com.demo.OrderService createOrder '{params, returnObj}'` |
| `trace` | 方法内部调用路径 + 耗时 | `trace com.demo.OrderService createOrder` |
| `monitor` | 方法调用统计（次数/耗时） | `monitor -c 5 com.demo.OrderService createOrder` |
| `dashboard` | 实时面板（线程/内存/GC） | `dashboard` |
| `sc` | 查看已加载类 | `sc com.demo.*` |
| `jad` | 反编译类（确认线上代码） | `jad com.demo.OrderService` |

### 5.3 thread -b —— 死锁定位（⭐）

```bash
# 一行命令找出"谁阻塞了谁"
thread -b

# 输出示例：
# "T1" Id=12 BLOCKED on com.example.LockObject@2a1b7c
#     at com.example.DeadlockDemo.lambda$main$0(DeadlockDemo.java:15)
#     -  blocked on <LockObject@2a1b7c>
#     -  locked <LockObject@3c5e6f>
#
# "T2" Id=13 BLOCKED on com.example.LockObject@3c5e6f
#     at com.example.DeadlockDemo.lambda$main$1(DeadlockDemo.java:27)
#     -  blocked on <LockObject@3c5e6f>
#     -  locked <LockObject@2a1b7c>
```

### 5.4 CPU 飙高排查全流程（⭐ 必会）

```
场景：线上某台机器 CPU 100%，服务响应变慢

排查步骤：

① 找到 CPU 最高的进程
   top -c
   # 找到 java 进程 PID（如 12345）

② 找到进程内 CPU 最高的线程（Linux）
   top -Hp 12345
   # 找到线程 PID（如 23456）
   # 或：
   ps -Lp 12345 -o pid,tid,pcpu,comm | sort -k3 -rn | head -5

③ 线程 PID 转十六进制（jstack 里是十六进制）
   printf "%x\n" 23456
   # 输出：5ba0

④ jstack 查看该线程的栈
   jstack 12345 | grep -A 30 "0x5ba0"
   # 找到该线程在做什么 → 定位问题代码

⑤ 或者直接 Arthas 一条命令（推荐）
   thread -n 3
   # 直接显示 CPU 最高的 3 个线程及其栈 → 定位代码
```

```
Arthas 快速定位的完整流程：
  ① 进入 Arthas：java -jar arthas-boot.jar → 选进程
  ② thread -n 3 → 看到 CPU 最高的线程栈
  ③ 分析栈顶方法 → 是死循环？是 GC？是锁竞争？是 IO 等待？
  ④ watch/trace 确认 → 修复
```

### 5.5 常见 CPU 飙高原因

```
① 死循环 / 空转自旋（CAS 竞争激烈）
   → 栈顶是业务方法循环体 / CAS 自旋

② 频繁 GC（Full GC 风暴）
   → dashboard 看 GC 次数 → 内存泄漏（配合 jmap -histo）

③ 锁竞争（大量线程在自旋/阻塞重试）
   → 栈里大量 BLOCKED / 自旋

④ 正则回溯 / 序列化开销
   → 栈顶是 Pattern.matcher / 序列化代码

⑤ 业务 bug（大循环、批量查询没分页）
   → 栈顶是业务代码
```

---

## 6. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：死锁检测的边界——jstack 不是万能的

```java
// 好消息：现代 JDK（6+）的 deadlock 检测通过 ownable synchronizers 机制
//   → jstack 对标准 ReentrantLock 死锁（互相 lock 等待）也会输出
//     "Found one Java-level deadlock"，不是只能检测 synchronized！

// 检测不到的常见场景：
//   ① 活锁：tryLock 循环重试（线程在跑，不是死锁）→ jstack 不报告
//   ② Condition.await 互相等待但无锁循环 → 可能检测不到
//   ③ 分布式死锁（跨进程）→ 单进程 jstack 无能为力
//   ④ 资源死锁（如连接池耗尽互相等对方释放连接）→ 不是锁死锁

// ✅ 排查流程建议：
//   1. jstack -l：看 "Found one Java-level deadlock"（锁死锁）
//   2. 没有报告但服务卡住：找大量 BLOCKED/WAITING 的线程 → 看栈
//   3. Arthas thread -b：找"阻塞别人"的锁
//   4. ThreadMXBean.findDeadlockedThreads()：程序化检测（含 AQS）
```

### 🕳️ 坑 2：锁排序时用了可变顺序

```java
// ❌ 按"业务值"排序锁 → 不同请求顺序不同 → 死锁
if (orderId < 100) { lock A; lock B; } else { lock B; lock A; }
// 两个 orderId 不同的请求 → 顺序相反 → 死锁！

// ✅ 用稳定的全局顺序：identityHashCode 或固定编号
```

### 🕳️ 坑 3：嵌套锁太多难以维护

```java
// 预防：尽量"一次只持有一把锁"
// 必须嵌套 → 锁排序 + 最小化持锁时间
// 持有 A 时不调用可能拿 B 的方法（不要在持锁时调用外部方法！）
```

### 🕳️ 坑 4：死锁测试用例卡住 CI

```java
// 练习里的死锁测试会让测试"挂住"
// ✅ 用 join(timeout) 限时等待，或写成"能自己结束"的版本
// ✅ 生产代码检测死锁用 ThreadMXBean 定时任务
```

### 🕳️ 坑 5：jstack 抓的是瞬时状态

```java
// jstack 只抓"那一刻"的栈 → 偶发问题要多抓几次
// ✅ 间隔抓多个 dump 对比：
for i in 1 2 3; do jstack -l 12345 > dump_$i.txt; sleep 5; done
// 对比多次 dump：哪些线程一直 BLOCKED/WAITING → 嫌疑
```

---

## 7. 面试高频考点

1. **死锁的四个必要条件？**
   → 互斥、持有并等待、不可抢占、循环等待。四者同时满足才死锁，打破任何一个即可预防。

2. **三种预防策略分别破解哪个条件？**
   → 锁排序 → 破解循环等待；tryLock 超时 → 破解持有并等待；死锁检测 → 事后发现。

3. **死锁和活锁的区别？**
   → 死锁：线程阻塞不动（WAITING/BLOCKED）；活锁：线程在运行但无进展（空转）。活锁用随机退避解决。

4. **jstack 排查死锁的流程？**
   → jps 找 PID → jstack -l → 找 "Found one Java-level deadlock" / BLOCKED 线程 → 看 locked/waiting to lock → 定位代码。

5. **CPU 飙高怎么排查？**
   → top 找进程 → top -Hp 找线程 → 转十六进制 → jstack 看栈；或 Arthas thread -n 3 一步到位。

---

## 8. 实战练习

### 练习 1：构造死锁 + ThreadMXBean 检测（60 分钟）★必做

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 1：构造 ReentrantLock 死锁 + ThreadMXBean 程序化检测
 *
 * 注意：死锁线程是 daemon（setDaemon(true)）——
 *       否则测试结束 JVM 不退出（死锁线程永久阻塞在等锁），surefire 会挂住
 */
public class DeadlockDetectTest {

    private static final ReentrantLock LOCK_A = new ReentrantLock();
    private static final ReentrantLock LOCK_B = new ReentrantLock();

    @Test
    public void testDeadlockDetection() throws InterruptedException {
        // 线程 1：拿 A 等 B
        Thread t1 = new Thread(() -> {
            LOCK_A.lock();
            try {
                System.out.println("T1 持有 A，等待 B...");
                sleep(200);   // 确保 T2 拿到 B
                LOCK_B.lock();  // 死锁点！
                System.out.println("T1 拿到 B");
                LOCK_B.unlock();
            } finally {
                LOCK_A.unlock();
            }
        }, "T1");
        t1.setDaemon(true);

        // 线程 2：拿 B 等 A
        Thread t2 = new Thread(() -> {
            LOCK_B.lock();
            try {
                System.out.println("T2 持有 B，等待 A...");
                sleep(200);
                LOCK_A.lock();  // 死锁点！
                System.out.println("T2 拿到 A");
                LOCK_A.unlock();
            } finally {
                LOCK_B.unlock();
            }
        }, "T2");
        t2.setDaemon(true);

        t1.start();
        t2.start();

        // 等待死锁形成
        Thread.sleep(1000);

        // ThreadMXBean 检测（能检测 AQS/Lock 死锁）
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mxBean.findDeadlockedThreads();

        if (deadlocked != null) {
            System.out.println("⚠️ 检测到死锁，涉及 " + deadlocked.length + " 个线程：");
            ThreadInfo[] infos = mxBean.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                System.out.println("  - " + info.getThreadName() +
                        " 状态: " + info.getThreadState());
                System.out.println("    锁: " + info.getLockInfo());
                System.out.println("    栈顶: " + info.getStackTrace()[0]);
            }
            System.out.println("✅ ThreadMXBean 成功检测到死锁");
        } else {
            System.out.println("❌ 未检测到死锁（不应该）");
        }

        // 注意：T1、T2 是 daemon 线程，测试结束 JVM 直接退出
        // 不会挂住测试（对比非 daemon 会卡住）
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 练习 2：锁排序预防死锁（45 分钟）★必做

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 2：用锁排序（Lock Ordering）预防死锁
 *
 * 对比练习 1：所有线程按"统一顺序"获取锁 → 不可能循环等待
 */
public class LockOrderingTest {

    private static final ReentrantLock LOCK_A = new ReentrantLock();
    private static final ReentrantLock LOCK_B = new ReentrantLock();

    // 统一锁顺序：根据 identityHashCode 排序（稳定且全局一致）
    private static final int HASH_A = System.identityHashCode(LOCK_A);
    private static final int HASH_B = System.identityHashCode(LOCK_B);

    private static ReentrantLock first() {
        return HASH_A < HASH_B ? LOCK_A : LOCK_B;
    }

    private static ReentrantLock second() {
        return HASH_A < HASH_B ? LOCK_B : LOCK_A;
    }

    // 所有线程都通过这个入口拿两把锁 → 顺序永远一致 → 不会死锁
    private static void acquireBoth(ReentrantLock first, ReentrantLock second) {
        first.lock();
        try {
            System.out.println(Thread.currentThread().getName() +
                    " 持有 " + (first == LOCK_A ? "A" : "B") + "，拿第二把...");
            sleep(100);
            second.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 两把锁都拿到了 ✅");
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    @Test
    public void testLockOrdering() throws InterruptedException {
        // 两个线程都按"先 first 后 second"获取 → 即使交替也能完成
        Thread t1 = new Thread(() -> acquireBoth(first(), second()), "T1");
        Thread t2 = new Thread(() -> acquireBoth(first(), second()), "T2");

        t1.start();
        t2.start();
        t1.join(3000);
        t2.join(3000);

        System.out.println(t1.isAlive() || t2.isAlive()
                ? "❌ 仍然死锁？"
                : "✅ 锁排序生效：两个线程都正常完成（无死锁）");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 练习 3：CPU 飙高模拟与排查（45 分钟）

```java
package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

/**
 * 练习 3：模拟 CPU 飙高（死循环），练习排查思路
 *
 * 排查步骤（终端操作）：
 * 1. 运行此测试（它会一直死循环）
 * 2. top 找到 java 进程 PID
 * 3. top -Hp <pid> 找到 CPU 最高的线程
 * 4. printf "%x\n" <tid> 转十六进制
 * 5. jstack <pid> | grep -A 30 "0x<hex>" 看栈
 * 6. 定位到 cpuBurning 方法的死循环
 */
public class CpuSpikeTest {

    @Test
    public void testCpuSpike() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // 4 个 CPU 密集死循环线程（模拟 CPU 飙高）
        // ⚠️ 必须 setDaemon(true)：否则测试结束 JVM 不退出（非 daemon 线程在跑），surefire 会挂住
        for (int i = 0; i < 4; i++) {
            Thread burner = new Thread(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println(Thread.currentThread().getName() + " 开始死循环...");
                cpuBurning();   // ← jstack 会定位到这里
            }, "cpu-burner-" + i);
            burner.setDaemon(true);   // ★ 关键：daemon 线程，测试结束自动终止
            burner.start();
        }

        latch.countDown();
        // 测试方法在这里"卡住"（4 个死循环线程在跑）
        // 利用这段时间执行 jstack 排查
        // 排查完手动停止测试
        Thread.sleep(10_000);
        System.out.println("（排查演示结束，正常情况应通过诊断定位 cpuBurning）");
    }

    /**
     * 模拟业务死循环（罪魁祸首）
     */
    private void cpuBurning() {
        double x = 0;
        while (true) {
            x += Math.sin(x) * 0.001;   // 空转计算（让 CPU 忙）
        }
    }
}
```

---

## 9. 自测题

1. **死锁的四个必要条件？怎么打破？**
   <details><summary>答案</summary>

   互斥、持有并等待、不可抢占、循环等待。打破：锁排序（破循环等待）、tryLock 超时（破持有并等待）、破坏互斥（如无锁设计 CAS）、死锁检测。
   </details>

2. **jstack 怎么定位死锁？Lock 死锁和 synchronized 死锁有什么区别？**
   <details><summary>答案</summary>

   jstack -l 找 "Found one Java-level deadlock"（synchronized 自动检测）；Lock 死锁不自动报告 → 看 WAITING (parking) 线程栈中的 AQS 等待信息，或用 ThreadMXBean.findDeadlockedThreads()。
   </details>

3. **死锁和活锁的区别？活锁怎么解决？**
   <details><summary>答案</summary>

   死锁：阻塞不动；活锁：运行但无进展（重复冲突重试）。活锁用随机退避打破"同时重试"的节奏。
   </details>

4. **CPU 飙高排查的完整流程？**
   <details><summary>答案</summary>

   top 找进程 → top -Hp 找线程 → printf "%x" 转十六进制 → jstack 看栈；或 Arthas thread -n 3 一步到位。看栈顶判断：死循环/GC/锁竞争/正则回溯。
   </details>

5. **thread -b 是干什么的？**
   <details><summary>答案</summary>

   Arthas 的 thread -b：找出"阻塞其他线程的锁"——直接显示哪个线程持有锁导致别人 BLOCKED，死锁定位神器。
   </details>

---

> 📬 **完成练习后，进入下一篇 [05-04-JMH性能压测](./05-04-JMH性能压测.md)（待发布）—— 微基准测试、JMH 陷阱、锁竞争压测**

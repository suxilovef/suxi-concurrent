# 04-04 ForkJoin 与工作窃取

> **阶段四·第 4 篇（收官）** | 前置：[04-03-CompletableFuture异步编排](./04-03-CompletableFuture异步编排.md) | 后续：[05-01-ThreadLocal与内存泄漏](./05-01-ThreadLocal与内存泄漏.md)（待发布）  
> **建议时长**：4~5 小时（分治思想 1h + 工作窃取 1.5h + ForkJoinTask 1.5h + 练习 1.5h）  
> **定位**：◈◈ 重点掌握 —— ForkJoinPool 是 CompletableFuture 默认线程池的底层，理解它才能理解 commonPool

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| ◈◈ | 分治思想、ForkJoinPool 结构、工作窃取算法、RecursiveTask/RecursiveAction | **理解原理 + 会写基本任务** |
| ◈◈ | fork/join 的正确写法、commonPool 与 CompletableFuture 的关系 | **必须理解（承上启下）** |
| ○ | ForkJoinTask 源码细节（WorkQueue、状态机）、ScheduledExecutor 对比 | **了解即可** |

---

## 1. 分治思想（Divide and Conquer）

### 1.1 什么是分治

```
大任务拆成小任务 → 小任务并行执行 → 结果合并

                 ┌──────────────────────┐
                 │  计算 1 到 100 的和    │
                 └──────────┬───────────┘
              fork（拆分）   │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
  ┌──────────┐        ┌──────────┐        ┌──────────┐
  │ 1 到 33  │        │ 34 到 66 │        │ 67 到 100 │
  └──────────┘        └──────────┘        └──────────┘
        │ 继续拆            │ 继续拆            │ 继续拆
        ▼                   ▼                   ▼
     ┌────┐ ┌────┐     ┌────┐ ┌────┐     单独算（够小了）
     │1-16│ │17-33│    │34-49│ │50-66│
     └────┘ └────┘     └────┘ └────┘
        │     │           │     │
        └──┬──┘           └──┬──┘
           ▼ join（合并）     ▼
        ┌──────┐        ┌──────┐
        │1-33 和│        │34-66 和│
        └──┬───┘        └──┬───┘
           └──────┬────────┘
                  ▼
           ┌────────────┐
           │ 1-100 的总和 │
           └────────────┘
```

```
分治的适用条件：
  ① 问题可以递归拆分
  ② 子问题相互独立（无共享状态）
  ③ 拆分到一定粒度后直接计算（阈值）
```

### 1.2 经典例子

```
归并排序、二分查找、斐波那契、大数乘法、矩阵乘法
→ 本质：把 O(n²) 的暴力计算拆成多个 O(n) 并行

注意：ForkJoin 适合 CPU 密集型计算（纯计算、无 IO）
     不适合 IO 密集（线程在等 IO 时 fork/join 收益不大）
```

---

## 2. ForkJoinPool 结构

### 2.1 与 ThreadPoolExecutor 的对比

| 维度 | ThreadPoolExecutor | ForkJoinPool |
|---|---|---|
| 队列 | 一个共享任务队列 | **每个线程一个私有双端队列（WorkQueue）** |
| 任务获取 | 从共享队列竞争 | 从自己的队列取（无竞争） |
| 负载均衡 | 无（排队等） | **工作窃取** |
| 任务类型 | Runnable/Callable | ForkJoinTask |
| 典型场景 | IO 密集型业务 | CPU 密集型计算 |
| 并行度 | 可配 | 默认 CPU 核数 |

### 2.2 核心结构

```
ForkJoinPool 内部：

┌──────────────────────────────────────────────┐
│ WorkQueue[0]（提交队列：外部任务的入口）        │
├──────────────────────────────────────────────┤
│ 工作线程 1 ── 私有 WorkQueue（双端队列）        │
│   ├── push（自己 fork 的任务，队尾入）          │
│   └── pop（自己取任务，队尾出 → LIFO）          │
├──────────────────────────────────────────────┤
│ 工作线程 2 ── 私有 WorkQueue                  │
│   ├── push / pop（同上）                      │
│   └── steal（窃取别人的，队头入/出 → FIFO）     │
├──────────────────────────────────────────────┤
│ 工作线程 3 ── 私有 WorkQueue                  │
└──────────────────────────────────────────────┘

每个 WorkQueue 是双端队列：
  自己的任务：从尾部 push/pop（LIFO，栈式）
  偷别人的：从头部 take（FIFO，队列式）
```

---

## 3. 工作窃取算法（Work Stealing）⭐

### 3.1 问题：任务分配不均

```
场景：4 个线程，任务拆分后：

线程 1：██████████████░░░░  （快做完了）
线程 2：██████████████████  （还有很多）
线程 3：████████████░░░░░░
线程 4：████████████████████

→ 线程 1 空闲了，但线程 2、4 还有大量任务
→ 传统线程池：线程 1 只能干等（任务分配不均 → 浪费）
```

### 3.2 工作窃取：忙的帮闲的

```
线程 1 空闲 → 去线程 2 的队列"偷"任务来做：

线程 2 的队列（双端）：
  [任务A][任务B][任务C][任务D][任务E]  ← 尾部（线程2 自己从这里取）
   ↑ 头部
   线程 1 从这里偷（FIFO）

→ 线程 2 自己取尾部（LIFO 栈式：最近的任务优先）
→ 偷窃者取头部（FIFO 队列式：最早的任务优先）

为什么偷头部而不是尾部？
  自己取尾部 → 优先处理"最新拆分"的任务（大任务先分完）
  别人偷头部 → 拿走"最老"的任务（不容易造成混乱）
  → 双向不冲突，无需锁竞争（不同端）
```

### 3.3 为什么能减少竞争

```
普通线程池：所有线程抢一个共享队列 → 锁竞争严重

ForkJoinPool：每个线程自己的队列
  - 自己取任务：无锁（LIFO 栈操作）
  - 偷别人任务：CAS（偶尔发生）
  → 大多数操作无竞争 → 高并发性能更好
```

### 3.4 工作窃取的意义

```
解决的问题：
  CPU 密集型任务拆分后"粒度不均" → 有些线程先完成 → 空闲浪费

解决方案：
  空闲线程主动去"偷"忙线程的任务
  → 所有线程都在干活 → 并行度拉满

一句话：不让任何一个 CPU 核心闲着
```

---

## 4. ForkJoinTask 两个子类

| 子类 | 返回值 | 适用 |
|---|---|---|
| `RecursiveTask<T>` | 有返回值 | 需要计算结果（求和、排序） |
| `RecursiveAction` | 无返回值 | 不需要结果（批量处理） |

### 4.1 RecursiveTask 使用模板（求和）

```java
// 用 ForkJoin 计算 1 到 N 的和
public class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10000;  // 拆分阈值

    private final long start;
    private final long end;

    public SumTask(long start, long end) {
        this.start = start;
        this.end = end;
    }

    @Override
    protected Long compute() {
        // ① 足够小 → 直接计算
        if (end - start <= THRESHOLD) {
            long sum = 0;
            for (long i = start; i <= end; i++) {
                sum += i;
            }
            return sum;
        }

        // ② 太大 → 拆分（二分）
        long mid = (start + end) / 2;
        SumTask leftTask = new SumTask(start, mid);
        SumTask rightTask = new SumTask(mid + 1, end);

        // ③ fork：异步执行子任务
        leftTask.fork();
        rightTask.fork();

        // ④ join：等待结果并合并
        return leftTask.join() + rightTask.join();
    }
}
```

> ⚠️ 教学简化版：两个子任务都 fork。生产优化版（见练习 1）：**一个 fork、一个直接 compute()**——少入队一个任务，递归栈深度减半，能避免深递归时的栈溢出。

### 4.2 执行 ForkJoin 任务

```java
// 方式 1：专用 ForkJoinPool（推荐）
ForkJoinPool pool = new ForkJoinPool();   // 并行度 = CPU 核数
Long result = pool.invoke(new SumTask(1, 100_000_000L));
System.out.println(result);

// 方式 2：commonPool（全局共享）
Long result = ForkJoinPool.commonPool().invoke(new SumTask(1, 100_000_000L));

// 方式 3：submit 异步
ForkJoinTask<Long> task = pool.submit(new SumTask(1, 100_000_000L));
Long result = task.join();
```

### 4.3 fork/join 的底层流程

```
compute() 被工作线程执行：
  fork()  → 子任务加入自己的 WorkQueue 尾部（不阻塞！）
  fork()  → 另一个子任务也入队
  join()  → 等待子任务完成

关键：fork 不是"新起线程"！是"把任务放进队列"
      子任务由当前线程或其他工作线程（窃取）执行

join() 的内部逻辑：
  子任务还没被其他线程执行 → 当前线程自己执行（递归 compute）
  子任务正被其他线程执行 → 阻塞等待
  → join 时可能触发"帮助执行"（减少等待）
```

---

## 5. commonPool 与 CompletableFuture（承上启下）⭐

### 5.1 关系

```
CompletableFuture 不传线程池 → 用 ForkJoinPool.commonPool()

ForkJoinPool.commonPool()：
  - 全局唯一的共享池（static 实例）
  - 线程数 = CPU 核数 - 1
  - 线程是守护线程（daemon）
```

### 5.2 为什么 CompletableFuture 用 ForkJoinPool

```
设计意图：
  多数 CompletableFuture 任务很短（纯计算）
  → 用工作窃取实现极致性能（无锁取任务）

实际问题：
  业务里 CompletableFuture 常用来做 IO（查 DB、调接口）
  → IO 任务阻塞线程 → commonPool 线程被占满 → 全部任务排队
  → 线程数太少（核数-1）→ 排队严重
  → 守护线程 → 主线程退出后任务可能没执行完

结论（再次强调）：
  生产必须传自定义线程池，别用 commonPool
```

### 5.3 commonPool 阻塞的隐患

```java
// ❌ 危险：在 commonPool 里执行阻塞任务
CompletableFuture.supplyAsync(() -> {
    // 这是 commonPool 的线程！
    Thread.sleep(5000);        // 阻塞 5 秒 → 占着 commonPool 一个线程
    return queryDB();
});  // 不传线程池 → commonPool

// commonPool 只有 核数-1 个线程
// 多个这样的任务 → commonPool 被占满 → 其他依赖 commonPool 的任务全部排队
// 甚至 Stream.parallel() 也被拖慢！（也用 commonPool）
```

---

## 6. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：fork 顺序错误导致栈溢出

```java
// ❌ 先 fork 两个子任务再 join —— 在任务特别深时可能栈溢出
leftTask.fork();
rightTask.fork();
return leftTask.join() + rightTask.join();

// ✅ 推荐：一个 fork 一个直接 compute（减少一层递归栈）
leftTask.fork();
Long right = rightTask.compute();   // 当前线程直接算右半
Long left = leftTask.join();
return left + right;
// 这样少 fork 一个 → 栈深度减半
```

### 🕳️ 坑 2：在 compute 里用阻塞 IO

```java
// ❌ ForkJoin 是 CPU 密集设计，阻塞 IO 会占着工作线程
// 工作线程阻塞 → 无法执行其他 fork 任务 → 窃取机制失效

// ✅ 阈值调大或换 ThreadPoolExecutor
```

### 🕳️ 坑 3：任务太小还拆

```java
// ❌ 任务只有 10 个数也拆分 → 拆分的开销 > 计算收益
// 阈值（THRESHOLD）要调：通常让每个子任务至少 1~10 万次操作
// 太小 → 线程切换/队列操作开销 > 并行收益
```

### 🕳️ 坑 4：commonPool 被 IO 任务污染

```java
// ❌ 用默认 commonPool 跑 IO 任务 → 影响所有用 commonPool 的代码
// （包括 Stream.parallel()、其他 CompletableFuture）

// ✅ 自定义线程池隔离
ForkJoinPool ioPool = new ForkJoinPool(16);  // IO 多的调大并行度
```

### 🕳️ 坑 5：join 抛异常的类型

```java
// ⚠️ 子任务抛异常 → join() 抛 RuntimeException 包装（ForkJoinTask 的包装）
// 子任务内最好自行处理异常，或 compute 里 try-catch

// ✅ 或使用 task.get()（检查异常版本，需要 try-catch）
```

---

## 7. 面试高频考点

1. **工作窃取算法是什么？解决了什么问题？**
   → 空闲线程从忙线程的队列头部偷任务。解决任务拆分不均导致的部分线程空闲。

2. **为什么自己的任务从队尾取（LIFO），偷的从队头取（FIFO）？**
   → 自己取尾部 = 优先处理最新拆分的子任务（减少 join 等待）；偷头部 = 拿走最老的任务；两端操作互不冲突 → 无锁。

3. **fork() 是新建线程吗？**
   → 不是。fork 只是把子任务放入当前工作线程的 WorkQueue，由当前线程或其他线程（窃取）执行。

4. **ForkJoinPool 和 ThreadPoolExecutor 的区别？**
   → 每个线程私有双端队列 vs 共享队列；工作窃取 vs 排队等待；适合 CPU 密集 vs 通用。

5. **commonPool 有什么风险？**
   → 线程数 = 核数-1、守护线程、被 IO 任务占满会影响所有使用 commonPool 的代码（Stream.parallel、CompletableFuture）。

---

## 8. 实战练习

### 练习 1：ForkJoin 求和（60 分钟）★必做

```java
package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * 练习 1：用 ForkJoin 计算 1 到 1 亿的和（分治）
 *
 * 目标：
 * 1. 理解 RecursiveTask 的 compute 模板（拆 → fork → join）
 * 2. 对比单线程计算耗时
 */
public class ForkJoinSumTest {

    static class SumTask extends RecursiveTask<Long> {
        private static final int THRESHOLD = 10000;
        private final long start;
        private final long end;

        SumTask(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        protected Long compute() {
            // ① 足够小 → 直接算
            if (end - start <= THRESHOLD) {
                long sum = 0;
                for (long i = start; i <= end; i++) {
                    sum += i;
                }
                return sum;
            }
            // ② 拆分
            long mid = (start + end) / 2;
            SumTask leftTask = new SumTask(start, mid);
            SumTask rightTask = new SumTask(mid + 1, end);
            // ③ 一个 fork，一个直接算（减少递归深度，见坑 1）
            leftTask.fork();
            Long right = rightTask.compute();
            Long left = leftTask.join();
            // ④ 合并
            return left + right;
        }
    }

    @Test
    public void testForkJoinSum() {
        long n = 100_000_000L;

        // 单线程版（对比）
        long start = System.currentTimeMillis();
        long singleSum = 0;
        for (long i = 1; i <= n; i++) {
            singleSum += i;
        }
        long singleTime = System.currentTimeMillis() - start;

        // ForkJoin 版
        start = System.currentTimeMillis();
        ForkJoinPool pool = new ForkJoinPool();   // 并行度 = CPU 核数
        long forkJoinSum = pool.invoke(new SumTask(1, n));
        long forkJoinTime = System.currentTimeMillis() - start;

        long expected = n * (n + 1) / 2;   // 等差数列公式验证

        System.out.println("单线程: " + singleSum + "（" + singleTime + "ms）");
        System.out.println("ForkJoin: " + forkJoinSum + "（" + forkJoinTime + "ms）");
        System.out.println("预期值: " + expected);
        System.out.println(forkJoinSum == expected ? "✅ 结果正确" : "❌ 结果错误");
        System.out.println("加速比: " + String.format("%.1f", (double) singleTime / Math.max(forkJoinTime, 1)) + "x");
        // 提示：如果加速比不明显甚至更慢，属正常现象——
        // 任务拆分/队列操作本身有开销，数据量不够大时并行收益 < 拆分开销（见坑 3）

        pool.shutdown();
    }
}
```

### 练习 2：并行归并排序（45 分钟）

```java
package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * 练习 2：用 RecursiveAction 实现并行归并排序（无返回值任务）
 */
public class ForkJoinMergeSortTest {

    static class MergeSortTask extends RecursiveAction {
        private static final int THRESHOLD = 1000;
        private final int[] arr;
        private final int left;
        private final int right;

        MergeSortTask(int[] arr, int left, int right) {
            this.arr = arr;
            this.left = left;
            this.right = right;
        }

        @Override
        protected void compute() {
            if (right - left <= THRESHOLD) {
                Arrays.sort(arr, left, right + 1);  // 小数组直接排序
                return;
            }
            int mid = (left + right) / 2;
            MergeSortTask leftTask = new MergeSortTask(arr, left, mid);
            MergeSortTask rightTask = new MergeSortTask(arr, mid + 1, right);

            // 并行递归排序
            leftTask.fork();
            rightTask.compute();
            leftTask.join();

            // 归并（合并两个有序子数组）
            merge(arr, left, mid, right);
        }

        private void merge(int[] arr, int left, int mid, int right) {
            int[] tmp = Arrays.copyOfRange(arr, left, right + 1);
            int i = 0, j = mid - left + 1;
            int k = left;
            while (i <= mid - left && j < tmp.length) {
                arr[k++] = tmp[i] <= tmp[j] ? tmp[i++] : tmp[j++];
            }
            while (i <= mid - left) arr[k++] = tmp[i++];
            while (j < tmp.length) arr[k++] = tmp[j++];
        }
    }

    @Test
    public void testParallelMergeSort() {
        int size = 1_000_000;
        Random random = new Random(42);
        int[] arr1 = new int[size];
        int[] arr2 = new int[size];
        for (int i = 0; i < size; i++) {
            arr1[i] = random.nextInt(1_000_000);
            arr2[i] = arr1[i];
        }

        // 单线程 Arrays.sort（对比）
        long start = System.currentTimeMillis();
        Arrays.sort(arr1);
        long singleTime = System.currentTimeMillis() - start;

        // ForkJoin 并行归并
        start = System.currentTimeMillis();
        ForkJoinPool pool = new ForkJoinPool();
        pool.invoke(new MergeSortTask(arr2, 0, arr2.length - 1));
        long forkJoinTime = System.currentTimeMillis() - start;

        System.out.println("单线程排序: " + singleTime + "ms");
        System.out.println("ForkJoin排序: " + forkJoinTime + "ms");
        System.out.println(Arrays.equals(arr1, arr2) ? "✅ 排序结果一致" : "❌ 排序结果不一致");
        System.out.println("加速比: " + String.format("%.1f", (double) singleTime / Math.max(forkJoinTime, 1)) + "x");

        pool.shutdown();
    }
}
```

### 练习 3：观察工作窃取（30 分钟，选做）

```java
package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * 练习 3：观察工作窃取 —— 故意制造"任务不均"，看空闲线程是否会帮忙
 *
 * 思路：拆分成 32 个耗时极不均匀的子任务（有的 1ms，有的 100ms）
 *       如果总耗时 ≈ 最慢任务（而不是所有任务之和），说明窃取生效
 */
public class WorkStealingTest {

    static class UnevenTask extends RecursiveTask<Long> {
        private static final int THRESHOLD = 1;   // 拆到单个任务
        private final int index;
        private final int total;

        UnevenTask(int index, int total) {
            this.index = index;
            this.total = total;
        }

        @Override
        protected Long compute() {
            if (total <= THRESHOLD) {
                // 任务耗时按 index 递增（最后一个最慢）
                long sleepMs = 1 + index * 3;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return sleepMs;
            }
            int half = total / 2;
            UnevenTask left = new UnevenTask(index, half);
            UnevenTask right = new UnevenTask(index + half, total - half);
            left.fork();
            Long r = right.compute();
            Long l = left.join();
            return l + r;
        }
    }

    @Test
    public void testWorkStealing() {
        int taskCount = 32;
        long start = System.currentTimeMillis();

        ForkJoinPool pool = new ForkJoinPool(4);  // 4 个线程
        Long total = pool.invoke(new UnevenTask(0, taskCount));

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("总任务量(单线程串行): ~" + total + "ms");
        System.out.println("实际耗时: " + elapsed + "ms");
        System.out.println("4 线程并行: 串行耗时 / 4 ≈ " + (total / 4) + "ms");
        System.out.println(elapsed < total / 2
                ? "✅ 明显并行（工作窃取让空闲线程帮忙了）"
                : "（本机并行度不足或线程数少，效果不明显）");

        pool.shutdown();
    }
}
```

---

## 9. 自测题

1. **工作窃取解决了什么问题？怎么实现？**
   <details><summary>答案</summary>

   解决任务拆分不均导致的 CPU 空闲。实现：每个线程私有双端队列，空闲线程从忙线程队列头部（FIFO）偷任务，自己从尾部（LIFO）取任务，两端操作无冲突。
   </details>

2. **fork() 是启动新线程吗？**
   <details><summary>答案</summary>

   不是。fork() 只是把子任务放入当前工作线程的 WorkQueue 尾部，子任务由当前线程或窃取它的其他线程执行，不创建新线程。
   </details>

3. **ForkJoinPool 和 ThreadPoolExecutor 的核心区别？**
   <details><summary>答案</summary>

   队列：私有双端队列 vs 共享队列；负载均衡：工作窃取 vs 排队等待；任务类型：ForkJoinTask vs Runnable；场景：CPU 密集计算 vs 通用（含 IO）。
   </details>

4. **为什么自己取队尾（LIFO）而偷取队头（FIFO）？**
   <details><summary>答案</summary>

   自己取队尾 = 优先执行最新拆分的任务（小任务先完成，减少 join 等待）；偷取队头 = 拿走最老任务。两端操作避免锁竞争。
   </details>

5. **commonPool 的风险？它和 CompletableFuture 什么关系？**
   <details><summary>答案</summary>

   commonPool = 全局共享 ForkJoinPool（核数-1 个守护线程）。CompletableFuture 不传线程池时用它。风险：IO 任务占满线程 → 影响所有使用者（Stream.parallel 等）；守护线程可能被 JVM 提前终止。生产必须传自定义池。
   </details>

---

> 📬 **🎉 阶段四收官！现在你已掌握：线程池核心源码 → 调优与优雅停机 → CompletableFuture 异步编排 → ForkJoin 工作窃取。异步编程能力已经建立！**
>
> 下一篇：[05-01-ThreadLocal与内存泄漏](./05-01-ThreadLocal与内存泄漏.md)（待发布）—— 进入阶段五：并发设计模式、问题排查与性能调优

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
        ForkJoinPool pool = new ForkJoinPool(); // 并行度 = CPU 核数
        long forkJoinSum = pool.invoke(new SumTask(1, n));
        long forkJoinTime = System.currentTimeMillis() - start;

        long expected = n * (n + 1) / 2; // 等差数列公式验证

        System.out.println("单线程: " + singleSum + "（" + singleTime + "ms）");
        System.out.println("ForkJoin: " + forkJoinSum + "（" + forkJoinTime + "ms）");
        System.out.println("预期值: " + expected);
        System.out.println(forkJoinSum == expected ? "✅ 结果正确" : "❌ 结果错误");
        System.out.println("加速比: " + String.format("%.1f", (double) singleTime / Math.max(forkJoinTime, 1)) + "x");

        pool.shutdown();
    }
}

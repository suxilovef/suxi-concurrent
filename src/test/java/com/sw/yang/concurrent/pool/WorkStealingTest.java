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
        private static final int THRESHOLD = 1; // 拆到单个任务
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

        ForkJoinPool pool = new ForkJoinPool(4); // 4 个线程
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

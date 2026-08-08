package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 2：优雅停机三件套（shutdown → awaitTermination → shutdownNow）
 */
public class GracefulShutdownTest {

    @Test
    public void testGracefulShutdown() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("grace-worker");
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        // 提交 5 个任务（每个 500ms）
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            pool.execute(() -> {
                System.out.println("任务 " + id + " 开始（" + Thread.currentThread().getName() + "）");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    System.out.println("任务 " + id + " 被中断！");
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println("任务 " + id + " 完成");
            });
        }

        System.out.println("=== 开始优雅停机 ===");
        pool.shutdown(); // ① 停止接收新任务，等待已提交的完成

        boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS); // ② 最多等 3 秒
        if (!terminated) {
            System.out.println("=== 3 秒未完成 → shutdownNow ===");
            List<Runnable> unfinished = pool.shutdownNow(); // ③ 强制中断
            System.out.println("未完成任务数: " + unfinished.size());
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }

        System.out.println("最终终止状态: " + pool.isTerminated());
        System.out.println("✅ 优雅停机流程执行完毕");
    }

    /**
     * 对比：不响应中断的任务 shutdownNow 也停不掉
     */
    @Test
    public void testUninterruptibleTask() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("stubborn");
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        pool.execute(() -> {
            System.out.println("顽固任务开始执行...");
            // ❌ 不检查中断标志 → interrupt 无效
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 2000) {
                // 忙循环（不响应中断）
            }
            System.out.println("顽固任务执行完（中断对它无效！）");
        });

        pool.shutdownNow(); // 尝试中断
        boolean terminated = pool.awaitTermination(1, TimeUnit.SECONDS);
        System.out.println("1 秒后是否终止: " + terminated);
        System.out.println("✅ 演示：不响应中断的任务，shutdownNow 也停不掉（任务必须自己配合）");
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}

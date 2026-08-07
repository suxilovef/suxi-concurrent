package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：验证线程池任务执行顺序（核心 → 队列 → 临时 → 拒绝）
 *
 * 配置：核心 2，最大 4，队列容量 2
 * 提交 7 个任务 → 观察各任务由哪个线程执行
 */
public class ExecuteOrderTest {

    @Test
    public void testExecuteOrder() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                     // 核心 2
                4,                     // 最大 4
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),   // 队列 2
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setName("pool-worker-" + seq.incrementAndGet());
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());

        // 提交 7 个任务
        for (int i = 1; i <= 7; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    System.out.println("任务 " + id + " 由线程 [" +
                            Thread.currentThread().getName() + "] 执行");
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RejectedExecutionException e) {
                System.out.println("任务 " + id + " 被拒绝！");
            }
        }

        // 观察输出：
        // 任务 1、2 → worker-1、worker-2（核心线程）
        // 任务 3、4 → 入队（等待）
        // 任务 5、6 → worker-3、worker-4（临时线程！队列满了）
        // 任务 7 → 被拒绝（队列满 + 线程满）

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 观察：核心(2) → 队列(2) → 临时(2) → 拒绝(1)");
    }
}

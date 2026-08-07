package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 2：线程池运行状态监控
 *
 * 目标：实时打印活跃线程数 / 队列积压 / 完成任务数
 */
public class PoolMonitorTest {

    @Test
    public void testMonitor() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("monitor-worker");
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        // 监控线程：每 500ms 打印一次状态
        Thread monitor = new Thread(() -> {
            while (!pool.isTerminated()) {
                System.out.println("线程数=" + pool.getPoolSize() +
                        " 活跃=" + pool.getActiveCount() +
                        " 队列积压=" + pool.getQueue().size() +
                        " 完成任务=" + pool.getCompletedTaskCount() +
                        " 最大线程数(历史)=" + pool.getLargestPoolSize());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "monitor");
        monitor.setDaemon(true);
        monitor.start();

        // 提交 20 个慢任务
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("✅ 监控结束，线程池已终止");
    }
}

package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 3：运行时动态调整线程池参数（美团方案的核心 API）
 */
public class DynamicConfigTest {

    @Test
    public void testDynamicAdjust() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("dynamic-worker");
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        System.out.println("初始: 核心=" + pool.getCorePoolSize() +
                " 最大=" + pool.getMaximumPoolSize());

        // 模拟配置中心推送新参数
        pool.setCorePoolSize(8);      // 动态扩容核心线程
        pool.setMaximumPoolSize(16);  // 动态扩容最大线程
        System.out.println("扩容后: 核心=" + pool.getCorePoolSize() +
                " 最大=" + pool.getMaximumPoolSize());

        // 提交一批任务验证
        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(500);
        System.out.println("当前线程数: " + pool.getPoolSize() +
                " 活跃: " + pool.getActiveCount());

        // 模拟高峰期过去，降容
        pool.setCorePoolSize(1);
        pool.setMaximumPoolSize(2);
        System.out.println("降容后: 核心=" + pool.getCorePoolSize() +
                " 最大=" + pool.getMaximumPoolSize() +
                "（多余线程空闲后自动回收）");

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 动态调参演示完成（生产中由配置中心触发）");
    }
}

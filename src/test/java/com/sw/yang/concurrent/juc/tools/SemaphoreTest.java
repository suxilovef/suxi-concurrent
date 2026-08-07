package com.sw.yang.concurrent.juc.tools;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 3：Semaphore 实现接口限流（最多同时 3 个请求）
 */
public class SemaphoreTest {

    private final Semaphore semaphore = new Semaphore(3);
    private final AtomicInteger concurrent = new AtomicInteger(0);
    private final AtomicInteger maxConcurrent = new AtomicInteger(0);
    private final AtomicInteger rejected = new AtomicInteger(0);

    // 模拟接口：最多 3 个并发
    private void handleRequest(int id) throws InterruptedException {
        // 用 tryAcquire 快速失败限流
        if (!semaphore.tryAcquire()) {
            rejected.incrementAndGet();
            System.out.println("请求 " + id + " 被拒绝（系统繁忙）");
            return;
        }
        try {
            int cur = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            Thread.sleep(100); // 模拟业务
            concurrent.decrementAndGet();
            System.out.println("请求 " + id + " 处理完成");
        } finally {
            semaphore.release(); // ✅ 必须释放
        }
    }

    @Test
    public void testRateLimit() throws InterruptedException {
        // 10 个并发请求
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try {
                    handleRequest(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "req-" + id);
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("最大并发: " + maxConcurrent.get());
        System.out.println("被拒绝数: " + rejected.get());
        System.out.println(maxConcurrent.get() <= 3
                ? "✅ 并发从未超过 3（限流生效）"
                : "❌ 超过 3 个并发！");
    }
}

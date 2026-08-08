package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：手写令牌桶限流器 + 并发验证
 *
 * 目标：
 * 1. 理解令牌桶算法（匀速补充 + 桶容量 + 允许突发）
 * 2. 验证限流效果：请求速率被控制在 refillRate 附近
 */
public class TokenBucketTest {

    /**
     * 令牌桶实现（synchronized 保证线程安全）
     */
    static class TokenBucketLimiter {
        private final int capacity;      // 桶容量（最大令牌数）
        private final double refillRate; // 每秒补充的令牌数
        private double tokens;           // 当前令牌数
        private long lastRefillTime;     // 上次补充时间

        TokenBucketLimiter(int capacity, double refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        public synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double elapsed = (now - lastRefillTime) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsed * refillRate);
            lastRefillTime = now;
        }
    }

    @Test
    public void testTokenBucket() throws InterruptedException {
        // 每秒 20 个令牌，桶容量 20（允许 1 秒突发）
        TokenBucketLimiter limiter = new TokenBucketLimiter(20, 20.0);
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        // 10 个线程疯狂请求 2 秒
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    if (limiter.tryAcquire()) {
                        passed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("放行: " + passed.get() + " 个请求");
        System.out.println("拒绝: " + rejected.get() + " 个请求");
        System.out.println("理论放行上限: 2 秒 × 20/s = 40（加上初始桶的 20 = 60）");
        System.out.println(passed.get() <= 60
                ? "✅ 限流生效（放行数未超过令牌上限）"
                : "❌ 限流失效！");
    }
}

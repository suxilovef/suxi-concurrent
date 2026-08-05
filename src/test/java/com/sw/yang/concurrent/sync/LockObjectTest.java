package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 1：验证三种锁形态的互斥关系
 *
 * 目标：
 * 1. 两个线程调用同一实例的实例方法 → 互斥（串行）
 * 2. 两个实例分别调用实例方法 → 不互斥（并发）
 * 3. 实例方法 vs 静态方法 → 不互斥（两把不同的锁）
 */
public class LockObjectTest {

    private static final class Counter {
        private int count = 0;

        public synchronized void incrInstance() {
            count++;
            sleep(50); // 放大竞争窗口
        }

        public static synchronized void incrStatic() {
            sleep(50);
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 同一实例，两个线程调用实例方法 → 应该互斥（串行 = 300ms）
     */
    @Test
    public void testSameInstance() throws InterruptedException {
        Counter c = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("同一实例调用实例方法: " + elapsed + "ms");
        System.out.println(elapsed >= 300 ? "✅ 互斥（串行执行）" : "❌ 未互斥（并发执行）");
        // 3次 × 50ms × 2线程 = 300ms（串行） vs 150ms（并发）
    }

    /**
     * 不同实例，两个线程调用实例方法 → 应该不互斥（并发 = 150ms）
     */
    @Test
    public void testDifferentInstance() throws InterruptedException {
        Counter c1 = new Counter();
        Counter c2 = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c1.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) c2.incrInstance(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("不同实例调用实例方法: " + elapsed + "ms");
        System.out.println(elapsed < 300 ? "✅ 不互斥（并发执行）" : "❌ 意外互斥");
        // 3次 × 50ms = 150ms（并发）
    }

    /**
     * 同一实例：实例方法 vs 静态方法 → 应该不互斥（两把不同的锁）
     */
    @Test
    public void testInstanceVsStatic() throws InterruptedException {
        Counter c = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) Counter.incrStatic(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("实例方法 vs 静态方法: " + elapsed + "ms");
        System.out.println(elapsed < 300 ? "✅ 不互斥（两把不同的锁）" : "❌ 意外互斥");
    }
}

package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：验证 volatile 不保证原子性 + 三种修复方案对比
 *
 * 10 个线程各执行 10000 次自增，预期结果为 100000
 */
public class AtomicityTest {

    private static volatile int volatileCount = 0;
    private static int syncCount = 0;
    private static final AtomicInteger atomicCount = new AtomicInteger(0);
    private static final Object lock = new Object();
    private static final int THREAD_COUNT = 10;
    private static final int ITERATIONS = 10000;

    /**
     * 方案 A：volatile 修饰 count —— 不保证原子性
     * 预期：100000，实际：通常 20000 ~ 40000
     */
    @Test
    public void way1_volatileFail() throws InterruptedException {
        volatileCount = 0;
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    volatileCount++; // getstatic → iadd → putstatic，非原子
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        int expected = THREAD_COUNT * ITERATIONS;
        System.out.println("volatile count 预期: " + expected);
        System.out.println("volatile count 实际: " + volatileCount);
        System.out.println("丢失率: " + (100.0 * (expected - volatileCount) / expected) + "%");
    }

    /**
     * 方案 B：AtomicInteger —— CAS 保证原子性，✅ 正确
     */
    @Test
    public void way2_atomicInteger() throws InterruptedException {
        atomicCount.set(0);
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    atomicCount.incrementAndGet(); // CAS 自旋，原子操作
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("AtomicInteger 预期: " + (THREAD_COUNT * ITERATIONS));
        System.out.println("AtomicInteger 实际: " + atomicCount.get());
        System.out.println(atomicCount.get() == THREAD_COUNT * ITERATIONS ? "✅ 正确" : "❌ 异常");
    }

    /**
     * 方案 C：synchronized 代码块 —— 加锁保证原子性，✅ 正确
     */
    @Test
    public void way3_synchronized() throws InterruptedException {
        syncCount = 0;
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    synchronized (lock) {
                        syncCount++;
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("synchronized 预期: " + (THREAD_COUNT * ITERATIONS));
        System.out.println("synchronized 实际: " + syncCount);
        System.out.println(syncCount == THREAD_COUNT * ITERATIONS ? "✅ 正确" : "❌ 异常");
    }
}

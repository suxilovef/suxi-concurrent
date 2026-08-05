package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：简单对比 CAS 自旋 vs synchronized 在不同竞争强度下的表现
 *
 * 注意：这是粗略对比，精确压测需要 JMH（阶段五会学到）
 *
 * 预期观察：
 * - 低竞争时 CAS 略快（无上下文切换）
 * - 高竞争时两者差距缩小，甚至 synchronized 更稳定
 * - 精确结论需要 JMH 多轮测试
 */
public class CasVsSyncTest {

    private static final int THREADS = 10;
    private static final int ITERATIONS = 100_000;

    @Test
    public void testCasPerformance() throws InterruptedException {
        AtomicInteger casCounter = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    casCounter.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("CAS AtomicInteger: " + elapsed + "ms, 结果=" + casCounter.get());
    }

    @Test
    public void testSyncPerformance() throws InterruptedException {
        Object lock = new Object();
        int[] counter = {0};
        long start = System.currentTimeMillis();

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    synchronized (lock) {
                        counter[0]++;
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("synchronized: " + elapsed + "ms, 结果=" + counter[0]);
    }

    /**
     * 用更多线程模拟更高竞争
     */
    @Test
    public void testHighContention() throws InterruptedException {
        int bigThreads = 50;
        int smallIterations = 20000;

        // CAS
        AtomicInteger casCounter = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        Thread[] casThreads = new Thread[bigThreads];
        for (int i = 0; i < bigThreads; i++) {
            casThreads[i] = new Thread(() -> {
                for (int j = 0; j < smallIterations; j++) {
                    casCounter.incrementAndGet();
                }
            });
            casThreads[i].start();
        }
        for (Thread t : casThreads) t.join();
        long casTime = System.currentTimeMillis() - start;

        // synchronized
        Object lock = new Object();
        int[] syncCounter = {0};
        start = System.currentTimeMillis();
        Thread[] syncThreads = new Thread[bigThreads];
        for (int i = 0; i < bigThreads; i++) {
            syncThreads[i] = new Thread(() -> {
                for (int j = 0; j < smallIterations; j++) {
                    synchronized (lock) {
                        syncCounter[0]++;
                    }
                }
            });
            syncThreads[i].start();
        }
        for (Thread t : syncThreads) t.join();
        long syncTime = System.currentTimeMillis() - start;

        System.out.println("=== 高竞争场景（" + bigThreads + " 线程）===");
        System.out.println("CAS AtomicInteger: " + casTime + "ms, 结果=" + casCounter.get());
        System.out.println("synchronized:      " + syncTime + "ms, 结果=" + syncCounter[0]);
        System.out.println("注意：这不是 JMH 精确测试，仅供参考趋势");
    }
}

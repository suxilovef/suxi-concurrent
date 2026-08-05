package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 wait 释放锁 / sleep 不释放锁
 */
public class WaitVsSleepTest {

    /**
     * wait 释放锁：holder 调用 wait(2000) 后，taker 能立刻拿到锁
     */
    @Test
    public void testWaitReleasesLock() throws InterruptedException {
        Object lock = new Object();
        long[] waitTime = new long[1];

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                System.out.println("Holder 获取锁，调用 wait(2000)...");
                try {
                    lock.wait(2000);  // 释放锁
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Holder wait 结束");
            }
        }, "holder");
        holder.start();

        Thread.sleep(500); // 确保 holder 先拿到锁

        Thread taker = new Thread(() -> {
            long start = System.currentTimeMillis();
            synchronized (lock) {
                waitTime[0] = System.currentTimeMillis() - start;
                System.out.println("Taker 获取锁，等待耗时: " + waitTime[0] + "ms");
            }
        }, "taker");
        taker.start();
        taker.join();

        System.out.println(waitTime[0] < 2000
                ? "✅ wait 释放了锁（taker 在 holder 等待期间就进来了）"
                : "❌ wait 没有释放锁？");
        holder.join();
    }

    /**
     * sleep 不释放锁：holder sleep(2000) 期间 taker 进不来
     */
    @Test
    public void testSleepHoldsLock() throws InterruptedException {
        Object lock = new Object();
        long[] sleepTime = new long[1];

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                System.out.println("Holder 获取锁，sleep(2000)...");
                try {
                    Thread.sleep(2000);  // 不释放锁
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Holder sleep 结束");
            }
        }, "holder");
        holder.start();

        Thread.sleep(500);

        Thread taker = new Thread(() -> {
            long start = System.currentTimeMillis();
            synchronized (lock) {
                sleepTime[0] = System.currentTimeMillis() - start;
                System.out.println("Taker 获取锁，等待耗时: " + sleepTime[0] + "ms");
            }
        }, "taker");
        taker.start();
        taker.join();

        System.out.println(sleepTime[0] >= 2000
                ? "✅ sleep 持有锁（taker 等到 sleep 结束才进来）"
                : "❌ sleep 释放了锁？");
        holder.join();
    }
}

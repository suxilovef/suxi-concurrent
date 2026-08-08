package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 1：构造 ReentrantLock 死锁 + ThreadMXBean 程序化检测
 *
 * 注意：死锁线程是 daemon，测试结束 JVM 可直接退出，不会挂住测试
 */
public class DeadlockDetectTest {

    private static final ReentrantLock LOCK_A = new ReentrantLock();
    private static final ReentrantLock LOCK_B = new ReentrantLock();

    @Test
    public void testDeadlockDetection() throws InterruptedException {
        // 线程 1：拿 A 等 B
        Thread t1 = new Thread(() -> {
            LOCK_A.lock();
            try {
                System.out.println("T1 持有 A，等待 B...");
                sleep(200); // 确保 T2 拿到 B
                LOCK_B.lock(); // 死锁点！
                System.out.println("T1 拿到 B");
                LOCK_B.unlock();
            } finally {
                LOCK_A.unlock();
            }
        }, "T1");
        t1.setDaemon(true);

        // 线程 2：拿 B 等 A
        Thread t2 = new Thread(() -> {
            LOCK_B.lock();
            try {
                System.out.println("T2 持有 B，等待 A...");
                sleep(200);
                LOCK_A.lock(); // 死锁点！
                System.out.println("T2 拿到 A");
                LOCK_A.unlock();
            } finally {
                LOCK_B.unlock();
            }
        }, "T2");
        t2.setDaemon(true);

        t1.start();
        t2.start();

        // 等待死锁形成
        Thread.sleep(1000);

        // ThreadMXBean 检测（能检测 AQS/Lock 死锁）
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mxBean.findDeadlockedThreads();

        if (deadlocked != null) {
            System.out.println("⚠️ 检测到死锁，涉及 " + deadlocked.length + " 个线程：");
            ThreadInfo[] infos = mxBean.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                System.out.println("  - " + info.getThreadName() +
                        " 状态: " + info.getThreadState());
                System.out.println("    锁: " + info.getLockInfo());
                System.out.println("    栈顶: " + info.getStackTrace()[0]);
            }
            System.out.println("✅ ThreadMXBean 成功检测到死锁");
        } else {
            System.out.println("❌ 未检测到死锁（不应该）");
        }

        // T1、T2 是 daemon 线程，测试结束 JVM 直接退出，不会挂住
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

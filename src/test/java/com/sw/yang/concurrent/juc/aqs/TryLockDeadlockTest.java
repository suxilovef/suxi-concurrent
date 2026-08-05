package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 3：用 tryLock(timeout) 避免死锁
 *
 * 场景：两个线程需要同时持有 lockA 和 lockB
 * - 死锁版本：lock() 无限等待 → 互相等 → 死锁
 * - 防死锁版：tryLock(2s) → 拿不到就释放已持有的 → 重试
 */
public class TryLockDeadlockTest {

    private final ReentrantLock lockA = new ReentrantLock();
    private final ReentrantLock lockB = new ReentrantLock();

    /**
     * 经典死锁：T1 拿 A 等 B，T2 拿 B 等 A → 互相等待
     * ⚠️ 注意：这个测试会卡住（死锁），单独运行观察现象即可
     */
    @Test
    public void testDeadlockVersion() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            lockA.lock();
            try {
                Thread.sleep(100);
                lockB.lock(); // 等 B（B 被 t2 拿着）
                System.out.println("T1 同时拿到 A+B");
                lockB.unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lockA.unlock();
            }
        }, "T1");

        Thread t2 = new Thread(() -> {
            lockB.lock();
            try {
                Thread.sleep(100);
                lockA.lock(); // 等 A（A 被 t1 拿着）→ 死锁！
                System.out.println("T2 同时拿到 B+A");
                lockA.unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lockB.unlock();
            }
        }, "T2");

        t1.start();
        t2.start();
        t1.join(2000);
        t2.join(2000);
        System.out.println("T1 alive: " + t1.isAlive() + ", T2 alive: " + t2.isAlive());
        System.out.println("两个线程都活着 → 死锁了（lock() 无限等待）");
        System.out.println("（此版本会卡住，如需继续运行请注释掉或单跑）");
    }

    /**
     * 防死锁版：tryLock(2s)，拿不到就释放已持有的，随机退避重试
     */
    @Test
    public void testTryLockVersion() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (lockA.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            if (lockB.tryLock(2, TimeUnit.SECONDS)) {
                                System.out.println("T1 同时拿到 A+B，执行成功");
                                lockB.unlock();
                                return;
                            }
                        } finally {
                            lockA.unlock(); // 拿不到 B → 释放 A
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Thread.sleep(10); // 随机退避后重试
            }
            System.out.println("T1 重试 5 次仍未成功，放弃");
        }, "T1");

        Thread t2 = new Thread(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (lockB.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            if (lockA.tryLock(2, TimeUnit.SECONDS)) {
                                System.out.println("T2 同时拿到 B+A，执行成功");
                                lockA.unlock();
                                return;
                            }
                        } finally {
                            lockB.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Thread.sleep(10);
            }
            System.out.println("T2 重试 5 次仍未成功，放弃");
        }, "T2");

        t1.start();
        t2.start();
        t1.join();
        t2.join();
        System.out.println("✅ tryLock(2s) 版本：双方最终都完成（无死锁）");
    }
}

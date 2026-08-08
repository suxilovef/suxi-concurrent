package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 2：用锁排序（Lock Ordering）预防死锁
 *
 * 对比练习 1：所有线程按"统一顺序"获取锁 → 不可能循环等待
 */
public class LockOrderingTest {

    private static final ReentrantLock LOCK_A = new ReentrantLock();
    private static final ReentrantLock LOCK_B = new ReentrantLock();

    // 统一锁顺序：根据 identityHashCode 排序（稳定且全局一致）
    private static final int HASH_A = System.identityHashCode(LOCK_A);
    private static final int HASH_B = System.identityHashCode(LOCK_B);

    private static ReentrantLock first() {
        return HASH_A < HASH_B ? LOCK_A : LOCK_B;
    }

    private static ReentrantLock second() {
        return HASH_A < HASH_B ? LOCK_B : LOCK_A;
    }

    // 所有线程都通过这个入口拿两把锁 → 顺序永远一致 → 不会死锁
    private static void acquireBoth(ReentrantLock first, ReentrantLock second) {
        first.lock();
        try {
            System.out.println(Thread.currentThread().getName() +
                    " 持有 " + (first == LOCK_A ? "A" : "B") + "，拿第二把...");
            sleep(100);
            second.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 两把锁都拿到了 ✅");
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    @Test
    public void testLockOrdering() throws InterruptedException {
        // 两个线程都按"先 first 后 second"获取 → 即使交替也能完成
        Thread t1 = new Thread(() -> acquireBoth(first(), second()), "T1");
        Thread t2 = new Thread(() -> acquireBoth(first(), second()), "T2");

        t1.start();
        t2.start();
        t1.join(3000);
        t2.join(3000);

        System.out.println(t1.isAlive() || t2.isAlive()
                ? "❌ 仍然死锁？"
                : "✅ 锁排序生效：两个线程都正常完成（无死锁）");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

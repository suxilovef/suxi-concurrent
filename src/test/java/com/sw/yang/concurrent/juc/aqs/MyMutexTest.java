package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 练习 1：基于 AQS 实现简易互斥锁（独占模式）
 *
 * 核心：只需要实现 tryAcquire / tryRelease 两个模板方法，
 *       排队、阻塞、唤醒全部由 AQS 完成
 */
public class MyMutexTest {

    static class MyMutex {
        private static class Sync extends AbstractQueuedSynchronizer {
            // 尝试获取：state 从 0 → 1
            @Override
            protected boolean tryAcquire(int acquires) {
                assert acquires == 1;
                if (compareAndSetState(0, 1)) {
                    setExclusiveOwnerThread(Thread.currentThread());
                    return true;
                }
                return false;
            }

            // 尝试释放：state 从 1 → 0
            @Override
            protected boolean tryRelease(int releases) {
                assert releases == 1;
                if (getState() == 0) throw new IllegalMonitorStateException();
                setExclusiveOwnerThread(null);
                setState(0); // 只有持有者才能释放，不需要 CAS
                return true;
            }

            @Override
            protected boolean isHeldExclusively() {
                return getState() == 1;
            }
        }

        private final Sync sync = new Sync();

        public void lock() { sync.acquire(1); }
        public void unlock() { sync.release(1); }
        public boolean isLocked() { return sync.isHeldExclusively(); }
    }

    @Test
    public void testMutex() throws InterruptedException {
        MyMutex mutex = new MyMutex();
        final int[] count = {0};

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    mutex.lock();
                    try {
                        count[0]++;
                    } finally {
                        mutex.unlock();
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("预期: 100000, 实际: " + count[0]);
        System.out.println(count[0] == 100000 ? "✅ MyMutex 正确" : "❌ 异常");
    }
}

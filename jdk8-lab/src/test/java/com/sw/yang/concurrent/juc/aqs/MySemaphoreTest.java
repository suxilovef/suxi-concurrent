package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 练习 2：基于 AQS 实现简易信号量（共享模式）
 *
 * 核心：tryAcquireShared 返回 <0 表示失败
 */
public class MySemaphoreTest {

    static class MySemaphore {
        private static class Sync extends AbstractQueuedSynchronizer {
            Sync(int permits) {
                setState(permits); // state = 剩余许可数
            }

            // 共享获取：许可数 -1
            @Override
            protected int tryAcquireShared(int acquires) {
                for (;;) {
                    int available = getState();
                    int remaining = available - acquires;
                    if (remaining < 0 || compareAndSetState(available, remaining)) {
                        return remaining; // <0 表示获取失败
                    }
                }
            }

            // 共享释放：许可数 +1
            @Override
            protected boolean tryReleaseShared(int releases) {
                for (;;) {
                    int current = getState();
                    int next = current + releases;
                    if (compareAndSetState(current, next)) {
                        return true;
                    }
                }
            }

            // getState() 是 protected，只能在 Sync（AQS 子类）内部访问
            // 对外通过委托暴露，和 JDK Semaphore 的 sync.getPermits() 同理
            int availablePermits() { return getState(); }
        }

        private final Sync sync;

        public MySemaphore(int permits) { sync = new Sync(permits); }

        public void acquire() { sync.acquireShared(1); }
        public void release() { sync.releaseShared(1); }
        public int availablePermits() { return sync.availablePermits(); }
    }

    @Test
    public void testSemaphore() throws InterruptedException {
        MySemaphore semaphore = new MySemaphore(3); // 最多 3 个并发
        final int[] concurrent = {0};
        final int[] maxConcurrent = {0};

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                try {
                    semaphore.acquire();
                    int cur = ++concurrent[0];
                    synchronized (maxConcurrent) {
                        if (cur > maxConcurrent[0]) maxConcurrent[0] = cur;
                    }
                    Thread.sleep(50); // 模拟业务
                    concurrent[0]--;
                    semaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("最大并发数: " + maxConcurrent[0]);
        System.out.println(maxConcurrent[0] <= 3 ? "✅ 最多 3 个并发（信号量生效）" : "❌ 超过 3 个并发！");
    }
}

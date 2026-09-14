package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 练习 3：用 Thread.getState() 观察 ObjectMonitor 三个队列的流转
 *
 * 队列 ↔ 线程状态（JDK 里是【文档化】的对应关系，不是巧合）：
 *   _cxq / _EntryList（抢锁失败，等 monitor enter） → BLOCKED
 *   _WaitSet（调了 wait()，等条件）                 → WAITING / TIMED_WAITING
 *
 * 核心结论（对应 docs/02-03 §3.5「错误说法 1：被唤醒后就直接变成 _owner」）：
 *   notify() 只是"移队"（_WaitSet → _EntryList），不是"给锁"。
 *   被唤醒的线程必须重新 CAS 竞争，抢不到就回去接着 park —— 这就是非公平的来源。
 *
 * ⚠️ 断言原则：断言【不变量】，不断言【实现细节】。
 *    比如"WAITING → BLOCKED 的翻转时刻"是实现细节（跨版本可能变），
 *    但"锁还被别人拿着时，被唤醒者绝不可能从 wait() 返回"是不变量。
 */
public class MonitorQueueStateTest {

    /** 轮询等待线程进入期望状态，超时则返回实际观察到的状态 */
    private static Thread.State awaitState(Thread t, Thread.State expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (t.getState() == expected) {
                return expected;
            }
            Thread.sleep(5);
        }
        return t.getState();
    }

    /** 在 lambda 里用 latch 免去 checked exception 的样板 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 抢锁失败者 → BLOCKED（待在 _cxq / _EntryList 里）
     *
     * holder 一直持锁不放，contender 进不去，只能排队。
     */
    @Test
    public void testContentionIsBlocked() throws InterruptedException {
        Object lock = new Object();
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                holderIn.countDown();
                awaitQuietly(release); // 一直持有锁，直到主线程放行
            }
        }, "holder");
        holder.start();
        assertTrue(holderIn.await(2, TimeUnit.SECONDS), "holder 没能进入临界区");

        Thread contender = new Thread(() -> {
            synchronized (lock) {
                // 抢到锁才算越过分界
            }
        }, "contender");
        contender.start();

        // 关键：CAS _owner 失败 → 进 _cxq → park，全程状态是 BLOCKED
        assertEquals(Thread.State.BLOCKED, awaitState(contender, Thread.State.BLOCKED, 2000),
                "抢锁失败的线程应处于 BLOCKED");
        System.out.println("✅ 抢锁失败（_cxq/_EntryList）→ " + contender.getState());

        release.countDown();
        contender.join(2000);
        assertFalse(contender.isAlive(), "contender 应在 holder 释放锁后进入临界区并结束");
    }

    /**
     * 调了 wait() 的线程 → WAITING（待在 _WaitSet 里）
     *
     * 注意与上一条的区别：等锁 = BLOCKED，等条件 = WAITING。
     * 这是两个不同的队列，靠状态就能区分。
     */
    @Test
    public void testWaitIsWaiting() throws InterruptedException {
        Object lock = new Object();
        CountDownLatch aboutToWait = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                aboutToWait.countDown();
                try {
                    lock.wait(); // 释放锁 + 自己进 _WaitSet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "waiter");
        waiter.start();
        assertTrue(aboutToWait.await(2, TimeUnit.SECONDS), "waiter 没能进入临界区");

        assertEquals(Thread.State.WAITING, awaitState(waiter, Thread.State.WAITING, 2000),
                "调了 wait() 的线程应处于 WAITING");
        System.out.println("✅ 调了 wait()（_WaitSet）→ " + waiter.getState());

        // 收尾：必须唤醒它，否则非 daemon 线程挂住，mvn 构建不退出
        synchronized (lock) {
            lock.notifyAll();
        }
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "waiter 应被唤醒后结束");
    }

    /**
     * ★核心：notify() 只是"移队"，不是"给锁"
     *
     * notify() 干的事：把 waiter 从 _WaitSet 摘出来，放进 _EntryList。
     * 仅此而已 —— 锁还在通知者手里，waiter 必须重新抢一次。
     *
     * 所以：通知者仍持锁时，waiter 绝不可能越过 wait() 的返回点。
     * 这里只断言这个【不变量】，不断言"此刻一定是 BLOCKED"
     * （翻转时刻是实现细节，写死会让测试跨版本 flaky）。
     */
    @Test
    public void testNotifyOnlyMovesQueue() throws InterruptedException {
        Object lock = new Object();
        CountDownLatch aboutToWait = new CountDownLatch(1);
        AtomicBoolean waitReturned = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                aboutToWait.countDown();
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 越过分界 = 已经重新抢到锁
                waitReturned.set(true);
            }
        }, "waiter");
        waiter.start();
        assertTrue(aboutToWait.await(2, TimeUnit.SECONDS), "waiter 没能进入临界区");
        assertEquals(Thread.State.WAITING, awaitState(waiter, Thread.State.WAITING, 2000),
                "waiter 应先在 _WaitSet 里 WAITING");

        synchronized (lock) {
            lock.notify();          // 只是 _WaitSet → _EntryList
            Thread.sleep(200);      // 给足它"抢锁"的机会

            // ★不变量：notify 只给了竞争资格，没给锁
            assertFalse(waitReturned.get(), "notify() 不该让 waiter 直接拿到锁");

            Thread.State s = waiter.getState();
            // 它只能还在队列里（BLOCKED=正在抢锁 / WAITING=尚未被调度的实现差异）
            assertTrue(s == Thread.State.BLOCKED || s == Thread.State.WAITING,
                    "通知者仍持锁时 waiter 不可能 RUNNABLE/TERMINATED，实际 " + s);
            System.out.println("notify() 之后、main 仍持锁时，waiter = " + s
                    + "（BLOCKED = 已在 _EntryList 里重新抢锁）");
        }

        waiter.join(2000);
        assertTrue(waitReturned.get(), "main 退出临界区后 waiter 才拿到锁并返回");
        System.out.println("✅ main 退出临界区后，waiter 才真正从 wait() 返回");
    }

    /**
     * 非公平性观察（非确定性，只打印不断言）
     *
     * 通知者退出临界区后【立刻】重新抢锁：它是热的（还在 CPU 上），
     * 被唤醒的 waiter 还在等 unpark + 被调度排队 → 通知者常常直接插队成功。
     * 这就是 barging，也是 synchronized 不保证公平的直接体现。
     *
     * 正因为结果依赖调度，这里不做断言 —— 打印统计，跑几次感受一下。
     */
    @Test
    public void testBargingObservation() throws InterruptedException {
        final int rounds = 100;
        int barged = 0;

        for (int i = 0; i < rounds; i++) {
            Object lock = new Object();
            CountDownLatch aboutToWait = new CountDownLatch(1);
            AtomicInteger winner = new AtomicInteger(-1); // 0 = 通知者插队成功，1 = waiter

            Thread waiter = new Thread(() -> {
                synchronized (lock) {
                    aboutToWait.countDown();
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    winner.compareAndSet(-1, 1);
                }
            }, "waiter");
            waiter.start();
            awaitQuietly(aboutToWait);
            awaitState(waiter, Thread.State.WAITING, 1000);

            synchronized (lock) {
                lock.notify();
            }
            // 立刻重新抢锁：抢到了也没人能拦着（非公平）
            synchronized (lock) {
                if (winner.compareAndSet(-1, 0)) {
                    barged++;
                }
            }
            waiter.join(2000);
        }

        System.out.println("=== barging 观察（" + rounds + " 轮）===");
        System.out.println("通知者退出后立刻插队成功：" + barged + " 次");
        System.out.println("waiter 先抢到：" + (rounds - barged) + " 次");
        System.out.println("（非确定性，仅观察：这就是 synchronized 非公平的直接证据）");
    }
}

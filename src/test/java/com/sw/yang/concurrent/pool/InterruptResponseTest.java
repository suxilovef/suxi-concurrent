package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中断响应三类阻塞点的对照实验（对应 04-02 §4.1 与坑 4/坑 5）
 *
 * 前置结论：interrupt() 只写一个 boolean 标志位 + 唤醒"可中断的阻塞点"。
 * 三类阻塞点对这个动作的反应完全不同，这决定了任务该怎么"配合"：
 *
 *   类别一  会抛 InterruptedException（sleep/join/wait/take/lockInterruptibly）
 *           → 且抛出的同时清除标志（本文用 category1 逐个证明）
 *           → 任务 catch 到就别继续了
 *
 *   类别二  park() 只返回、不抛异常、标志保留
 *           → 任务必须自己 isInterrupted() 查一次（AQS 的 acquireQueued 就是这么写的）
 *
 *   类别三  synchronized 抢锁 / 传统 Socket IO / Lock.lock() 完全无感知
 *           → interrupt 到了也停不住，只能换可中断版本或超时轮询兜底
 *
 * 所有可能"停不下来"的线程均设为 daemon，避免拖住构建。
 */
public class InterruptResponseTest {

    // ==================== 类别一：抛异常型 ====================

    @Test
    public void category1_throwsInterruptedException() throws InterruptedException {
        System.out.println("=== 类别一：抛 InterruptedException 的阻塞点 ===");

        Probe sleep = interruptAfter("sleep(5s)", () -> Thread.sleep(5000));
        Probe take = interruptAfter("queue.take()", () -> EMPTY_QUEUE.take());

        Thread victim = daemonThread("victim", () -> interruptibleSleep(5000));
        victim.start();
        Probe join = interruptAfter("victim.join()", victim::join);

        ReentrantLock lock = new ReentrantLock();
        lock.lock();                                    // 主线程持锁，逼子线程进等待队列
        Probe lockInterruptibly;
        try {
            lockInterruptibly = interruptAfter("lockInterruptibly()", lock::lockInterruptibly);
        } finally {
            lock.unlock();
        }

        // 四个阻塞点表现一致：都是"抛异常 + 标志被清"
        for (Probe p : new Probe[]{sleep, take, join, lockInterruptibly}) {
            assertEquals("抛 InterruptedException", p.outcome);
            assertFalse(p.flagInsideCatch, "InterruptedException 抛出时，中断标志已被清除");
        }
    }

    // ==================== 类别二：park 静默返回 ====================

    /**
     * park() 与 sleep() 的关键差别：不抛异常、只返回，且标志原样保留。
     * 这里用无超时的 park() —— 它能返回就只可能是被 interrupt（或 unpark/伪唤醒）。
     */
    @Test
    public void category2_parkReturnsSilently() throws InterruptedException {
        System.out.println("=== 类别二：park() 只返回、不抛异常 ===");

        AtomicBoolean returned = new AtomicBoolean(false);
        AtomicBoolean flagAfterPark = new AtomicBoolean(false);

        Thread parker = new Thread(() -> {
            LockSupport.park();                                       // ← 没有超时参数
            returned.set(true);
            flagAfterPark.set(Thread.currentThread().isInterrupted()); // ← 标志还在吗？
        }, "parker");
        parker.setDaemon(true);
        parker.start();

        Thread.sleep(300);
        parker.interrupt();
        parker.join(2000);

        System.out.println("  park() 返回=" + returned.get()
                + "，返回后 isInterrupted()=" + flagAfterPark.get());
        assertTrue(returned.get(), "park 被中断应立即返回（无超时却返回，即证明是中断唤醒）");
        assertTrue(flagAfterPark.get(), "park 不抛异常 → 标志保留 → 必须自己查 isInterrupted()");
        System.out.println("  对照：sleep() 同样被中断，却会抛异常并清掉标志（见 category1）");
    }

    // ==================== 类别三：完全无感知 ====================

    /** synchronized 抢锁：interrupt 被记录，但 ObjectMonitor 的等待队列压根不看它 */
    @Test
    public void category3a_synchronizedIgnoresInterrupt() throws InterruptedException {
        System.out.println("=== 类别三之一：synchronized 抢锁不可中断 ===");

        Object monitor = new Object();
        AtomicBoolean acquired = new AtomicBoolean(false);
        AtomicBoolean flagCarried = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {                    // ← 卡在这一行，interrupt 完全无效
                acquired.set(true);
            }
            flagCarried.set(Thread.currentThread().isInterrupted());
        }, "blocked-on-monitor");
        waiter.setDaemon(true);

        synchronized (monitor) {                        // 主线程持锁不放
            waiter.start();
            Thread.sleep(300);                          // 让子线程真正撞到 monitor 上
            waiter.interrupt();
            Thread.sleep(300);

            System.out.println("  interrupt 后 300ms：isAlive=" + waiter.isAlive()
                    + "，是否已拿到锁=" + acquired.get());
            assertTrue(waiter.isAlive(), "synchronized 抢锁不可中断 → interrupt 后线程仍在等");
            assertFalse(acquired.get(), "没等到锁，也没退出");
        }                                               // ← 主线程在这里释放 monitor

        waiter.join(2000);
        assertTrue(acquired.get(), "主线程释放锁后，子线程才拿到锁继续执行");
        assertTrue(flagCarried.get(), "标志一直留着 —— 被记录了，只是 synchronized 不看它");
    }

    /** Lock.lock() 同样不可中断（可中断版本是 lockInterruptibly，见类别一） */
    @Test
    public void category3b_lockDotLockIgnoresInterrupt() throws InterruptedException {
        System.out.println("=== 类别三之二：Lock.lock() 不可中断 ===");

        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean acquired = new AtomicBoolean(false);

        lock.lock();
        Thread waiter = new Thread(() -> {
            lock.lock();                                // ← 不可中断版本
            acquired.set(true);
            lock.unlock();
        }, "blocked-on-lock");
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(300);
        waiter.interrupt();
        Thread.sleep(300);

        System.out.println("  interrupt 后 300ms：isAlive=" + waiter.isAlive() + "（仍在 AQS 队列里排队）");
        assertTrue(waiter.isAlive(), "lock() 不可中断 → 线程仍在等待");
        assertFalse(acquired.get());

        lock.unlock();                                  // 释放后子线程才拿到
        waiter.join(2000);
        assertTrue(acquired.get(), "只有主线程释放锁，它才走得了");
    }

    /**
     * 传统阻塞 Socket IO：read() 卡在内核态，不响应 interrupt，也不抛异常。
     * 唯一的解救方式是关闭 socket 或设 soTimeout。
     * 注：HotSpot 上的既定行为；若某平台实现不同，此断言会失败，可据此调整认知。
     */
    @Test
    public void category3c_classicSocketIoIgnoresInterrupt() throws Exception {
        System.out.println("=== 类别三之三：传统阻塞 Socket IO 不可中断 ===");

        AtomicReference<Socket> clientRef = new AtomicReference<>();
        AtomicBoolean readReturned = new AtomicBoolean(false);

        try (ServerSocket server = new ServerSocket(0)) {          // 端口 0 → 随机端口
            Thread reader = new Thread(() -> {
                try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
                    clientRef.set(client);
                    client.getInputStream().read();            // 服务端不写数据 → 永久阻塞
                    readReturned.set(true);
                } catch (Exception e) {
                    System.out.println("  关闭 socket 后 read() 抛出: " + e.getClass().getSimpleName());
                }
            }, "blocked-on-socket-read");
            reader.setDaemon(true);
            reader.start();

            for (int i = 0; i < 40 && clientRef.get() == null; i++) {
                Thread.sleep(50);                              // 等 TCP 建连完成
            }

            reader.interrupt();
            Thread.sleep(500);
            System.out.println("  interrupt 后 500ms：read() 是否返回=" + readReturned.get()
                    + "，线程是否还活着=" + reader.isAlive());
            assertFalse(readReturned.get(), "传统阻塞 Socket IO 不响应 interrupt");
            assertTrue(reader.isAlive(), "线程仍卡在 read 上");

            clientRef.get().close();                           // ← 唯一能让它出来的办法
            reader.join(2000);
            System.out.println("  关闭 socket 后线程退出: " + !reader.isAlive());
            assertFalse(reader.isAlive(), "关闭 socket 才解得了阻塞");
        }
    }

    /** 类别三的兜底改法：把"无限等"换成"有限等"，让不可中断的等待重新变得可中断 */
    @Test
    public void category3_fix_byPollingWithTimeout() throws InterruptedException {
        System.out.println("=== 类别三的兜底：tryLock(超时) 把不可中断变成可中断 ===");

        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean gaveUp = new AtomicBoolean(false);

        lock.lock();
        Thread poller = new Thread(() -> {
            try {
                if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {   // ← 带超时，且响应中断
                    gaveUp.set(true);
                    return;
                }
                lock.unlock();
            } catch (InterruptedException e) {
                gaveUp.set(true);                                  // 等待中被中断 → 照样能退出
                Thread.currentThread().interrupt();
            }
        }, "polling-lock");
        poller.setDaemon(true);
        poller.start();

        Thread.sleep(100);
        poller.interrupt();
        poller.join(2000);

        System.out.println("  子线程是否成功退出=" + gaveUp.get());
        assertTrue(gaveUp.get(), "tryLock(超时) 响应中断 → 停在等待中的任务也能配合停机");
        assertFalse(poller.isAlive());
        lock.unlock();
    }

    // ==================== 工具 ====================

    private static final BlockingQueue<String> EMPTY_QUEUE = new ArrayBlockingQueue<>(1);

    @FunctionalInterface
    private interface BlockingAction {
        void run() throws InterruptedException;
    }

    private static final class Probe {
        volatile String outcome = "（未返回）";
        volatile boolean flagInsideCatch;
    }

    /** 让动作在独立线程上跑起来 → 等它真正阻塞 → interrupt → 记录它怎么反应 */
    private static Probe interruptAfter(String name, BlockingAction action) throws InterruptedException {
        Probe probe = new Probe();
        Thread t = new Thread(() -> {
            try {
                action.run();
                probe.outcome = "正常返回（没被打断）";
            } catch (InterruptedException e) {
                probe.flagInsideCatch = Thread.currentThread().isInterrupted();  // 关键观察点
                probe.outcome = "抛 InterruptedException";
                Thread.currentThread().interrupt();                              // 恢复标志，别吞
            } catch (Exception e) {
                probe.outcome = "抛 " + e.getClass().getSimpleName();
            }
        }, name);
        t.setDaemon(true);
        t.start();

        Thread.sleep(300);
        t.interrupt();
        t.join(2000);

        if ("抛 InterruptedException".equals(probe.outcome)) {
            System.out.println("  " + pad(name) + " → 抛 InterruptedException，catch 内 isInterrupted()="
                    + probe.flagInsideCatch);
        } else {
            System.out.println("  " + pad(name) + " → " + probe.outcome);
        }
        return probe;
    }

    private static Thread daemonThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    private static void interruptibleSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String pad(String s) {
        return s.length() >= 20 ? s : s + " ".repeat(20 - s.length());
    }
}

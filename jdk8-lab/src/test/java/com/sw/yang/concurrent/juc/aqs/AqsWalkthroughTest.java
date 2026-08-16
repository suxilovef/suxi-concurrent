package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AQS + ReentrantLock 源码走读配套实验（场景 1~5）
 *
 * 用法：跑每个 @Test 前，先在对应源码位置下断点或打印日志对照：
 *   - 场景 1：ReentrantLock.lock → NonfairSync.lock → AQS.compareAndSetState
 *   - 场景 2：AQS.addWaiter/enq → acquireQueued → shouldParkAfterFailedAcquire → release/unparkSuccessor
 *   - 场景 3：Sync.nonfairTryAcquire 重入分支 / tryRelease
 *   - 场景 4：AQS.doAcquireInterruptibly / doAcquireNanos
 *   - 场景 5：AQS await()/signal()/transferForSignal
 *
 * 观察重点（对应文档 03-01 / 03-02）：
 *   a. 场景 2：T2 排队后线程状态是 WAITING（已 park）
 *   b. 场景 5：signal 之后 C 仍是 WAITING（signal 只搬家不唤醒！），
 *      unlock 之后才被唤醒 —— 验证"SIGNAL 接力"
 */
public class AqsWalkthroughTest {

    /** 场景 1：无竞争获取 —— lock() 快路径 */
    @Test
    public void scene1_noContention() {
        System.out.println("当前 JDK: " + System.getProperty("java.version"));

        ReentrantLock lock = new ReentrantLock();          // 默认非公平（NonfairSync）

        System.out.println("【1】lock() 入口：NonfairSync.lock() → CAS(state, 0, 1)");
        lock.lock();
        System.out.println("【2】已持有：可重入次数 = " + lock.getHoldCount());

        System.out.println("【3】unlock() 入口：release(1) → tryRelease");
        lock.unlock();
        System.out.println("【4】已释放");
    }

    /** 场景 2：竞争排队 —— T2 CAS 失败 → 入队 → park → 被唤醒 */
    @Test
    public void scene2_contention() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();                                        // 主线程先持锁（扮演 T1）

        Thread t2 = new Thread(() -> {
            System.out.println("T2: 开始 lock()（将发生：CAS 失败 → addWaiter 入队 → park）");
            lock.lock();                                    // 阻塞点：park 直到被唤醒
            System.out.println("T2: lock() 返回，已拿到锁（setHead 晋升完成）");
            lock.unlock();
        }, "T2");
        t2.start();

        Thread.sleep(200);                                  // 等 T2 完成入队 + park
        System.out.println("观察 a：T2 线程状态（应为 WAITING）: " + t2.getState());

        System.out.println("T1(主): unlock() → release → unparkSuccessor 唤醒 T2");
        lock.unlock();
        t2.join();
        System.out.println("观察 b：T2 已结束（TERMINATED）");
    }

    /** 场景 3：可重入 —— 同线程二次 lock，state 叠加 */
    @Test
    public void scene3_reentrant() {
        ReentrantLock lock = new ReentrantLock();

        lock.lock();                                        // 第 1 次：state 0→1
        lock.lock();                                        // 第 2 次：重入分支 state 1→2
        System.out.println("【1】重入 2 次后 state = " + lock.getHoldCount() + "（expect 2）");

        lock.unlock();                                      // state 2→1，锁还在
        System.out.println("【2】unlock 1 次后 state = " + lock.getHoldCount() + "（expect 1）");

        lock.unlock();                                      // state 1→0，真正释放
        System.out.println("【3】unlock 2 次后 state = " + lock.getHoldCount() + "（expect 0）");
    }

    /** 场景 4：可中断 —— lockInterruptibly 等待中被打断 */
    @Test
    public void scene4_interruptible() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();                                        // 主线程持锁

        Thread t = new Thread(() -> {
            try {
                lock.lockInterruptibly();                   // 拿不到 → doAcquireInterruptibly → park
                System.out.println("T: 拿到锁（不该到这里）");
                lock.unlock();
            } catch (InterruptedException e) {
                System.out.println("T: 等待中被中断 → InterruptedException（可中断语义）");
            }
        }, "T");
        t.start();

        Thread.sleep(200);
        System.out.println("主线程: t.interrupt()");
        t.interrupt();
        t.join();

        lock.unlock();
    }

    /** 场景 5：Condition —— await/signal 的两队切换 + SIGNAL 接力 */
    @Test
    public void scene5_condition() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        Condition notEmpty = lock.newCondition();

        Thread consumer = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("C: 持锁，条件不满足 → notEmpty.await()");
                notEmpty.await();                           // 入条件队列 → 全量释放锁 → park
                System.out.println("C: await 返回（已重新抢到锁），条件满足");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "C");
        consumer.start();

        Thread.sleep(200);                                  // C 已 await，锁应已释放
        lock.lock();                                        // 主线程拿锁
        System.out.println("主线程: lock() 成功 —— 证明 await 已释放锁");
        System.out.println("观察 a：C 状态（应为 WAITING）: " + consumer.getState());

        notEmpty.signal();                                  // 只把 C 搬去同步队列，不 unpark
        Thread.sleep(100);
        System.out.println("观察 b：signal 之后 C 状态（应仍是 WAITING，信号接力中）: " + consumer.getState());

        lock.unlock();                                      // 这里才触发 unparkSuccessor 唤醒 C
        consumer.join();
        System.out.println("观察 c：C 已结束（unlock 才真正唤醒它）");
    }
}

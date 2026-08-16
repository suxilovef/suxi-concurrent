package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 2：lock() 与 lockInterruptibly() 对中断的响应差异
 */
public class InterruptTest {

    /**
     * lock() 不响应中断：中断标志被记录，但线程继续等待
     */
    @Test
    public void testLockNotInterruptible() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock(); // 主线程先持锁

        Thread t = new Thread(() -> {
            System.out.println("子线程开始 lock()...");
            lock.lock(); // 拿不到锁，无限等待
            System.out.println("子线程获得了锁（主线程释放后）");
            lock.unlock();
        }, "waiter");
        t.start();

        Thread.sleep(500);
        t.interrupt(); // 中断子线程
        Thread.sleep(500);
        System.out.println("lock() 子线程是否还活着（不响应中断）: " + t.isAlive());
        lock.unlock(); // 释放锁，让子线程结束
        t.join();
    }

    /**
     * lockInterruptibly() 响应中断：等待中收到 interrupt → 抛异常退出
     */
    @Test
    public void testLockInterruptibly() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        lock.lock(); // 主线程先持锁

        Thread t = new Thread(() -> {
            System.out.println("子线程开始 lockInterruptibly()...");
            try {
                lock.lockInterruptibly();
                System.out.println("子线程获得了锁");
                lock.unlock();
            } catch (InterruptedException e) {
                System.out.println("子线程收到中断，抛 InterruptedException → 退出等待");
                Thread.currentThread().interrupt(); // 恢复中断标志
            }
        }, "waiter");
        t.start();

        Thread.sleep(500);
        t.interrupt(); // 中断子线程
        t.join(2000);
        System.out.println("lockInterruptibly 子线程是否还活着: " + t.isAlive());
        lock.unlock();
    }
}

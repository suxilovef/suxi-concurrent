package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 1：观察公平锁与非公平锁的线程获取顺序
 *
 * 实验设计：
 * 1. 一个线程持锁 100ms（制造排队）
 * 2. 10 个线程排队
 * 3. 观察获取顺序：公平锁严格按编号，非公平锁可能插队
 */
public class FairnessTest {

    @Test
    public void testFairness() throws InterruptedException {
        testLock(new ReentrantLock(true), "公平锁");
        testLock(new ReentrantLock(false), "非公平锁");
    }

    private void testLock(ReentrantLock lock, String name) throws InterruptedException {
        System.out.println("\n=== " + name + " ===");
        final int[] acquireOrder = new int[10];
        final int[] counter = {0};

        // 占锁线程：持有 100ms 再释放，制造排队
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "holder");
        holder.start();
        Thread.sleep(10); // 确保 holder 已持锁

        // 10 个等待线程同时启动（排队）
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                lock.lock();
                try {
                    acquireOrder[counter[0]++] = id; // 记录获取顺序
                } finally {
                    lock.unlock();
                }
            }, "T" + i);
            threads[i].start();
        }

        holder.join();
        for (Thread t : threads) t.join();

        System.out.print("获取顺序: ");
        for (int i = 0; i < 10; i++) {
            System.out.print("T" + acquireOrder[i] + " ");
        }
        System.out.println();
        System.out.println(name + "下获取顺序" + (name.equals("公平锁") ? "严格按编号" : "可能乱序（插队）"));
    }
}

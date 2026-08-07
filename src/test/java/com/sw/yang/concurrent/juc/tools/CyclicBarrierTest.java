package com.sw.yang.concurrent.juc.tools;

import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * 练习 2：CyclicBarrier 实现两阶段并行计算
 *
 * 场景：报表生成分两阶段，每阶段所有线程完成后才能进入下一阶段
 */
public class CyclicBarrierTest {

    @Test
    public void testTwoPhase() throws InterruptedException {
        final int threadCount = 3;
        CyclicBarrier barrier = new CyclicBarrier(threadCount, () ->
                System.out.println("=== 屏障触发：本阶段全部完成 ==="));

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int id = i + 1;
            threads[i] = new Thread(() -> {
                try {
                    // 阶段 1：并行统计
                    Thread.sleep(100 * id);
                    System.out.println("线程" + id + " 完成阶段1（统计）");
                    barrier.await();

                    // 阶段 2：并行渲染（必须等所有统计完成）
                    Thread.sleep(50 * id);
                    System.out.println("线程" + id + " 完成阶段2（渲染）");
                    barrier.await();

                    System.out.println("线程" + id + " 全部完成 ✅");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException e) {
                    System.out.println("线程" + id + " 屏障被破坏: " + e.getMessage());
                }
            }, "T" + id);
            threads[i].start();
        }

        for (Thread t : threads) t.join();
        System.out.println("✅ 两阶段并行完成（屏障自动重置了 2 次）");
    }

    /**
     * 演示：CountDownLatch 无法复用，CyclicBarrier 可以
     */
    @Test
    public void testReusable() throws InterruptedException, BrokenBarrierException {
        CyclicBarrier barrier = new CyclicBarrier(2);

        // 第一轮
        Thread t1 = new Thread(() -> {
            try {
                barrier.await();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("第一轮：两个线程都到了");
        });
        t1.start();
        barrier.await();
        t1.join();

        // 第二轮（无需重建对象！）
        Thread t2 = new Thread(() -> {
            try {
                barrier.await();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("第二轮：CyclicBarrier 复用成功");
        });
        t2.start();
        barrier.await();
        t2.join();

        System.out.println("✅ CyclicBarrier 可循环使用（CountDownLatch 做不到）");
    }
}

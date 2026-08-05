package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

/**
 * 练习 1：验证 volatile 解决可见性问题
 *
 * 实验步骤：
 * 1. 先运行 way1_noVolatile() — 去掉 flag 的 volatile，观察 reader 是否退不出
 * 2. 给 flag 加 volatile — reader 立即退出
 * 3. 理解 happens-before 规则③：volatile 写 happens-before volatile 读
 */
public class VisibilityTest {

    // TODO 步骤1：不加 volatile，运行 way1，大概率 reader 线程退不出
    // TODO 步骤2：加上 volatile，再次运行，reader 线程立即退出
    private static volatile boolean flag = false;  // 试着去掉 volatile 看看

    @Test
    public void way1_volatileDemo() throws InterruptedException {
        Thread reader = new Thread(() -> {
            int count = 0;
            while (!flag) {
                count++;
                // 提示：如果循环内加 System.out.println，内部 synchronized 会
                // 强制刷新缓存，即使没有 volatile 也可能"碰巧"正确
            }
            System.out.println("Reader 退出，自旋次数: " + count);
        }, "reader-thread");
        reader.start();

        Thread.sleep(1000); // 确保 reader 先跑起来

        flag = true; // volatile 写
        System.out.println("Writer 已设置 flag = true");

        reader.join(5000);
        if (reader.isAlive()) {
            System.out.println("❌ 5 秒后 reader 仍在运行 → 可见性问题复现！");
        } else {
            System.out.println("✅ reader 正常退出 → volatile 保证了可见性");
        }
    }

    /**
     * 对比实验：循环内有 println 的情况
     * println 内部有 synchronized，会强制从主内存刷新，可能"修复"可见性问题
     */
    @Test
    public void way2_withPrintln() throws InterruptedException {
        Thread reader = new Thread(() -> {
            int count = 0;
            while (!flag) {
                count++;
                if (count % 100_000_000 == 0) {
                    System.out.println("reader 仍在等待... count=" + count);
                }
            }
            System.out.println("Reader 退出, 总自旋次数: " + count);
        }, "reader-thread");
        reader.start();

        Thread.sleep(2000);
        flag = true;
        System.out.println("Writer 已设置 flag = true");

        reader.join(10000);
        System.out.println("Reader 是否还活着: " + reader.isAlive());
    }
}

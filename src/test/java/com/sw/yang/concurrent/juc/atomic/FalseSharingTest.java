package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证伪共享对性能的影响
 *
 * 对比：两个线程各自累加相邻变量（伪共享）vs 填充隔离（无伪共享）
 *
 * 注意：结果受 CPU 架构影响，多核机器上差异明显；
 *       单核或低核机器可能差异不大
 */
public class FalseSharingTest {

    private static final int ITERATIONS = 100_000_000;

    // ❌ 伪共享：count1 和 count2 相邻（同一缓存行）
    // ⚠️ 必须加 volatile —— 否则 JIT 可能把字段提升到寄存器，
    //    两个线程各自用寄存器累加，反而"更快"，结论会被反转！
    static class AdjacentCounters {
        volatile long count1;
        volatile long count2;
    }

    // ✅ 无伪共享：填充隔离（每个 count 独占缓存行）
    static class PaddedCounters {
        long p1, p2, p3, p4, p5, p6, p7; // 填充 56 字节
        volatile long count1;
        long q1, q2, q3, q4, q5, q6, q7; // 填充 56 字节
        volatile long count2;
    }

    @Test
    public void testFalseSharing() throws InterruptedException {
        AdjacentCounters adjacent = new AdjacentCounters();
        PaddedCounters padded = new PaddedCounters();

        // 伪共享版本
        long start = System.currentTimeMillis();
        Thread t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) adjacent.count1++; });
        Thread t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) adjacent.count2++; });
        t1.start(); t2.start();
        t1.join(); t2.join();
        long adjacentTime = System.currentTimeMillis() - start;

        // 无伪共享版本
        start = System.currentTimeMillis();
        Thread t3 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) padded.count1++; });
        Thread t4 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) padded.count2++; });
        t3.start(); t4.start();
        t3.join(); t4.join();
        long paddedTime = System.currentTimeMillis() - start;

        System.out.println("伪共享:     " + adjacentTime + "ms");
        System.out.println("无伪共享:   " + paddedTime + "ms");
        System.out.println("加速比:     " + String.format("%.1f", (double) adjacentTime / paddedTime) + "x");
        System.out.println(adjacentTime > paddedTime
                ? "✅ 伪共享确实拖慢性能（多核机器效果明显）"
                : "（本机差异不明显，可能单核/低核，换多核机器再试）");
    }
}

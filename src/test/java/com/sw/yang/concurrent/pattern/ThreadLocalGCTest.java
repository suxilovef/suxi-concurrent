package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：观察 ThreadLocal value 无法被 GC 回收
 *
 * 实验：key 是弱引用可回收，但 value 被 ThreadLocalMap 强引用不可回收，
 *       直到 remove() 或线程结束
 */
public class ThreadLocalGCTest {

    @Test
    public void testValueNotCollected() throws InterruptedException {
        // 大对象（模拟泄漏的 value）
        byte[] bigData = new byte[1024 * 1024 * 10]; // 10MB

        ThreadLocal<byte[]> tl = new ThreadLocal<>();
        tl.set(bigData);
        bigData = null; // 外部引用断开

        // 提示 GC
        System.gc();
        Thread.sleep(1000);

        // ThreadLocal 对象没有外部引用 → 弱引用 key 可回收
        // 但 value（10MB）被 ThreadLocalMap 强引用 → 无法回收
        // 直到调用 remove() 或线程结束

        System.out.println("GC 后：ThreadLocal 对象（key）可回收，但 value（10MB）仍被线程的 ThreadLocalMap 强引用");
        System.out.println("→ 这就是泄漏点：不 remove()，value 无法释放");

        tl.remove(); // 主动清理后 value 才可回收
        System.gc();
        Thread.sleep(1000);
        System.out.println("✅ remove 后 value 才被释放（可用 JVisualVM/内存监控观察）");
    }
}

package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * 练习 1：用 CAS + volatile 手写一个线程安全的计数器
 *
 * 目标：
 * 1. 理解 CAS 自旋的基本模式
 * 2. 理解反射获取 Unsafe 的必要性
 * 3. 验证多线程自增的正确性
 */
public class CasCounterTest {

    @Test
    public void testMyCasCounter() throws InterruptedException {
        MyCasCounter counter = new MyCasCounter();

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    counter.increment();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("预期: 100000");
        System.out.println("实际: " + counter.get());
        System.out.println(counter.get() == 100000 ? "✅ CAS 计数器正确" : "❌ 异常");
    }
}

/**
 * 基于 CAS + volatile 的简易线程安全计数器
 *
 * 核心原理：
 * - volatile 保证每次读到的 value 是最新值
 * - CAS 保证"读-改-写"的原子性
 * - 自旋循环：CAS 失败 → 重读 → 重试
 */
class MyCasCounter {
    private volatile int value;

    private static final Unsafe U;
    private static final long VALUE_OFFSET;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
            VALUE_OFFSET = U.objectFieldOffset(
                    MyCasCounter.class.getDeclaredField("value"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public void increment() {
        int current, next;
        do {
            current = value;     // volatile 读
            next = current + 1;
        } while (!U.compareAndSwapInt(this, VALUE_OFFSET, current, next));
    }

    public int get() {
        return value;           // volatile 读
    }
}

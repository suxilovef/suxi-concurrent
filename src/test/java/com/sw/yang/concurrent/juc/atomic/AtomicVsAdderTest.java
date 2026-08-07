package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 练习 1：对比 AtomicLong vs LongAdder 在不同竞争强度下的性能
 *
 * 预期结果：
 * - 线程少（竞争低）：AtomicLong 略快
 * - 线程多（竞争高）：LongAdder 明显更快
 */
public class AtomicVsAdderTest {

    private static final int ITERATIONS = 10_000_000;

    @Test
    public void testCompare() throws InterruptedException {
        testAtomicLong(2);
        testLongAdder(2);
        testAtomicLong(8);
        testLongAdder(8);
        testAtomicLong(32);
        testLongAdder(32);
    }

    private void testAtomicLong(int threads) throws InterruptedException {
        AtomicLong counter = new AtomicLong(0);
        long start = System.currentTimeMillis();

        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS / threads; j++) {
                    counter.incrementAndGet();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("AtomicLong " + threads + " 线程: " + elapsed + "ms, 结果=" + counter.get());
    }

    private void testLongAdder(int threads) throws InterruptedException {
        LongAdder counter = new LongAdder();
        long start = System.currentTimeMillis();

        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS / threads; j++) {
                    counter.increment();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("LongAdder " + threads + " 线程: " + elapsed + "ms, 结果=" + counter.sum());
    }
}

package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.StampedLock;

/**
 * 练习 3：StampedLock 乐观读 + 写锁
 *
 * 核心：tryOptimisticRead 不锁直接读，validate 校验是否被写
 */
public class StampedLockTest {

    static class Point {
        private double x, y;
        private final StampedLock lock = new StampedLock();

        // 写操作
        public void move(double dx, double dy) {
            long stamp = lock.writeLock();
            try {
                x += dx;
                y += dy;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        // 乐观读：不加锁读，最后校验
        public double distanceFromOrigin() {
            long stamp = lock.tryOptimisticRead(); // ① 乐观读（不锁）
            double currentX = x;
            double currentY = y;

            if (!lock.validate(stamp)) {           // ② 校验：期间是否被写
                stamp = lock.readLock();           // ③ 失败 → 悲观读
                try {
                    currentX = x;
                    currentY = y;
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return Math.sqrt(currentX * currentX + currentY * currentY);
        }
    }

    @Test
    public void testStampedLock() throws InterruptedException {
        Point point = new Point();

        // 写线程
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                point.move(1, 1);
            }
        }, "writer");
        writer.start();

        // 读线程（乐观读）
        Thread[] readers = new Thread[5];
        for (int i = 0; i < 5; i++) {
            readers[i] = new Thread(() -> {
                double sum = 0;
                for (int j = 0; j < 10000; j++) {
                    sum += point.distanceFromOrigin();
                }
                System.out.println(Thread.currentThread().getName() + " 总距离: " + sum);
            }, "reader-" + i);
            readers[i].start();
        }

        for (Thread t : readers) t.join();
        writer.join();
        System.out.println("✅ StampedLock 乐观读运行完成（读无锁 + 写互斥）");
    }
}

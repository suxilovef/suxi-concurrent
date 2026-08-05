package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 synchronized 可重入
 *
 * 可重入 = 同一个线程可以重复获取同一把锁，不会把自己锁死
 */
public class ReentrantTest {

    private final Object lock = new Object();
    private int depth = 0;

    /**
     * 递归方法，每次进入都获取同一把锁
     * 如果 synchronized 不可重入 → 第 2 层就死锁了
     */
    public void reentrantMethod() {
        synchronized (lock) {
            depth++;
            System.out.println("已进入第 " + depth + " 层，线程: " + Thread.currentThread().getName());
            if (depth < 3) {
                reentrantMethod(); // 递归调用，再次获取同一把锁
            }
            depth--;
        }
    }

    @Test
    public void testReentrant() throws InterruptedException {
        System.out.println("=== 递归可重入测试（同一线程）===");
        reentrantMethod();
        System.out.println("✅ 同一线程递归获取同一把锁 3 次，未死锁 → 可重入");

        System.out.println("\n=== 跨线程测试（其他线程等待锁）===");
        Thread t = new Thread(() -> {
            synchronized (lock) {
                System.out.println("线程 " + Thread.currentThread().getName() + " 获取锁成功");
            }
        }, "other-thread");
        t.start();
        t.join();
        System.out.println("✅ 其他线程正常获取锁（锁已被释放）");
    }
}

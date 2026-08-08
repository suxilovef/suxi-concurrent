package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 1：验证线程池场景下 ThreadLocal 的 value 残留（泄漏）
 *
 * 实验设计：
 * 1. 固定线程池（1 个线程）+ ThreadLocal
 * 2. 提交多个任务，每个任务 set 不同数据但不 remove
 * 3. 观察：第二个任务能读到第一个任务残留的 value（串号！）
 */
public class ThreadLocalLeakTest {

    private static final ThreadLocal<String> context = new ThreadLocal<>();

    @Test
    public void testLeakInPool() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("leak-worker");
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        // 任务 1：设置上下文，但不 remove（模拟忘记）
        pool.execute(() -> {
            context.set("任务1的用户");
            System.out.println("任务 1 设置: " + context.get());
            // 忘记 remove！
        });

        Thread.sleep(200);

        // 任务 2：没有 set，直接 get
        pool.execute(() -> {
            String value = context.get();
            System.out.println("任务 2 读到: " + value);
            System.out.println("（任务 2 没有 set，却读到了任务 1 的值 → 串号！）");
            context.remove(); // 修复示范
        });

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 演示完成：不 remove 会导致数据串号 + 泄漏");
    }

    @Test
    public void testRemoveFix() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("fix-worker");
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        // 正确写法：try-finally + remove
        pool.execute(() -> {
            context.set("任务A");
            try {
                System.out.println("任务 A: " + context.get());
            } finally {
                context.remove();
            }
        });

        Thread.sleep(200);

        pool.execute(() -> {
            String value = context.get();
            System.out.println("任务 B 读到: " + value + "（null = 没有串号 ✅）");
            if (value == null) {
                System.out.println("✅ remove 生效：任务 B 拿不到任务 A 的残留");
            }
        });

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}

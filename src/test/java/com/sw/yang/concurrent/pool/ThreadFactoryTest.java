package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 3：线程工厂命名 + 任务异常捕获
 */
public class ThreadFactoryTest {

    @Test
    public void testFactoryAndException() throws InterruptedException {
        // 自定义线程工厂：命名 + 异常处理器
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("biz-task-" + t.getId());
            t.setUncaughtExceptionHandler((thread, e) ->
                    System.out.println("线程 " + thread.getName() + " 异常: " + e.getMessage()));
            return t;
        };

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        // 提交一个会抛异常的任务
        pool.execute(() -> {
            System.out.println("执行任务（线程: " + Thread.currentThread().getName() + "）");
            throw new RuntimeException("业务异常");
        });

        // 再提交一个正常任务 → 验证线程池是否还能工作（会自动补线程）
        Thread.sleep(200);
        pool.execute(() ->
                System.out.println("第二个任务执行（线程: " + Thread.currentThread().getName() + "）"));

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 异常被捕获，线程池未崩溃");
    }
}

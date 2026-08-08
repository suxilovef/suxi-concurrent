package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 1：验证四种拒绝策略的行为差异
 *
 * 配置：核心 1，最大 1，队列 1 → 提交 3 个任务 → 第 3 个必被拒绝
 */
public class RejectPolicyTest {

    private ThreadPoolExecutor createPool(RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("reject-worker");
                    return t;
                },
                handler);
    }

    @Test
    public void testAbortPolicy() throws InterruptedException {
        ThreadPoolExecutor pool = createPool(new ThreadPoolExecutor.AbortPolicy());
        submit3Tasks(pool); // 第 3 个抛 RejectedExecutionException
        pool.shutdown();
        System.out.println("✅ AbortPolicy：第 3 个任务抛异常（默认策略）");
    }

    @Test
    public void testCallerRunsPolicy() throws InterruptedException {
        ThreadPoolExecutor pool = createPool(new ThreadPoolExecutor.CallerRunsPolicy());
        submit3Tasks(pool); // 第 3 个由主线程（调用 execute 的线程）执行
        pool.shutdown();
        System.out.println("✅ CallerRunsPolicy：第 3 个任务由 " + Thread.currentThread().getName() + " 执行");
    }

    @Test
    public void testDiscardPolicy() throws InterruptedException {
        ThreadPoolExecutor pool = createPool(new ThreadPoolExecutor.DiscardPolicy());
        submit3Tasks(pool); // 第 3 个静默丢弃
        pool.shutdown();
        System.out.println("✅ DiscardPolicy：第 3 个任务被静默丢弃（无异常无日志）");
    }

    @Test
    public void testDiscardOldestPolicy() throws InterruptedException {
        ThreadPoolExecutor pool = createPool(new ThreadPoolExecutor.DiscardOldestPolicy());
        submit3Tasks(pool); // 第 3 个挤掉队首任务
        pool.shutdown();
        System.out.println("✅ DiscardOldestPolicy：第 3 个任务挤掉了队列里最老的任务");
    }

    private void submit3Tasks(ThreadPoolExecutor pool) {
        // 任务 1：占住唯一线程
        pool.execute(() -> {
            System.out.println("任务 1 执行（" + Thread.currentThread().getName() + "）");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 任务 2：进队列
        pool.execute(() -> System.out.println("任务 2 执行（" + Thread.currentThread().getName() + "）"));
        // 任务 3：队列满 + 线程满 → 触发拒绝策略
        try {
            pool.execute(() -> System.out.println("任务 3 执行（" + Thread.currentThread().getName() + "）"));
        } catch (RejectedExecutionException e) {
            System.out.println("任务 3 被拒绝（AbortPolicy 抛异常）");
        }
    }
}

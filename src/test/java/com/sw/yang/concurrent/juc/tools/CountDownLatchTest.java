package com.sw.yang.concurrent.juc.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 练习 1：CountDownLatch 等待多个数据源查询完成再汇总
 *
 * 真实场景：接口并发查询用户信息、订单信息、优惠信息，全部完成后聚合返回
 */
public class CountDownLatchTest {

    @Test
    public void testAggregate() throws InterruptedException {
        final int taskCount = 3;
        CountDownLatch latch = new CountDownLatch(taskCount);
        Map<String, String> results = new ConcurrentHashMap<>();

        long start = System.currentTimeMillis();

        // 并发执行 3 个查询任务
        for (int i = 1; i <= taskCount; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    Thread.sleep(200 * id); // 模拟不同耗时
                    results.put("source-" + id, "data-" + id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown(); // ✅ 必须 finally！
                }
            }, "query-" + id).start();
        }

        // 限时等待（生产规范：不无限等）
        boolean allDone = latch.await(3, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("汇总结果: " + results);
        System.out.println("总耗时: " + elapsed + "ms（串行需要 1200ms，并行约 600ms）");
        System.out.println(allDone
                ? "✅ 所有任务完成（并行使总耗时 ≈ 最慢任务）"
                : "❌ 超时，部分任务未完成");
    }

    /**
     * 对比：忘记 countDown 的效果（演示死锁风险）
     * 用 await(2s) 限时避免永久挂起
     */
    @Test
    public void testForgotCountDown() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                Thread.sleep(100);
                // 忘了 latch.countDown()！模拟 bug
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        boolean done = latch.await(2, TimeUnit.SECONDS);
        System.out.println(done ? "（正常完成）" : "❌ 2 秒超时——子线程忘了 countDown，await 永远等不到");
        System.out.println("（这就是为什么生产要用限时 await + countDown 放 finally）");
    }
}

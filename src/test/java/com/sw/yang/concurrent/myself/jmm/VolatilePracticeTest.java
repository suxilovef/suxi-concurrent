package com.sw.yang.concurrent.myself.jmm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-08-23
 * @Description:
 * @Version: 1.0
 */
public class VolatilePracticeTest {
    private boolean running = true;
    private final AtomicInteger processedCount = new AtomicInteger(0);

    @Test
    public void testGracefulShutdown() throws InterruptedException {
        Thread worker =  new Thread(() -> {
            while (running) {
                processedCount.incrementAndGet();
            }

            System.out.print("Worker 检测到 running = false，开始清理。。。。");

        },"worker");
        worker.start();

        // 让 worker 跑一会儿，给 JIT 编译时间
        //（无 volatile 时 JIT 把 running 提升出循环 → 循环永不退出）
        Thread.sleep(2000);
        System.out.println("已处理 " + processedCount.get() + " 个任务");

        // 优雅停机
        System.out.println("发送停机信号...");
        running = false;
        // 忙等循环不会阻塞，不需要 interrupt 唤醒；
        // interrupt 只用于唤醒"阻塞中的任务"（如 sleep / wait / blockingQueue.take）

        worker.join(3000);
        System.out.println("停机后任务数: " + processedCount.get());
        System.out.println(worker.isAlive() ? "❌ Worker 未能退出" : "✅ Worker 已安全退出");

    }
}

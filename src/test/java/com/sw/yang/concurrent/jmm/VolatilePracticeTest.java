package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：用 volatile 实现优雅停机
 *
 * 模拟 Worker 线程处理任务，支持随时安全停止
 * 分别测试有 volatile 和无 volatile 的行为差异
 */
public class VolatilePracticeTest {

    // TODO: 试试去掉 volatile，观察 shutdown 后 worker 是否能及时退出
    private volatile boolean running = true;
    private final AtomicInteger processedCount = new AtomicInteger(0);

    @Test
    public void testGracefulShutdown() throws InterruptedException {
        Thread worker = new Thread(() -> {
            while (running) {
                processedCount.incrementAndGet();
                try {
                    Thread.sleep(10); // 模拟 IO 操作
                } catch (InterruptedException e) {
                    System.out.println("Worker 被中断唤醒，继续检查 running 标志...");
                }
            }
            System.out.println("Worker 检测到 running=false，开始清理...");
            System.out.println("Worker 安全退出，共处理: " + processedCount.get());
        }, "worker");
        worker.start();

        Thread.sleep(2000);
        System.out.println("2 秒内已处理 " + processedCount.get() + " 个任务");

        System.out.println("发送停机信号...");
        running = false;
        worker.interrupt(); // 唤醒可能正在 sleep 的 worker

        worker.join(3000);
        System.out.println("停机后任务数: " + processedCount.get());
        System.out.println(worker.isAlive() ? "❌ Worker 未能退出" : "✅ Worker 已安全退出");
    }

    /**
     * 对比测试：不用 volatile，JIT 可能将 running 缓存到寄存器
     */
    @Test
    public void testWithoutVolatile() throws InterruptedException {
        // 局部变量，不能在 lambda 中修改，所以用数组包装
        boolean[] running = {true};
        AtomicInteger count = new AtomicInteger(0);

        Thread worker = new Thread(() -> {
            while (running[0]) {
                count.incrementAndGet();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // wake up
                }
            }
            System.out.println("Worker(无volatile) 退出, 共处理: " + count.get());
        }, "worker-no-volatile");
        worker.start();

        Thread.sleep(2000);
        System.out.println("发送停机...");
        running[0] = false;
        worker.interrupt();

        worker.join(3000);
        System.out.println(worker.isAlive() ? "❌ Worker(无volatile) 未能退出" : "✅ Worker(无volatile) 已退出");
        System.out.println("注意：无 volatile 的 boolean[] 也可能因其他原因（如中断后重新读取）退出，并不稳定");
    }
}

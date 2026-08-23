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

    /**
     * 复现实验：去掉 volatile 的"纯读自旋"循环，读会被 JIT 提升出循环 → 死循环
     *
     * 与 01-03 练习一 CAS 计数器的关键区别（循环形状不同，结论不可互套）：
     * - CAS 自旋：循环体内每轮迭代都对同一变量做条件写（lock cmpxchg）
     *   → load 被循环体内的写 kill 掉 → 不可能被提升 → 去掉 volatile 不会死循环
     * - 纯读自旋（本实验）：循环体内没有任何对 running[0] 的写
     *   → load 是循环不变量 → C2 的 LICM 把它提升出循环（寄存器缓存）
     *   → 主线程的 running[0]=false 永远不可见 → 死循环
     *
     * ⚠️ 循环体必须是"纯读"：实测在循环体内加一个 AtomicBoolean.get()（volatile 读）
     * 做"逃生门"，其 acquire 屏障会抑制提升，立刻复现不出来——这是"形状决定行为"
     * 的第二个例子，与 CAS 计数器的结论同源。
     *
     * 为什么需要等待 3 秒才发停机信号：解释执行 / C1 阶段每轮都重新读内存，
     * 能及时退出；只有 C2 编译 + OSR 之后读才被提升。紧循环毫秒级触发编译，3 秒留足余量。
     *
     * 复现后如何收尾（死循环线程没有"优雅"停止手段——这正是生产代码要避免
     * 写出这种循环的原因；JDK 21 的 Thread.stop() 已抛 UnsupportedOperationException）：
     * - 循环上界 MAX：只是寄存器比较，不引入内存操作，不影响提升；
     *   取值远超任何机器 5 秒内的自旋能力（防误报"未复现"），到界后 worker 自行退出
     * - daemon 线程兜底：即使 join 超时也不阻塞 JVM 退出
     */
    @Test
    public void testNoVolatileInfiniteLoopRepro() throws InterruptedException {
        // 非 volatile 标志：lambda 捕获的局部变量必须 effectively final，用数组包装
        boolean[] running = {true};
        final long MAX = 50_000_000_000L; // 自旋上界：本机实测约 2~3e9 次/秒 → 约 20 秒跑满

        Thread worker = new Thread(() -> {
            long spins = 0;
            while (running[0] && spins < MAX) { // 纯读 + 寄存器比较：体内无内存操作 → load 可被提升
                spins++;
            }
            System.out.println("Worker(无volatile纯读自旋) 退出, spins=" + spins);
        }, "worker-hoist-repro");
        worker.setDaemon(true); // 兜底：即使 join 超时也不阻塞 JVM 退出
        worker.start();

        // 等待 JIT 编译 + OSR 完成（紧循环毫秒级触发，3 秒留足余量）
        Thread.sleep(3000);

        System.out.println("发送停机信号 running=false ...");
        running[0] = false;

        worker.join(2000);
        if (worker.isAlive()) {
            System.out.println("✅ 复现：running=false 后 worker 仍在自旋 —— 读已被 JIT 提升，缺 volatile 导致修改不可见");
            worker.join(45_000); // 等自旋上界到达，worker 自行退出（本机约 20 秒，期间占一个核）
            System.out.println(worker.isAlive()
                    ? "⚠️ 上界未到 worker 仍未退出（daemon，不阻塞 JVM；本机实测不会走这里）"
                    : "Worker 已自行退出，未留下后台空转线程");
        } else {
            System.out.println("❌ 未复现：worker 已退出（可能尚未触发 C2 提升，可把等待时间从 3000 调大重试）");
        }
    }
}

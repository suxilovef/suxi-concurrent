package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

/**
 * 练习 3：模拟 CPU 飙高（死循环），练习排查思路
 *
 * 排查步骤（终端操作）：
 * 1. 运行此测试（它会一直死循环）
 * 2. top 找到 java 进程 PID
 * 3. top -Hp <pid> 找到 CPU 最高的线程
 * 4. printf "%x\n" <tid> 转十六进制
 * 5. jstack <pid> | grep -A 30 "0x<hex>" 看栈
 * 6. 定位到 cpuBurning 方法的死循环
 */
public class CpuSpikeTest {

    @Test
    public void testCpuSpike() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // 4 个 CPU 密集死循环线程（模拟 CPU 飙高）
        // ⚠️ 必须 setDaemon(true)：否则测试结束 JVM 不退出（非 daemon 线程在跑），surefire 会挂住
        for (int i = 0; i < 4; i++) {
            Thread burner = new Thread(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println(Thread.currentThread().getName() + " 开始死循环...");
                cpuBurning(); // ← jstack 会定位到这里
            }, "cpu-burner-" + i);
            burner.setDaemon(true); // ★ 关键：daemon 线程，测试结束自动终止
            burner.start();
        }

        latch.countDown();
        // 测试方法在这里"卡住"（4 个死循环线程在跑）
        // 利用这段时间执行 jstack 排查
        // 排查完手动停止测试
        Thread.sleep(10_000);
        System.out.println("（排查演示结束，正常情况应通过诊断定位 cpuBurning）");
    }

    /**
     * 模拟业务死循环（罪魁祸首）
     */
    private void cpuBurning() {
        double x = 0;
        while (true) {
            x += Math.sin(x) * 0.001; // 空转计算（让 CPU 忙）
        }
    }
}

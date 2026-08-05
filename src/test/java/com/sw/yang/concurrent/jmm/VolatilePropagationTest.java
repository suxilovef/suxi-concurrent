package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 volatile 的"附带刷新"效果
 *
 * volatile 写：不仅写回 volatile 变量本身，还将之前修改的普通变量一并刷新到主内存
 * volatile 读：不仅读到 volatile 变量最新值，还将工作内存置为无效，后续读主内存
 */
public class VolatilePropagationTest {

    private int normalValue = 0;             // 普通变量
    private volatile boolean signal = false; // volatile 信号

    @Test
    public void testVolatilePropagation() throws InterruptedException {
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100); // 确保 reader 先开始等待
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            normalValue = 42;    // ① 先写普通变量
            signal = true;       // ② 再写 volatile → 附带刷新 normalValue 到主内存
            System.out.println("Writer: normalValue=" + normalValue + ", signal=true");
        }, "writer");

        Thread reader = new Thread(() -> {
            int attempts = 0;
            while (!signal) {   // ③ 读 volatile → 附带刷新工作内存
                attempts++;
            }
            // ④ happens-before: ① hb ② hb ③ hb ④
            int value = normalValue;
            System.out.println("Reader: signal=true, normalValue=" + value +
                    " (自旋次数=" + attempts + ")");
            if (value == 42) {
                System.out.println("✅ volatile 附带刷新生效，普通变量的修改也可见");
            } else {
                System.out.println("❌ 不应该出现此情况");
            }
        }, "reader");

        reader.start();
        writer.start();

        writer.join();
        reader.join(5000);
    }

    /**
     * 反向测试：先写 signal 再写 normalValue —— volatile 的附带刷新只在写之前
     * 这种情况下 reader 不一定能看到 normalValue = 42
     */
    @Test
    public void testReverseOrder() throws InterruptedException {
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            signal = true;       // ① volatile 写 → 此时 normalValue 还是旧值 0
            normalValue = 42;    // ② 普通写 → 没有 happens-before 保证！
            System.out.println("Writer(reverse): signal=true, normalValue=" + normalValue);
        }, "writer");

        Thread reader = new Thread(() -> {
            while (!signal) { }
            int value = normalValue;
            System.out.println("Reader(reverse): normalValue=" + value +
                    " (可能是 0 也可能是 42, 无保证!)");
        }, "reader");

        reader.start();
        writer.start();

        writer.join();
        reader.join(5000);
    }
}

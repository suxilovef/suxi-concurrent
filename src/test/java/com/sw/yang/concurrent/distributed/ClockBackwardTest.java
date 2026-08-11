package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

/**
 * 练习 3：时钟回拨的三种处理策略
 *
 * 模拟：simulateClockBackward 把 lastTimestamp 设为未来 → 真实触发回拨分支
 */
public class ClockBackwardTest {

    /**
     * 策略 1：抛异常（简单粗暴）
     */
    static class ThrowGenerator {
        private long lastTimestamp = -1;

        public synchronized long nextId() {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException(
                        "时钟回拨 " + (lastTimestamp - timestamp) + "ms");
            }
            lastTimestamp = timestamp;
            return timestamp; // 简化演示
        }

        /** 模拟回拨：把 lastTimestamp 设为未来（测试用） */
        void simulateClockBackward(long ms) {
            lastTimestamp = System.currentTimeMillis() + ms;
        }
    }

    /**
     * 策略 2：等待追平（小回拨）
     */
    static class WaitGenerator {
        private static final long MAX_BACKWARD_MS = 5; // 阈值：5ms
        private long lastTimestamp = -1;

        public synchronized long nextId() {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                long offset = lastTimestamp - timestamp;
                if (offset <= MAX_BACKWARD_MS) {
                    // 小回拨 → 等待追平
                    while (System.currentTimeMillis() < lastTimestamp) {
                        // 自旋等待
                    }
                } else {
                    throw new IllegalStateException(
                            "回拨 " + offset + "ms 超过阈值 " + MAX_BACKWARD_MS + "ms");
                }
            }
            lastTimestamp = System.currentTimeMillis();
            return lastTimestamp;
        }

        /** 模拟回拨：把 lastTimestamp 设为未来（测试用） */
        void simulateClockBackward(long ms) {
            lastTimestamp = System.currentTimeMillis() + ms;
        }
    }

    @Test
    public void testThrowStrategy() {
        ThrowGenerator generator = new ThrowGenerator();
        System.out.println("正常生成: " + generator.nextId());

        // 模拟时钟回拨 100ms（把 lastTimestamp 设为未来）
        generator.simulateClockBackward(100);

        try {
            generator.nextId();
            System.out.println("❌ 回拨后仍生成了 ID（策略失效）");
        } catch (IllegalStateException e) {
            System.out.println("✅ 抛异常策略生效: " + e.getMessage());
        }
    }

    @Test
    public void testWaitStrategy() {
        WaitGenerator generator = new WaitGenerator();
        System.out.println("正常生成: " + generator.nextId());

        // 小回拨 3ms（<= 阈值 5ms）→ 等待追平后正常生成
        generator.simulateClockBackward(3);
        long start = System.currentTimeMillis();
        long id = generator.nextId();
        System.out.println("小回拨 3ms → 等待追平后生成: " + id +
                "（等待 " + (System.currentTimeMillis() - start) + "ms）");

        // 大回拨 100ms（> 阈值 5ms）→ 抛异常
        generator.simulateClockBackward(100);
        try {
            generator.nextId();
            System.out.println("❌ 大回拨仍生成了 ID（策略失效）");
        } catch (IllegalStateException e) {
            System.out.println("✅ 大回拨抛异常: " + e.getMessage());
        }
    }
}

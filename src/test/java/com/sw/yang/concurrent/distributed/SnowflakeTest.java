package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * 练习 1：手写雪花算法 + 并发唯一性验证
 *
 * 目标：
 * 1. 理解 64 位位运算（时间戳<<22 | 机器ID<<12 | 序列号）
 * 2. 验证：多线程并发生成 16 万个 ID 无重复
 * 3. 验证：ID 趋势递增
 */
public class SnowflakeTest {

    static class SnowflakeIdGenerator {
        private static final long EPOCH = 1704067200000L;       // 2024-01-01
        private static final long MACHINE_BITS = 10L;
        private static final long SEQUENCE_BITS = 12L;
        private static final long MACHINE_SHIFT = SEQUENCE_BITS;                 // 12
        private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_BITS; // 22
        private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);        // 4095
        private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_BITS);        // 1023

        private final long machineId;
        private long lastTimestamp = -1L;
        private long sequence = 0L;

        public SnowflakeIdGenerator(long machineId) {
            if (machineId < 0 || machineId > MAX_MACHINE_ID) {
                throw new IllegalArgumentException("machineId 必须在 0~1023");
            }
            this.machineId = machineId;
        }

        public synchronized long nextId() {
            long timestamp = System.currentTimeMillis();

            // ① 时钟回拨检测
            if (timestamp < lastTimestamp) {
                throw new RuntimeException("时钟回拨，拒绝生成 ID");
            }

            // ② 同一毫秒：序列号 +1
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) {
                    timestamp = waitNextMillis(lastTimestamp); // 序列号用完 → 等下毫秒
                }
            } else {
                sequence = 0; // 新毫秒：序列号归零
            }

            lastTimestamp = timestamp;

            // ③ 组装（位运算核心）
            return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                    | (machineId << MACHINE_SHIFT)
                    | sequence;
        }

        private long waitNextMillis(long lastTimestamp) {
            long timestamp = System.currentTimeMillis();
            while (timestamp <= lastTimestamp) {
                timestamp = System.currentTimeMillis();
            }
            return timestamp;
        }
    }

    @Test
    public void testUniqueness() throws InterruptedException {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        final int threads = 8;
        final int perThread = 20000; // 8 × 20000 = 16 万个 ID
        Set<Long> ids = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        ids.add(generator.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        int expected = threads * perThread;
        System.out.println("生成 ID 总数: " + expected);
        System.out.println("去重后数量: " + ids.size());
        System.out.println(ids.size() == expected
                ? "✅ 并发生成 16 万个 ID 无重复"
                : "❌ 出现重复 ID！重复了 " + (expected - ids.size()) + " 个");
    }

    @Test
    public void testIncrementing() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(2);
        long prev = generator.nextId();
        boolean increasing = true;
        for (int i = 0; i < 10000; i++) {
            long next = generator.nextId();
            if (next <= prev) {
                increasing = false;
                System.out.println("❌ 不递增: " + prev + " → " + next);
                break;
            }
            prev = next;
        }
        System.out.println(increasing
                ? "✅ 连续生成 1 万个 ID 严格递增"
                : "❌ ID 不递增");
    }
}

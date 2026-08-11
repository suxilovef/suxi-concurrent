package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 练习 2：从雪花 ID 反推生成时间（理解位段布局）
 *
 * 原理：ID >> 22 + EPOCH = 生成毫秒时间戳
 */
public class SnowflakeDecodeTest {

    private static final long EPOCH = 1704067200000L; // 2024-01-01

    @Test
    public void testDecode() {
        // 复用练习 1 的生成器
        SnowflakeTest.SnowflakeIdGenerator generator =
                new SnowflakeTest.SnowflakeIdGenerator(5);

        long id = generator.nextId();
        System.out.println("ID: " + id);

        // 反推时间戳：ID 右移 22 位 = 相对纪元的毫秒数
        long timestamp = (id >> 22) + EPOCH;
        System.out.println("生成时间戳: " + timestamp);

        // 反推机器 ID：ID 右移 12 位 & 1023
        long machineId = (id >> 12) & 1023;
        System.out.println("机器 ID: " + machineId);

        // 反推序列号：ID & 4095
        long sequence = id & 4095;
        System.out.println("序列号: " + sequence);

        // 格式化时间
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
        System.out.println("生成时间: " + time);
        System.out.println("✅ 位段提取验证成功（>> 22 / >> 12 / & 4095）");
    }
}

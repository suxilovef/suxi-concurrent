package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;

/**
 * 练习 1：用 JOL 观察无锁 / 偏向锁 / 轻量级锁 / 重量级锁
 *
 * 注意：
 * - JDK 15+ 默认禁用偏向锁，本实验在 JDK 8 上观察效果最完整
 * - JDK 17 上观察"无锁 → 轻量级 → 重量级"三态
 */
public class JolLockDemoTest {

    private static final class LockObject {
        // 空对象，只有对象头
    }

    @Test
    public void testLockStates() throws InterruptedException {
        LockObject obj = new LockObject();

        System.out.println("=== 1. 无锁状态（刚创建）===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // 观察：最后 2 位是 01，第 3 位是 0 → 无锁

        // 等待偏向锁延迟结束（默认 4 秒，仅 JDK 8 有效）
        Thread.sleep(4000);

        System.out.println("=== 2. 等待 4 秒后（可偏向但未偏向）===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // JDK 8 上观察：第 3 位变为 1 → 可偏向
        // JDK 17 上观察：保持无锁

        System.out.println("=== 3. 获取锁后 ===");
        synchronized (obj) {
            System.out.println(ClassLayout.parseInstance(obj).toPrintable());
            // JDK 8 上观察：thread 位段有值 → 偏向锁
            // JDK 17 上观察：锁标志变为 00 → 轻量级锁
        }

        System.out.println("=== 4. 释放锁后 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // JDK 8：偏向锁保留（同一线程下次进入零开销）
        // JDK 17：恢复无锁
    }

    /**
     * 验证 hashCode 与偏向锁互斥
     */
    @Test
    public void testHashCodeKillsBiasing() throws InterruptedException {
        LockObject obj = new LockObject();
        Thread.sleep(4000); // 等待偏向锁就绪

        System.out.println("=== 调用 hashCode 之前 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());

        obj.hashCode(); // 关键操作

        System.out.println("=== 调用 hashCode 之后 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // 观察：biased_lock 位变为 0，该对象不再可能进入偏向锁
    }
}

package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;

/**
 * 练习 1：用 JOL 观察无锁 / 偏向锁 / 轻量级锁 / 重量级锁
 *
 * 注意：
 * - JDK 15+ 默认禁用偏向锁；JDK 17 需加 -XX:+UseBiasedLocking 才能观察偏向锁
 * - 偏向位是 new 那一刻从「类原型头」盖上去的 → 等延迟必须等【在 new 之前】，
 *   new 出来之后再等，Mark Word 不会自己变
 * - 延迟默认值随版本变：JDK 8 = 4000ms，JDK 15+ = 0ms（JDK 17 上这行 sleep 只是白等）
 * - JDK 17 默认（无偏向锁）观察"无锁 → 轻量级 → 重量级"三态
 */
public class JolLockDemoTest {

    private static final class LockObject {
        // 空对象，只有对象头
    }

    @Test
    public void testLockStates() throws InterruptedException {
        // 对照组：延迟期内 new 出来的对象（JDK 8 上才看得出差别）
        LockObject early = new LockObject();

        // 等偏向锁延迟结束 —— 必须在 new 之前
        // JDK 8：必需；JDK 17 + -XX:+UseBiasedLocking：延迟已是 0，白等 4 秒
        Thread.sleep(4000);

        LockObject obj = new LockObject();

        System.out.println("=== 1. 对照：延迟期内 new 的对象 ===");
        System.out.println(ClassLayout.parseInstance(early).toPrintable());
        // JDK 8 上观察：第 3 位 = 0 → 永久无锁（等多久都不会变可偏向）

        System.out.println("=== 2. 延迟期后 new（可偏向但未偏向）===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // JDK 8 上观察：第 3 位 = 1 → 可偏向，thread 位段为空
        // JDK 17 默认观察：仍是 01 / 第 3 位 0 → 无锁（偏向锁被禁用）

        System.out.println("=== 3. 获取锁后 ===");
        synchronized (obj) {
            System.out.println(ClassLayout.parseInstance(obj).toPrintable());
            // JDK 8 上观察：thread 位段有值，锁标志仍是 01 → 偏向锁
            // JDK 17 默认观察：锁标志变为 00 → 轻量级锁
        }

        System.out.println("=== 4. 释放锁后 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // JDK 8：与第 3 步逐位相同 → 进出临界区零写入（这才是偏向锁的意义）
        // JDK 17 默认：恢复 0x…01 → 轻量级锁每次进出要写两次 Mark Word
    }

    /**
     * 验证 hashCode 与偏向锁互斥
     */
    @Test
    public void testHashCodeKillsBiasing() throws InterruptedException {
        // 必须等延迟结束再 new，否则对象本来就不可偏向，测不出差别
        Thread.sleep(4000);
        LockObject obj = new LockObject();

        System.out.println("=== 调用 hashCode 之前 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());

        obj.hashCode(); // 关键操作

        System.out.println("=== 调用 hashCode 之后 ===");
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        // 观察：biased_lock 位变为 0，该对象不再可能进入偏向锁
    }
}

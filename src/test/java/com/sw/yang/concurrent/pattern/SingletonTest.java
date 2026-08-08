package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 练习 1：单例模式并发验证 + 反射攻击演示
 *
 * 目标：
 * 1. 验证 DCL / 静态内部类 / 枚举三种单例在多线程下都是单例
 * 2. 演示反射攻击 DCL，对比枚举天然防反射
 */
public class SingletonTest {

    // DCL 单例
    static class DclSingleton {
        private static volatile DclSingleton instance;

        private DclSingleton() {
        }

        public static DclSingleton getInstance() {
            if (instance == null) {
                synchronized (DclSingleton.class) {
                    if (instance == null) {
                        instance = new DclSingleton();
                    }
                }
            }
            return instance;
        }
    }

    // 静态内部类单例
    static class HolderSingleton {
        private HolderSingleton() {
        }

        private static class Holder {
            private static final HolderSingleton INSTANCE = new HolderSingleton();
        }

        public static HolderSingleton getInstance() {
            return Holder.INSTANCE;
        }
    }

    // 枚举单例
    enum EnumSingleton {
        INSTANCE
    }

    /**
     * 并发验证：100 个线程同时获取，必须是同一个实例
     */
    @Test
    public void testConcurrentSingleton() throws InterruptedException {
        int threads = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<DclSingleton> ref = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await(); // 同时起跑
                    ref.compareAndSet(null, DclSingleton.getInstance());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        // 再拿一个比较
        DclSingleton another = DclSingleton.getInstance();
        System.out.println(ref.get() == another
                ? "✅ 100 线程并发获取 DCL 单例 → 同一个实例"
                : "❌ 出现了多个实例！");

        // 静态内部类
        System.out.println(HolderSingleton.getInstance() == HolderSingleton.getInstance()
                ? "✅ 静态内部类单例"
                : "❌ 静态内部类异常");
        // 枚举
        System.out.println(EnumSingleton.INSTANCE == EnumSingleton.INSTANCE
                ? "✅ 枚举单例"
                : "❌ 枚举异常");
    }

    /**
     * 反射攻击演示：DCL 可被反射破坏，枚举不能
     */
    @Test
    public void testReflectionAttack() {
        // 攻击 DCL
        try {
            Constructor<DclSingleton> c = DclSingleton.class.getDeclaredConstructor();
            c.setAccessible(true);
            DclSingleton hacked = c.newInstance();
            System.out.println("❌ DCL 被反射攻击：创建了第二个实例！" +
                    (hacked != DclSingleton.getInstance()));
        } catch (Exception e) {
            System.out.println("DCL 构造器抛异常: " + e.getMessage());
        }

        // 攻击枚举（枚举没有无参构造器，且 JVM 禁止反射创建）
        try {
            Constructor<EnumSingleton> c = EnumSingleton.class.getDeclaredConstructor(String.class, int.class);
            c.setAccessible(true);
            EnumSingleton hacked = c.newInstance("HACKED", 99);
            System.out.println("❌ 枚举被反射攻击？" + (hacked != EnumSingleton.INSTANCE));
        } catch (Exception e) {
            System.out.println("✅ 枚举天然防反射: " + e.getClass().getSimpleName());
        }
    }
}

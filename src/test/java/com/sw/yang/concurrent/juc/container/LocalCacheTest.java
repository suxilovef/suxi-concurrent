package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 练习 2：基于 CHM 的本地缓存（computeIfAbsent 懒加载）
 *
 * 核心：computeIfAbsent 是原子的，并发下加载逻辑只执行一次
 */
public class LocalCacheTest {

    static class LocalCache {
        private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        private final AtomicLong loadCount = new AtomicLong(0);

        // 模拟昂贵的数据加载（只应该执行一次）
        private String loadFromDB(String key) {
            try {
                Thread.sleep(50); // 模拟 DB 查询
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loadCount.incrementAndGet();
            return "value-" + key + "-" + System.nanoTime();
        }

        public String get(String key) {
            return cache.computeIfAbsent(key, this::loadFromDB); // 原子懒加载
        }
    }

    @Test
    public void testLazyLoad() throws InterruptedException {
        LocalCache cache = new LocalCache();

        // 10 个线程同时 get 同一个 key
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                String v = cache.get("key1");
                System.out.println(Thread.currentThread().getName() + " 得到: " + v);
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("实际加载次数: " + cache.loadCount.get());
        System.out.println(cache.loadCount.get() == 1
                ? "✅ computeIfAbsent 并发下只加载了一次"
                : "❌ 加载了多次（不应该）");
    }
}

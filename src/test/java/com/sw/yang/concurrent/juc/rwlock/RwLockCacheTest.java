package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 练习 1：用 ReentrantReadWriteLock 实现线程安全缓存
 *
 * 目标：
 * 1. 验证读读并行（多个读者同时进入）
 * 2. 验证读写互斥
 */
public class RwLockCacheTest {

    static class Cache {
        private final Map<String, String> data = new HashMap<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();

        public String get(String key) {
            readLock.lock();
            try {
                return data.get(key);
            } finally {
                readLock.unlock();
            }
        }

        public void put(String key, String value) {
            writeLock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 写入 " + key);
                Thread.sleep(100); // 放大写操作窗口
                data.put(key, value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeLock.unlock();
            }
        }
    }

    @Test
    public void testCache() throws InterruptedException {
        Cache cache = new Cache();

        // 3 个读者并发读
        Thread[] readers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            readers[i] = new Thread(() -> {
                for (int j = 0; j < 3; j++) {
                    String v = cache.get("key1");
                    System.out.println(Thread.currentThread().getName() + " 读到: " + v);
                }
            }, "reader-" + i);
            readers[i].start();
        }

        // 1 个写者
        Thread writer = new Thread(() -> {
            for (int j = 0; j < 3; j++) {
                cache.put("key1", "value-" + j);
            }
        }, "writer");
        writer.start();

        for (Thread t : readers) t.join();
        writer.join();
        System.out.println("✅ 读写锁缓存运行完成");
    }
}

package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 练习 3：缓存击穿防护 —— 缓存失效时只允许一个线程重建
 *
 * 场景：热点 key 过期瞬间，100 个请求同时来 → 只能有一个去查库
 */
public class CacheBreakdownTest {

    static class SafeCache {
        private final Map<String, Object> cache = new HashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final AtomicInteger dbLoadCount = new AtomicInteger(0); // 查库次数

        public Object getOrLoad(String key) {
            // ① 读锁快速路径
            lock.readLock().lock();
            Object v;
            try {
                v = cache.get(key);
            } finally {
                lock.readLock().unlock();
            }
            if (v != null) return v;

            // ② 写锁重建（double-check）
            lock.writeLock().lock();
            try {
                v = cache.get(key); // 再次检查（别人可能已重建）
                if (v == null) {
                    v = loadFromDB(key); // 只有第一次进来的人才查库
                    cache.put(key, v);
                }
                return v;
            } finally {
                lock.writeLock().unlock();
            }
        }

        private Object loadFromDB(String key) {
            dbLoadCount.incrementAndGet(); // 模拟查库
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "data-" + key;
        }

        int getDbLoadCount() {
            return dbLoadCount.get();
        }
    }

    @Test
    public void testCacheBreakdown() throws InterruptedException {
        SafeCache cache = new SafeCache();
        int threads = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // 100 个线程同时请求同一个 key（模拟缓存击穿）
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    cache.getOrLoad("hot-key");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        System.out.println("100 个并发请求，实际查库次数: " + cache.getDbLoadCount());
        System.out.println(cache.getDbLoadCount() == 1
                ? "✅ 击穿防护生效（只有 1 次查库）"
                : "❌ 击穿了！查库 " + cache.getDbLoadCount() + " 次");
    }
}

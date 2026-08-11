package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;

/**
 * 练习 1：手写 Redis 分布式锁（SET NX EX + Lua 释放）
 *
 * 前置：本地启动 Redis（默认 6379 端口）
 */
public class RedisLockTest {

    static class RedisLock {
        private final Jedis jedis;
        private final String lockKey;
        private final String lockValue; // 唯一标识
        private final int expireSeconds;

        RedisLock(Jedis jedis, String lockKey, int expireSeconds) {
            this.jedis = jedis;
            this.lockKey = lockKey;
            this.lockValue = UUID.randomUUID().toString();
            this.expireSeconds = expireSeconds;
        }

        /**
         * 加锁：SET NX EX（原子，Jedis 4.x 用 SetParams）
         */
        public boolean lock() {
            String result = jedis.set(lockKey, lockValue,
                    SetParams.setParams().nx().ex(expireSeconds));
            return "OK".equals(result);
        }

        /**
         * 释放锁：Lua 原子校验 + 删除
         */
        public boolean unlock() {
            String lua =
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end";
            Object result = jedis.eval(lua,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(lockValue));
            return Long.valueOf(1).equals(result);
        }
    }

    @Test
    public void testDistributedLock() throws InterruptedException {
        // 前提：本地 Redis 运行中
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.ping(); // 连接测试，连不上会抛异常
        } catch (Exception e) {
            System.out.println("⚠️ Redis 未启动，请先启动本地 Redis（redis-server）");
            return;
        }

        JedisPool pool = new JedisPool("localhost", 6379);
        final int[] processedCount = {0};

        // 10 个线程竞争同一个锁（模拟 10 个服务实例）
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                try (Jedis jedis = pool.getResource()) {
                    RedisLock lock = new RedisLock(jedis, "task:once:lock", 10);
                    if (lock.lock()) {
                        try {
                            // 模拟业务：只有一个线程能进入
                            System.out.println(Thread.currentThread().getName() + " 拿到锁，执行任务");
                            processedCount[0]++;
                            Thread.sleep(100);
                        } finally {
                            lock.unlock();
                            System.out.println(Thread.currentThread().getName() + " 释放锁");
                        }
                    } else {
                        System.out.println(Thread.currentThread().getName() + " 没拿到锁，跳过");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "instance-" + i);
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        System.out.println("实际执行任务次数: " + processedCount[0]);
        System.out.println(processedCount[0] == 1
                ? "✅ 分布式锁生效：10 个实例只有 1 个执行了任务"
                : "❌ 分布式锁失效！执行了 " + processedCount[0] + " 次");
        pool.close();
    }
}

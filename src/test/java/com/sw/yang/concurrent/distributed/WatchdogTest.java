package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 练习 3：手写简易看门狗续期（理解 Redisson 的原理）
 *
 * 场景：锁 2 秒过期，但业务要跑 5 秒
 *      看门狗每 1 秒续期 → 锁始终有效 → 业务不被中途打断
 */
public class WatchdogTest {

    @Test
    public void testWatchdog() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.ping();
        } catch (Exception e) {
            System.out.println("⚠️ Redis 未启动，请先启动本地 Redis");
            return;
        }

        try (Jedis jedis = new Jedis("localhost", 6379)) {
            String key = "test:watchdog:lock";
            String value = "watchdog-demo";
            jedis.del(key);

            // ① 加锁（2 秒过期，Jedis 4.x 用 SetParams）
            jedis.set(key, value, SetParams.setParams().nx().ex(2));
            System.out.println("加锁成功，锁 2 秒过期");

            // ② 看门狗：每 1 秒续期一次
            // ⚠️ 教学简化：这里只判断 TTL > 0 就续期（单线程演示没问题）
            //    真实实现必须校验 value —— 否则锁被别人抢走后会把别人的锁续期！
            //    （Redisson 用 Lua 校验 hash 中的 threadId 再续期）
            AtomicBoolean running = new AtomicBoolean(true);
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
            watchdog.scheduleAtFixedRate(() -> {
                if (running.get()) {
                    // 续期：把过期时间重新设为 2 秒
                    Long remain = jedis.ttl(key);
                    if (remain > 0) {
                        jedis.expire(key, 2);
                        System.out.println("看门狗续期，剩余 TTL 重置为 2 秒");
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);

            // ③ 模拟 5 秒业务
            for (int i = 1; i <= 5; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("业务执行第 " + i + " 秒，锁 TTL: " + jedis.ttl(key));
            }

            // ④ 业务完成，停看门狗 + 释放锁
            running.set(false);
            watchdog.shutdown();
            jedis.del(key);
            System.out.println("业务完成，释放锁");

            System.out.println("✅ 看门狗让锁撑过了 2 秒过期（5 秒业务全程持锁）");
        }
    }
}

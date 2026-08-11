package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * 练习 2：验证"Lua 释放"防止误删别人的锁
 *
 * 场景模拟：
 * 1. 线程 A 加锁（value=uuid-A）
 * 2. 锁过期（模拟 1 秒过期）
 * 3. 线程 B 加锁（value=uuid-B）
 * 4. 线程 A 释放 → Lua 校验 value 不匹配 → 释放失败 → B 的锁安全
 */
public class MisdeleteTest {

    @Test
    public void testLuaPreventsMisdelete() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.ping();
        } catch (Exception e) {
            System.out.println("⚠️ Redis 未启动，请先启动本地 Redis");
            return;
        }

        try (Jedis jedis = new Jedis("localhost", 6379)) {
            String key = "test:misdelete:lock";
            jedis.del(key); // 清理现场

            // ① 线程 A 加锁（Jedis 4.x 用 SetParams）
            String valueA = "uuid-A";
            jedis.set(key, valueA, SetParams.setParams().nx().ex(1)); // 1 秒过期（模拟）
            System.out.println("A 加锁成功: " + jedis.get(key));

            // ② 等锁过期
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // ③ 线程 B 加锁
            String valueB = "uuid-B";
            jedis.set(key, valueB, SetParams.setParams().nx().ex(10));
            System.out.println("B 加锁成功（A 的锁已过期）: " + jedis.get(key));

            // ④ A 释放锁（错误写法：直接 del → 误删 B 的锁！）
            jedis.del(key); // ❌ 没有校验！
            System.out.println("A 直接 del 后: " + jedis.get(key) + "（B 的锁被误删！）");

            // ⑤ 重新模拟 + Lua 正确释放
            jedis.set(key, valueA, SetParams.setParams().nx().ex(1));
            Thread.sleep(1500);
            jedis.set(key, valueB, SetParams.setParams().nx().ex(10));

            String lua =
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end";
            Long result = (Long) jedis.eval(lua,
                    Collections.singletonList(key),
                    Collections.singletonList(valueA)); // A 用自己 value 释放
            System.out.println("A 用 Lua 释放结果: " + result + "（0 = 释放失败，因为锁是 B 的）");
            System.out.println("Lua 释放后: " + jedis.get(key) + "（B 的锁完好 ✅）");

            jedis.del(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

# 06-01 Redis 分布式锁

> **阶段六·第 1 篇** | 前置：[05-04-JMH性能压测](./05-04-JMH性能压测.md) | 后续：[06-02-雪花算法与分布式ID](./06-02-雪花算法与分布式ID.md)（待发布）  
> **建议时长**：6~7 小时（基础版 2h + 看门狗 1.5h + RedLock 1.5h + 选型 1h + 练习 1.5h）  
> 🛠️ **日常高频**：定时任务互斥、库存扣减、幂等控制——分布式锁是分布式系统的基石

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | SET NX EX 加锁、Lua 脚本释放锁、UUID 防误删、看门狗续期 | **能手写完整实现 + 讲清每个细节** |
| ⭐⭐⭐ | Redis 主从切换丢锁、RedLock 算法与争议、与 ZK 的选型 | **知道风险 + 会选型** |
| ◈◈ | 锁超时与业务时长的矛盾、可重入实现、Curator/Redisson 对比 | **理解设计取舍** |
| ○ | RedLock 的时钟跳跃细节、etcd 实现 | **了解即可** |

---

## 1. 为什么需要分布式锁

### 1.1 单机锁 vs 分布式锁

```
单机锁（synchronized / ReentrantLock）：
  只对本 JVM 内的线程有效
  → 多个 JVM 实例部署时，每个实例各有一把锁 → 互不协调！

分布式锁：
  多个 JVM 之间共享的锁（存在 Redis / ZK / DB 中）
  → 所有实例竞争同一把锁 → 全局互斥

场景（定时任务）：
  3 个实例都部署了同一个定时任务
  单机锁：3 个实例同时执行 → 任务重复执行 3 次！
  分布式锁：只有 1 个实例拿到锁 → 只执行 1 次 ✅
```

### 1.2 分布式锁的三个核心要求

```
① 互斥：同一时刻只有一个客户端持有锁
② 安全：持锁者崩溃后锁能自动释放（防死锁）
③ 可靠：不能误删别人的锁
```

---

## 2. Redis 分布式锁基础版（🛠️ 必会）

### 2.1 加锁：SET NX EX

```java
// 核心命令：一条命令完成"加锁 + 过期"
SET lock_key lock_value NX EX 30

// 参数含义：
//   lock_key   锁的名字（如 "order:123:lock"）
//   lock_value 锁的值（必须是唯一标识，如 UUID）
//   NX         Not eXists：key 不存在才设置（保证互斥）
//   EX 30      过期时间 30 秒（防止持锁者崩溃导致死锁）
```

```
为什么用一条命令？
  ❌ setnx(lock, value); expire(lock, 30);  ← 两条命令！
  → setnx 成功但 expire 失败 → 锁没有过期时间 → 崩溃后死锁！

  ✅ SET lock value NX EX 30  ← 一条命令
  → 原子操作：设置和过期绑定在一起 → 不会出现"有锁无过期"
```

### 2.2 释放锁：Lua 脚本（⭐ 核心）

```java
// 释放锁必须"先校验再删除"，且要原子！
// 用 Lua 脚本（Redis 保证脚本原子执行）：

// 释放锁的 Lua 脚本：
//   KEYS[1] = 锁的 key
//   ARGV[1] = 自己持有锁时的 value（UUID）
//   只有 value 匹配才删除 → 防止误删别人的锁

String releaseLua =
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "  return redis.call('del', KEYS[1]) " +
    "else " +
    "  return 0 " +
    "end";

// 执行：
Long result = (Long) jedis.eval(releaseLua, 
        Collections.singletonList(lockKey), 
        Collections.singletonList(lockValue));
// 返回 1：删除成功（锁是我的）
// 返回 0：value 不匹配（锁不是我的，不能删！）
```

```
为什么释放必须用 Lua？

❌ 错误做法：先 get 判断，再 del（两条命令）
  String v = jedis.get(lockKey);       // ① 读取
  if (lockValue.equals(v)) {           // ② 判断是我的
      jedis.del(lockKey);              // ③ 删除
  }
  → 问题：① 和 ③ 之间有竞争窗口！
  → 场景：锁过期了 → 别人抢到锁（value 换成别人的）→ 你 del
          → 删掉了别人的锁！→ 别人的临界区失去保护！

✅ Lua 脚本：get + 判断 + del 在 Redis 内原子执行
  → 中间插不进任何其他命令 → 不会误删
```

### 2.3 完整实现（手写版）

```java
// ⚠️ Jedis 4.x 的 API 变化：SET NX EX 要用 SetParams（旧的 5 参数重载已移除）
import redis.clients.jedis.params.SetParams;

public class RedisLock {
    private final Jedis jedis;          // Redis 客户端
    private final String lockKey;       // 锁的 key
    private final String lockValue;     // 唯一标识（UUID）
    private final int expireSeconds;    // 过期时间

    public RedisLock(Jedis jedis, String lockKey, int expireSeconds) {
        this.jedis = jedis;
        this.lockKey = lockKey;
        this.lockValue = UUID.randomUUID().toString();  // 每个锁实例唯一
        this.expireSeconds = expireSeconds;
    }

    /**
     * 加锁：SET lock_key value NX EX 30（原子，Jedis 4.x 用 SetParams）
     */
    public boolean lock() {
        String result = jedis.set(lockKey, lockValue,
                SetParams.setParams().nx().ex(expireSeconds));
        return "OK".equals(result);
    }

    /**
     * 释放锁：Lua 脚本（原子校验 + 删除）
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
```

### 2.4 使用姿势

```java
RedisLock lock = new RedisLock(jedis, "order:" + orderId + ":lock", 30);

if (!lock.lock()) {
    throw new RuntimeException("订单正在处理中，请稍后");
}
try {
    // 临界区：处理订单
    processOrder(orderId);
} finally {
    lock.unlock();   // 必须释放！
}
```

---

## 3. 核心问题：锁超时 vs 业务时长（⭐ 必考）

### 3.1 问题场景

```
场景：锁过期时间 30 秒，但业务执行了 40 秒

时间线：
  t=0s   线程 A 拿到锁（锁 30 秒后过期）
  t=30s  锁过期！线程 B 抢到锁（开始处理同一业务）
  t=40s  线程 A 业务完成 → 释放锁
          → 释放的是"线程 B 的锁"！？
  → 幸好有 Lua 校验（value 不匹配 → 释放失败）✅

  但如果 B 的锁也被 A 的误删（无 Lua）→ 两个线程同时在临界区 → 数据错误！

核心矛盾：
  锁过期太短 → 业务没跑完锁就没了 → 锁形同虚设
  锁过期太长 → 持锁者崩溃 → 锁长时间不释放 → 服务阻塞
```

### 3.2 解决方案：看门狗（Watchdog）续期

```
思路：锁快过期时，自动续期（延长过期时间）
      业务没结束 → 锁就一直续期 → 不会中途过期

Redisson 的实现（红锁客户端）：
  lock() 时：
    ① 默认锁过期时间 30 秒
    ② 启动一个"看门狗"定时任务（每 10 秒执行一次）
    ③ 如果锁还被持有 → 把过期时间续到 30 秒
  unlock() 时：
    ④ 取消看门狗任务
    ⑤ 释放锁

  效果：只要业务在跑，锁就永远有效
       业务结束 → 正常释放 → 无残留
       进程崩溃 → 看门狗随进程死亡 → 锁最终过期释放 ✅
```

```
看门狗续期的代码逻辑（Redisson 简化）：

lock() {
    // 1. tryLock 拿到锁，leaseTime 默认 30s
    // 2. 如果未指定 leaseTime → 开启定时续期
    scheduleExpirationRenewal(threadId);
}

renewExpiration() {
    // 每 10 秒执行一次
    timer.schedule(() -> {
        if (锁还被当前线程持有) {
            // Lua：把过期时间重新设置为 30 秒
            renewLeaseTime(30s);
            // 递归安排下一次续期
            scheduleExpirationRenewal(threadId);
        }
    }, 10, TimeUnit.SECONDS);
}
```

### 3.3 看门狗的取舍

```
✅ 好处：业务执行多久，锁就续期多久 → 不会中途过期
❌ 风险：业务死循环/卡死 → 锁永远不释放 → 其他请求永久等待

→ 生产规范：
   ① 指定 leaseTime（锁的最长持有时间）→ 不用看门狗
   ② 或业务代码自己控制总时长（超时中断）
   ③ 看门狗是"默认行为"，长任务要显式评估
```

---

## 4. Redis 主从切换丢锁（⭐ 必考）

### 4.1 问题场景

```
Redis 高可用架构：主从复制（Master/Slave）

主从复制是"异步"的：
  Master 写成功 → 返回客户端 OK → 异步同步给 Slave

丢锁场景：
  ① 线程 A 在 Master 上加锁成功（SET NX EX）
  ② Master 还没把锁同步给 Slave → Master 宕机！
  ③ 哨兵/集群把 Slave 提升为新的 Master
  ④ 新 Master 上"没有这把锁"！
  ⑤ 线程 B 在新 Master 上加锁成功 → 两个线程同时持有锁！

→ 互斥被破坏！分布式锁失效！
```

```
                ┌──────────┐
   线程 A ──加锁──→│ Master   │──异步复制──→┐
                └──────────┘               │
                     │ 宕机！               ▼
                     │             ┌──────────┐
                     │             │  Slave   │
                     └──升级为 Master└──────────┘
                                      ↑
                        线程 B 在新 Master 上加锁成功！
                        （锁没复制过来 → 加锁成功）
                        → A、B 同时持有"同一把锁"
```

### 4.2 解决方案：RedLock 算法

```
RedLock 思路：不信任单个 Redis，向 N 个独立 Redis 实例加锁

加锁流程（N=5 个独立 Redis）：
  ① 向所有 5 个实例发送 SET NX EX（同一 key、同一 value）
  ② 记录加锁耗时，要求：加锁成功数 >= N/2+1（3 个）
     且总耗时 < 锁过期时间
  ③ 满足 → 加锁成功
  ④ 不满足 → 向所有实例释放锁（回滚）

释放锁：向所有 5 个实例发送 Lua 释放脚本

意义：单个 Master 丢锁不影响整体（只要多数派有锁）
```

### 4.3 RedLock 的争议（Martin Kleppmann 的批评）

```
争议核心：RedLock 不是绝对安全的分布式锁

问题 1：时钟跳跃
  实例的时钟回拨 → 锁的过期时间计算错误 → 锁提前过期

问题 2：GC 停顿（Stop-The-World）
  线程 A 拿到锁 → JVM Full GC 暂停 40 秒
  → 锁过期 → 线程 B 拿到锁 → A GC 恢复 → 两个线程同时在临界区！
  → RedLock 无法防止这种情况（单机锁也无法）

问题 3：网络分区
  部分实例不可达 → 加锁成功的实例数不确定

Martin 的结论：用"fencing token"（隔离令牌）更可靠
  → 每次加锁带一个递增令牌
  → 写入时校验令牌：旧令牌的写入被拒绝
```

### 4.4 实际选型建议

```
生产实际（多数场景）：

  互联网业务（允许极小概率并发）：
    → Redis 单实例锁 + 看门狗（Redisson）
    → 简单、高性能、够用

  对安全要求极高（金融/支付）：
    → ZK / etcd（强一致）
    → 或 RedLock + fencing token

  不要过度设计：
    Redis 单实例锁丢锁的概率极低（需要主从切换瞬间加锁）
    → 先用单实例 + 业务幂等兜底，出了问题再升级
```

---

## 5. 三种分布式锁方案对比（🛠️ 选型）

| 维度 | Redis | ZooKeeper | 数据库 |
|---|---|---|---|
| 一致性 | 弱（主从异步） | **强（ZAB 协议）** | 强（ACID） |
| 性能 | 高（内存） | 中（磁盘+网络） | 低（IO） |
| 自动释放 | 过期时间 | **临时节点（会话断开）** | 无（需手动） |
| 可重入 | 自实现 | Curator 支持 | 自实现 |
| 复杂度 | 低 | 中（运维 ZK 集群） | 低 |
| 适用 | 互联网主流 | 强一致要求 | 低频操作 |

```
选型决策树：

需要强一致性？────────────是──→ ZooKeeper / etcd
    │否
需要高性能 + 简单？────────是──→ Redis（Redisson）
    │否
已有 DB 且低频？───────────是──→ 数据库唯一索引
    │否
──────────────────────→ Redis + 幂等兜底
```

---

## 6. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：value 用固定值 → 误删别人的锁

```java
// ❌ value 固定 → 任何持锁者释放时都能匹配 → 误删
SET lock order_lock_value NX EX 30
// 线程 A 加锁 → 锁过期 → 线程 B 加锁（同样的 value）
// 线程 A 释放 → value 匹配（都是固定值）→ 删掉了 B 的锁！

// ✅ value 必须是唯一标识（UUID/线程ID+随机数）
SET lock <uuid-a> NX EX 30
// A 释放时 value 不匹配 B 的 uuid → 释放失败 → 安全
```

### 🕳️ 坑 2：不用 Lua 释放锁

```java
// ❌ get 判断 + del 两条命令 → 竞争窗口 → 误删
if (value.equals(jedis.get(key))) {
    jedis.del(key);   // 中间锁可能已被别人抢走！
}

// ✅ Lua 原子脚本（见 §2.2）
```

### 🕳️ 坑 3：忘了 try-finally 释放锁

```java
// ❌ 业务异常 → 锁不释放 → 等到过期 → 30 秒阻塞
lock();
process();
unlock();   // process 抛异常 → 不执行！

// ✅ 必须 finally
if (lock()) {
    try {
        process();
    } finally {
        unlock();
    }
}
```

### 🕳️ 坑 4：锁的粒度太粗/太细

```java
// ❌ 全订单一把锁 → 不同订单互相阻塞
lock("order:lock");
// ✅ 按业务维度拆分
lock("order:" + orderId + ":lock");   // 每个订单独立锁
// 但注意：如果两个操作需要"锁多个订单" → 锁排序（阶段五）
```

### 🕳️ 坑 5：把 Redis 锁当绝对安全

```java
// ⚠️ Redis 锁有丢锁风险（主从切换/GC 停顿）
// ✅ 业务兜底：幂等（唯一索引/版本号）+ 乐观锁
// 双重保险：分布式锁防并发 + 幂等防重复
```

---

## 7. 面试高频考点

1. **Redis 分布式锁怎么实现？**
   → 加锁 SET NX EX（原子），释放 Lua 脚本（校验 value 再删），value 用 UUID 防误删。

2. **为什么释放锁必须用 Lua 脚本？**
   → get 判断 + del 是两条命令，中间有竞争窗口：锁过期后被别人抢走，旧持锁者 del 会误删。Lua 原子执行。

3. **锁过期时间怎么设？业务比锁时间长怎么办？**
   → 看门狗续期（Redisson 每 10 秒续期 30 秒）；或指定最长 leaseTime；业务自控时长。

4. **Redis 主从切换为什么丢锁？RedLock 怎么解决？**
   → 主从异步复制：Master 宕机时锁没同步到 Slave，新 Master 无锁。RedLock 向 N 个独立实例加锁，多数派（N/2+1）成功才加锁成功。

5. **Redis 锁和 ZK 锁怎么选？**
   → 强一致用 ZK（临时节点自动释放）；性能/简单用 Redis（过期时间+看门狗）；Redis 丢锁概率低，业务幂等兜底。

---

## 8. 实战练习

### 练习 1：手写 Redis 分布式锁（60 分钟）★必做

```java
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
 * 依赖：jedis（见 pom.xml）
 */
public class RedisLockTest {

    static class RedisLock {
        private final Jedis jedis;
        private final String lockKey;
        private final String lockValue;   // 唯一标识
        private final int expireSeconds;

        RedisLock(Jedis jedis, String lockKey, int expireSeconds) {
            this.jedis = jedis;
            this.lockKey = lockKey;
            this.lockValue = UUID.randomUUID().toString();
            this.expireSeconds = expireSeconds;
        }

        /** 加锁：SET NX EX（原子，Jedis 4.x 用 SetParams） */
        public boolean lock() {
            String result = jedis.set(lockKey, lockValue,
                    SetParams.setParams().nx().ex(expireSeconds));
            return "OK".equals(result);
        }

        /** 释放锁：Lua 原子校验 + 删除 */
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
            jedis.ping();   // 连接测试，连不上会抛异常
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
```

### 练习 2：模拟误删别人的锁（45 分钟）★必做

```java
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
            jedis.del(key);   // 清理现场

            // ① 线程 A 加锁（Jedis 4.x 用 SetParams）
            String valueA = "uuid-A";
            jedis.set(key, valueA, SetParams.setParams().nx().ex(1));   // 1 秒过期（模拟）
            System.out.println("A 加锁成功: " + jedis.get(key));

            // ② 等锁过期
            try { Thread.sleep(1500); } catch (InterruptedException e) { }

            // ③ 线程 B 加锁
            String valueB = "uuid-B";
            jedis.set(key, valueB, SetParams.setParams().nx().ex(10));
            System.out.println("B 加锁成功（A 的锁已过期）: " + jedis.get(key));

            // ④ A 释放锁（错误写法：直接 del → 误删 B 的锁！）
            jedis.del(key);   // ❌ 没有校验！
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
                    Collections.singletonList(valueA));   // A 用自己 value 释放
            System.out.println("A 用 Lua 释放结果: " + result + "（0 = 释放失败，因为锁是 B 的）");
            System.out.println("Lua 释放后: " + jedis.get(key) + "（B 的锁完好 ✅）");

            jedis.del(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 练习 3：看门狗续期模拟（45 分钟）

```java
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
                try { Thread.sleep(1000); } catch (InterruptedException e) { }
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
```

---

## 9. 自测题

1. **加锁为什么必须一条 SET NX EX 命令？**
   <details><summary>答案</summary>

   setnx + expire 两条命令不是原子的：setnx 成功但 expire 失败 → 锁没有过期时间 → 持锁者崩溃后死锁。SET NX EX 原子完成"设置 + 过期"。
   </details>

2. **释放锁为什么必须 Lua 脚本？直接 del 有什么风险？**
   <details><summary>答案</summary>

   get 判断 + del 有竞争窗口：锁过期后别人抢到，旧持锁者 del 会误删别人的锁（B 的临界区失去保护）。Lua 原子校验 value + 删除。
   </details>

3. **value 为什么必须唯一？**
   <details><summary>答案</summary>

   Lua 释放时靠 value 判断"锁是不是我的"。value 固定 → 任何持锁者释放都匹配 → 误删别人的锁。UUID 保证唯一。
   </details>

4. **业务时长 > 锁过期时间怎么办？**
   <details><summary>答案</summary>

   看门狗续期（Redisson 每 10 秒续 30 秒）；或指定最长 leaseTime；业务自控时长。注意看门狗的风险：业务死循环 → 锁永不释放。
   </details>

5. **主从切换为什么丢锁？怎么解决？**
   <details><summary>答案</summary>

   主从异步复制，Master 宕机时锁没同步到 Slave → 新 Master 无锁 → 其他线程能加锁。RedLock 向 N 个独立实例加锁，多数派成功才算成功；或业务幂等兜底。
   </details>

---

> 📬 **完成练习后，进入下一篇 [06-02-雪花算法与分布式ID](./06-02-雪花算法与分布式ID.md)（待发布）—— 位结构、时钟回拨、号段模式**

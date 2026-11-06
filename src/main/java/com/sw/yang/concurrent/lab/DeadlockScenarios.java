package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static com.sw.yang.concurrent.lab.LabSupport.newThread;
import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * 死锁场景（对应 docs/05-03 §1.2 / §2 / §6 坑 1）。
 *
 * <p>两个变体，用来对照 jstack 的检测形态差异：
 * <ul>
 *   <li>{@link #injectSync} —— {@code synchronized} 监视器锁死锁；</li>
 *   <li>{@link #injectReentrant} —— {@code ReentrantLock} 死锁（走 AQS / ownable synchronizers）。</li>
 * </ul>
 *
 * <p><b>关键设计：每次注入都 new 一对专用锁对象</b>，绝不碰任何全局单例锁。
 * 这样死锁只污染注入出来的那两个线程，{@code /lab/status} 等接口照常可用 ——
 * 否则"注入死锁"会把观察工具一起打死，就没法排查了。
 *
 * <p><b>为什么标记 RESTART_REQUIRED</b>：两个线程互持锁永久阻塞。既不能
 * {@code interrupt}（阻塞在等锁上不响应中断），也没有循环可被标志位唤醒
 * → 进程内无法清理。这正是 docs/05-03 §6 说的"死锁只能重启"。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class DeadlockScenarios {

    private final FaultRegistry registry;

    public DeadlockScenarios(FaultRegistry registry) {
        this.registry = registry;
    }

    /**
     * synchronized 死锁：T1 持 A 等 B，T2 持 B 等 A。
     *
     * <p>{@code delayMs} 是"拿到第一把锁后先睡一会儿"的时长，用来保证两个线程
     * 都先拿到各自的第一把锁。睡太短可能 T1 两把都拿完就结束了，构造不出死锁。
     */
    public Map<String, Object> injectSync(long delayMs) {
        Object lockA = new Object();
        Object lockB = new Object();
        return inject("deadlock/sync", delayMs, lockA, lockB, false);
    }

    /** ReentrantLock 死锁：同上，但用 {@code ReentrantLock.lock()}。 */
    public Map<String, Object> injectReentrant(long delayMs) {
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        return inject("deadlock/lock", delayMs, lockA, lockB, true);
    }

    private Map<String, Object> inject(String type, long delayMs,
                                       Object lockA, Object lockB, boolean reentrant) {
        String id = registry.nextId(type);
        String t1Name = id + "-T1";
        String t2Name = id + "-T2";

        // T1：先 A 后 B
        Thread t1 = newThread(() -> {
            if (reentrant) {
                ((ReentrantLock) lockA).lock();
                try {
                    sleepQuietly(delayMs);
                    ((ReentrantLock) lockB).lock();   // ← 死锁点：永远等不到
                    try {
                        // 死锁后走不到这里
                    } finally {
                        ((ReentrantLock) lockB).unlock();
                    }
                } finally {
                    ((ReentrantLock) lockA).unlock();
                }
            } else {
                synchronized (lockA) {
                    sleepQuietly(delayMs);
                    synchronized (lockB) {            // ← 死锁点：永远等不到
                        // 走不到这里
                    }
                }
            }
        }, t1Name);

        // T2：先 B 后 A —— 顺序相反是死锁的根因
        Thread t2 = newThread(() -> {
            if (reentrant) {
                ((ReentrantLock) lockB).lock();
                try {
                    sleepQuietly(delayMs);
                    ((ReentrantLock) lockA).lock();   // ← 死锁点
                    try {
                        // 走不到这里
                    } finally {
                        ((ReentrantLock) lockA).unlock();
                    }
                } finally {
                    ((ReentrantLock) lockB).unlock();
                }
            } else {
                synchronized (lockB) {
                    sleepQuietly(delayMs);
                    synchronized (lockA) {            // ← 死锁点
                        // 走不到这里
                    }
                }
            }
        }, t2Name);

        t1.start();
        t2.start();

        registry.register(new FaultRegistry.ActiveFault(
                id, type, Instant.now(), List.of(t1Name, t2Name),
                FaultRegistry.CleanupKind.RESTART_REQUIRED,
                "真死锁：进程内无法清理，需重启进程"), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", type);
        result.put("id", id);
        result.put("threads", List.of(t1Name, t2Name));
        result.put("cleanup", "RESTART_REQUIRED");
        result.put("hint", "jstack -l <pid> 找 'Found one Java-level deadlock'；清理需重启进程");
        return result;
    }
}

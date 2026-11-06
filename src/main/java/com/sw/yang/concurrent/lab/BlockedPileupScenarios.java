package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.sw.yang.concurrent.lab.LabSupport.newThread;
import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * BLOCKED 堆积场景（对应 docs/05-03 §2.3 线程状态解读 + §5.3 {@code thread -b}）。
 *
 * <p>模拟线上最常见的"假死"：<b>一个线程攥着锁不放，后面所有人排队等</b>。
 * jstack 里能看到 1 个 {@code locked <X>} + N 个 {@code BLOCKED ... waiting to lock <X>}，
 * Arthas 的 {@code thread -b} 会直接点名那个持有者。
 *
 * <p>与 {@link DeadlockScenarios} 的区别：这里<b>不是死锁</b>（只有一个方向在等），
 * 只要持有者放手就能自愈 —— 所以它是可清理的。这个对照很重要：
 * "大量 BLOCKED" ≠ "死锁"，别一看到排队就喊死锁。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class BlockedPileupScenarios {

    private final LabProperties props;
    private final FaultRegistry registry;

    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public BlockedPileupScenarios(LabProperties props, FaultRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /**
     * @param holders 持锁线程数（一般 1 个就够，多个能观察"多把锁各自排队"）
     * @param waiters 排队线程数（会被 {@code lab.blocked.max-waiters} 截断）
     */
    public Map<String, Object> start(int holders, int waiters) {
        stop();

        int h = Math.max(1, holders);
        int maxWaiters = Math.max(1, props.getBlocked().getMaxWaiters());
        int w = Math.min(Math.max(1, waiters), maxWaiters);

        running = true;
        List<String> names = new ArrayList<>();

        // 每把锁配一个 holder + 分摊 waiter
        List<Object> locks = new ArrayList<>();
        for (int i = 0; i < h; i++) {
            locks.add(new Object());
        }

        for (int i = 0; i < h; i++) {
            Object lock = locks.get(i);
            String name = "lab-blocked-holder-" + i;
            names.add(name);
            // 持有者：持锁 + 循环查标志位。
            // ★ 用"小睡循环"而不是一次 sleep(60s)：这样 stop() 能秒退，
            //   否则清理要等到 sleep 结束 —— 可清理性取决于循环粒度。
            Thread t = newThread(() -> {
                synchronized (lock) {
                    while (running) {
                        sleepQuietly(50);
                    }
                }
            }, name);
            threads.add(t);
            t.start();
        }

        // 给 holder 一点时间先抢到锁，否则 waiter 可能反过来先拿到
        sleepQuietly(200);

        for (int i = 0; i < w; i++) {
            Object lock = locks.get(i % h);
            String name = "lab-blocked-waiter-" + i;
            names.add(name);
            Thread t = newThread(() -> {
                synchronized (lock) {
                    // holder 放手后才会走到这里 —— 走到就说明"自愈了"
                    sleepQuietly(1);
                }
            }, name);
            threads.add(t);
            t.start();
        }

        String id = registry.nextId("blocked");
        registry.register(new FaultRegistry.ActiveFault(
                id, "blocked", Instant.now(), names,
                FaultRegistry.CleanupKind.RECOVERABLE,
                "不是死锁：单一方向等待，持有者放手即可自愈"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", "blocked");
        result.put("id", id);
        result.put("holders", h);
        result.put("waiters", w);
        result.put("threads", names);
        result.put("cleanup", "DELETE /lab/blocked");
        result.put("hint", "Arthas `thread -b` 会直接点名持有锁的那个线程；注意这不是死锁");
        return result;
    }

    /** 清理：置标志位让 holder 退出同步块并释放锁 → waiter 依次通过。 */
    public Map<String, Object> stop() {
        running = false;
        List<Thread> snapshot = new ArrayList<>(threads);
        threads.clear();
        for (Thread t : snapshot) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long alive = snapshot.stream().filter(Thread::isAlive).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", true);
        result.put("threadsSignalled", snapshot.size());
        result.put("threadsStillAlive", alive);
        result.put("note", alive == 0 ? "全部退出，锁已释放" : "仍有线程存活（观察是否卡在非中断点）");
        return result;
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("threadCount", threads.size());
        return m;
    }
}

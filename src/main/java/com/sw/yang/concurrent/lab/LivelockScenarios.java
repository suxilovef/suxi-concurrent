package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static com.sw.yang.concurrent.lab.LabSupport.newThread;
import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * 活锁场景（对应 docs/05-03 §3.1 + §6 坑 1 ①）。
 *
 * <p><b>这个场景的存在意义是"反证"</b>：它和 {@link DeadlockScenarios} 一样有
 * 两把锁、一样互相等待，但线程状态是 <b>RUNNABLE</b> 而不是 BLOCKED/WAITING ——
 * 所以 {@code jstack} <b>不会</b>报 "Found one Java-level deadlock"。
 * 用它来说明"jstack 抓不到活锁"这条检测边界。
 *
 * <p>构造要点：两个线程以<b>相反顺序</b> {@code tryLock} 两把锁。
 * <ul>
 *   <li>无退避：T1 拿到 A 想要 B、T2 拿到 B 想要 A → 都失败 → 都释放 → <b>立刻</b>重试
 *       → 双方节奏锁死在同一拍上 → 一直撞车，谁也不前进；</li>
 *   <li>有退避：失败后随机 sleep，打散"同时重试"的节奏 → 迟早有人一次拿全。</li>
 * </ul>
 *
 * <p>指标 {@code attempts} / {@code success} 就是活锁的量化证据：
 * 前者爆涨到千万级，后者几乎不动。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class LivelockScenarios {

    private final FaultRegistry registry;

    private final ReentrantLock lockA = new ReentrantLock();
    private final ReentrantLock lockB = new ReentrantLock();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private volatile boolean running = false;

    public LivelockScenarios(FaultRegistry registry) {
        this.registry = registry;
    }

    /** 活锁版：{@code tryLock} 失败后立刻重试，不退避。 */
    public Map<String, Object> startNoBackoff(int requested) {
        return start(requested, false);
    }

    /** 修复版：失败后随机退避，打散重试节奏。 */
    public Map<String, Object> startWithBackoff(int requested) {
        return start(requested, true);
    }

    private Map<String, Object> start(int requested, boolean backoff) {
        stop();

        int n = Math.max(2, requested);
        running = true;
        attempts.set(0);
        success.set(0);

        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 偶数号先 A 后 B，奇数号先 B 后 A —— 顺序相反才可能活锁
            boolean aFirst = (i % 2 == 0);
            String name = "lab-livelock-" + (backoff ? "backoff-" : "") + i;
            names.add(name);
            Thread t = newThread(() -> {
                while (running) {
                    ReentrantLock first = aFirst ? lockA : lockB;
                    ReentrantLock second = aFirst ? lockB : lockA;
                    if (tryAcquireBoth(first, second, backoff)) {
                        try {
                            success.incrementAndGet();
                            sleepQuietly(1);   // 拿到锁做一丁点事
                        } finally {
                            second.unlock();
                            first.unlock();
                        }
                    }
                }
            }, name);
            threads.add(t);
            t.start();
        }

        String type = backoff ? "livelock/backoff" : "livelock";
        String id = registry.nextId(type);
        registry.register(new FaultRegistry.ActiveFault(
                id, type, Instant.now(), names,
                FaultRegistry.CleanupKind.RECOVERABLE,
                backoff
                        ? "修复版：随机退避打破同步节奏，线程会持续拿到锁"
                        : "活锁：jstack 报不出死锁（线程是 RUNNABLE 不是 BLOCKED），这是刻意留的检测边界"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", type);
        result.put("id", id);
        result.put("backoff", backoff);
        result.put("threads", names);
        result.put("cleanup", "DELETE /lab/livelock");
        result.put("hint", backoff
                ? "观察 success 持续增长 → 退避生效"
                : "jstack 不会报 deadlock；观察 attempts 爆涨而 success 几乎不动 → 空转无进展");
        return result;
    }

    /**
     * 尝试同时拿两把锁；拿不全就全放掉。
     *
     * @return 两把都拿到返回 true；因 {@code running=false} 退出返回 false
     */
    private boolean tryAcquireBoth(ReentrantLock first, ReentrantLock second, boolean backoff) {
        while (running) {
            boolean gotFirst = false;
            try {
                gotFirst = first.tryLock();
                if (gotFirst && second.tryLock()) {
                    return true;
                }
            } finally {
                // 只拿到第一把 → 必须放掉，否则退化成"持有并等待"（那是死锁的温床）
                if (gotFirst && !second.isHeldByCurrentThread()) {
                    first.unlock();
                }
            }
            attempts.incrementAndGet();
            if (backoff) {
                // 随机退避：让两个线程的重试节奏错开，这是破解活锁的关键
                sleepQuietly(ThreadLocalRandom.current().nextInt(5) + 1L);
            }
            // 无退避：什么都不做，立刻重试 → 与对方保持同拍 → 活锁
            if (!running) {
                return false;
            }
        }
        return false;
    }

    /** 停止并返回统计（attempts/success 是活锁 vs 退避的量化对照）。 */
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
        long done = snapshot.stream().filter(t -> !t.isAlive()).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", true);
        result.put("threadsSignalled", snapshot.size());
        result.put("threadsExited", done);
        result.put("attempts", attempts.get());
        result.put("success", success.get());
        double ratio = attempts.get() == 0 ? 0.0 : (double) success.get() / attempts.get();
        result.put("successRatio", String.format("%.6f", ratio));
        return result;
    }

    /** 实时指标，供 /lab/status 观察活锁的"投入产出比"。 */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("threadCount", threads.size());
        m.put("attempts", attempts.get());
        m.put("success", success.get());
        return m;
    }
}

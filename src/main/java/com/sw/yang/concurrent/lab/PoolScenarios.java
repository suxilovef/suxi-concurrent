package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * 线程池故障场景（对应 docs/05-03 §6 坑 1 里的"资源死锁"）。
 *
 * <p>这是<b>最容易在真实线上踩、又最难看出来</b>的一类故障：jstack 里
 * <b>没有任何死锁报告</b>，线程状态全是正常的 {@code WAITING (parking)}，
 * 但整个服务就是不动了 —— 因为它不是"锁死锁"，而是"资源死锁"：
 * 池里的线程全在等<b>排在同一个池队列里、永远轮不到执行的子任务</b>。
 *
 * <p>两个变体：
 * <ul>
 *   <li>{@link #selfDeadlock} —— 池内自等待（池内任务提交子任务并 {@code get()}）。
 *       这是"接口全超时但 jstack 报不出死锁"的经典成因。</li>
 *   <li>{@link #exhaust} —— 池被长任务占满，外部请求只能排队
 *       （配合 {@code /lab/biz/slow-task} 看"别人的接口也跟着挂了"）。</li>
 * </ul>
 *
 * <p><b>为什么要自己持有一个池</b>，而不是用 Spring 的：{@code shutdownNow()}
 * 能精准清理干净，不污染 Tomcat 自己的线程池 —— 否则"清理故障"会变成
 * "把服务搞挂"。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class PoolScenarios {

    private final FaultRegistry registry;

    private volatile ThreadPoolExecutor pool;

    public PoolScenarios(FaultRegistry registry) {
        this.registry = registry;
    }

    /** 池内自等待：每个任务向同一个池提交子任务、然后阻塞等它完成。 */
    public Map<String, Object> selfDeadlock(int poolSize, int tasks) {
        stop();
        ThreadPoolExecutor p = ensurePool(poolSize);

        for (int i = 0; i < tasks; i++) {
            p.execute(() -> {
                // 子任务要执行，必须先有空闲 worker；
                // 但所有 worker 都被这些父任务占着 → 子任务永远留在队列里
                Future<?> child = p.submit(() -> {
                    // 走不到这里
                });
                try {
                    child.get();   // ← 死等：这就是"资源死锁"的现场
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();   // shutdownNow 时从这里退出
                } catch (ExecutionException e) {
                    // 子任务异常，父任务照常结束
                }
            });
        }

        String id = registry.nextId("pool/self-deadlock");
        registry.register(new FaultRegistry.ActiveFault(
                id, "pool/self-deadlock", Instant.now(),
                threadNames(p),
                FaultRegistry.CleanupKind.RECOVERABLE,
                "资源死锁：jstack 不报 deadlock，线程是 WAITING；DELETE /lab/pool 可解"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", "pool/self-deadlock");
        result.put("id", id);
        result.put("poolSize", poolSize);
        result.put("tasks", tasks);
        result.put("poolState", poolState(p));
        result.put("cleanup", "DELETE /lab/pool");
        result.put("hint", "jstack 里搜不到 'Found one Java-level deadlock'，但 worker 全 WAITING 在 FutureTask.get");
        return result;
    }

    /** 池耗尽：用长任务占满全部 worker，后续任务只能排队。 */
    public Map<String, Object> exhaust(int poolSize, long holdMs) {
        stop();
        ThreadPoolExecutor p = ensurePool(poolSize);

        for (int i = 0; i < poolSize; i++) {
            p.execute(() -> sleepQuietly(holdMs));
        }

        String id = registry.nextId("pool/exhaust");
        registry.register(new FaultRegistry.ActiveFault(
                id, "pool/exhaust", Instant.now(),
                threadNames(p),
                FaultRegistry.CleanupKind.RECOVERABLE,
                "池耗尽：worker 全 TIMED_WAITING，外部请求排队超时"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", "pool/exhaust");
        result.put("id", id);
        result.put("poolSize", poolSize);
        result.put("holdMs", holdMs);
        result.put("poolState", poolState(p));
        result.put("cleanup", "DELETE /lab/pool");
        result.put("hint", "现在再调 /lab/biz/slow-task，请求会堆在队列里直到超时");
        return result;
    }

    /**
     * 给"业务接口"用的提交入口 —— 故意共用同一个池，
     * 这样注入故障后能看到"别的接口也一起挂了"这个真实症状。
     */
    public Map<String, Object> submitBusinessTask(long workMs) {
        ThreadPoolExecutor p = ensurePool(2);
        long t0 = System.nanoTime();
        Future<?> f = p.submit(() -> sleepQuietly(workMs));
        try {
            f.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("status", "INTERRUPTED", "poolState", poolState(p));
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "TIMEOUT");
            m.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
            m.put("poolState", poolState(p));
            m.put("hint", "池被故障占满了 —— 这就是'别人的接口也跟着超时'");
            return m;
        }
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "OK");
        m.put("elapsedMs", elapsed);
        m.put("poolState", poolState(p));
        return m;
    }

    /** 清理：shutdownNow 会中断 worker，父任务的 {@code child.get()} 抛中断后退出。 */
    public Map<String, Object> stop() {
        ThreadPoolExecutor p = this.pool;
        Map<String, Object> result = new LinkedHashMap<>();
        if (p == null) {
            result.put("stopped", true);
            result.put("note", "没有活动线程池");
            return result;
        }
        List<Runnable> dropped = p.shutdownNow();
        try {
            p.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        result.put("stopped", true);
        result.put("droppedTasks", dropped.size());
        result.put("terminated", p.isTerminated());
        if (dropped.isEmpty() && !p.isTerminated()) {
            result.put("note", "仍有 worker 未退出（可能卡在非中断点）");
        }
        this.pool = null;
        return result;
    }

    public Map<String, Object> state() {
        ThreadPoolExecutor p = this.pool;
        if (p == null) {
            return Map.of("active", false);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", true);
        m.putAll(poolState(p));
        return m;
    }

    private Map<String, Object> poolState(ThreadPoolExecutor p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolSize", p.getCorePoolSize());
        m.put("activeCount", p.getActiveCount());
        m.put("queuedTasks", p.getQueue().size());
        m.put("completedTasks", p.getCompletedTaskCount());
        m.put("shutdown", p.isShutdown());
        return m;
    }

    private List<String> threadNames(ThreadPoolExecutor p) {
        List<String> names = new ArrayList<>();
        ThreadFactory f = p.getThreadFactory();
        if (f != null) {
            // ThreadFactory 本身不持有已创建的线程引用，这里只能给个占位说明；
            // 真正的线程名在 jstack 里以 lab-pool-* 前缀出现。
            names.add("lab-pool-*（jstack 里按此前缀找）");
        }
        return names;
    }

    private synchronized ThreadPoolExecutor ensurePool(int poolSize) {
        int size = Math.max(1, poolSize);
        ThreadPoolExecutor existing = this.pool;
        if (existing != null && !existing.isShutdown() && existing.getCorePoolSize() == size) {
            return existing;
        }
        if (existing != null) {
            existing.shutdownNow();
        }
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor created = new ThreadPoolExecutor(
                size, size,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "lab-pool-" + seq.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                });
        this.pool = created;
        return created;
    }
}

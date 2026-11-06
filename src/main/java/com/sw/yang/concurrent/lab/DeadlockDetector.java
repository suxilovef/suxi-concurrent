package com.sw.yang.concurrent.lab;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置的程序化死锁检测（对应 docs/05-03 §4）。
 *
 * <p><b>和 jstack 的本质差别</b>：
 * <ul>
 *   <li>本类 —— <b>进程内、自动、持续</b>；代价是必须"提前把检测代码接进去"；</li>
 *   <li>jstack —— <b>进程外、人工、瞬时快照</b>；代价是必须"事后还能 attach 上去"。</li>
 * </ul>
 * 两者互补而非替代。演练时先注入死锁，再调 {@code POST /lab/detect/now}，
 * 就能看到同一份事实被两条路径各发现一次。
 *
 * <p>用 {@code findDeadlockedThreads()} 而不是已过时的
 * {@code findMonitorDeadlockedThreads()}：前者覆盖 monitor <b>和</b>
 * ownable synchronizer（也就是 {@code ReentrantLock} 那条 AQS 路径），后者只认
 * {@code synchronized}。
 *
 * <p><b>为什么调度线程必须是 daemon</b>：如果检测器自己是个非 daemon 线程，
 * 它会一直撑着 JVM 不退出 —— 这就和"死锁线程撑住 JVM"这个被观察现象混在一起，
 * 分不清到底是谁让进程活着的。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class DeadlockDetector {

    private static final Logger log = LoggerFactory.getLogger(DeadlockDetector.class);

    private final LabProperties props;

    private final ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lab-deadlock-detector");
                t.setDaemon(true);   // ★ 见类注释
                return t;
            });

    private final AtomicInteger hits = new AtomicInteger();
    private volatile Instant lastScanAt;
    private volatile List<String> lastDeadlockedThreads = List.of();

    public DeadlockDetector(LabProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() {
        if (!props.getDetector().isEnabled()) {
            log.warn("[lab] DeadlockDetector 已配置为关闭（lab.detector.enabled=false）");
            return;
        }
        int interval = Math.max(1, props.getDetector().getIntervalSeconds());
        scheduler.scheduleAtFixedRate(this::scanAndReport, 0, interval, TimeUnit.SECONDS);
        log.warn("[lab] DeadlockDetector 已启动，每 {}s 扫描一次 findDeadlockedThreads()", interval);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 立即扫描一次（供 {@code POST /lab/detect/now} 调用）。 */
    public Map<String, Object> scanNow() {
        return scanAndReport();
    }

    private Map<String, Object> scanAndReport() {
        Map<String, Object> result = new LinkedHashMap<>();
        lastScanAt = Instant.now();

        long[] ids = mxBean.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            result.put("deadlocked", false);
            result.put("threads", List.of());
            result.put("scannedAt", lastScanAt.toString());
            result.put("totalHits", hits.get());
            return result;
        }

        hits.incrementAndGet();
        ThreadInfo[] infos = mxBean.getThreadInfo(ids, true, true);
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;   // 线程可能在取信息前就没了
            }
            names.add(info.getThreadName());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("threadName", info.getThreadName());
            d.put("threadId", info.getThreadId());
            d.put("state", String.valueOf(info.getThreadState()));
            d.put("lockName", info.getLockName());
            d.put("lockOwnerName", info.getLockOwnerName());
            StackTraceElement[] stack = info.getStackTrace();
            d.put("stackTop", stack.length > 0 ? stack[0].toString() : "(空栈)");
            details.add(d);
        }
        lastDeadlockedThreads = names;

        log.error("[lab] ⚠️ 检测到死锁！涉及 {} 个线程：{}", names.size(), names);
        for (Map<String, Object> d : details) {
            log.error("[lab]    - {} [{}] 等待 {}（持有者 {}）栈顶 {}",
                    d.get("threadName"), d.get("state"), d.get("lockName"),
                    d.get("lockOwnerName"), d.get("stackTop"));
        }

        result.put("deadlocked", true);
        result.put("count", names.size());
        result.put("threads", names);
        result.put("details", details);
        result.put("scannedAt", lastScanAt.toString());
        result.put("totalHits", hits.get());
        return result;
    }

    /** 供 /lab/status 展示的检测器状态。 */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.getDetector().isEnabled());
        m.put("intervalSeconds", props.getDetector().getIntervalSeconds());
        m.put("totalHits", hits.get());
        m.put("lastScanAt", lastScanAt == null ? null : lastScanAt.toString());
        m.put("lastDeadlockedThreads", lastDeadlockedThreads);
        return m;
    }
}

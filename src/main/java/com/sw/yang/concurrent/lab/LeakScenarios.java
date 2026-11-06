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
 * 内存泄漏 / GC 风暴场景（对应 docs/05-03 §5.5 ②）。
 *
 * <p>用来观察"CPU 飙高其实是 GC 在烧"这条岔路：线程栈里看不到任何业务死循环，
 * 但 {@code dashboard} / {@code jstat} 显示 GC 次数和耗时持续攀升，
 * {@code jmap -histo:live} 能点名到底是哪个类在涨。
 *
 * <p><b>为什么设 {@code lab.leak.max-mb} 上限</b>：不设的话会把 JVM 撑到 OOM，
 * 之后连 {@code /lab/status} 都调不动，反而没法观察 —— 演练要的是
 * "看得到 GC 恶化"，不是"把服务弄死"。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class LeakScenarios {

    /** 泄漏的"元凶"类 —— 刻意用一个自定义类型，好让 jmap -histo 一眼认出来。 */
    public static class LabLeakPayload {
        @SuppressWarnings("unused")
        private final byte[] payload;

        LabLeakPayload(int bytes) {
            this.payload = new byte[bytes];
        }
    }

    private final LabProperties props;
    private final FaultRegistry registry;

    /** 静态引用，故意不让它被回收 —— 这就是泄漏本身。 */
    private final List<LabLeakPayload> sink = new ArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private volatile int allocatedMb = 0;

    public LeakScenarios(LabProperties props, FaultRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /**
     * @param mbPerSec 每秒塞入的 MB（到 {@code lab.leak.max-mb} 就停止增长）
     */
    public Map<String, Object> start(int mbPerSec) {
        stop();
        running = true;
        int rate = Math.max(1, mbPerSec);
        int cap = Math.max(rate, props.getLeak().getMaxMb());

        String name = "lab-leak";
        Thread t = newThread(() -> {
            while (running) {
                int nowMb = allocatedMb;
                if (nowMb >= cap) {
                    // 到顶就停手，只保留对象让 GC 压力持续存在
                    sleepQuietly(500);
                    continue;
                }
                int add = Math.min(rate, cap - nowMb);
                synchronized (sink) {
                    for (int i = 0; i < add; i++) {
                        sink.add(new LabLeakPayload(1024 * 1024));
                    }
                }
                allocatedMb = nowMb + add;
                sleepQuietly(1000);
            }
        }, name);
        threads.add(t);
        t.start();

        String id = registry.nextId("leak");
        registry.register(new FaultRegistry.ActiveFault(
                id, "leak", Instant.now(), List.of(name),
                FaultRegistry.CleanupKind.RECOVERABLE,
                "DELETE /lab/leak 清引用，等下一次 Full GC 释放"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", "leak");
        result.put("id", id);
        result.put("mbPerSec", rate);
        result.put("maxMb", cap);
        result.put("payloadClass", LabLeakPayload.class.getName());
        result.put("cleanup", "DELETE /lab/leak");
        result.put("hint", "Arthas dashboard 看 GC 攀升；jmap -histo:live <pid> | head -20 找 LabLeakPayload");
        return result;
    }

    /** 清理：清空引用，下一次 Full GC 就能释放。 */
    public Map<String, Object> stop() {
        running = false;
        for (Thread t : new ArrayList<>(threads)) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
        synchronized (sink) {
            sink.clear();
        }
        int released = allocatedMb;
        allocatedMb = 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", true);
        result.put("releasedMb", released);
        result.put("note", "引用已清空，等下次 GC 回收（可用 jcmd <pid> GC.run 立即触发）");
        return result;
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("allocatedMb", allocatedMb);
        m.put("retainedObjects", sink.size());
        return m;
    }
}

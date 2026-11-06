package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 活动故障注册表 —— 这是"能反复演练"的中枢。
 *
 * <p>为什么要单独抽一个注册表，而不是让每个 Scenario 自己记状态：
 * <ol>
 *   <li>状态接口要一次性列出<b>所有</b>正在注入的故障（演练前确认现场干净）；</li>
 *   <li>清理接口 `clean &lt;场景|all&gt;` 要能按 id 反查 stopper 并调用；</li>
 *   <li>把"谁在跑"收敛到一处，才可能做到"演练前清空 / 演练后清空"。</li>
 * </ol>
 *
 * <p>为什么 {@code stopper} 不放进 {@link ActiveFault} record 里：
 * record 要序列化成 JSON 返回给前端，而 {@code Runnable} 没法序列化。
 * 拆成两张表（元信息 + 清理动作）比加 {@code @JsonIgnore} 更不容易出错。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class FaultRegistry {

    /** 这个故障能不能在进程内清理掉。 */
    public enum CleanupKind {
        /** 可以通过置标志位 / shutdownNow 让它停下来。 */
        RECOVERABLE,
        /**
         * 真死锁：两个线程互持锁永久阻塞，既不能 interrupt（阻塞在等锁上不响应中断），
         * 也不能被标志位唤醒（根本没有在跑循环）→ 只能重启进程。
         * 这不是缺陷，是 docs/05-03 §6「死锁只能重启」的现场证据。
         */
        RESTART_REQUIRED
    }

    /** 对外暴露的故障元信息（可 JSON 序列化）。 */
    public record ActiveFault(String id,
                              String type,
                              Instant startedAt,
                              List<String> threadNames,
                              CleanupKind cleanup,
                              String note) {
    }

    private final Map<String, ActiveFault> faults = new LinkedHashMap<>();
    private final Map<String, Runnable> stoppers = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 生成形如 {@code deadlock-sync-3} 的故障 id。 */
    public String nextId(String type) {
        return type.replace('/', '-') + "-" + seq.incrementAndGet();
    }

    /**
     * 登记一个活动故障。
     *
     * @param stopper 清理动作；真死锁传 {@code null}（因为没法清理）
     */
    public synchronized void register(ActiveFault fault, Runnable stopper) {
        faults.put(fault.id(), fault);
        if (stopper != null) {
            stoppers.put(fault.id(), stopper);
        }
    }

    /** 注销并返回被移除的故障；不存在返回 null。 */
    public synchronized ActiveFault unregister(String id) {
        stoppers.remove(id);
        return faults.remove(id);
    }

    /** 当前所有活动故障（快照）。 */
    public synchronized List<ActiveFault> snapshot() {
        return new ArrayList<>(faults.values());
    }

    public synchronized ActiveFault get(String id) {
        return faults.get(id);
    }

    /** 取清理动作；没有则返回 null。 */
    public synchronized Runnable stopperFor(String id) {
        return stoppers.get(id);
    }

    /** 把注册表清空（只清账本，不执行清理动作）。 */
    public synchronized void clear() {
        faults.clear();
        stoppers.clear();
    }

    /** 按类型前缀匹配所有活动故障 id，供 `clean cpu` 这类按场景清理用。 */
    public synchronized List<String> idsByTypePrefix(String prefix) {
        List<String> ids = new ArrayList<>();
        for (ActiveFault f : faults.values()) {
            if (f.type().startsWith(prefix) || f.type().equals(prefix)) {
                ids.add(f.id());
            }
        }
        return ids;
    }
}

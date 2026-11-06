package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static com.sw.yang.concurrent.lab.LabSupport.clampAtLeastOne;
import static com.sw.yang.concurrent.lab.LabSupport.newThread;

/**
 * CPU 飙高场景（对应 docs/05-03 §5.4 排查全流程 + §5.5 成因分类）。
 *
 * <p>两个变体，覆盖 §5.5 里两种不同的"栈顶长相"：
 * <ul>
 *   <li>{@code spin} —— 业务代码死循环（§5.5 ①/⑤），栈顶是 {@link #burnLoop}；</li>
 *   <li>{@code regex} —— 正则灾难性回溯（§5.5 ④），栈顶是 {@code Pattern} 内部，
 *       这一类在真实线上特别迷惑人，因为栈里全是 JDK 代码、看不到自己的业务方法名。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code volatile boolean running} 而不是 {@code interrupt()}</b>：
 * 纯 CPU 空转循环里没有任何阻塞点（没有 sleep / wait / IO），{@code interrupt()}
 * 只是置个标志位，循环体不检查它就毫无效果。所以必须显式查标志位才能停下来。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class CpuScenarios {

    private final LabProperties props;
    private final FaultRegistry registry;

    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final AtomicLong iterations = new AtomicLong();
    private volatile boolean running = false;

    public CpuScenarios(LabProperties props, FaultRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /**
     * 启动 CPU 空转。
     *
     * @param requested 期望线程数（会被 {@code lab.cpu.max-threads} 截断）
     * @param mode      {@code spin} 或 {@code regex}
     */
    public Map<String, Object> burn(int requested, String mode) {
        stop();   // 幂等：先清掉上一轮，避免线程越注越多

        int max = clampAtLeastOne(props.getCpu().getMaxThreads());
        int n = Math.min(clampAtLeastOne(requested), max);
        boolean regex = "regex".equalsIgnoreCase(mode);

        running = true;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = "lab-cpu-" + (regex ? "regex-" : "spin-") + i;
            names.add(name);
            Thread t = newThread(() -> {
                if (regex) {
                    catastrophicBacktracking();
                } else {
                    burnLoop();
                }
            }, name);
            threads.add(t);
            t.start();
        }

        String id = registry.nextId("cpu/burn");
        registry.register(new FaultRegistry.ActiveFault(
                id, "cpu/burn", Instant.now(), names,
                FaultRegistry.CleanupKind.RECOVERABLE,
                "DELETE /lab/cpu/burn 可停下（靠 volatile 标志位）"),
                this::stop);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injected", "cpu/burn");
        result.put("id", id);
        result.put("mode", regex ? "regex" : "spin");
        result.put("threads", names);
        result.put("requested", requested);
        result.put("actual", n);
        result.put("cappedBy", max);
        result.put("cleanup", "DELETE /lab/cpu/burn");
        result.put("hint", "top -Hp <pid> 找高 CPU 线程 → printf \"%x\" <tid> → jstack <pid> | grep -A30 0x<hex>");
        return result;
    }

    /** 停止全部空转线程，返回统计。 */
    public Map<String, Object> stop() {
        running = false;
        List<Thread> snapshot = new ArrayList<>(threads);
        threads.clear();
        for (Thread t : snapshot) {
            // 循环体每轮查一次 running，所以这里只需等它自然退出；
            // 但 regex 模式下"一轮"要跑完整个回溯，会有秒级延迟 —— 这正是
            // "清理延迟取决于循环粒度"的现场例子。
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
        result.put("iterations", iterations.get());
        return result;
    }

    /** 当前空转线程数与累计轮次，供 /lab/status 展示。 */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("threadCount", threads.size());
        m.put("iterations", iterations.get());
        return m;
    }

    private void burnLoop() {
        double x = 0;
        while (running) {
            // 让编译器没法把整个循环优化掉：x 参与下一轮计算
            x += Math.sin(x) * 0.001;
            iterations.incrementAndGet();
        }
        // 退出前把结果"用掉"，否则 JIT 可能判定整个循环无副作用
        if (x == Double.MAX_VALUE) {
            System.out.println(x);
        }
    }

    /**
     * 正则灾难性回溯（ReDoS），对应 docs/05-03 §5.5 ④。
     *
     * <p>{@code (a+)+b} 配上 "aaaa...a!" 会让回溯次数随 a 的个数<b>指数增长</b>。
     * 取 25 个 a 是为了让"单次匹配"落在秒级：既足够把 CPU 打满，
     * 又不会长到 {@link #stop()} 完全没响应。
     */
    private void catastrophicBacktracking() {
        String input = "a".repeat(25) + "!";
        Pattern pattern = Pattern.compile("(a+)+b");
        while (running) {
            pattern.matcher(input).matches();   // 单次调用内部就是上亿步回溯
            iterations.incrementAndGet();
        }
    }
}

package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故障注入实验室的 REST 门面（对应 docs/05-05）。
 *
 * <h2>为什么每类故障都是"注入用 POST、清理用 DELETE"</h2>
 * 因为它们是<b>状态变更</b>不是查询：注入会在后台留下常驻线程，
 * 清理会真的把线程停掉。用 GET 既不符合语义，也会被浏览器/代理预取
 * 意外触发 —— 对一个"能打死服务"的接口来说这很危险。
 *
 * <h2>为什么注入接口必须"立刻返回"</h2>
 * 故障要留在<b>后台线程</b>里，而不是在当前请求线程里发生。
 * 否则注入动作本身会占死一个 Tomcat worker，你还没开始排查，
 * 排查工具（HTTP 调 /lab/status）就先没响应了 —— 那就把"注入故障"
 * 变成了"另一个故障"。
 *
 * <h2>安全</h2>
 * 整个类挂 {@code @ConditionalOnProperty(lab.enabled=true)}：
 * 默认<b>不注册</b>，访问 {@code /lab/**} 得到 404。
 * 加上部署时只绑定 loopback（宿主机 {@code --server.address=127.0.0.1}、
 * 容器 {@code -p 127.0.0.1:8081:8080}），构成"默认关 + 不暴露"双保险。
 */
@RestController
@RequestMapping("/lab")
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class LabController {

    private final FaultRegistry registry;
    private final DeadlockScenarios deadlocks;
    private final CpuScenarios cpu;
    private final LivelockScenarios livelock;
    private final PoolScenarios pool;
    private final BlockedPileupScenarios blocked;
    private final LeakScenarios leak;
    private final DeadlockDetector detector;
    private final OrderService orderService;

    public LabController(FaultRegistry registry,
                         DeadlockScenarios deadlocks,
                         CpuScenarios cpu,
                         LivelockScenarios livelock,
                         PoolScenarios pool,
                         BlockedPileupScenarios blocked,
                         LeakScenarios leak,
                         DeadlockDetector detector,
                         OrderService orderService) {
        this.registry = registry;
        this.deadlocks = deadlocks;
        this.cpu = cpu;
        this.livelock = livelock;
        this.pool = pool;
        this.blocked = blocked;
        this.leak = leak;
        this.detector = detector;
        this.orderService = orderService;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /**
     * 演练前后的"现场确认"接口。
     *
     * <p>返回的 {@code pid} / {@code availableProcessors} / {@code maxHeapMb}
     * 三个数在 docs/05-05 第二幕里是关键证据：
     * 同一份 jar，裸跑时是 {@code pid≠1 / 4 核 / ~900MB}，
     * 容器里变成 {@code pid=1 / 2 核 / ~375MB}。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Runtime rt = Runtime.getRuntime();
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labEnabled", true);
        m.put("pid", ProcessHandle.current().pid());
        m.put("availableProcessors", rt.availableProcessors());
        m.put("maxHeapMb", rt.maxMemory() / 1024 / 1024);
        m.put("usedHeapMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        m.put("jvmUptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        m.put("threadCount", mxBean.getThreadCount());
        m.put("peakThreadCount", mxBean.getPeakThreadCount());
        m.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());

        m.put("activeFaults", registry.snapshot());
        m.put("detector", detector.state());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cpu", cpu.state());
        detail.put("livelock", livelock.state());
        detail.put("pool", pool.state());
        detail.put("blocked", blocked.state());
        detail.put("leak", leak.state());
        m.put("scenarioState", detail);
        return m;
    }

    // ------------------------------------------------------------------
    // 死锁（docs/05-03 §1.2 / §2）
    // ------------------------------------------------------------------

    @PostMapping("/deadlock/sync")
    public Map<String, Object> deadlockSync(
            @RequestParam(defaultValue = "200") long delayMs) {
        return deadlocks.injectSync(delayMs);
    }

    @PostMapping("/deadlock/lock")
    public Map<String, Object> deadlockLock(
            @RequestParam(defaultValue = "200") long delayMs) {
        return deadlocks.injectReentrant(delayMs);
    }

    // ------------------------------------------------------------------
    // CPU 飙高（§5.4 / §5.5）
    // ------------------------------------------------------------------

    @PostMapping("/cpu/burn")
    public Map<String, Object> cpuBurn(
            @RequestParam(defaultValue = "4") int threads,
            @RequestParam(defaultValue = "spin") String mode) {
        return cpu.burn(threads, mode);
    }

    @DeleteMapping("/cpu/burn")
    public Map<String, Object> cpuBurnStop() {
        registry.idsByTypePrefix("cpu/burn").forEach(registry::unregister);
        return cpu.stop();
    }

    // ------------------------------------------------------------------
    // 活锁（§3.1 / §6 坑 1）—— 用来证明"jstack 抓不到"
    // ------------------------------------------------------------------

    @PostMapping("/livelock")
    public Map<String, Object> livelockStart(
            @RequestParam(defaultValue = "2") int threads) {
        return livelock.startNoBackoff(threads);
    }

    @PostMapping("/livelock/backoff")
    public Map<String, Object> livelockBackoff(
            @RequestParam(defaultValue = "2") int threads) {
        return livelock.startWithBackoff(threads);
    }

    @DeleteMapping("/livelock")
    public Map<String, Object> livelockStop() {
        registry.idsByTypePrefix("livelock").forEach(registry::unregister);
        return livelock.stop();
    }

    // ------------------------------------------------------------------
    // 线程池（§6 坑 1「资源死锁」）
    // ------------------------------------------------------------------

    @PostMapping("/pool/self-deadlock")
    public Map<String, Object> poolSelfDeadlock(
            @RequestParam(defaultValue = "2") int poolSize,
            @RequestParam(defaultValue = "2") int tasks) {
        return pool.selfDeadlock(poolSize, tasks);
    }

    @PostMapping("/pool/exhaust")
    public Map<String, Object> poolExhaust(
            @RequestParam(defaultValue = "2") int poolSize,
            @RequestParam(defaultValue = "60000") long holdMs) {
        return pool.exhaust(poolSize, holdMs);
    }

    @DeleteMapping("/pool")
    public Map<String, Object> poolStop() {
        registry.idsByTypePrefix("pool").forEach(registry::unregister);
        return pool.stop();
    }

    // ------------------------------------------------------------------
    // BLOCKED 堆积（§2.3 / §5.3 thread -b）
    // ------------------------------------------------------------------

    @PostMapping("/blocked")
    public Map<String, Object> blockedStart(
            @RequestParam(defaultValue = "1") int holders,
            @RequestParam(defaultValue = "8") int waiters) {
        return blocked.start(holders, waiters);
    }

    @DeleteMapping("/blocked")
    public Map<String, Object> blockedStop() {
        registry.idsByTypePrefix("blocked").forEach(registry::unregister);
        return blocked.stop();
    }

    // ------------------------------------------------------------------
    // 内存泄漏 / GC 风暴（§5.5 ②）
    // ------------------------------------------------------------------

    @PostMapping("/leak")
    public Map<String, Object> leakStart(
            @RequestParam(defaultValue = "20") int mbPerSec) {
        return leak.start(mbPerSec);
    }

    @DeleteMapping("/leak")
    public Map<String, Object> leakStop() {
        registry.idsByTypePrefix("leak").forEach(registry::unregister);
        return leak.stop();
    }

    // ------------------------------------------------------------------
    // 程序化死锁检测（§4）
    // ------------------------------------------------------------------

    /** 让内置 Detector 立即扫描一次，对比"进程内自动发现" vs "事后 jstack"。 */
    @PostMapping("/detect/now")
    public Map<String, Object> detectNow() {
        return detector.scanNow();
    }

    // ------------------------------------------------------------------
    // 业务演示（§5.2 watch / trace / monitor）
    // ------------------------------------------------------------------

    @GetMapping("/biz/order")
    public OrderDto createOrder(
            @RequestParam(defaultValue = "u-1001") String userId,
            @RequestParam(defaultValue = "88") long skuId,
            @RequestParam(defaultValue = "2") int qty,
            @RequestParam(defaultValue = "0") long slowMs,
            @RequestParam(defaultValue = "0") int failRate) {
        return orderService.createOrder(userId, skuId, qty, slowMs, failRate);
    }

    /** 走 lab 自持的小线程池 —— 池被 {@code /lab/pool/*} 占满时这里会超时。 */
    @GetMapping("/biz/slow-task")
    public Map<String, Object> slowTask(
            @RequestParam(defaultValue = "100") long ms) {
        return pool.submitBusinessTask(ms);
    }

    // ------------------------------------------------------------------
    // 一键清理
    // ------------------------------------------------------------------

    /**
     * 清理所有<b>可清理</b>的故障。
     *
     * <p>死锁（{@code RESTART_REQUIRED}）会被单独列出并如实告知"清不掉，需重启" ——
     * 不假装清理成功，因为这正是 docs/05-03 §6 的教学点。
     */
    @DeleteMapping("/all")
    public Map<String, Object> cleanAll() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 先记下清不掉的，避免被下面的 unregister 抹掉账本
        List<FaultRegistry.ActiveFault> unrestartable = new ArrayList<>();
        for (FaultRegistry.ActiveFault f : registry.snapshot()) {
            if (f.cleanup() == FaultRegistry.CleanupKind.RESTART_REQUIRED) {
                unrestartable.add(f);
            }
        }

        result.put("cpu", cpu.stop());
        result.put("livelock", livelock.stop());
        result.put("pool", pool.stop());
        result.put("blocked", blocked.stop());
        result.put("leak", leak.stop());

        registry.clear();
        // 把清不掉的重新登记回去，保证 /lab/status 如实反映"还有死锁没解决"
        for (FaultRegistry.ActiveFault f : unrestartable) {
            registry.register(f, null);
        }

        result.put("restartRequired", unrestartable);
        result.put("note", unrestartable.isEmpty()
                ? "全部故障已清理"
                : "有 " + unrestartable.size() + " 个真死锁无法在进程内清理，请重启进程");
        return result;
    }
}

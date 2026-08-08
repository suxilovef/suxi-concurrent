package com.sw.yang.concurrent.pattern;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 3：用 ThreadLocal 实现 TraceId 链路追踪（真实场景）
 *
 * 场景：请求进来 → 生成 TraceId → 后续所有日志带上 TraceId
 */
public class TraceIdTest {

    // TraceId 上下文（static 作为全局钥匙）
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    // 模拟：设置 TraceId（通常由 Filter/Interceptor 在请求入口调用）
    private static void setTraceId() {
        TRACE_ID.set(UUID.randomUUID().toString().substring(0, 8));
    }

    // 模拟：业务日志（带上当前线程的 TraceId）
    private static void log(String msg) {
        System.out.println("[" + TRACE_ID.get() + "] " + msg);
    }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> {
                Thread t = new Thread(r);
                t.setName("biz-worker");
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Test
    public void testTraceId() throws InterruptedException {
        // 模拟两个并发请求
        for (int i = 1; i <= 3; i++) {
            final int reqId = i;
            new Thread(() -> {
                setTraceId(); // ① 请求入口生成 TraceId
                log("请求 " + reqId + " 开始");

                // ② 异步任务（线程池）—— 这里 TraceId 会丢失！
                executor.execute(() -> {
                    // ❌ 线程池线程没有 TRACE_ID → 日志无 TraceId
                    log("异步处理请求 " + reqId + "（注意：TraceId 丢失了！）");
                    // ✅ 正确做法：任务提交前捕获，执行时恢复（TTL 的原理）
                });

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log("请求 " + reqId + " 结束");
                TRACE_ID.remove(); // ③ 请求结束清理
            }, "req-" + i).start();
        }

        Thread.sleep(1000);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 演示完成：线程池场景 TraceId 丢失（这就是 TTL 存在的意义）");
    }
}

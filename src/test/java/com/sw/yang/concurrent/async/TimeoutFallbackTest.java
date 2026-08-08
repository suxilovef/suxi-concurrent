package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 3：异常恢复 + 超时降级（生产核心场景）
 */
public class TimeoutFallbackTest {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> {
                Thread t = new Thread(r);
                t.setName("fallback-worker");
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    // 模拟：可能抛异常的第三方调用
    private String callThirdParty(boolean fail) {
        sleep(500);
        if (fail) throw new RuntimeException("第三方服务挂了");
        return "第三方正常数据";
    }

    // 模拟：很慢的调用（2 秒）
    private String slowCall() {
        sleep(2000);
        return "慢接口数据";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void testExceptionFallback() throws InterruptedException {
        // 异常恢复：第三方挂了 → 返回降级数据
        String result = CompletableFuture.supplyAsync(() -> callThirdParty(true), executor)
                .exceptionally(e -> {
                    System.out.println("捕获异常: " + e.getCause().getMessage());
                    return "降级数据";
                })
                .join();
        System.out.println("异常恢复结果: " + result);
        System.out.println("✅ exceptionally 异常恢复成功");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void testTimeoutFallback() throws InterruptedException {
        // 超时降级：慢接口 2 秒，等 1 秒就放弃
        CompletableFuture<String> slowF =
                CompletableFuture.supplyAsync(() -> slowCall(), executor);

        // 竞速：slowF vs 手动 1 秒超时
        CompletableFuture<String> timeoutF = new CompletableFuture<>();
        CompletableFuture<Object> race = CompletableFuture.anyOf(slowF, timeoutF);

        // 1 秒后手动完成 timeout（兜底）
        executor.execute(() -> {
            sleep(1000);
            timeoutF.complete("超时降级数据");
        });

        long start = System.currentTimeMillis();
        Object result = race.join();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("1 秒竞速结果: " + result + "（耗时 " + elapsed + "ms）");
        System.out.println("✅ 超时降级成功（不等慢接口的 2 秒）");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}

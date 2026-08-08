package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 2：thenCompose 依赖编排
 *
 * 场景：查订单 → 用订单的 userId 查用户 → 用用户的 level 算折扣
 */
public class ComposeChainTest {

    static class Order {
        String orderId;
        long userId;

        Order(String o, long u) {
            orderId = o;
            userId = u;
        }
    }

    static class User {
        String name;
        int level;

        User(String n, int l) {
            name = n;
            level = l;
        }
    }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> {
                Thread t = new Thread(r);
                t.setName("compose-worker");
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private Order queryOrder(String orderId) {
        sleep(200);
        return new Order(orderId, 1001L);
    }

    private User queryUser(long userId) {
        sleep(200);
        return new User("用户-" + userId, 3);
    }

    private double calcDiscount(User user) {
        sleep(100);
        return user.level >= 3 ? 0.8 : 0.9; // 高级会员 8 折
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void testDependentChain() throws InterruptedException {
        long start = System.currentTimeMillis();

        CompletableFuture<Double> discountF =
                CompletableFuture.supplyAsync(() -> queryOrder("O-2024-001"), executor) // ① 查订单
                .thenCompose(order ->                                                  // ② 依赖订单
                        CompletableFuture.supplyAsync(() -> queryUser(order.userId), executor))
                .thenCompose(user ->                                                  // ③ 依赖用户
                        CompletableFuture.supplyAsync(() -> calcDiscount(user), executor));

        // ④ 最终结果
        double discount = discountF.join();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("最终折扣: " + discount);
        System.out.println("总耗时: " + elapsed + "ms（串行 500ms，不是并行 200ms）");
        System.out.println("✅ thenCompose 依赖编排成功（前一步结果传给下一步）");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}

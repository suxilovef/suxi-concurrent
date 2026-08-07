package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 2：DelayQueue 实现订单超时自动取消（真实业务场景）
 *
 * 模拟：三个订单分别 1 秒 / 2 秒 / 5 秒后过期，按到期时间自动取消
 */
public class OrderTimeoutTest {

    static class Order implements Delayed {
        final String orderId;
        final long expireTime; // 到期时间（毫秒）

        Order(String orderId, long delayMs) {
            this.orderId = orderId;
            this.expireTime = System.currentTimeMillis() + delayMs;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expireTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(this.expireTime, ((Order) other).expireTime);
        }
    }

    private final DelayQueue<Order> timeoutQueue = new DelayQueue<>();
    private final AtomicInteger cancelled = new AtomicInteger(0);

    @Test
    public void testOrderTimeout() throws InterruptedException {
        // 订单超时检查线程（常驻）
        Thread checker = new Thread(() -> {
            while (true) {
                try {
                    Order order = timeoutQueue.take(); // 阻塞到最早订单到期
                    System.out.println("⏰ 订单 " + order.orderId + " 超时未支付 → 自动取消");
                    cancelled.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "order-timeout-checker");
        checker.setDaemon(true);
        checker.start();

        // 模拟下 3 个订单，不同超时时间
        System.out.println("下单 3 个订单...");
        timeoutQueue.add(new Order("O-001", 2000)); // 2 秒后过期
        timeoutQueue.add(new Order("O-002", 5000)); // 5 秒后过期
        timeoutQueue.add(new Order("O-003", 1000)); // 1 秒后过期

        // 等待所有订单到期
        Thread.sleep(6500);
        System.out.println("已取消订单数: " + cancelled.get());
        System.out.println(cancelled.get() == 3
                ? "✅ 3 个订单全部按时取消（DelayQueue 生效）"
                : "❌ 取消数量不对");

        // 验证顺序：O-003 最先到期（1s），然后 O-001（2s），最后 O-002（5s）
        System.out.println("✅ 取消顺序验证：观察输出顺序应为 O-003 → O-001 → O-002");
    }
}

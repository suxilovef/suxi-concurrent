package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * 业务演示：支付服务。
 *
 * <p>为什么它要独立成一个 bean、而不是塞进 {@link OrderService} 里：
 * Arthas 的 {@code trace} 要打出<b>跨 bean 的嵌套调用树</b>才有意义 ——
 * 全挤在一个类里，trace 只能显示一行，看不出"耗时花在哪个下游"。
 *
 * <p>为什么 {@code charge} 里有自己的锁：让 {@code trace} 的耗时树里
 * 除了纯 sleep 之外，还有一段"可能被锁竞争拉长"的真实抖动。
 */
@Service
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class PayService {

    private final ReentrantLock channelLock = new ReentrantLock();

    /**
     * @param failRatePercent 模拟支付渠道失败的概率（0~100）。
     *                        非 0 时 {@code monitor} 的 fail 计数才有料，
     *                        {@code watch ... -e} 也才看得到异常栈。
     */
    public PayResult charge(String orderId, BigDecimal amount, int failRatePercent) {
        channelLock.lock();
        try {
            // 模拟调用第三方支付渠道的网络耗时
            sleepQuietly(10 + ThreadLocalRandom.current().nextInt(21));

            if (failRatePercent > 0 && ThreadLocalRandom.current().nextInt(100) < failRatePercent) {
                throw new IllegalStateException("支付渠道拒绝：" + orderId);
            }
            return new PayResult(orderId, "PAID", amount);
        } finally {
            channelLock.unlock();
        }
    }

    /** 支付结果。 */
    public record PayResult(String orderId, String status, BigDecimal amount) {
    }
}

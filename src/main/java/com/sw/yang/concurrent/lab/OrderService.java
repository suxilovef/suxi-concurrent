package com.sw.yang.concurrent.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static com.sw.yang.concurrent.lab.LabSupport.sleepQuietly;

/**
 * 业务演示：下单服务（Arthas {@code watch} / {@code trace} / {@code monitor} 的落点）。
 *
 * <p>为什么长这样（每条都对应一个 Arthas 能力，不是随手写的"假业务"）：
 * <ul>
 *   <li><b>跨 bean 嵌套调用</b> → {@code trace ... createOrder} 打出有意义的耗时树：
 *       {@code validate → deductStock → PayService.charge → buildDto}；</li>
 *   <li><b>{@code slowMs} 参数可控</b> → 能在 trace 里人为拉长某一帧，
 *       演练"怎么从耗时树定位慢点"；</li>
 *   <li><b>入参含基本类型 + 对象</b> → {@code watch '{params, returnObj}'} 的输出有观感；</li>
 *   <li><b>可配置的失败率</b> → {@code monitor -c 5} 的 fail 计数非 0，
 *       {@code watch ... -e} 能看到异常栈。</li>
 * </ul>
 *
 * <p>注意 {@code synchronized (stockLock)} 里<b>调用了外部 bean 方法</b>
 * （{@code payService.charge}）。这是 docs/05-03 §6 坑 3 明确警告的反模式
 * —— "持锁时不要调用外部方法"。这里故意留着，可以在演练时用
 * {@code trace} 直观看到"锁被持有了多久"，进而理解为什么它危险。
 * 想演示锁竞争时，并发打这个接口即可。
 */
@Service
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class OrderService {

    private final PayService payService;
    private final AtomicLong seq = new AtomicLong();

    /** 库存锁。 */
    private final Object stockLock = new Object();

    public OrderService(PayService payService) {
        this.payService = payService;
    }

    /**
     * @param slowMs        人为拖慢扣库存的毫秒数（trace 用）
     * @param failRatePercent 支付失败概率（monitor 的 fail 计数用）
     */
    public OrderDto createOrder(String userId, long skuId, int qty,
                                long slowMs, int failRatePercent) {
        long start = System.nanoTime();

        validate(userId, qty);

        BigDecimal amount = BigDecimal.valueOf(qty).multiply(BigDecimal.valueOf(19.9));
        String orderId = "ORD-" + skuId + "-" + seq.incrementAndGet();

        String payStatus;
        synchronized (stockLock) {                      // ← 持锁期间调用了外部 bean
            deductStock(skuId, qty, slowMs);
            payStatus = payService.charge(orderId, amount, failRatePercent).status();
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return buildDto(orderId, userId, skuId, qty, amount, payStatus, elapsedMs);
    }

    private void validate(String userId, int qty) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (qty <= 0) {
            // 故意抛异常：让 monitor 的 fail 计数有内容
            throw new IllegalArgumentException("qty 必须 > 0，实际 " + qty);
        }
    }

    private void deductStock(long skuId, int qty, long slowMs) {
        sleepQuietly(20 + ThreadLocalRandom.current().nextInt(61) + Math.max(0, slowMs));
        if (skuId < 0) {
            throw new IllegalStateException("skuId 非法：" + skuId);
        }
    }

    private OrderDto buildDto(String orderId, String userId, long skuId, int qty,
                              BigDecimal amount, String payStatus, long elapsedMs) {
        return new OrderDto(orderId, userId, skuId, qty, amount, payStatus, elapsedMs);
    }
}

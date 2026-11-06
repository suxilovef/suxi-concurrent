package com.sw.yang.concurrent.lab;

import java.math.BigDecimal;

/**
 * 业务演示用的订单返回值（供 Arthas {@code watch} 观察 returnObj）。
 *
 * <p>刻意包含基本类型 + 对象类型混合字段，这样
 * {@code watch ... createOrder '{params, returnObj}'} 的输出既有教学价值又好看。
 */
public record OrderDto(String orderId,
                       String userId,
                       long skuId,
                       int qty,
                       BigDecimal totalAmount,
                       String payStatus,
                       long elapsedMs) {
}

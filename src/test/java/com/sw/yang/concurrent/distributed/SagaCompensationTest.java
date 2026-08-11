package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 练习 3：模拟 Saga 补偿 —— 理解"反向回滚"思想
 *
 * 场景：下单 → 扣库存 → 扣款 → 发货（4 步）
 *       第 3 步失败 → 反向补偿（撤销扣款 → 释放库存 → 取消订单）
 */
public class SagaCompensationTest {

    // 模拟各服务的"操作日志"（记录已执行步骤）
    private final List<String> executed = new ArrayList<>();
    private final List<String> compensated = new ArrayList<>();

    /**
     * 执行步骤（模拟），failAtStep 时第 N 步抛异常
     */
    private void executeSaga(int failAtStep) {
        try {
            step1_createOrder(); // ① 下单
            step2_deductStock(); // ② 扣库存
            if (failAtStep == 3) throw new RuntimeException("扣款失败");
            step3_deductMoney(); // ③ 扣款
            step4_ship();        // ④ 发货
            System.out.println("🎉 全流程成功");
        } catch (Exception e) {
            System.out.println("❌ 执行失败：" + e.getMessage() + " → 开始补偿");
            compensate(); // 反向补偿
        }
    }

    private void step1_createOrder() {
        executed.add("下单");
        System.out.println("✓ 下单成功");
    }

    private void step2_deductStock() {
        executed.add("扣库存");
        System.out.println("✓ 扣库存成功");
    }

    private void step3_deductMoney() {
        executed.add("扣款");
        System.out.println("✓ 扣款成功");
    }

    private void step4_ship() {
        executed.add("发货");
        System.out.println("✓ 发货成功");
    }

    /**
     * 反向补偿：逆序撤销已执行的步骤
     */
    private void compensate() {
        // 从后往前补偿
        for (int i = executed.size() - 1; i >= 0; i--) {
            String step = executed.get(i);
            switch (step) {
                case "扣库存":
                    compensated.add("释放库存");
                    System.out.println("↩ 补偿：释放库存");
                    break;
                case "下单":
                    compensated.add("取消订单");
                    System.out.println("↩ 补偿：取消订单");
                    break;
                case "扣款":
                    compensated.add("退回款项");
                    System.out.println("↩ 补偿：退回款项");
                    break;
                default:
                    // 发货后无需补偿（或记录售后）
            }
        }
        System.out.println("✅ 补偿完成，业务回到一致状态");
    }

    @Test
    public void testSagaSuccess() {
        System.out.println("=== 场景 1：全流程成功 ===");
        executeSaga(99); // 不失败
    }

    @Test
    public void testSagaCompensation() {
        System.out.println("=== 场景 2：第 3 步失败 → 反向补偿 ===");
        executeSaga(3); // 扣款失败
        System.out.println("\n执行过的步骤: " + executed);
        System.out.println("补偿执行的步骤: " + compensated);
        System.out.println("✅ Saga 反向补偿思想演示完成");
    }
}

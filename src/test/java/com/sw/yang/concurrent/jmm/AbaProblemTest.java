package com.sw.yang.concurrent.jmm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 练习 3：模拟 ABA 问题 + AtomicStampedReference 修复
 *
 * 场景简化版：
 * 1. 线程 1 读到一个值 A，准备 CAS 成 C，但被暂停
 * 2. 线程 2 把 A 改成 B，又改回 A
 * 3. 线程 1 恢复，CAS(A→C) —— 如果是普通 CAS 就成功了（ABA bug）
 *
 * AtomicStampedReference 用版本号避免了这个问题
 */
public class AbaProblemTest {

    @Test
    public void testAbaProblem() {
        // 使用 AtomicStampedReference，初始值 "A"，版本号 0
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 0);

        System.out.println("初始值: " + ref.getReference() + ", 版本: " + ref.getStamp());

        // 模拟线程 2：A → B → A（版本号从 0 → 1 → 2）
        int[] stampHolder = new int[1];
        String current = ref.get(stampHolder);
        int stamp = stampHolder[0];

        boolean ok1 = ref.compareAndSet(current, "B", stamp, stamp + 1);
        System.out.println("A→B: " + (ok1 ? "成功" : "失败") + ", 当前值: " +
                ref.getReference() + ", 版本: " + ref.getStamp());

        current = ref.get(stampHolder);
        stamp = stampHolder[0];
        boolean ok2 = ref.compareAndSet(current, "A", stamp, stamp + 1);
        System.out.println("B→A: " + (ok2 ? "成功" : "失败") + ", 当前值: " +
                ref.getReference() + ", 版本: " + ref.getStamp());

        // 模拟线程 1 用旧版本号 0 尝试 CAS → 失败！
        boolean ok3 = ref.compareAndSet("A", "C", 0, 1);
        System.out.println("用旧版本号 CAS A→C: " + (ok3 ? "成功" : "失败（ABA 被阻止！）"));
        System.out.println("最终值: " + ref.getReference() + ", 版本: " + ref.getStamp());

        // 正确做法：用当前版本号
        current = ref.get(stampHolder);
        stamp = stampHolder[0];
        boolean ok4 = ref.compareAndSet(current, "C", stamp, stamp + 1);
        System.out.println("用当前版本号 CAS A→C: " + (ok4 ? "成功" : "失败") +
                ", 最终值: " + ref.getReference() + ", 版本: " + ref.getStamp());
    }
}

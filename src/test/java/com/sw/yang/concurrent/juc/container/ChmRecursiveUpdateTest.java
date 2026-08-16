package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重构版（JDK 14+，主工程跑 JDK 17/21）行为验证——与 jdk8-lab/ChmVerifyTest 对照
 *
 * 对应文档 03-04：
 *   - 坑 2：get(null) 抛 NPE（重构版与经典版一致，无条件 key.hashCode()）
 *   - 坑 4：computeIfAbsent 递归行为随版本变化——JDK 8 死循环挂起；
 *       重构版 putVal 增加 RESERVED 检测 → 抛 IllegalStateException("Recursive update")
 *
 * JDK 8 侧对照见 jdk8-lab/ChmVerifyTest（get 返回 null / put 挂起）。
 */
public class ChmRecursiveUpdateTest {

    @Test
    public void get_null_throws_npe() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        assertThrows(NullPointerException.class, () -> map.get(null),
                "get(null) 抛 NPE：与经典版一致，无 HashMap 的 key==null 特判");
    }

    @Test
    public void computeIfAbsent_recursive_put_throws() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> map.computeIfAbsent("k", k -> map.put("k", "v")));
        System.out.println("重构版：mapping 函数内 put 同一 key → " + e);
    }

    @Test
    public void computeIfAbsent_recursive_get_behavior() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        String result = map.computeIfAbsent("k", k -> map.get("k"));
        System.out.println("重构版：mapping 函数内 get 同一 key → 返回 " + result);
        assertNull(result);
    }
}

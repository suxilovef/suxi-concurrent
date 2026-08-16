package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConcurrentHashMap 经典版（JDK 8）源码结论验证（对应文档 03-04）
 *
 * 用法：与文档对照逐个跑——每个方法验证文档里的一条结论：
 *   - get_null_throws_npe                    → §4.3 / 坑 2：get(null) 抛 NPE
 *       （文档曾误写"返回 null"；javap 证实 JDK 8 get 无条件 key.hashCode()）
 *   - all_public_methods_reject_null_key     → §4.3：所有公开方法对 null key/value 一律 NPE
 *       （HashMap 有 key==null 特判，CHM 没有）
 *   - hashmap_get_null_allowed               → 对照：HashMap.get(null) 不抛
 *   - computeIfAbsent_get_same_key_returns_null → 坑 4（JDK 8）：mapping 函数内 get 同一 key
 *       → 命中 ReservationNode（hash=-3）→ find 返回 null → 函数返回 null → 不存入、不异常
 *   - computeIfAbsent_put_same_key_hangs     → 坑 4（JDK 8）：mapping 函数内 put 同一 key
 *       → putVal 只识别 MOVED，遇到 RESERVED 无事发生 → 死循环挂起
 *   - sizeCtl_lifecycle                       → §2.3：0（未初始化）→ 正数阈值 n - n/4（初始化后）
 *   - resizeStamp_is_capacity_fingerprint     → §2.3 角色 4：高 16 位是"容量指纹"不是"扩容序列号"：
 *       同一容量 → 同一 stamp；不同容量 → 不同 stamp；stamp = numberOfLeadingZeros(n) | 0x8000
 *   - resize_sizeCtl_low16_base2              → §2.3 角色 3 / §6.6 收尾：
 *       扩容中 sc < 0，低 16 位 = 参与线程总数 + 1（基数 2，helper 加入后 > 2），
 *       高 16 位与容量指纹一致；扩容完成后 sc = 新容量 - 新容量/4
 *
 * ⚠️ "挂起"测试刻意用 daemon 线程 + 超时判定（非 daemon 会卡死构建，见仓库 CLAUDE.md）。
 */
public class ChmVerifyTest {

    // —— 坑 2 / §4.3：null 检查 ——

    @Test
    public void get_null_throws_npe() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        assertThrows(NullPointerException.class, () -> map.get(null),
                "get(null) 抛 NPE：内部无条件调用 key.hashCode()，没有 HashMap 的特判");
    }

    @Test
    public void all_public_methods_reject_null_key() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("k", "v");
        assertThrows(NullPointerException.class, () -> map.put(null, "v"));
        assertThrows(NullPointerException.class, () -> map.put("k", null));
        assertThrows(NullPointerException.class, () -> map.get(null));
        assertThrows(NullPointerException.class, () -> map.containsKey(null));
        assertThrows(NullPointerException.class, () -> map.remove(null));
        assertThrows(NullPointerException.class, () -> map.compute(null, (k, v) -> "x"));
        assertThrows(NullPointerException.class, () -> map.computeIfAbsent(null, k -> "x"));
        assertThrows(NullPointerException.class, () -> map.merge(null, "x", (a, b) -> a));
        assertThrows(NullPointerException.class, () -> map.merge("k", null, (a, b) -> a));
        System.out.println("✅ 所有公开方法对 null key/value 一律 NPE");
    }

    @Test
    public void hashmap_get_null_allowed() {
        HashMap<String, String> hm = new HashMap<>();
        hm.put(null, "null-key-ok");
        assertEquals("null-key-ok", hm.get(null),
                "HashMap.get(null) 不抛：有 (key == null) ? 0 : key.hashCode() 特判");
    }

    // —— 坑 4：computeIfAbsent 递归（JDK 8 行为）——

    @Test
    public void computeIfAbsent_get_same_key_returns_null() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        // mapping 函数内 get 同一 key：JDK 8 不抛异常、返回 null（ReservationNode.find → null）
        String result = map.computeIfAbsent("k", k -> map.get("k"));
        System.out.println("JDK 8：computeIfAbsent 内 get 同一 key → 返回 " + result);
        assertNull(result, "函数返回 null → 不存入、无异常（静默拿到错误语义）");
        assertNull(map.get("k"), "key 未被放入");
    }

    @Test
    public void computeIfAbsent_put_same_key_hangs() throws Exception {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean returned = new AtomicBoolean(false);

        Thread t = new Thread(() -> {
            try {
                map.computeIfAbsent("k", k -> map.put("k", "v"));  // 函数内 put 同一 key
                returned.set(true);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.setDaemon(true);   // 死循环不退出，非 daemon 会卡死构建
        t.start();
        t.join(2000);

        System.out.println("JDK 8：computeIfAbsent 内 put 同一 key → 2s 后线程存活=" + t.isAlive()
                + "，返回=" + returned.get() + "，异常=" + thrown.get());
        assertTrue(t.isAlive(), "JDK 8 预期死循环挂起（putVal 只识别 MOVED，遇到 RESERVED 无事发生）");
        assertFalse(returned.get());
        assertNull(thrown.get());
        // 该线程永久自旋，daemon 故不阻塞 JVM 退出
    }

    // —— §2.3 / §6.6：sizeCtl ——

    private static int readSizeCtl(ConcurrentHashMap<?, ?> map) throws Exception {
        Field f = ConcurrentHashMap.class.getDeclaredField("sizeCtl");
        f.setAccessible(true);
        return f.getInt(map);
    }

    private static Object[] readTable(ConcurrentHashMap<?, ?> map) throws Exception {
        Field f = ConcurrentHashMap.class.getDeclaredField("table");
        f.setAccessible(true);
        return (Object[]) f.get(map);
    }

    private static int resizeStamp(int n) throws Exception {
        Method m = ConcurrentHashMap.class.getDeclaredMethod("resizeStamp", int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, n);
    }

    @Test
    public void sizeCtl_lifecycle() throws Exception {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        assertEquals(0, readSizeCtl(map), "未初始化 → sizeCtl = 0");

        map.put("a", "1");   // 触发 initTable
        int n = readTable(map).length;
        assertEquals(n - (n >>> 2), readSizeCtl(map),
                "初始化后 → sizeCtl = n - n/4 = n * 0.75（扩容阈值）");
        System.out.println("初始容量 n=" + n + "，sizeCtl 阈值=" + readSizeCtl(map));
    }

    @Test
    public void resizeStamp_is_capacity_fingerprint() throws Exception {
        assertEquals(resizeStamp(16), resizeStamp(16),
                "同一容量 → 同一 stamp（不是递增的\"第几次扩容\"序号）");
        assertNotEquals(resizeStamp(16), resizeStamp(32), "不同容量 → 不同 stamp");
        for (int n : new int[]{16, 32, 1024, 1 << 20}) {
            assertEquals(Integer.numberOfLeadingZeros(n) | (1 << 15), resizeStamp(n),
                    "stamp = numberOfLeadingZeros(n) | 0x8000");
        }
        assertTrue((resizeStamp(16) & (1 << 15)) != 0,
                "bit15 置位 → 左移 16 位后成为符号位 → 扩容中 sizeCtl 恒为负");
        System.out.println("✅ resizeStamp 由容量推导（容量指纹），与\"第几次扩容\"无关");
    }

    @Test
    public void resize_sizeCtl_low16_base2() throws Exception {
        // 构造 2^20 容量：首 put 即 initTable(n=2^20)，阈值 = 786432
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(1 << 20);
        final int threshold = (1 << 20) - (1 << 20 >>> 2);

        // 预填充到阈值（≈0.8s），下一次 put 触发扩容 2^20 → 2^21
        for (int i = 0; i < threshold; i++) {
            map.put(i, i);
        }
        System.out.println("预填充 " + threshold + " 个，sizeCtl=" + readSizeCtl(map)
                + "，表长=" + readTable(map).length);

        // 预置采样线程：扩容窗口内抓 sc < 0 的首个样本 + 低 16 位最大值
        AtomicReference<Integer> captured = new AtomicReference<>();
        AtomicInteger capturedN = new AtomicInteger();
        AtomicInteger maxLow16 = new AtomicInteger(0);
        Thread sampler = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int sc;
                try {
                    sc = readSizeCtl(map);
                } catch (Exception e) {
                    return;
                }
                if (sc < 0) {
                    maxLow16.accumulateAndGet(sc & 0xffff, Math::max);
                    if (captured.get() == null) {
                        captured.set(sc);
                        try {
                            capturedN.set(readTable(map).length);
                        } catch (Exception ignored) {
                        }
                    }
                }
                Thread.yield();
            }
        });

        // 4 个 helper 持续 put：遇到 ForwardingNode 会 helpTransfer 加入扩容（sc + 1）
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger keyGen = new AtomicInteger(1 << 22);
        Thread[] helpers = new Thread[4];
        for (int h = 0; h < helpers.length; h++) {
            helpers[h] = new Thread(() -> {
                while (!stop.get()) {
                    map.put(keyGen.getAndIncrement(), 1);
                }
            });
            helpers[h].setDaemon(true);
            helpers[h].start();
        }

        sampler.start();
        map.put(threshold, 1);   // 触发扩容
        sampler.join(11_000);
        stop.set(true);

        // —— 扩容中的 sizeCtl 断言 ——
        assertNotNull(captured.get(), "应在扩容窗口内采到负 sizeCtl");
        int sc = captured.get();
        int low16 = sc & 0xffff;
        int high16 = sc >>> 16;
        int n = capturedN.get();
        System.out.println("扩容采样：sc=" + sc + "，低16位=" + low16
                + "，高16位=0x" + Integer.toHexString(high16)
                + "，表长 n=" + n + "，期望指纹=0x"
                + Integer.toHexString(Integer.numberOfLeadingZeros(n) | (1 << 15)));
        assertEquals(Integer.numberOfLeadingZeros(n) | (1 << 15), high16,
                "高 16 位 = 当前表容量的指纹");
        assertTrue(low16 >= 2, "低 16 位基数 2（只有发起者时 = 2）");
        System.out.println("采样窗口内低 16 位最大值 = " + maxLow16.get()
                + (maxLow16.get() >= 3 ? "（> 2 → helper 已加入扩容）" : "（= 2 → 未抓到 helper 加入，时序问题）"));

        // —— 扩容收尾断言（§6.6）：等扩容结束，sizeCtl 重置为新阈值 ——
        long deadline = System.currentTimeMillis() + 10_000;
        while (readSizeCtl(map) < 0 && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        int finalN = readTable(map).length;
        assertEquals(finalN - (finalN >>> 2), readSizeCtl(map),
                "扩容完成 → sizeCtl = 新容量 - 新容量/4（新阈值）");
        System.out.println("扩容完成：表长=" + finalN + "，sizeCtl 新阈值=" + readSizeCtl(map));
    }
}

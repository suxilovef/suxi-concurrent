package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 练习 3：高并发读写，观察 size() 的弱一致性
 */
public class ConcurrencyTest {

    @Test
    public void testConcurrentReadWrite() throws InterruptedException {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        final int total = 50000;

        // 写线程
        Thread writer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                map.put(i, i);
            }
            System.out.println("写入完成，共 " + total + " 个");
        }, "writer");

        // 读线程
        Thread reader = new Thread(() -> {
            long sum = 0;
            for (int i = 0; i < total; i++) {
                Integer v = map.get(i);
                if (v != null) sum += v; // 可能读到"稍旧"的数据（弱一致性）
            }
            System.out.println("读线程看到的总和: " + sum);
        }, "reader");

        writer.start();
        Thread.sleep(100); // 写一半时开始读
        reader.start();

        writer.join();
        reader.join();
        System.out.println("最终 size: " + map.size());
        System.out.println("✅ 高并发读写无异常（弱一致性是设计特性）");
    }
}

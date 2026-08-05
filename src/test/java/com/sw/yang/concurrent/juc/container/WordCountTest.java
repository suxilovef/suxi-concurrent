package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 练习 1：多线程统计单词出现次数（经典场景）
 *
 * 对比：
 * - 正确写法：merge() 原子操作
 * - 错误写法：containsKey + put 非原子（丢失更新）
 */
public class WordCountTest {

    private static final String[] WORDS =
            {"java", "concurrent", "hashmap", "java", "thread", "concurrent",
             "lock", "java", "aqs", "thread", "concurrent", "hashmap"};

    /**
     * ✅ 正确：merge 是原子操作（存在则 +1，不存在则 1）
     */
    @Test
    public void testCorrectWay() throws InterruptedException {
        ConcurrentHashMap<String, Integer> count = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(WORDS.length);

        for (String word : WORDS) {
            new Thread(() -> {
                count.merge(word, 1, Integer::sum); // 原子
                done.countDown();
            }, "t-" + word).start();
        }
        done.await();

        System.out.println("统计结果（正确写法）: " + count);
        System.out.println("java=" + count.get("java") + ", concurrent=" + count.get("concurrent"));
        System.out.println(count.get("java") == 3 ? "✅ 统计正确" : "❌ 统计错误");
    }

    /**
     * ❌ 错误：containsKey + put 之间有竞争窗口（丢失更新）
     */
    @Test
    public void testWrongWay() throws InterruptedException {
        ConcurrentHashMap<String, Integer> count = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(WORDS.length);

        for (String word : WORDS) {
            new Thread(() -> {
                if (count.containsKey(word)) {
                    count.put(word, count.get(word) + 1); // 丢失更新！
                } else {
                    count.put(word, 1);
                }
                done.countDown();
            }, "t-" + word).start();
        }
        done.await();

        System.out.println("统计结果（错误写法）: " + count);
        System.out.println("java=" + count.get("java") + "（预期 3，经常 < 3）");
        System.out.println(count.get("java") == 3 ? "（这次碰巧正确）" : "❌ 数据丢失（并发写覆盖）");
    }
}

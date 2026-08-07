package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：用 ArrayBlockingQueue 实现生产者-消费者（生产推荐写法）
 *
 * 对比 02-03 的 wait/notify 手写版本 → 代码量减少 80%
 */
public class BlockingQueueProducerConsumerTest {

    private final BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
    private final AtomicInteger produced = new AtomicInteger(0);
    private final AtomicInteger consumed = new AtomicInteger(0);
    private volatile boolean running = true;

    @Test
    public void testProducerConsumer() throws InterruptedException {
        Thread[] producers = new Thread[2];
        Thread[] consumers = new Thread[3];

        for (int i = 0; i < 2; i++) {
            producers[i] = new Thread(() -> {
                while (running) {
                    try {
                        queue.put(produced.incrementAndGet()); // 满则阻塞
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "producer-" + i);
            producers[i].start();
        }

        for (int i = 0; i < 3; i++) {
            consumers[i] = new Thread(() -> {
                while (running) {
                    try {
                        queue.take(); // 空则阻塞
                        consumed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "consumer-" + i);
            consumers[i].start();
        }

        Thread.sleep(2000);
        running = false;
        // 中断阻塞在 put/take 上的线程（interrupt 能打断阻塞）
        for (Thread t : producers) t.interrupt();
        for (Thread t : consumers) t.interrupt();
        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("生产: " + produced.get() + ", 消费: " + consumed.get());
        System.out.println("队列残留: " + queue.size());
        System.out.println("✅ BlockingQueue 生产者-消费者运行完成（代码量是 wait/notify 的 1/5）");
    }
}

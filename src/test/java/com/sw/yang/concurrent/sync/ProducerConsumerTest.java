package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：基于 wait/notifyAll 的生产者-消费者
 *
 * 目标：
 * 1. 2 个生产者 + 3 个消费者，缓冲区容量 10
 * 2. 验证数据不丢失、不重复
 * 3. 刻意改成 if 条件检查，观察 bug
 *
 * 修改建议（每个都验证一次）：
 * - Producer 的 while → if：多生产者时可能重复生产或错误
 * - Consumer 的 while → if：可能 NPE（poll 返回 null）
 * - notifyAll → notify：多消费者时可能死锁
 */
public class ProducerConsumerTest {

    private final Object lock = new Object();
    private final LinkedList<Integer> buffer = new LinkedList<>();
    private static final int CAPACITY = 10;

    private volatile boolean running = true;
    private final AtomicInteger produced = new AtomicInteger(0);
    private final AtomicInteger consumed = new AtomicInteger(0);

    class Producer implements Runnable {
        @Override
        public void run() {
            while (running) {
                synchronized (lock) {
                    // TODO: 改成 if (buffer.size() >= CAPACITY) 试试多生产者场景
                    while (buffer.size() >= CAPACITY) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    int item = produced.incrementAndGet();
                    buffer.add(item);
                    lock.notifyAll();  // TODO: 改成 notify() 试试多消费者场景
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    class Consumer implements Runnable {
        @Override
        public void run() {
            while (running) {
                synchronized (lock) {
                    // TODO: 改成 if (buffer.isEmpty()) 试试——可能 NPE 或数据不一致
                    while (buffer.isEmpty()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    int item = buffer.removeFirst();
                    consumed.incrementAndGet();
                    lock.notifyAll();
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Test
    public void testProducerConsumer() throws InterruptedException {
        Thread[] producers = new Thread[2];
        Thread[] consumers = new Thread[3];

        for (int i = 0; i < producers.length; i++) {
            producers[i] = new Thread(new Producer(), "producer-" + i);
            producers[i].start();
        }
        for (int i = 0; i < consumers.length; i++) {
            consumers[i] = new Thread(new Consumer(), "consumer-" + i);
            consumers[i].start();
        }

        Thread.sleep(2000); // 运行 2 秒
        running = false;

        // 唤醒所有可能阻塞的线程（优雅停止）
        synchronized (lock) {
            lock.notifyAll();
        }

        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("生产总数: " + produced.get());
        System.out.println("消费总数: " + consumed.get());
        System.out.println("缓冲区残留: " + buffer.size());
        System.out.println(produced.get() == consumed.get() + buffer.size()
                ? "✅ 数据一致（不丢失不重复）" : "❌ 数据不一致！");
    }
}

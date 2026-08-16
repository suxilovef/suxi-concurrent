package com.sw.yang.concurrent.juc.aqs;

import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 3：用 Lock + 双 Condition 实现有界阻塞队列（ArrayBlockingQueue 简化版）
 *
 * 核心：notEmpty 精确唤醒消费者，notFull 精确唤醒生产者
 */
public class MyBlockingQueueTest {

    static class MyBlockingQueue<E> {
        private final LinkedList<E> queue = new LinkedList<>();
        private final int capacity;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition(); // 消费者等待：队列非空
        private final Condition notFull = lock.newCondition();  // 生产者等待：队列不满

        public MyBlockingQueue(int capacity) {
            this.capacity = capacity;
        }

        public void put(E e) throws InterruptedException {
            lock.lock();
            try {
                while (queue.size() >= capacity) {
                    notFull.await(); // 队列满 → 生产者等待
                }
                queue.add(e);
                notEmpty.signal(); // 精确唤醒一个消费者！（not notifyAll）
            } finally {
                lock.unlock();
            }
        }

        public E take() throws InterruptedException {
            lock.lock();
            try {
                while (queue.isEmpty()) {
                    notEmpty.await(); // 队列空 → 消费者等待
                }
                E e = queue.removeFirst();
                notFull.signal(); // 精确唤醒一个生产者！
                return e;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }
    }

    @Test
    public void testBlockingQueue() throws InterruptedException {
        MyBlockingQueue<Integer> queue = new MyBlockingQueue<>(10);
        final int[] sum = {0};

        // 2 个生产者，各生产 100 个
        Thread[] producers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int id = i;
            producers[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        queue.put(id * 100 + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "producer-" + id);
            producers[i].start();
        }

        // 3 个消费者
        Thread[] consumers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            consumers[i] = new Thread(() -> {
                try {
                    while (true) {
                        Integer v = queue.take();
                        synchronized (sum) {
                            sum[0] += v;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-" + i);
            consumers[i].start();
        }

        for (Thread t : producers) t.join();

        // 等消费者消费完
        Thread.sleep(1000);
        System.out.println("队列残留: " + queue.size());

        // 理论总数：producer0 产出 0..99，producer1 产出 100..199
        int expected = 0;
        for (int i = 0; i < 100; i++) expected += i + (i + 100);
        System.out.println("预期总和: " + expected + ", 实际消费总和: " + sum[0]);
        System.out.println(sum[0] == expected ? "✅ 双 Condition 精确唤醒正确" : "❌ 数据不一致");
    }
}

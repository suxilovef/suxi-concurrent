package com.sw.yang.concurrent.juc.atomic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 练习 3：用 AtomicStampedReference 实现无 ABA 的无锁栈
 *
 * 对比 03-06 §3.2 的 AtomicReference 版本：
 * 每次 push/pop 都递增版本号 → CAS 时同时校验值 + 版本号
 */
public class LockFreeStackTest {

    static class Stack<T> {
        private static class Node<T> {
            final T value;
            Node<T> next;

            Node(T value) {
                this.value = value;
            }
        }

        // 值 + 版本号（stamp）一起 CAS
        private final AtomicStampedReference<Node<T>> head =
                new AtomicStampedReference<>(null, 0);

        public void push(T value) {
            Node<T> newNode = new Node<>(value);
            while (true) {
                Node<T> oldHead = head.getReference();
                int stamp = head.getStamp();
                newNode.next = oldHead;
                if (head.compareAndSet(oldHead, newNode, stamp, stamp + 1)) {
                    return;
                }
            }
        }

        public T pop() {
            while (true) {
                Node<T> oldHead = head.getReference();
                int stamp = head.getStamp();
                if (oldHead == null) return null;
                if (head.compareAndSet(oldHead, oldHead.next, stamp, stamp + 1)) {
                    return oldHead.value;
                }
            }
        }
    }

    @Test
    public void testStack() throws InterruptedException {
        Stack<Integer> stack = new Stack<>();

        // 两个生产者 + 两个消费者
        Thread[] producers = new Thread[2];
        Thread[] consumers = new Thread[2];

        for (int i = 0; i < 2; i++) {
            producers[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) stack.push(j);
            }, "producer-" + i);
            consumers[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) stack.pop();
            }, "consumer-" + i);
        }

        for (Thread t : producers) t.start();
        for (Thread t : consumers) t.start();
        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("✅ AtomicStampedReference 无锁栈运行完成（ABA 已防御）");
    }
}

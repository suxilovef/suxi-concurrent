package com.sw.yang.concurrent.juc.container;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 3：CopyOnWriteArrayList 实现线程安全的监听器列表
 *
 * 场景：事件总线，多个线程注册/移除监听器，事件发布时遍历所有监听器
 * 关键：遍历期间注册/移除不抛 ConcurrentModificationException
 */
public class ListenerTest {

    interface Listener {
        void onEvent(String event);
    }

    static class EventBus {
        // 监听器列表：读多写少（注册/移除少，事件分发多）
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private final AtomicInteger delivered = new AtomicInteger(0);

        public void register(Listener listener) {
            listeners.add(listener);
        }

        public void unregister(Listener listener) {
            listeners.remove(listener);
        }

        public void publish(String event) {
            for (Listener l : listeners) { // 遍历时安全（快照迭代器）
                l.onEvent(event);
                delivered.incrementAndGet();
            }
        }
    }

    @Test
    public void testEventBus() throws InterruptedException {
        EventBus bus = new EventBus();

        // 注册 5 个监听器
        for (int i = 0; i < 5; i++) {
            final int id = i;
            bus.register(event -> System.out.println("Listener-" + id + " 收到: " + event));
        }

        // 一个线程反复发布事件，另一个线程同时注册/移除监听器
        Thread publisher = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                bus.publish("event-" + i);
            }
        }, "publisher");

        Thread modifier = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                bus.register(event -> { /* 动态注册 */ });
                if (i % 2 == 0) {
                    bus.unregister(bus.listeners.get(0)); // 动态移除
                }
            }
        }, "modifier");

        publisher.start();
        modifier.start();
        publisher.join();
        modifier.join();

        System.out.println("✅ 发布/注册/移除并发执行无异常（CopyOnWriteArrayList 快照迭代器）");
    }
}

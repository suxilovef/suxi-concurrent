package com.sw.yang.concurrent.juc.locks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LockSupport permit（许可）机制配套实验（对应文档 02-04 §2/§4/§6）
 *
 * 用法：与文档对照逐个跑——每个方法验证文档里的一条结论：
 *   - permit_saved_before_park            → §2.2 推演②：unpark 可提前，park 直接消费返回（不丢唤醒）
 *   - permit_not_accumulated              → §2.1 推论 1：许可不累积（0/1 槽，不是计数信号量）
 *   - park_then_unpark                    → §2.2 推演①：阻塞中的线程被指定线程唤醒（WAITING → 返回）
 *   - unpark_targets_specific_thread      → §2.1 推论 3：unpark 唤醒的是"人"不是"队列"
 *   - interrupt_returns_but_keeps_flag    → §4：park 被打断 → 返回不抛异常、中断标志保留
 *   - interrupt_before_park_returns_immediately → §4 补充：中断标志先置位，park 也立即返回
 *   - clearing_flag_restores_park         → §4 因果链基石：interrupted() 消费标志后 park 恢复正常阻塞
 *   - parkNanos_timeout_returns           → §6：无人唤醒时 parkNanos 睡满超时自然返回
 *
 * 前置知识：park 返回的三条路——permit 可用 / 被 interrupt / 伪唤醒（§5）。
 * 本类刻意不对"伪唤醒"做断言（无法确定性构造），它在文档里由 while 循环防御（§5/坑 1）。
 *
 * ⚠️ JDK 8 Windows 平台差异（本类多次探针实证，见 git 记录）：
 *   1. interrupt() 会给线程留下"幽灵信号"（os::interrupt → parker()->unpark() 的
 *      SetEvent 残留）。带标志的 park() 会消费它；但 Thread.interrupted() 只清标志、
 *      不碰事件——若 interrupt 与 park 之间没有 park() 垫底，清除标志后
 *      下一个 park 仍会立即返回一次。
 *   2. 关键坑：park() 消费幽灵之后，若在"interrupt 相关时序敏感段"内发生
 *      任何控制台输出（System.out / System.err 实际写入，文件写不会！），
 *      会复活幽灵信号，导致后续 parkNanos 提前返回。
 *   因此本类所有时序敏感段（interrupt → park → interrupted → parkNanos）
 *   之间不穿插任何 I/O，先测量后打印；interrupt 相关测试结束后主动 drain
 *   （一次 parkNanos(1)）避免污染后续测试。
 */
public class ParkPermitTest {

    /** 推演②：先 unpark 后 park —— 信号提前保存，park 直接消费返回（不丢唤醒的第一性原理） */
    @Test
    public void permit_saved_before_park() {
        // 单线程即可验证：本线程先拿 permit，再 park 立即消费返回
        LockSupport.unpark(Thread.currentThread());
        long t0 = System.nanoTime();
        LockSupport.park();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("【1】unpark 后立即 park：返回耗时 " + elapsedMs + "ms（expect ~0，未阻塞）");
        assertTrue(elapsedMs < 500, "先 unpark 后 park 应立即返回，却阻塞了 " + elapsedMs + "ms");
    }

    /** 推论 1：许可不累积 —— 两次 unpark 只顶一次 park，第二次 park 必须真正阻塞 */
    @Test
    public void permit_not_accumulated() {
        LockSupport.unpark(Thread.currentThread());
        LockSupport.unpark(Thread.currentThread());
        LockSupport.park();                                  // 消费唯一一份 permit，立即返回

        long t0 = System.nanoTime();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));  // 没有 permit 了 → 睡满超时
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("【1】两次 unpark + 一次 park 后，parkNanos(300ms) 实际阻塞 "
                + elapsedMs + "ms（expect ≈300，说明 permit 没累积）");
        assertTrue(elapsedMs >= 200, "permit 应已耗尽，parkNanos 却提前返回：" + elapsedMs + "ms");
    }

    /** 推演①：先 park 后 unpark —— 阻塞中的线程被指定线程唤醒 */
    @Test
    public void park_then_unpark() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            parked.countDown();                              // 通知主线程：我即将 park
            LockSupport.park();
            System.out.println("T: park 返回（被主线程 unpark 唤醒）");
        }, "T");
        t.start();

        assertTrue(parked.await(5, TimeUnit.SECONDS), "T 未就位");
        waitUntil(Thread.State.WAITING, t);                  // 确认 T 已真正进入 park 阻塞
        System.out.println("主线程: T 状态（应为 WAITING）: " + t.getState());

        LockSupport.unpark(t);                               // 唤醒"人"
        t.join(5000);
        System.out.println("主线程: T 已结束: " + t.getState());
        assertFalse(t.isAlive(), "unpark 后 T 应被唤醒并退出");
    }

    /** 推论 3：unpark 唤醒的是"人"不是"队列" —— 只唤醒指定线程，其他阻塞线程不受影响 */
    @Test
    public void unpark_targets_specific_thread() throws InterruptedException {
        CountDownLatch bothParked = new CountDownLatch(2);
        AtomicBoolean aReturned = new AtomicBoolean(false);
        AtomicBoolean bReturned = new AtomicBoolean(false);

        Thread a = new Thread(() -> { bothParked.countDown(); LockSupport.park(); aReturned.set(true); }, "A");
        Thread b = new Thread(() -> { bothParked.countDown(); LockSupport.park(); bReturned.set(true); }, "B");
        a.start();
        b.start();

        assertTrue(bothParked.await(5, TimeUnit.SECONDS), "A/B 未就位");
        waitUntil(Thread.State.WAITING, a);
        waitUntil(Thread.State.WAITING, b);

        LockSupport.unpark(a);                               // 只唤醒 A
        a.join(5000);
        System.out.println("【1】只 unpark(A)：A 返回 = " + aReturned.get() + "，B 返回 = " + bReturned.get());
        assertTrue(aReturned.get(), "A 应被唤醒");
        assertFalse(bReturned.get(), "B 不应受影响（unpark 唤醒的是指定线程）");

        LockSupport.unpark(b);                               // 收尾：唤醒 B，避免遗留阻塞线程
        b.join(5000);
        assertTrue(bReturned.get(), "B 应被第二次 unpark 唤醒");
    }

    /** §4：park 中的线程被 interrupt → 立即返回、不抛异常、中断标志保留（与 wait 的关键差异） */
    @Test
    public void interrupt_returns_but_keeps_flag() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean(false);
        AtomicBoolean flagKept = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            parked.countDown();
            LockSupport.park();                              // 阻塞中被打断 → 返回（不抛异常）
            returned.set(true);
            flagKept.set(Thread.currentThread().isInterrupted());  // 标志应保留
        }, "T");
        t.start();

        assertTrue(parked.await(5, TimeUnit.SECONDS), "T 未就位");
        waitUntil(Thread.State.WAITING, t);

        t.interrupt();
        t.join(5000);
        System.out.println("【1】interrupt 后 park 返回 = " + returned.get()
                + "，中断标志保留 = " + flagKept.get());
        assertTrue(returned.get(), "park 应因中断立即返回");
        assertTrue(flagKept.get(), "park 不抛异常且中断标志应保留");
    }

    /** §4 补充：中断标志先于 park 置位 → park 同样立即返回（中断是第三条"唤醒信号"） */
    @Test
    public void interrupt_before_park_returns_immediately() {
        Thread.currentThread().interrupt();                  // 先置标志
        long t0 = System.nanoTime();
        LockSupport.park();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        boolean flagKept = Thread.currentThread().isInterrupted();
        Thread.interrupted();                                // 清理标志
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));  // drain 幽灵信号（见类 javadoc）

        System.out.println("【1】带中断标志 park：返回耗时 " + elapsedMs + "ms，标志 = " + flagKept);
        assertTrue(elapsedMs < 500, "带中断标志的 park 应立即返回：" + elapsedMs + "ms");
        assertTrue(flagKept, "park 不消费中断标志");
    }

    /** §4 因果链基石：interrupted() 消费标志后 park 恢复正常阻塞 —— 不清标志才会空转 */
    @Test
    public void clearing_flag_restores_park() {
        // ⚠️ 时序敏感段（interrupt→park→interrupted→parkNanos）内零 I/O：
        // 任何控制台输出都会复活 interrupt 遗留的幽灵信号（见类 javadoc）。
        // 先测量，后打印。
        Thread.currentThread().interrupt();
        LockSupport.park();                                  // 带标志 → 立即返回（空转源头）
        boolean flag = Thread.interrupted();                 // 消费标志（等价于 parkAndCheckInterrupt）

        long t0 = System.nanoTime();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("【1】带标志 park 立即返回");
        System.out.println("【2】interrupted() 消费标志 = " + flag
                + "，之后 isInterrupted = " + Thread.currentThread().isInterrupted());
        System.out.println("【3】标志清除后 parkNanos(300ms) 实际阻塞 " + elapsedMs
                + "ms（expect ≈300，恢复正常阻塞）");
        assertTrue(flag);
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(elapsedMs >= 200, "标志清除后应正常阻塞，却提前返回：" + elapsedMs + "ms");
    }

    /** §6：parkNanos 无人唤醒 → 睡满超时自然返回（park 不是 sleep，unpark 可提前唤醒它） */
    @Test
    public void parkNanos_timeout_returns() {
        // 先消费前置测试可能遗留的 interrupt 幽灵信号（JDK 8 Windows，见类 javadoc），
        // 保证本测试从"事件干净"状态开始——有幽灵则立即返回并消费，无则 1ms 超时。
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));

        long t0 = System.nanoTime();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("【1】parkNanos(300ms) 无人唤醒，自然返回耗时 " + elapsedMs + "ms");
        assertTrue(elapsedMs >= 200, "parkNanos 应至少睡满近似时长：" + elapsedMs + "ms");
        assertFalse(Thread.currentThread().isInterrupted(), "超时返回与中断无关");
    }

    /** 轮询等待线程进入指定状态（比固定 sleep 更稳，避免慢机器上的脆弱性） */
    private void waitUntil(Thread.State state, Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != state && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(state, t.getState(), "线程未在 5s 内进入 " + state);
    }
}

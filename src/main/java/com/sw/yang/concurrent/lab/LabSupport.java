package com.sw.yang.concurrent.lab;

/**
 * lab 包内部的小工具，不注册为 bean。
 */
final class LabSupport {

    private LabSupport() {
    }

    /** sleep 但把中断转回标志位（不吞掉中断语义）。 */
    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 造一个 lab 故障线程。
     *
     * <p>为什么是<b>非 daemon</b>：生产里死锁/空转的就是这种业务线程，用 daemon
     * 反而失真。注意 daemon 与否<b>不影响</b> jstack 的报告内容（报告只看线程状态与
     * 栈），所以这里纯粹是为了观感保真。
     */
    static Thread newThread(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(false);
        return t;
    }

    /** 取 CPU 核数兜底值，避免 maxThreads 配置成 0 或负数。 */
    static int clampAtLeastOne(int value) {
        return Math.max(1, value);
    }
}

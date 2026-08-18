package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 演示 execute() 双重检查里的兜底分支：
 *
 *   else if (workerCountOf(recheck) == 0)
 *       addWorker(null, false);   // 没有线程了 → 补一个空线程去队列取任务
 *
 * 配置 core=0：池里一个常驻线程都不养，所有线程都是"临时线程"，
 * 空闲 keepAliveTime 后全部退出 → 池会周期性回到"0 线程"状态。
 * 此时任务入队成功，只能靠这一行补线程来取。
 */
public class CoreZeroFallbackTest {

    @Test
    public void testFallbackWhenWorkerCountZero() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, 5, 500, TimeUnit.MILLISECONDS,   // keepAlive 500ms，方便观察线程回收
                new LinkedBlockingQueue<>(3),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("temp-worker-" + t.getId());
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        // ── 第一波：提交任务 A（此刻池里 0 线程）──
        System.out.println("① 提交 A 前：poolSize=" + pool.getPoolSize() + " ← 池里一个线程都没有");
        pool.execute(() -> System.out.println("    A 入队成功 → recheck 发现 wc==0 → 触发 addWorker(null,false) 补线程"));
        pool.execute(() -> System.out.println("    任务由线程 [" + Thread.currentThread().getName()
                + "] 执行，此刻 poolSize=" + pool.getPoolSize()));
        Thread.sleep(150);
        System.out.println("   A 执行完，线程开始 poll(500ms) 空闲等待……");

        // ── 等临时线程空闲超时退出：池回到 0 线程 ──
        Thread.sleep(1000);
        System.out.println("② 空闲超时后：poolSize=" + pool.getPoolSize() + " ← 临时线程已退出，池又空了");

        // ── 第二波：提交任务 B（再次 0 线程）──
        System.out.println("③ 提交 B 前：poolSize=" + pool.getPoolSize() + " ← 和 A 来时一模一样");
        pool.execute(() -> System.out.println("    B 入队成功 → recheck 发现 wc==0 → 又要补线程！"));
        pool.execute(() -> System.out.println("    任务由线程 [" + Thread.currentThread().getName()
                + "] 执行，此刻 poolSize=" + pool.getPoolSize()));
        Thread.sleep(150);

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("✅ 结束：poolSize=" + pool.getPoolSize());
    }
}

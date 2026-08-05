package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;

/**
 * 练习 2：验证 wait() 会触发锁升级为重量级锁
 *
 * 原理：wait() 需要 ObjectMonitor 的 _WaitSet 结构，
 *       所以调用 wait 时锁会强制膨胀为重量级锁
 */
public class WaitUpgradeTest {

    @Test
    public void testWaitUpgradesLock() throws InterruptedException {
        Object obj = new Object();

        Thread t = new Thread(() -> {
            synchronized (obj) {
                System.out.println("=== 获取锁后（wait 之前）===");
                System.out.println(ClassLayout.parseInstance(obj).toPrintable());
                try {
                    obj.wait(100); // wait 需要 _WaitSet → 强制膨胀为重量级锁
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        t.start();
        t.join();
    }
}

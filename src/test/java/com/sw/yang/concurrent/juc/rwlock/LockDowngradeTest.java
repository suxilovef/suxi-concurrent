package com.sw.yang.concurrent.juc.rwlock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 练习 2：验证写锁降级为读锁的正确姿势
 *
 * 正确顺序：写锁 → 获取读锁 → 释放写锁（保持连续性）
 * 错误顺序：写锁 → 释放写锁 → 获取读锁（中间有竞争窗口）
 */
public class LockDowngradeTest {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private volatile String value = "init";

    @Test
    public void testDowngrade() {
        writeLock.lock();
        try {
            value = "updated";
            System.out.println("写锁中更新 value = " + value);

            // 降级：先获取读锁
            readLock.lock();
            System.out.println("获取读锁成功（写锁降级）");
        } finally {
            writeLock.unlock(); // 释放写锁，此时只持有读锁
        }

        try {
            // 现在只持有读锁
            System.out.println("读锁中读取 value = " + value);
        } finally {
            readLock.unlock();
        }
        System.out.println("✅ 写锁降级成功");
    }

    /**
     * 演示读锁无法升级为写锁（会死锁，所以不真正调用）
     */
    @Test
    public void testUpgradeNotAllowed() {
        readLock.lock();
        try {
            System.out.println("持有读锁，尝试升级为写锁...");
            // 如果在这里调 writeLock.lock() → 永远阻塞（死锁）
            // 所以注释掉，避免测试卡住
            System.out.println("读锁升级写锁不受支持（会死锁，已跳过演示）");
        } finally {
            readLock.unlock();
        }
    }
}

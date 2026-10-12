package com.sw.yang.concurrent.myself.aqs;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-09-14
 * @Description:
 * @Version: 1.0
 */
public class TestAqs {
}

class MyLock implements Lock {

    private MySync sync =  new MySync();

    class MySync extends AbstractQueuedSynchronizer {

    }
    // 加锁（不成功会进入等待队列）
    @Override
    public void lock() {

    }
    // 加锁 可打断
    @Override
    public void lockInterruptibly() throws InterruptedException {

    }
    // 尝试加锁（一次）
    @Override
    public boolean tryLock() {
        return false;
    }
    // 尝试加锁，带超时
    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return false;
    }

    // 解锁
    @Override
    public void unlock() {

    }
    // 创建条件变量
    @Override
    public Condition newCondition() {
        return null;
    }
}

package com.sw.yang.concurrent.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 练习 2：三种计数方案压测（AtomicLong / synchronized / ReentrantLock）
 *
 * 分别用 threads=1、8、32 跑三遍，观察竞争强度的影响
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class LockBenchmark {

    private AtomicLong atomicCount = new AtomicLong(0);
    private long syncCount = 0;
    private long lockCount = 0;
    private final ReentrantLock lock = new ReentrantLock();

    @Benchmark
    public void atomicInc(Blackhole bh) {
        bh.consume(atomicCount.incrementAndGet());
    }

    @Benchmark
    public synchronized void syncInc(Blackhole bh) {
        bh.consume(++syncCount);
    }

    @Benchmark
    public void lockInc(Blackhole bh) {
        lock.lock();
        try {
            bh.consume(++lockCount);
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(LockBenchmark.class.getSimpleName())
                .threads(8) // 修改这里：1 / 8 / 32
                .result("lock-result-threads8.json")
                .build();
        new Runner(opt).run();
    }
}

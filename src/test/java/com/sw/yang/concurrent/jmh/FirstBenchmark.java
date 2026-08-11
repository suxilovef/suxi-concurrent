package com.sw.yang.concurrent.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 练习 1：第一个 JMH 基准测试
 *
 * 目标：
 * 1. 跑通 JMH 环境
 * 2. 对比"有 Blackhole"和"没有 Blackhole"的结果差异
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class FirstBenchmark {

    private AtomicLong atomic = new AtomicLong(0);

    // ✅ 正确：结果被 Blackhole 消费
    @Benchmark
    public void atomicIncrement(Blackhole bh) {
        bh.consume(atomic.incrementAndGet());
    }

    // ❌ 错误示范：结果没人用 → JIT 可能把计算消除
    @Benchmark
    public void atomicIncrementNoConsume() {
        atomic.incrementAndGet(); // 返回值被丢弃！
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(FirstBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}

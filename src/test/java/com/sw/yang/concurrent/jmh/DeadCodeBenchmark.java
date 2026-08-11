package com.sw.yang.concurrent.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * 练习 3：亲眼看到死代码消除的效果
 *
 * 关键：两个方法都必须是 void！
 *   - 返回值方法会被 JMH 自动消费（不会消除）
 *   - void + 无 consume：sum 计算结果被 JIT 删除 → 吞吐量"巨大"
 *   - void + bh.consume：真实计算 → 吞吐量明显下降
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class DeadCodeBenchmark {

    @Benchmark
    public void noBlackhole() {
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i;
        }
        // sum 未被使用 → JIT 可能把整个循环删掉（死代码消除）
    }

    @Benchmark
    public void withBlackhole(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i;
        }
        bh.consume(sum); // 强制使用结果 → 计算无法被消除
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(DeadCodeBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
        System.out.println("对比两个方法的吞吐量差异（noBlackhole 通常虚高）");
    }
}

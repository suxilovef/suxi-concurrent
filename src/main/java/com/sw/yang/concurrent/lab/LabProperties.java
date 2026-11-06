package com.sw.yang.concurrent.lab;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 故障注入实验室的配置（对应 docs/05-05）。
 *
 * <p>为什么默认 {@code lab.enabled=false}：这些接口能"打死"服务，且
 * {@link com.sw.yang.concurrent.ConcurrentApplicationTests} 是裸 {@code @SpringBootTest}，
 * 默认关才能保证默认上下文里没有 lab bean —— 既安全，也不污染主工程。
 *
 * <p>为什么给 lab 包内的类都挂 {@code @ConditionalOnProperty} 而不是给本类挂：
 * 本类需要"永远存在且永远能绑定"，条件判断本身要读 {@code lab.enabled}，
 * 若本类也被条件化就形成鸡生蛋问题。本类只是属性容器，不注册任何端点，无风险。
 */
@Component
@ConfigurationProperties(prefix = "lab")
public class LabProperties {

    /** 总开关，默认关。启动时用 {@code --lab.enabled=true} 打开。 */
    private boolean enabled = false;

    private final Detector detector = new Detector();
    private final Cpu cpu = new Cpu();
    private final Blocked blocked = new Blocked();
    private final Leak leak = new Leak();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Detector getDetector() {
        return detector;
    }

    public Cpu getCpu() {
        return cpu;
    }

    public Blocked getBlocked() {
        return blocked;
    }

    public Leak getLeak() {
        return leak;
    }

    /** 内置 ThreadMXBean 死锁检测（docs/05-03 §4）。 */
    public static class Detector {
        /** 是否启用定时扫描。 */
        private boolean enabled = true;
        /** 扫描间隔（秒）。 */
        private int intervalSeconds = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }
    }

    /** CPU 空转场景的阈值。 */
    public static class Cpu {
        /**
         * 空转线程数上限。服务器只有 4 核，不设上限的话手滑传个 100
         * 会把整台机器打死，连 attach 排查的机会都没有。
         */
        private int maxThreads = 4;

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }
    }

    /** BLOCKED 堆积场景的阈值。 */
    public static class Blocked {
        /** 等待线程数上限。 */
        private int maxWaiters = 32;

        public int getMaxWaiters() {
            return maxWaiters;
        }

        public void setMaxWaiters(int maxWaiters) {
            this.maxWaiters = maxWaiters;
        }
    }

    /** 内存泄漏场景的阈值。 */
    public static class Leak {
        /**
         * 累计占用上限（MB）。到顶就停止新增，避免真的把 JVM 撑到 OOM
         * 之后连 /lab/status 都调不动、没法观察。
         */
        private int maxMb = 512;

        public int getMaxMb() {
            return maxMb;
        }

        public void setMaxMb(int maxMb) {
            this.maxMb = maxMb;
        }
    }
}

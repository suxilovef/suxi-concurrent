package com.sw.yang.concurrent.lab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 启动横幅：lab 开启时把"我现在很危险"喊出来。
 *
 * <p>为什么值得单独一个类：这些接口能真的把服务搞死，而"忘了关"
 * 是这类演练最主要的风险。启动日志是最低成本的提醒位置。
 */
@Component
@ConditionalOnProperty(name = "lab.enabled", havingValue = "true")
public class LabStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LabStartupLogger.class);

    private final FaultRegistry registry;

    public LabStartupLogger(FaultRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        long pid = ProcessHandle.current().pid();
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        log.warn("================================================================");
        log.warn("  ⚠️  LAB MODE：故障注入接口已激活（/lab/**）");
        log.warn("  这些接口能注入死锁 / CPU 飙高 / 内存泄漏，仅限内网演练使用！");
        log.warn("  演练结束后请停掉进程，或去掉 --lab.enabled=true 重启。");
        log.warn("  --------------------------------------------------------------");
        log.warn("  pid                        = {}", pid);
        log.warn("  availableProcessors        = {}", Runtime.getRuntime().availableProcessors());
        log.warn("  maxHeap                    = {} MB",
                Runtime.getRuntime().maxMemory() / 1024 / 1024);
        log.warn("  jvmArgs                    = {}", jvmArgs.isEmpty() ? "(无显式参数)" : jvmArgs);
        log.warn("  当前活动故障               = {}", registry.snapshot().size());
        log.warn("================================================================");
    }
}

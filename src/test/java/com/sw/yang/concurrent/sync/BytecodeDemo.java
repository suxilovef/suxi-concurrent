package com.sw.yang.concurrent.sync;

/**
 * 练习 3：javap 字节码分析目标类
 *
 * 使用步骤（终端执行）：
 * 1. 编译：javac BytecodeDemo.java
 * 2. 反编译：javap -v -p BytecodeDemo.class
 * 3. 观察 monitorenter / monitorexit 指令：
 *    - 正常路径的 monitorexit（第 13 行附近）
 *    - 异常路径的 monitorexit（第 19 行附近，编译器自动生成）
 */
public class BytecodeDemo {

    public void demo() {
        synchronized (this) {
            System.out.println("hello");
        }
    }

    public synchronized void syncMethod() {
        System.out.println("synchronized method");
    }
}

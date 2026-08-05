# 02-01 Synchronized 使用与字节码

> **阶段二·第 1 篇** | 前置：[01-03-CAS原理与Unsafe入门](./01-03-CAS原理与Unsafe入门.md) | 后续：[02-02-对象头与锁升级全链路](./02-02-对象头与锁升级全链路.md)（待发布）  
> **建议时长**：4~5 小时（使用 1h + 字节码 1.5h + 可重入/锁对比 1h + 练习 1.5h）  
> 🛠️ **日常高频**：synchronized 是每天都要写的同步关键字，但大多数人只会"锁方法"不知道为什么

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | 三种加锁形态、锁的对象分别是谁、monitorenter/monitorexit 字节码、可重入性、Synchronized vs Lock 对比 | **深入理解 + 能手写示例 + 面试能讲清** |
| ◈◈ | 异常自动释放锁的编译机制、javap 实操 | **知道原理 + 会用 javap 验证** |
| ○ | 对象头细节（下篇详讲）、JIT 锁优化（阶段二后篇） | **知道有，下一篇深入** |

---

## 1. Synchronized 三种加锁形态

> 核心问题：**synchronized 锁的到底是什么？** —— 答案是：**一个 Java 对象**。

### 1.1 三种形态

```java
public class SynchronizedUsage {

    // ── 形态 1：修饰实例方法 → 锁的是当前实例对象（this）──
    public synchronized void instanceMethod() {
        // 锁 = this（调用这个方法的实例）
    }

    // ── 形态 2：修饰静态方法 → 锁的是 Class 对象 ──
    public static synchronized void staticMethod() {
        // 锁 = SynchronizedUsage.class（类对象，全局唯一）
    }

    // ── 形态 3：修饰代码块 → 锁的是指定对象 ──
    private final Object lock = new Object();

    public void blockMethod() {
        synchronized (lock) {
            // 锁 = lock 对象（自己指定，最灵活）
        }
    }
}
```

### 1.2 三种形态锁的对象总结

| 形态 | 锁的对象 | 关键特征 |
|---|---|---|
| 修饰实例方法 | `this`（当前实例） | 每个实例有自己的锁，不同实例互不影响 |
| 修饰静态方法 | `Class` 对象（如 `Foo.class`） | 全局唯一，所有实例共享同一把锁 |
| 修饰代码块 | 括号中指定的对象 | 最灵活，可以锁任意对象 |

```java
// 两个实例、各调实例方法 → 不互斥！
Foo a = new Foo();
Foo b = new Foo();
a.instanceMethod();  // 锁 a
b.instanceMethod();  // 锁 b（不同锁 → 可以同时执行）

// 两个实例、各调静态方法 → 互斥！
a.staticMethod();    // 锁 Foo.class
b.staticMethod();    // 锁 Foo.class（同一把锁 → 只能一个执行）
```

### 1.3 经典面试题：实例方法 + 静态方法同时调用

```java
public class Demo {
    public synchronized void m1() { ... }          // 锁 this
    public static synchronized void m2() { ... }   // 锁 Demo.class
}

// 同一个实例，同时调用 m1 和 m2 → 互斥吗？
demo.m1();  // 锁 demo 实例
demo.m2();  // 锁 Demo.class
// → 不互斥！两把不同的锁，可以同时执行
```

> ⚠️ **面试必考**：`this` 锁和 `Class` 锁是两把锁，互不干扰。

---

## 2. 字节码层面：monitorenter / monitorexit

### 2.1 示例代码

```java
public class SyncBytecode {
    public void demo() {
        synchronized (this) {
            System.out.println("hello");
        }
    }
}
```

### 2.2 javap 反编译

```bash
# 编译
javac SyncBytecode.java
# 反编译查看字节码
javap -v SyncBytecode.class
```

```java
public void demo();
  descriptor: ()V
  flags: ACC_PUBLIC
  Code:
    stack=2, locals=3, args_size=1
       0: aload_0                    // 将 this 压入操作数栈（锁对象）
       1: dup                        // 复制一份（后面 exit 要用）
       2: astore_1                   // 存入局部变量槽 1
       3: monitorenter               // ★ 获取锁：进入 monitor
       4: getstatic     #2           // System.out
       7: ldc           #3           // "hello"
       9: invokevirtual #4           // println
      12: aload_1                    // 取出锁对象
      13: monitorexit                // ★ 释放锁：退出 monitor
      14: goto          22           // 跳转到正常结束
      17: astore_2                   // 捕获异常
      18: aload_1                    // 取出锁对象
      19: monitorexit                // ★ 异常时也释放锁
      20: aload_2                    // 重新抛出异常
      21: athrow
      22: return
```

### 2.3 关键发现

```
1. monitorenter：进入时获取锁（如果锁已被其他线程持有 → 阻塞等待）
2. monitorexit：正常退出时释放锁
3. 第 19 行：异常时也会执行 monitorexit ← 编译器自动生成的"异常路径释放锁"
4. 这就是 synchronized 不需要手动释放锁、异常自动释放的原因！
```

### 2.4 为什么会有两个 monitorexit？

```
编译器为 synchronized 块生成两个退出路径：

正常路径：执行完代码 → monitorexit → return
异常路径：执行中抛异常 → monitorexit（释放锁！）→ athrow（重新抛出异常）

→ 这就是"synchronized 异常自动释放锁"的字节码证据
→ 对比：ReentrantLock 必须手动 unlock，忘记就会死锁
```

---

## 3. 可重入性

### 3.1 什么是可重入

> **同一个线程，可以重复获取同一把锁，不会把自己锁死。**

```java
public class ReentrantDemo {
    public synchronized void outer() {
        System.out.println("outer 拿到锁");
        inner();  // 同一个线程再次获取同一把锁
    }

    public synchronized void inner() {
        System.out.println("inner 再次拿到锁");  // ✅ 不阻塞！
    }
}

// 执行 outer()
// outer 获取锁 → 调用 inner → 又要获取同一把锁
// 如果是"不可重入"，这里就死锁了
// 因为 JVM 记录了"锁的持有者是当前线程"，所以直接放行
```

### 3.2 可重入的实现机制（JVM 层面）

```
JVM 的 monitor 会记录：
  - 持有者线程（Owner Thread）
  - 重入计数（Recursion Count）

外层进入：Owner = 当前线程, Count = 1
内层进入：Owner == 当前线程 → Count++（直接通过，无需排队）
内层退出：Count--
外层退出：Count-- → 0 时释放锁（Owner = null）
```

### 3.3 可重入的现实意义

```java
// 继承场景：父类方法调用子类方法
public class Parent {
    public synchronized void doSomething() {
        System.out.println("父类方法");
    }
}

public class Child extends Parent {
    @Override
    public synchronized void doSomething() {
        System.out.println("子类方法（获得锁）");
        super.doSomething();  // 同一线程再次获取同一把锁 → 放行
        System.out.println("子类方法结束");
    }
}
```

> 如果 synchronized 不可重入，`super` 调用和继承重写场景全部会死锁，所以**可重入是锁的必备特性**。

---

## 4. Synchronized vs Lock 全面对比

> ⭐⭐⭐ **面试必问，也是日常选型的关键**

| 维度 | synchronized | ReentrantLock |
|---|---|---|
| 底层机制 | JVM 内置（monitor） | AQS（Java 代码） |
| 获取方式 | 自动获取/释放 | 手动 lock() / unlock() |
| 异常处理 | 异常自动释放锁 | **必须 finally 中 unlock** |
| 可中断 | ❌ 不支持 | ✅ `lockInterruptibly()` |
| 超时获取 | ❌ 不支持 | ✅ `tryLock(timeout, unit)` |
| 公平锁 | ❌ 非公平 | ✅ 公平/非公平可选 |
| 多条件（Condition） | ❌ 只有一个等待队列 | ✅ 多个 Condition |
| 锁的性质 | 可重入 | 可重入 |
| 锁粒度 | 只能锁整个方法/块 | 可以精确控制临界区 |
| 性能（JDK 8+） | 低竞争两者接近 | 高竞争下略占优（可控） |
| 适用场景 | 简单同步 | 需要中断/超时/多条件/公平 |

```java
// synchronized 写法（简单）
public synchronized void task() {
    doSomething();
}

// ReentrantLock 写法（必须 try-finally）
private final ReentrantLock lock = new ReentrantLock();

public void task() {
    lock.lock();
    try {
        doSomething();
    } finally {
        lock.unlock();  // ★ 忘记这行 → 死锁！
    }
}
```

### 选型建议

```
能用 synchronized 就不用 Lock：
  - 代码简单，不易出错（自动释放锁）
  - 性能上 JDK 8+ 两者差距已经很小

需要 Lock 的场景：
  ✅ 需要 tryLock 超时（防止死锁）
  ✅ 需要 lockInterruptibly（可中断等待）
  ✅ 需要多个 Condition（生产者-消费者精确唤醒）
  ✅ 需要公平锁
```

---

## 5. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：锁的不是你想的"那个对象"

```java
// ❌ 错误：锁的是"字符串字面量"，所有线程共享同一把锁
// 不同业务代码如果用了相同的字面量，会互相阻塞！
public void bad(String key) {
    synchronized (key.intern()) {  // intern() 的字符串是全局共享的！
        // ...
    }
}

// ✅ 正确：使用私有锁对象
private final Object lock = new Object();
public void good() {
    synchronized (lock) {
        // ...
    }
}
```

### 🕳️ 坑 2：锁被重新赋值

```java
private Object lock = new Object();

public void wrong() {
    synchronized (lock) {
        // 临界区
    }
}

public void updateLock() {
    lock = new Object();  // ❌ 锁对象被替换！其他线程对新对象加锁 → 互斥失效
}
```

### 🕳️ 坑 3：基本类型的包装类作为锁

```java
// ❌ 错误：Integer 有缓存池，-128~127 是同一个对象
Integer lock = 100;
synchronized (lock) { ... }

// ❌ 更隐蔽：自动装箱创建新对象
Integer lock2 = new Integer(100);  // 不同对象！
```

### 🕳️ 坑 4：String 作为锁

```java
// ❌ String 常量池共享 → 两个不同类用同一个字符串字面量 → 互相阻塞
synchronized ("order_lock") { ... }
// 其他代码如果也用 "order_lock" 这个字面量 → 同一把锁
```

### 🕳️ 坑 5：锁方法 vs 锁代码块

```java
// ❌ 锁整个方法 → 性能差（锁粒度太粗）
public synchronized void saveOrder(Order order) {
    validate(order);       // 不需要锁
    checkStock(order);     // 不需要锁
    synchronized (stockLock) {
        deductStock(order); // 只需要锁这里
    }
    sendMsg(order);        // 不需要锁
}

// ✅ 只锁临界区 → 锁粒度更细
public void saveOrder(Order order) {
    validate(order);
    checkStock(order);
    synchronized (stockLock) {
        deductStock(order);
    }
    sendMsg(order);
}
```

---

## 6. 面试高频考点

1. **synchronized 锁的是什么？三种形态分别锁什么对象？**
   → 实例方法锁 this，静态方法锁 Class 对象，代码块锁指定对象。

2. **synchronized 为什么能自动释放锁？**
   → 编译器自动生成两条 monitorexit：正常路径和异常路径各一条。异常路径先释放锁再抛出异常。

3. **什么是可重入？可重入的实现机制是什么？**
   → 同一线程可重复获取同一把锁。JVM monitor 记录持有者线程 + 重入计数，重入时计数 +1，归零才释放。

4. **synchronized 和 Lock 的区别？什么时候用 Lock？**
   → 需要 tryLock 超时、可中断、多 Condition、公平锁时用 Lock；简单场景用 synchronized。

5. **两个线程分别调用同一实例的 synchronized 实例方法和 synchronized 静态方法，会互斥吗？**
   → 不会。一个锁 this，一个锁 Class 对象，两把不同的锁。

---

## 7. 实战练习

### 练习 1：锁对象验证（30 分钟）

```java
package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 1：验证三种锁形态的互斥关系
 *
 * 目标：
 * 1. 两个线程调用同一实例的实例方法 → 互斥
 * 2. 两个实例分别调用实例方法 → 不互斥
 * 3. 实例方法 vs 静态方法 → 不互斥
 */
public class LockObjectTest {

    private static final class Counter {
        private int count = 0;

        public synchronized void incrInstance() {
            count++;
            sleep(50); // 放大竞争窗口
        }

        public static synchronized void incrStatic() {
            sleep(50);
        }

        private void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Test
    public void testSameInstance() throws InterruptedException {
        Counter c = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("同一实例调用实例方法: " + elapsed + "ms");
        System.out.println(elapsed >= 300 ? "✅ 互斥（串行执行）" : "❌ 未互斥（并发执行）");
        // 3次 × 50ms × 2线程 = 300ms（串行） vs 150ms（并发）
    }

    @Test
    public void testDifferentInstance() throws InterruptedException {
        Counter c1 = new Counter();
        Counter c2 = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c1.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) c2.incrInstance(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("不同实例调用实例方法: " + elapsed + "ms");
        System.out.println(elapsed < 300 ? "✅ 不互斥（并发执行）" : "❌ 意外互斥");
        // 3次 × 50ms = 150ms（并发）
    }

    @Test
    public void testInstanceVsStatic() throws InterruptedException {
        Counter c = new Counter();
        long start = System.currentTimeMillis();

        Thread t1 = new Thread(() -> { for (int i = 0; i < 3; i++) c.incrInstance(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 3; i++) Counter.incrStatic(); });
        t1.start(); t2.start();
        t1.join(); t2.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("实例方法 vs 静态方法: " + elapsed + "ms");
        System.out.println(elapsed < 300 ? "✅ 不互斥（两把不同的锁）" : "❌ 意外互斥");
    }
}
```

### 练习 2：可重入验证（20 分钟）

```java
package com.sw.yang.concurrent.sync;

import org.junit.jupiter.api.Test;

/**
 * 练习 2：验证 synchronized 可重入
 */
public class ReentrantTest {

    private final Object lock = new Object();
    private int depth = 0;

    public void reentrantMethod() {
        synchronized (lock) {
            depth++;
            System.out.println("已进入第 " + depth + " 层，线程: " + Thread.currentThread().getName());
            if (depth < 3) {
                reentrantMethod(); // 递归调用，再次获取同一把锁
            }
            depth--;
        }
    }

    @Test
    public void testReentrant() throws InterruptedException {
        System.out.println("=== 递归可重入测试（同一线程）===");
        reentrantMethod();
        System.out.println("✅ 同一线程递归获取同一把锁 N 次，未死锁 → 可重入");

        System.out.println("\n=== 跨线程测试（不同线程无法进入）===");
        Thread t = new Thread(() -> {
            synchronized (lock) {
                System.out.println("线程 " + Thread.currentThread().getName() + " 获取锁");
            }
        }, "other-thread");
        t.start();
        t.join();
        System.out.println("✅ 其他线程正常获取锁（锁已被释放）");
    }
}
```

### 练习 3：javap 实操（20 分钟）

```bash
# 1. 编译示例代码
cd src/test/java
javac com/sw/yang/concurrent/sync/BytecodeDemo.java

# 2. 反编译
javap -v -p com/sw/yang/concurrent/sync/BytecodeDemo.class

# 3. 找到 monitorenter / monitorexit
#    观察：正常路径 + 异常路径 两条 monitorexit
```

---

## 8. 自测题

1. **synchronized 修饰静态方法，锁的是哪个对象？两个不同实例同时调用这个静态方法会互斥吗？**
   <details><summary>答案</summary>

   锁的是 `Class` 对象（如 `Foo.class`），不是实例。Class 对象全局唯一，所以**不同实例调用同一个静态方法也会互斥**。
   </details>

2. **synchronized 方法抛异常后，锁会自动释放吗？字节码层面如何保证？**
   <details><summary>答案</summary>

   会。编译器为 synchronized 块生成两个退出路径：正常路径（执行完 → monitorexit → return）和异常路径（捕获异常 → monitorexit → athrow）。异常路径中先释放锁再重新抛出异常。
   </details>

3. **synchronized 是可重入的吗？为什么必须可重入？**
   <details><summary>答案</summary>

   是。JVM monitor 记录持有者线程和重入计数。若不可重入，继承场景（子类 synchronized 方法调用 super 的同名 synchronized 方法）会死锁。
   </details>

4. **什么场景下必须使用 ReentrantLock 而不是 synchronized？**
   <details><summary>答案</summary>

   ① 需要 `tryLock(timeout)` 超时获取（防死锁）
   ② 需要 `lockInterruptibly()` 可中断等待
   ③ 需要多个 Condition（生产者-消费者精确唤醒）
   ④ 需要公平锁
   其余简单场景用 synchronized 更简洁安全。
   </details>

5. **为什么推荐用私有锁对象而不是 String 作为锁？**
   <details><summary>答案</summary>

   String 有常量池，相同字面量是同一个对象，可能导致不同模块互相阻塞。使用 `private final Object lock = new Object()` 保证锁对象唯一且私有。
   </details>

---

> 📬 **完成自测题 + 3 个练习后，进入下一篇 [02-02-对象头与锁升级全链路](./02-02-对象头与锁升级全链路.md)（待发布）**

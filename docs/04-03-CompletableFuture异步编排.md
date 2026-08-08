# 04-03 CompletableFuture 异步编排

> **阶段四·第 3 篇** | 前置：[04-02-线程池调优与优雅停机](./04-02-线程池调优与优雅停机.md) | 后续：[04-04-ForkJoin与工作窃取](./04-04-ForkJoin与工作窃取.md)（待发布）  
> **建议时长**：6~7 小时（基础 API 2h + 编排 API 2.5h + 异常处理 1h + 实战 1.5h）  
> 🛠️ **日常高频**：接口聚合、异步编排、超时降级，后端开发几乎每天用

---

## 📌 优先级导航

| 标记 | 知识点 | 策略 |
|---|---|---|
| 🛠️ ⭐⭐⭐ | supplyAsync/runAsync、thenApply/thenCompose/thenCombine、allOf/anyOf、exceptionally/handle | **会用 + 知道区别 + 能写聚合代码** |
| ⭐⭐⭐ | 默认线程池风险、异常传播机制、自定义线程池传参 | **必须理解（生产事故高发）** |
| ◈◈ | applyToEither、whenComplete、complete 手动完成 | **知道用途** |
| ○ | orTimeout/completeOnTimeout（JDK 9+） | **了解即可** |

---

## 1. 为什么要 CompletableFuture

### 1.1 Future 的三大局限

```java
// 传统 Future 的问题：
Future<Integer> f1 = executor.submit(task1);
Future<Integer> f2 = executor.submit(task2);

// 局限 1：无法编排
//   → 想"f1 完成后把结果传给 f2" → 只能手动阻塞等 f1
int r1 = f1.get();          // 阻塞！
int r2 = f2.get();          // 阻塞！

// 局限 2：get() 阻塞主线程
//   → 异步的意义被削弱（主线程还是被卡住）

// 局限 3：无法统一处理异常
//   → 每个 get 都要 try-catch，代码冗余
```

### 1.2 CompletableFuture 解决什么

```
CompletableFuture = Future + Promise + 编排能力

Future：异步获取结果
Promise：手动完成（complete/completeExceptionally）
编排：thenApply / thenCompose / allOf ... 数十个 API

→ 异步任务可以"链式组合"：A 完成 → 自动触发 B → 自动触发 C
→ 不再手动阻塞等待，回调驱动
```

---

## 2. 创建异步任务（🛠️ 最常用）

### 2.1 两个创建方法

```java
// 有返回值的异步任务
CompletableFuture<String> future =
        CompletableFuture.supplyAsync(() -> {
            // 模拟耗时操作
            return "结果";
        });

// 无返回值的异步任务
CompletableFuture<Void> future =
        CompletableFuture.runAsync(() -> {
            // 执行操作（无返回值）
        });
```

### 2.2 默认线程池（⚠️ 生产大坑）

```java
// 不传线程池 → 使用 ForkJoinPool.commonPool()
CompletableFuture.supplyAsync(() -> doWork());
//          ↑
// 内部使用 ForkJoinPool.commonPool()：
//   - 线程数 = CPU 核数 - 1
//   - 线程是"守护线程"（daemon）！

// 风险 1：CPU 密集型任务和 IO 任务共用一个池 → 互相干扰
// 风险 2：线程数太少（核数-1），大量任务排队
// 风险 3：守护线程 → 主线程结束，异步任务可能被 JVM 提前终止！

// ✅ 生产规范：必须传自定义线程池
CompletableFuture.supplyAsync(() -> doWork(), orderExecutor);
```

### 2.3 生产规范示例

```java
// 自定义业务线程池（阶段四前两篇的知识！）
ThreadPoolExecutor bizExecutor = new ThreadPoolExecutor(
        8, 16, 60, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000),
        r -> { Thread t = new Thread(r); t.setName("biz-async"); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy());

// 所有异步任务都传这个池
// ⚠️ 注意：带 executor 的是 thenApplyAsync(fn, executor)
//         thenApply(fn) 没有 executor 重载！
CompletableFuture.supplyAsync(this::queryOrder, bizExecutor)
        .thenApplyAsync(order -> enrich(order), bizExecutor)   // 后续也传！
        .exceptionally(e -> defaultOrder());
```

---

## 3. 结果转换：thenApply / thenAccept / thenRun

### 3.1 三个转换方法

| 方法 | 输入 | 输出 | 用途 |
|---|---|---|---|
| `thenApply(fn)` | 上一步结果 | **返回新结果** | 转换（有返回值） |
| `thenAccept(consumer)` | 上一步结果 | 无返回值 | 消费（不返回） |
| `thenRun(action)` | 不需要结果 | 无返回值 | 执行（不关心结果） |

```java
CompletableFuture<String> future =
        CompletableFuture.supplyAsync(() -> "hello")   // String
        .thenApply(s -> s + " world")                   // String（转换）
        .thenApply(String::toUpperCase)                 // String（再转换）
        .thenAccept(s -> System.out.println("最终: " + s));  // 消费

// 输出：最终: HELLO WORLD
```

### 3.2 同步 vs 异步后缀

```
thenApply(fn)      → 用上一步的线程执行（同步，无线程切换）
thenApplyAsync(fn) → 用默认 commonPool 执行（异步，切换线程）
thenApplyAsync(fn, executor) → 用指定线程池执行（推荐）

规则：
  不带 Async：沿用上一步的执行线程（省一次线程切换）
  带 Async：指定/默认线程池执行（可控）
  生产规范：全部传自定义线程池，避免 commonPool
```

### 3.3 用图理解链式调用

```
supplyAsync("hello")     → "hello"
    ↓ thenApply (转换)
"hello world"
    ↓ thenApply (转换)
"HELLO WORLD"
    ↓ thenAccept (消费)
打印
```

---

## 4. 组合：thenCompose / thenCombine（⭐ 核心区别）

### 4.1 thenCompose —— 依赖组合（结果作为下一个任务输入）

```java
// 场景：查订单 → 用订单号查用户（第二个任务依赖第一个的结果）
CompletableFuture<User> future =
        CompletableFuture.supplyAsync(() -> queryOrder(orderId), executor)
        .thenCompose(order ->
                CompletableFuture.supplyAsync(() -> queryUser(order.getUserId()), executor));

// 关键：thenCompose 的参数返回的是 CompletableFuture
// 作用：扁平化，避免 CompletableFuture<CompletableFuture<User>>
```

```
为什么需要 thenCompose？

// ❌ 用 thenApply 会得到嵌套：
CompletableFuture<CompletableFuture<User>> bad =
        f1.thenApply(order -> queryUserAsync(order.getUserId()));
//            ↑ 返回的是 CompletableFuture<User>，被包了一层

// ✅ thenCompose 自动扁平化：
CompletableFuture<User> good =
        f1.thenCompose(order -> queryUserAsync(order.getUserId()));
// 类似 Stream 的 flatMap
```

### 4.2 thenCombine —— 独立组合（两个任务并行，结果合并）

```java
// 场景：并发查订单 + 查用户（两个独立任务），最后合并
CompletableFuture<Order> f1 = CompletableFuture.supplyAsync(() -> queryOrder(id), executor);
CompletableFuture<User> f2 = CompletableFuture.supplyAsync(() -> queryUser(id), executor);

// 两个都完成后，用 BiFunction 合并
CompletableFuture<OrderDetail> result =
        f1.thenCombine(f2, (order, user) -> new OrderDetail(order, user));
```

### 4.3 thenCompose vs thenCombine 对比（面试必考）

| 维度 | thenCompose | thenCombine |
|---|---|---|
| 任务关系 | **依赖**（B 用 A 的结果） | **独立**（A、B 并行） |
| 执行方式 | A 完成后才启动 B（串行） | A、B 同时启动（并行） |
| 参数 | Function → CompletableFuture | BiFunction → 结果 |
| 类比 | flatMap（扁平化） | zip（合并） |

```
记忆口诀：
  thenCompose：前一个完成后，用其结果启动后一个（串联）
  thenCombine：两个各自跑，都完成后合并结果（并联）
```

---

## 5. 聚合：allOf / anyOf（🛠️ 高频）

### 5.1 allOf —— 等所有任务完成

```java
// 场景：商品详情页 = 查商品 + 查库存 + 查价格 + 查评价（并发）
CompletableFuture<Product> p = CompletableFuture.supplyAsync(() -> queryProduct(id), executor);
CompletableFuture<Stock> s = CompletableFuture.supplyAsync(() -> queryStock(id), executor);
CompletableFuture<Price> pr = CompletableFuture.supplyAsync(() -> queryPrice(id), executor);
CompletableFuture<Reviews> rv = CompletableFuture.supplyAsync(() -> queryReviews(id), executor);

// allOf 等待所有完成（注意：返回 CompletableFuture<Void>，拿不到结果！）
CompletableFuture<Void> all = CompletableFuture.allOf(p, s, pr, rv);

// 等待全部完成
all.join();

// 各自 get 结果（此时都已完成，不会阻塞）
Product product = p.join();
Stock stock = s.join();
...
```

```
⚠️ allOf 的坑：
  返回的是 CompletableFuture<Void> —— 结果要自己从原来的 future 里拿！
  正确姿势：allOf().join() 后，逐个 join() 原始 future
```

### 5.2 anyOf —— 任一完成即可

```java
// 场景：多个数据源查询，谁先返回用谁的（容灾/择优）
CompletableFuture<Object> first =
        CompletableFuture.anyOf(
                CompletableFuture.supplyAsync(() -> queryFromRedis(), executor),
                CompletableFuture.supplyAsync(() -> queryFromDB(), executor),
                CompletableFuture.supplyAsync(() -> queryFromRemote(), executor));

// 返回最先完成的那个的结果
Object result = first.join();
// 注意：返回类型是 Object（因为不知道哪个先完成）
```

### 5.3 商品详情聚合实战（完整代码，🛠️ 必练）

```java
public class ProductDetailService {
    private final ThreadPoolExecutor executor = buildExecutor();

    public ProductDetailVO getProductDetail(Long productId) {
        long start = System.currentTimeMillis();

        // ① 并发发起 4 个查询
        CompletableFuture<Product> productF =
                CompletableFuture.supplyAsync(() -> productMapper.selectById(productId), executor);
        CompletableFuture<Stock> stockF =
                CompletableFuture.supplyAsync(() -> stockService.getStock(productId), executor);
        CompletableFuture<Price> priceF =
                CompletableFuture.supplyAsync(() -> priceService.getPrice(productId), executor);
        CompletableFuture<List<Review>> reviewF =
                CompletableFuture.supplyAsync(() -> reviewService.listReviews(productId), executor);

        // ② 等所有完成
        CompletableFuture.allOf(productF, stockF, priceF, reviewF).join();

        // ③ 组装（此时全部完成，join 不阻塞）
        return ProductDetailVO.builder()
                .product(productF.join())
                .stock(stockF.join())
                .price(priceF.join())
                .reviews(reviewF.join())
                .build();
        // 总耗时 ≈ 最慢的那个查询（而不是 4 个之和！）
    }
}
```

---

## 6. 竞速：applyToEither / acceptEither

```java
// 两个任务谁先完成，用谁的结果（类似 anyOf，但类型安全）

// 场景：本地缓存 vs 远程查询，谁快用谁
CompletableFuture<String> cacheF =
        CompletableFuture.supplyAsync(() -> cache.get(key), executor);
CompletableFuture<String> remoteF =
        CompletableFuture.supplyAsync(() -> remote.query(key), executor);

// 先完成的触发转换
CompletableFuture<String> fastest =
        cacheF.applyToEither(remoteF, result -> {
            // result 是"先完成那个"的结果
            return "使用: " + result;
        });
```

---

## 7. 异常处理（🛠️ 生产必备）

### 7.1 异常沿链传播

```
异常传播机制（重要！）：

  supplyAsync(可能抛异常)
    ↓ 异常自动向下游传播
  thenApply(不会执行！)
    ↓ 继续传播
  thenApply(不会执行！)
    ↓
  exceptionally(捕获并恢复)  ← 在链尾兜底

→ 链条上任何一个环节抛异常 → 跳过中间所有环节 → 直达链尾的异常处理器
→ 没有异常处理器 → 异常被吞掉（join/get 时才抛）
```

### 7.2 三个异常处理方法对比

| 方法 | 触发时机 | 返回值 | 类比 |
|---|---|---|---|
| `exceptionally(fn)` | **仅异常**时 | 返回恢复值（正常结果类型） | catch |
| `handle(fn)` | 正常/异常都触发 | 返回新结果（可恢复或转换） | catch + finally |
| `whenComplete(fn)` | 正常/异常都触发 | 不改变结果（副作用） | finally（观察） |

```java
// ① exceptionally —— 只处理异常，恢复默认值
CompletableFuture<Integer> f =
        CompletableFuture.supplyAsync(() -> 10 / 0, executor)  // 抛异常
        .exceptionally(e -> -1);                                // 恢复为 -1
System.out.println(f.join());  // -1（不抛异常）

// ② handle —— 正常/异常都处理
CompletableFuture<Integer> f2 =
        CompletableFuture.supplyAsync(() -> 10 / 2, executor)  // 正常：5
        .handle((result, e) -> {
            if (e != null) return -1;      // 异常分支
            return result * 2;              // 正常分支
        });
System.out.println(f2.join());  // 10

// ③ whenComplete —— 只观察不改变（类似日志）
CompletableFuture<Integer> f3 =
        CompletableFuture.supplyAsync(() -> 42, executor)
        .whenComplete((result, e) -> {
            // 这里不能改结果！只是观察
            if (e != null) log.error("任务失败", e);
            else log.info("任务成功: {}", result);
        });
System.out.println(f3.join());  // 42（结果不变）
```

### 7.3 超时降级（生产核心场景）

```java
// 场景：第三方接口太慢，不能无限等
CompletableFuture<String> f =
        CompletableFuture.supplyAsync(() -> callThirdParty(), executor)
        .exceptionally(e -> "降级数据");    // 异常恢复

// 手动实现超时：anyOf 竞速（JDK 9+ 有 orTimeout，JDK 8 要自己实现）
CompletableFuture<String> timeout = new CompletableFuture<>();
CompletableFuture<Object> race =
        CompletableFuture.anyOf(f, timeout);

// ⚠️ ThreadPoolExecutor 没有 schedule 方法！
// 需要 ScheduledExecutorService 来做定时兜底：
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.schedule(() -> timeout.complete("超时降级"), 3, TimeUnit.SECONDS);
// 3 秒后手动完成 timeout → race 结束

Object result = race.join();
```

```
生产降级三板斧：
  1. 异常 → exceptionally 恢复默认值
  2. 超时 → 竞速 + 手动 complete 兜底
  3. 空值 → 判断后走兜底逻辑
```

---

## 8. 手动完成：complete / completeExceptionally

```java
// CompletableFuture 是"Promise"：可以手动完成

CompletableFuture<String> f = new CompletableFuture<>();

// 线程 A：真正的工作线程
executor.execute(() -> {
    try {
        String result = doWork();
        f.complete(result);                  // 手动完成（成功）
    } catch (Exception e) {
        f.completeExceptionally(e);          // 手动完成（失败）
    }
});

// 线程 B：等待结果
String result = f.join();

// 用途：
//  - 回调转异步（把回调风格包装成 Future）
//  - 超时控制（上面 7.3 的 timeout 兜底）
//  - 手动触发（事件驱动）
```

---

## 9. 生产注意事项 & 常见坑点

### 🕳️ 坑 1：不传线程池 → commonPool 灾难

```java
// ❌ 全部用默认 commonPool（CPU 核数-1 个守护线程）
CompletableFuture.supplyAsync(() -> queryDB());
CompletableFuture.supplyAsync(() -> callHttp());
// → IO 任务占满 commonPool → CPU 任务被饿死 → 服务卡死

// ✅ 所有异步任务都传业务线程池
CompletableFuture.supplyAsync(() -> queryDB(), bizExecutor);
```

### 🕳️ 坑 2：get() 无限阻塞

```java
// ❌ get() 没有超时 → 任务永远不完成 → 线程永久阻塞
future.get();

// ✅ 带超时
future.get(3, TimeUnit.SECONDS);   // 3 秒超时抛 TimeoutException
// 或 join() + 竞速兜底
```

### 🕳️ 坑 3：在 thenApply 里做耗时操作

```java
// ❌ 把 IO 操作放在 thenApply（同步执行）→ 阻塞上一步线程
future.thenApply(order -> callThirdParty(order));  // 同步 IO → 卡线程

// ✅ 耗时操作放 supplyAsync（异步）
future.thenCompose(order ->
        CompletableFuture.supplyAsync(() -> callThirdParty(order), executor));
```

### 🕳️ 坑 4：异常被吞掉

```java
// ❌ 链条中间异常没有处理 → 异常被"挂起"到链尾
// 如果链尾也没处理 → join() 时抛 CompletionException（包装）

// ✅ 链尾必须有兜底
future.exceptionally(e -> { log.error("异步任务失败", e); return fallback(); });
```

### 🕳️ 坑 5：allOf 后直接拿结果

```java
// ❌ allOf().join() 返回 null！不是所有结果
CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2);
Object x = all.join();   // null！

// ✅ 必须从原始 future 拿
all.join();
Object r1 = f1.join();   // 此时已完成
Object r2 = f2.join();
```

### 🕳️ 坑 6：join 抛 CompletionException

```java
// ⚠️ join() 抛的是 CompletionException（包装了原始异常）
// get() 抛的是 ExecutionException
// 处理：e.getCause() 拿原始异常
try {
    future.join();
} catch (CompletionException e) {
    Throwable cause = e.getCause();   // 真正的异常
    log.error("异步任务失败: {}", cause.getMessage());
}
```

---

## 10. 面试高频考点

1. **thenApply 和 thenCompose 的区别？**
   → thenApply 转换结果（返回普通值）；thenCompose 结果依赖前一步并返回 CompletableFuture（扁平化，避免嵌套）。类似 map vs flatMap。

2. **为什么生产必须传自定义线程池？**
   → 默认 commonPool 线程数太少（核数-1）、是守护线程（主线程结束任务可能被终止）、CPU/IO 任务互相干扰。

3. **allOf 和 anyOf 的区别？**
   → allOf 等全部完成（Void，结果要自己从原始 future 拿）；anyOf 任一完成即可（返回 Object）。

4. **异常传播机制？exceptionally/handle/whenComplete 区别？**
   → 异常沿链传播到最近的处理器；exceptionally 仅异常时恢复、handle 正常异常都处理且可转换、whenComplete 只观察不改变结果。

5. **CompletableFuture 怎么实现超时？**
   → JDK 9+ 用 orTimeout/completeOnTimeout；JDK 8 用 anyOf 竞速 + 手动 complete 兜底。

---

## 11. 实战练习

### 练习 1：商品详情聚合（60 分钟）★必做

```java
package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 练习 1：商品详情聚合 —— 并发查询 4 个数据源，全部完成后组装
 *
 * 目标：
 * 1. 理解 allOf 的使用
 * 2. 验证总耗时 ≈ 最慢任务（并行效果）
 */
public class ProductDetailTest {

    static class Product { String name; Product(String n) { name = n; } }
    static class Stock { int count; Stock(int c) { count = c; } }
    static class Price { double price; Price(double p) { price = p; } }
    static class Review { String content; Review(String c) { content = c; } }
    static class ProductDetail {
        Product product; Stock stock; Price price; List<Review> reviews;
        @Override public String toString() {
            return "ProductDetail{product=" + product.name + ", stock=" + stock.count
                    + ", price=" + price.price + ", reviews=" + reviews.size() + "条}";
        }
    }

    private ThreadPoolExecutor buildExecutor() {
        return new ThreadPoolExecutor(
                4, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> { Thread t = new Thread(r); t.setName("product-async"); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // 模拟 4 个数据源（不同耗时）
    private Product queryProduct(long id) { sleep(300); return new Product("商品-" + id); }
    private Stock queryStock(long id) { sleep(200); return new Stock(99); }
    private Price queryPrice(long id) { sleep(400); return new Price(99.9); }
    private List<Review> queryReviews(long id) { sleep(500); return List.of(new Review("好评")); }
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 串行版（对比用）：总耗时 = 4 个耗时之和 = 1400ms
     */
    @Test
    public void serialVersion() {
        long start = System.currentTimeMillis();
        ProductDetail detail = new ProductDetail();
        detail.product = queryProduct(1L);
        detail.stock = queryStock(1L);
        detail.price = queryPrice(1L);
        detail.reviews = queryReviews(1L);
        System.out.println("串行结果: " + detail);
        System.out.println("串行耗时: " + (System.currentTimeMillis() - start) + "ms（≈1400ms）");
    }

    /**
     * 并行版：总耗时 = 最慢任务 = 500ms
     */
    @Test
    public void parallelVersion() throws InterruptedException {
        ThreadPoolExecutor executor = buildExecutor();
        long start = System.currentTimeMillis();

        CompletableFuture<Product> productF =
                CompletableFuture.supplyAsync(() -> queryProduct(1L), executor);
        CompletableFuture<Stock> stockF =
                CompletableFuture.supplyAsync(() -> queryStock(1L), executor);
        CompletableFuture<Price> priceF =
                CompletableFuture.supplyAsync(() -> queryPrice(1L), executor);
        CompletableFuture<List<Review>> reviewF =
                CompletableFuture.supplyAsync(() -> queryReviews(1L), executor);

        // 等全部完成
        CompletableFuture.allOf(productF, stockF, priceF, reviewF).join();

        // 组装（此时全部完成）
        ProductDetail detail = new ProductDetail();
        detail.product = productF.join();
        detail.stock = stockF.join();
        detail.price = priceF.join();
        detail.reviews = reviewF.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("并行结果: " + detail);
        System.out.println("并行耗时: " + elapsed + "ms（≈500ms，最慢任务）");
        System.out.println(elapsed < 900
                ? "✅ 并行生效（远小于串行的 1400ms）"
                : "❌ 没有并行效果？");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

### 练习 2：依赖编排 thenCompose（45 分钟）

```java
package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

/**
 * 练习 2：thenCompose 依赖编排
 *
 * 场景：查订单 → 用订单的 userId 查用户 → 用用户的 level 算折扣
 */
public class ComposeChainTest {

    static class Order { String orderId; long userId; Order(String o, long u) { orderId = o; userId = u; } }
    static class User { String name; int level; User(String n, int l) { name = n; level = l; } }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> { Thread t = new Thread(r); t.setName("compose-worker"); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private Order queryOrder(String orderId) {
        sleep(200);
        return new Order(orderId, 1001L);
    }

    private User queryUser(long userId) {
        sleep(200);
        return new User("用户-" + userId, 3);
    }

    private double calcDiscount(User user) {
        sleep(100);
        return user.level >= 3 ? 0.8 : 0.9;   // 高级会员 8 折
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    public void testDependentChain() throws InterruptedException {
        long start = System.currentTimeMillis();

        CompletableFuture<Double> discountF =
                CompletableFuture.supplyAsync(() -> queryOrder("O-2024-001"), executor)  // ① 查订单
                .thenCompose(order ->                                                  // ② 依赖订单
                        CompletableFuture.supplyAsync(() -> queryUser(order.userId), executor))
                .thenCompose(user ->                                                  // ③ 依赖用户
                        CompletableFuture.supplyAsync(() -> calcDiscount(user), executor));

        // ④ 最终结果
        double discount = discountF.join();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("最终折扣: " + discount);
        System.out.println("总耗时: " + elapsed + "ms（串行 500ms，不是并行 200ms）");
        System.out.println("✅ thenCompose 依赖编排成功（前一步结果传给下一步）");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

### 练习 3：超时降级 + 异常恢复（45 分钟）★必做

```java
package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

/**
 * 练习 3：异常恢复 + 超时降级（生产核心场景）
 */
public class TimeoutFallbackTest {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            r -> { Thread t = new Thread(r); t.setName("fallback-worker"); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    // 模拟：可能抛异常的第三方调用
    private String callThirdParty(boolean fail) {
        sleep(500);
        if (fail) throw new RuntimeException("第三方服务挂了");
        return "第三方正常数据";
    }

    // 模拟：很慢的调用（2 秒）
    private String slowCall() {
        sleep(2000);
        return "慢接口数据";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    public void testExceptionFallback() throws InterruptedException {
        // 异常恢复：第三方挂了 → 返回降级数据
        String result = CompletableFuture.supplyAsync(() -> callThirdParty(true), executor)
                .exceptionally(e -> {
                    System.out.println("捕获异常: " + e.getCause().getMessage());
                    return "降级数据";
                })
                .join();
        System.out.println("异常恢复结果: " + result);
        System.out.println("✅ exceptionally 异常恢复成功");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void testTimeoutFallback() throws InterruptedException {
        // 超时降级：慢接口 2 秒，等 1 秒就放弃
        CompletableFuture<String> slowF =
                CompletableFuture.supplyAsync(() -> slowCall(), executor);

        // 竞速：slowF vs 手动 1 秒超时
        CompletableFuture<String> timeoutF = new CompletableFuture<>();
        CompletableFuture<Object> race = CompletableFuture.anyOf(slowF, timeoutF);

        // 1 秒后手动完成 timeout（兜底）
        executor.execute(() -> {
            sleep(1000);
            timeoutF.complete("超时降级数据");
        });

        long start = System.currentTimeMillis();
        Object result = race.join();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("1 秒竞速结果: " + result + "（耗时 " + elapsed + "ms）");
        System.out.println("✅ 超时降级成功（不等慢接口的 2 秒）");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

---

## 12. 自测题

1. **thenApply 和 thenCompose 的区别？各自什么时候用？**
   <details><summary>答案</summary>

   thenApply：结果转换（同步处理前一步结果，返回普通值）；thenCompose：结果作为下一步异步任务的输入（返回 CompletableFuture，扁平化避免嵌套）。依赖串联用 thenCompose，简单转换用 thenApply。
   </details>

2. **为什么 CompletableFuture 生产必须传线程池？默认线程池有什么问题？**
   <details><summary>答案</summary>

   默认 commonPool：线程数 = CPU 核数-1（太少）、守护线程（主线程结束任务可能被 JVM 终止）、CPU/IO 任务互相干扰。传自定义线程池可控、可监控、可隔离。
   </details>

3. **allOf 返回什么？怎么拿结果？**
   <details><summary>答案</summary>

   返回 CompletableFuture<Void>（没有结果）。先 allOf().join() 等待全部完成，再从各个原始 future 逐个 join() 拿结果（此时已完成不阻塞）。
   </details>

4. **异常在链上怎么传播？exceptionally 和 handle 的区别？**
   <details><summary>答案</summary>

   异常沿链跳过所有中间环节直达最近的处理器。exceptionally 仅异常时触发（返回恢复值）；handle 正常/异常都触发（可判断 e 是否为空分别处理）。
   </details>

5. **怎么实现超时降级？**
   <details><summary>答案</summary>

   JDK 9+：orTimeout() / completeOnTimeout()；JDK 8：anyOf 竞速 + 手动 complete 兜底（定时任务 3 秒后 complete("降级")）。
   </details>

---

> 📬 **完成练习后，进入下一篇 [04-04-ForkJoin与工作窃取](./04-04-ForkJoin与工作窃取.md)（待发布）—— 阶段四收官篇，分治思想与工作窃取算法**

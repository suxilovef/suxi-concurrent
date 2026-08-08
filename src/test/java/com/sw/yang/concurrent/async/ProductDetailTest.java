package com.sw.yang.concurrent.async;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 练习 1：商品详情聚合 —— 并发查询 4 个数据源，全部完成后组装
 *
 * 目标：
 * 1. 理解 allOf 的使用
 * 2. 验证总耗时 ≈ 最慢任务（并行效果）
 */
public class ProductDetailTest {

    static class Product {
        String name;

        Product(String n) {
            name = n;
        }
    }

    static class Stock {
        int count;

        Stock(int c) {
            count = c;
        }
    }

    static class Price {
        double price;

        Price(double p) {
            price = p;
        }
    }

    static class Review {
        String content;

        Review(String c) {
            content = c;
        }
    }

    static class ProductDetail {
        Product product;
        Stock stock;
        Price price;
        List<Review> reviews;

        @Override
        public String toString() {
            return "ProductDetail{product=" + product.name + ", stock=" + stock.count
                    + ", price=" + price.price + ", reviews=" + reviews.size() + "条}";
        }
    }

    private ThreadPoolExecutor buildExecutor() {
        return new ThreadPoolExecutor(
                4, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("product-async");
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // 模拟 4 个数据源（不同耗时）
    private Product queryProduct(long id) {
        sleep(300);
        return new Product("商品-" + id);
    }

    private Stock queryStock(long id) {
        sleep(200);
        return new Stock(99);
    }

    private Price queryPrice(long id) {
        sleep(400);
        return new Price(99.9);
    }

    private List<Review> queryReviews(long id) {
        sleep(500);
        return List.of(new Review("好评"));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

import java.sql.*;

/**
 * 练习 1：乐观锁（版本号）扣库存 —— 模拟并发扣减
 *
 * 前置：本地 MySQL，创建表：
 *   CREATE TABLE stock (
 *     sku_id BIGINT PRIMARY KEY,
 *     count INT NOT NULL,
 *     version INT NOT NULL
 *   );
 *   INSERT INTO stock VALUES (1, 100, 0);
 *
 * 目标：10 个线程各扣 1 件，乐观锁保证最终 count = 90
 */
public class OptimisticLockTest {

    private static final String URL = "jdbc:mysql://localhost:3306/test?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "123456";

    /**
     * 乐观锁扣减：带版本号更新
     */
    private static int deductWithVersion(long skuId, int count, int version) throws SQLException {
        String sql = "UPDATE stock SET count = count - ?, version = version + 1 " +
                     "WHERE sku_id = ? AND version = ?";
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, count);
            ps.setLong(2, skuId);
            ps.setInt(3, version);
            return ps.executeUpdate(); // 1 成功 / 0 版本冲突
        }
    }

    private static int getCount(long skuId) throws SQLException {
        String sql = "SELECT count FROM stock WHERE sku_id = ?";
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, skuId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    public void testOptimisticLock() throws Exception {
        // 前置检查
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("✅ 已连接 MySQL（请确认已建 stock 表并插入 sku_id=1, count=100, version=0）");
        } catch (SQLException e) {
            System.out.println("⚠️ 无法连接 MySQL：" + e.getMessage());
            return;
        }

        final int threads = 10;
        final int[] success = {0};
        final int[] conflict = {0};
        Thread[] ts = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                // 乐观锁：查询版本 → 带版本更新 → 冲突重试（最多 5 次）
                for (int retry = 0; retry < 5; retry++) {
                    try {
                        // 查询当前版本
                        String q = "SELECT version FROM stock WHERE sku_id = 1";
                        int version;
                        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
                             Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery(q)) {
                            rs.next();
                            version = rs.getInt(1);
                        }
                        // 带版本扣减
                        int rows = deductWithVersion(1, 1, version);
                        if (rows == 1) {
                            synchronized (success) {
                                success[0]++;
                            }
                            return;
                        }
                    } catch (SQLException e) {
                        // 数据库异常
                        return;
                    }
                }
                synchronized (conflict) {
                    conflict[0]++; // 重试 5 次仍失败
                }
            }, "thread-" + i);
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        System.out.println("成功扣减: " + success[0] + " 次");
        System.out.println("冲突失败: " + conflict[0] + " 次");
        System.out.println("最终库存: " + getCount(1) + "（预期 100 - 10 = 90）");
        System.out.println(getCount(1) == 90
                ? "✅ 乐观锁生效：并发扣减不超卖"
                : "❌ 库存异常！");
    }
}

package com.sw.yang.concurrent.distributed;

import org.junit.jupiter.api.Test;

import java.sql.*;

/**
 * 练习 2：悲观锁（SELECT ... FOR UPDATE）扣库存 —— 与乐观锁对比
 *
 * 原理：事务内锁行 → 其他事务等待 → 串行扣减 → 天然安全
 * 代价：并发性能低于乐观锁（锁等待）
 */
public class PessimisticLockTest {

    private static final String URL = "jdbc:mysql://localhost:3306/test?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "123456";

    @Test
    public void testPessimisticLock() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("✅ 已连接 MySQL");
        } catch (SQLException e) {
            System.out.println("⚠️ 无法连接 MySQL：" + e.getMessage());
            return;
        }

        final int threads = 10;
        final int[] success = {0};
        Thread[] ts = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    conn.setAutoCommit(false); // 开事务

                    // ① 锁行（悲观锁核心）
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(
                                 "SELECT count FROM stock WHERE sku_id = 1 FOR UPDATE")) {
                        rs.next();
                        int count = rs.getInt(1);
                        if (count <= 0) {
                            conn.rollback();
                            return; // 库存不足
                        }
                    }

                    // ② 扣减
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate(
                                "UPDATE stock SET count = count - 1 WHERE sku_id = 1");
                    }

                    // ③ 提交（释放锁）
                    conn.commit();
                    synchronized (success) {
                        success[0]++;
                    }
                } catch (SQLException e) {
                    // 锁等待超时等异常
                }
            }, "thread-" + i);
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        System.out.println("成功扣减: " + success[0] + " 次");
        System.out.println("✅ 悲观锁：事务内锁行，串行扣减（注意：执行前请把库存重置为 100）");
        System.out.println("对比：乐观锁无阻塞性能高，悲观锁有锁等待但无需重试");
    }
}

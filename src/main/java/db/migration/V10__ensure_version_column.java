package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.*;

/**
 * V10: 确保 customer_transfer 表包含 version 列（乐观锁）。
 *
 * <p>V7 和 V9 使用 MySQL 特有的 PREPARE/EXECUTE 动态 SQL 添加列，
 * 在某些 JDBC 驱动配置下可能未正常执行。
 * 本迁移使用纯 JDBC DatabaseMetaData 检查，ALTER TABLE 时捕获
 * 1060 (Duplicate column) 错误实现幂等，比 INFORMATION_SCHEMA +
 * PREPARE/EXECUTE 更可靠。
 * </p>
 *
 * <p>此外补充确保 poll_count 列存在（同样受 V7 可靠性影响）。</p>
 *
 * @author Bookstore Dev Team
 * @since 2026-07-05
 */
public class V10__ensure_version_column extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        // MySQL 的 getColumns 对列名大小写敏感，统一用小写匹配（InnoDB 默认小写存储）
        if (!columnExists(meta, "customer_transfer", "version")) {
            executeDdl(conn,
                "ALTER TABLE customer_transfer ADD COLUMN version INT NOT NULL DEFAULT 0 " +
                "COMMENT '乐观锁版本号' AFTER updated_at");
        }

        if (!columnExists(meta, "customer_transfer", "poll_count")) {
            executeDdl(conn,
                "ALTER TABLE customer_transfer ADD COLUMN poll_count INT NOT NULL DEFAULT 0 " +
                "COMMENT '轮询追踪次数 (pending_confirm状态)' AFTER retry_count");
        }

        // 历史数据迁移（idempotent）
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE customer_transfer SET poll_count = retry_count " +
                         "WHERE status = 'pending_confirm' AND poll_count = 0");
        }
    }

    private boolean columnExists(DatabaseMetaData meta, String table, String column)
            throws SQLException {
        // MySQL 将表名存储为小写（lower_case_table_names 默认行为），
        // 使用 null catalog / null schemaPattern 让驱动自动匹配当前数据库
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private void executeDdl(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            // MySQL 错误码 1060 = Duplicate column name — 幂等忽略
            if (e.getErrorCode() != 1060) {
                throw e;
            }
        }
    }
}

package com.chao.punch.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class PunchSchemaMigrator implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String schema;
            try (PreparedStatement ps = conn.prepareStatement("select database()");
                 ResultSet rs = ps.executeQuery()) {
                schema = rs.next() ? rs.getString(1) : null;
            }
            if (schema == null || schema.isBlank()) {
                return;
            }

            ensureColumn(conn, schema, "punch_records", "duration_seconds", "ALTER TABLE punch_records ADD COLUMN duration_seconds INT NULL");
            ensureColumn(conn, schema, "punch_records", "started_at", "ALTER TABLE punch_records ADD COLUMN started_at DATETIME NULL");
            ensureColumn(conn, schema, "punch_records", "ended_at", "ALTER TABLE punch_records ADD COLUMN ended_at DATETIME NULL");
        }
    }

    private void ensureColumn(Connection conn, String schema, String table, String column, String ddl) {
        try (PreparedStatement ps = conn.prepareStatement(
                "select count(*) from information_schema.columns where table_schema=? and table_name=? and column_name=?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("检查字段失败: {}.{} {}", table, column, e.getMessage());
            return;
        }

        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
            log.info("已执行DDL: {}", ddl);
        } catch (Exception e) {
            log.warn("执行DDL失败: {} {}", ddl, e.getMessage());
        }
    }
}

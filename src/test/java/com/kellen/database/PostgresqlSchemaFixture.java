package com.kellen.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅允许独立 PostgreSQL 18 测试库；所有业务 fixture 位于每测试独占 schema。 */
abstract class PostgresqlSchemaFixture {
    protected Connection connection;
    protected String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", System.getenv("FLEET_PG_TEST_USERNAME"));
        properties.setProperty("password", System.getenv("FLEET_PG_TEST_PASSWORD"));
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        connection = DriverManager.getConnection(System.getenv("FLEET_PG_TEST_URL"), properties);
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT current_database(), current_setting('server_version_num')::int")) {
            result.next();
            // 先守卫库名，再执行任何 DDL；禁止把业务库当测试库。
            assertThat(result.getString(1)).startsWith("fleet_pg_test_");
            assertThat(result.getInt(2)).isGreaterThanOrEqualTo(180000);
        }
        schema = "fixture_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema + ", public");
    }

    @AfterEach
    void removeOnlyOwnSchema() throws Exception {
        if (connection != null) {
            try {
                if (schema != null) {
                    execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally {
                connection.close();
            }
        }
    }

    protected void script(String path) throws SQLException {
        // 每份真实脚本一个事务，避免数百 COMMENT 的逐条提交放大 NAS WAL 同步成本。
        // 所有语句仍实际执行；不改变服务预算、WAL/fsync 或共享 DdlHelper 的验证方式。
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("fixture requires autocommit outside DDL");
        }
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(path));
            connection.commit();
        } catch (RuntimeException | SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    protected void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    protected long number(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}

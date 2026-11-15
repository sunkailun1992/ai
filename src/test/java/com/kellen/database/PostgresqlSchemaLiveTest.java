package com.kellen.database;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.kellen.memory.AiChatMemoryRepository;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 服务自己的 Flyway 迁移在独占 schema 上实际执行，并回归标识列、布尔、时间与大小写不敏感比较；不连接真实业务库。 */
@EnabledIfEnvironmentVariable(named = "FLEET_PG_TEST_URL", matches = ".+")
class PostgresqlSchemaLiveTest extends PostgresqlSchemaFixture {

    private static final List<String> TABLES = List.of("ai_chat_memory", "ai_chat_memory_summary", "ai_chat_message",
            "ai_file_object", "ai_model_selection", "ai_patient_memory", "ai_role_prompt", "ai_storage_configuration",
            "ai_storage_operation", "ai_storage_setting");

    private static final List<String> UNUSED_TABLES = List.of("ai_chat_conversation", "ai_chat_conversation_message",
            "ai_integration_outbox", "ai_questionnaire_answer", "ai_questionnaire_session", "ai_questionnaire_template",
            "ai_wechat_session", "ai_wechat_user");

    private static final List<String> STORAGE_TABLES = List.of("ai_storage_configuration", "ai_storage_operation", "ai_storage_setting");

    @Test
    void migratesEmptySchemaToLatestVersion() throws Exception {
        assertThat(migrate(null).success).isTrue();
        assertThat(appliedVersions()).containsExactly("1", "2", "3", "4", "5");
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_type = 'BASE TABLE' AND table_name NOT IN ('undo_log', 'flyway_schema_history') ORDER BY 1"))
                .containsExactlyElementsOf(TABLES).doesNotContainAnyElementsOf(UNUSED_TABLES);
        for (String table : TABLES) {
            assertThat(columns(table)).as(table)
                    .contains("id", "create_time", "update_time", "creator", "updater", "is_deleted", "tenant_id", "version")
                    .doesNotContain("create_date_time", "modify_date_time", "create_name", "modify_name", "is_delete",
                            "code", "label", "sorting");
        }
        assertThat(columns("ai_role_prompt")).contains("description");
        for (String table : TABLES.stream().filter(table -> !table.equals("ai_role_prompt")).toList()) {
            assertThat(columns(table)).as(table).doesNotContain("description");
        }
        for (String table : STORAGE_TABLES) {
            assertThat(dataType(table, "create_time")).as(table).isEqualTo("timestamp without time zone");
            assertThat(dataType(table, "update_time")).as(table).isEqualTo("timestamp without time zone");
        }
        assertThat(dataType("ai_storage_operation", "started_at")).isEqualTo("timestamp with time zone");
        assertThat(dataType("ai_storage_configuration", "credential_updated_at")).isEqualTo("timestamp with time zone");
        assertThat(number("SELECT count(*) FROM ai_storage_setting WHERE id = 'PLATFORM'")).as("存储设置初始行").isEqualTo(1);
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'ddl_history'")).isZero();
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'undo_log'")).isEqualTo(1);
    }

    @Test
    void storageAuditTimesKeepShanghaiWallClock() throws Exception {
        migrate("4");
        execute("INSERT INTO ai_storage_configuration(id, scope, platform, name, provider, endpoint, bucket, upload_enabled, key_id,"
                + " nonce, ciphertext, credential_revision, config_revision, credential_updated_at, create_time, update_time)"
                + " VALUES ('fixture-storage', 'PLATFORM', 'fixture-platform', 'fixture', 'MINIO', 'https://storage.invalid', 'fixture',"
                + " false, 'k', 'n', 'c', 1, 1, TIMESTAMPTZ '2026-11-15 10:00:00+08', TIMESTAMPTZ '2026-11-15 10:00:00+08',"
                + " TIMESTAMPTZ '2026-11-15 10:00:00+08')");
        migrate(null);
        assertThat(number("SELECT count(*) FROM ai_storage_configuration WHERE id = 'fixture-storage'"
                + " AND create_time = TIMESTAMP '2026-11-15 10:00:00' AND update_time = TIMESTAMP '2026-11-15 10:00:00'"))
                .isEqualTo(1);
    }

    @Test
    void chatMemoryDeleteStaysPhysical() throws Exception {
        migrate(null);
        execute("INSERT INTO ai_chat_memory(id, conversation_id, message_type, content) VALUES (900001, 'fixture-conversation', 'USER', 'synthetic')");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiChatMemoryRepository.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(new SingleConnectionDataSource(connection, true));
        try (var session = factory.getObject().openSession(true)) {
            session.getMapper(AiChatMemoryRepository.class).deleteMemoryIds(List.of(900001L));
        }
        assertThat(number("SELECT count(*) FROM ai_chat_memory WHERE id = 900001")).isZero();
    }

    @Test
    void dropUnusedTablesRefusesWhenATableStillHasRows() throws Exception {
        migrate("4");
        execute("INSERT INTO ai_integration_outbox(event_type, target_system, aggregate_type, aggregate_id, idempotency_key, payload_json, status)"
                + " VALUES ('fixture', 'fixture', 'fixture', 'fixture', 'fixture-key', '{}', 'PENDING')");
        assertThatThrownBy(() -> migrate(null)).isInstanceOf(FlywayException.class);
        assertThat(appliedVersions()).containsExactly("1", "2", "3", "4");
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'ai_integration_outbox'")).isEqualTo(1);
    }

    @Test
    void identityAllowsImportedIdsAndNewGeneratedRowsWithBooleanFields() throws Exception {
        migrate(null);
        execute("INSERT INTO ai_model_selection(id, config_key, provider) VALUES (900000, 'fixture-imported', 'google')");
        execute("INSERT INTO ai_model_selection(config_key, provider) VALUES ('fixture-generated', 'qwen')");
        assertThat(number("SELECT count(*) FROM ai_model_selection WHERE is_deleted = false")).isEqualTo(2);
        assertThat(number("SELECT id FROM ai_model_selection WHERE config_key = 'fixture-imported'")).isEqualTo(900000);
        assertThat(number("SELECT id FROM ai_model_selection WHERE config_key = 'fixture-generated'")).isPositive();
    }

    @Test
    void secondMigrationRunIsANoOpAndKeepsPostgresqlTypes() throws Exception {
        migrate(null);
        assertThat(migrate(null).migrationsExecuted).isZero();
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND column_name = 'is_deleted' AND data_type = 'boolean'"))
                .isEqualTo(10);
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND column_name = 'update_time' AND data_type = 'timestamp without time zone'"))
                .isEqualTo(10);
        assertThat(number("SELECT CASE WHEN 'Café' COLLATE fleet_general_ci = 'CAFE' THEN 1 ELSE 0 END")).isEqualTo(1);
        assertThat(number("SELECT CASE WHEN 'Café' COLLATE fleet_general_ci LIKE 'caf%' THEN 1 ELSE 0 END")).isEqualTo(1);
    }

    private String dataType(String table, String column) throws Exception {
        return strings("SELECT data_type FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = '" + table + "' AND column_name = '" + column + "'").get(0);
    }
}

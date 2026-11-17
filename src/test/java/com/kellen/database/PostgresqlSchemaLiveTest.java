package com.kellen.database;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kellen.agent.AiModelSelectionEntity;
import com.kellen.audit.ChatMessageEntity;
import com.kellen.file.AiFileObjectEntity;
import com.kellen.memory.AiChatMemoryRepository;
import com.kellen.memory.ChatMemoryEntity;
import com.kellen.memory.ChatMemorySummaryEntity;
import com.kellen.memory.PatientMemoryEntity;
import com.kellen.prompt.AiRolePromptEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;
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

    /** 继承 AiGovernanceEntity 的 7 张表；插入时自动填创建部门。 */
    private static final List<Class<?>> ENTITIES = List.of(ChatMemoryEntity.class, ChatMemorySummaryEntity.class,
            ChatMessageEntity.class, AiFileObjectEntity.class, AiModelSelectionEntity.class, PatientMemoryEntity.class,
            AiRolePromptEntity.class);

    @Test
    void migratesEmptySchemaToLatestVersion() throws Exception {
        assertThat(migrate(null).success).isTrue();
        assertThat(appliedVersions()).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_type = 'BASE TABLE' AND table_name NOT IN ('undo_log', 'flyway_schema_history') ORDER BY 1"))
                .containsExactlyElementsOf(TABLES).doesNotContainAnyElementsOf(UNUSED_TABLES);
        for (String table : TABLES) {
            assertThat(columns(table)).as(table)
                    .contains("id", "code", "description", "create_time", "create_by", "update_time", "update_by", "is_deleted",
                            "label", "sorting", "version", "tenant_id")
                    .doesNotContain("create_date_time", "modify_date_time", "create_name", "modify_name", "is_delete", "creator", "updater");
        }
        assertThat(strings("SELECT table_name FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND column_name = 'create_dept' AND data_type = 'character varying' AND character_maximum_length = 64"
                + " AND is_nullable = 'YES' ORDER BY 1")).containsExactlyElementsOf(TABLES);
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

    /** 每张表都必须有的全部基础字段；undo_log 与 Flyway 记录表是框架表，除外。 */
    private static final List<String> BASE_COLUMNS = List.of("id", "code", "description", "create_time", "create_by",
            "update_time", "update_by", "is_deleted", "label", "sorting", "version", "tenant_id", "create_dept");

    @Test
    void everyTableHasAllBaseColumns() throws Exception {
        migrate(null);
        List<String> tables = strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_type = 'BASE TABLE' AND table_name NOT IN ('undo_log', 'flyway_schema_history') ORDER BY 1");
        assertThat(tables).isNotEmpty();
        for (String table : tables) {
            assertThat(columns(table)).as(table).containsAll(BASE_COLUMNS)
                    .doesNotContain("creator", "updater", "create_name", "modify_name", "create_date_time", "modify_date_time");
        }
    }

    @Test
    void storageAuditTimesKeepShanghaiWallClock() throws Exception {
        migrate("3");
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
        migrate("3");
        execute("INSERT INTO ai_integration_outbox(event_type, target_system, aggregate_type, aggregate_id, idempotency_key, payload_json, status)"
                + " VALUES ('fixture', 'fixture', 'fixture', 'fixture', 'fixture-key', '{}', 'PENDING')");
        assertThatThrownBy(() -> migrate(null)).isInstanceOf(FlywayException.class);
        assertThat(appliedVersions()).containsExactly("1", "2", "3");
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'ai_integration_outbox'")).isEqualTo(1);
    }

    @Test
    void createDeptIsAddedWithoutDroppingAnyColumnOrRow() throws Exception {
        migrate("4");
        execute("INSERT INTO ai_chat_memory(id, conversation_id, message_type, content, label, sorting) VALUES (900002, 'fixture-conversation', 'USER', 'synthetic', 'kept', 7)");
        migrate(null);
        assertThat(appliedVersions()).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(number("SELECT count(*) FROM ai_chat_memory WHERE id = 900002 AND label = 'kept' AND sorting = 7"
                + " AND create_dept IS NULL")).isEqualTo(1);
        assertThat(strings("SELECT col_description(('" + schema + ".ai_chat_memory')::regclass, ordinal_position::int)"
                + " FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'ai_chat_memory' AND column_name = 'create_dept'")).containsExactly("创建部门ID");
    }

    @Test
    void everyEntityFieldMapsToAColumnOfItsTable() throws Exception {
        migrate(null);
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "entity-columns");
        for (Class<?> entity : ENTITIES) {
            TableInfo info = TableInfoHelper.initTableInfo(assistant, entity);
            List<String> mapped = new ArrayList<>(List.of(info.getKeyColumn()));
            info.getFieldList().forEach(field -> mapped.add(field.getColumn()));
            assertThat(columns(info.getTableName())).as(entity.getSimpleName()).containsAll(mapped);
        }
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

package com.kellen.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL 实际执行、重复建表及布尔/时间/字符比较回归，不连接真实业务库。 */
@EnabledIfEnvironmentVariable(named = "FLEET_PG_TEST_URL", matches = ".+")
class PostgresqlSchemaLiveTest extends PostgresqlSchemaFixture {

    @Test
    void identityAllowsImportedIdsAndNewGeneratedRowsWithBooleanFields() throws Exception {
        script("db/postgresql/ai-schema.sql");
        execute("INSERT INTO ai_model_selection(id, config_key, provider) VALUES (900000, 'fixture-imported', 'google')");
        execute("INSERT INTO ai_model_selection(config_key, provider) VALUES ('fixture-generated', 'qwen')");
        assertThat(number("SELECT count(*) FROM ai_model_selection WHERE is_delete = false")).isEqualTo(2);
        assertThat(number("SELECT id FROM ai_model_selection WHERE config_key = 'fixture-imported'")).isEqualTo(900000);
        assertThat(number("SELECT id FROM ai_model_selection WHERE config_key = 'fixture-generated'")).isPositive();
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND is_identity = 'YES'"))
                .isEqualTo(15);
        execute("INSERT INTO ai_wechat_session(user_id, token_hash, expires_at) VALUES (900000, 'synthetic-fixture', CURRENT_TIMESTAMP + INTERVAL '1 day')");
        assertThat(number("SELECT count(*) FROM ai_wechat_session WHERE revoked = false")).isEqualTo(1);
    }
    @Test
    void createsTheCurrentBusinessSchemaTwiceWithoutMysqlSyntax() throws Exception {
        script("db/postgresql/ai-schema.sql");
        script("db/postgresql/ai-schema.sql");
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema + "' AND table_type = 'BASE TABLE'"))
                .isEqualTo(15);
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND column_name = 'is_delete' AND data_type = 'boolean'"))
                .isEqualTo(15);
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema + "' AND column_name = 'modify_date_time' AND data_type = 'timestamp without time zone'"))
                .isEqualTo(15);
        assertThat(number("SELECT CASE WHEN 'Café' COLLATE public.fleet_general_ci = 'CAFE' THEN 1 ELSE 0 END")).isEqualTo(1);
        assertThat(number("SELECT CASE WHEN 'Café' COLLATE public.fleet_general_ci LIKE 'caf%' THEN 1 ELSE 0 END")).isEqualTo(1);
    }
}

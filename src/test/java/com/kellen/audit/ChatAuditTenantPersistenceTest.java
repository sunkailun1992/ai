package com.kellen.audit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kellen.agent.AiAgentClient;
import com.kellen.auth.AiRequestIdentity;
import com.kellen.config.mybatis.MyMetaObjectHandler;
import com.kellen.conversation.AiChatRequest;
import com.kellen.conversation.AiDialogueService;
import com.kellen.conversation.ChatConversationMessageService;
import com.kellen.conversation.ChatConversationService;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.rag.RagRetrievalClient;
import com.kellen.security.config.TenantProperties;
import com.kellen.utils.context.TenantContextHolder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** 真实 MyBatis INSERT：保留旧库默认租户 1，关闭插件也必须写入已验证调用租户。 */
@SpringBootTest(classes = ChatAuditTenantPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.name=audit-tenant-isolated", "tenant.enabled=false",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false",
                "test.audit-tenant.fixture=true"})
class ChatAuditTenantPersistenceTest {
    @Autowired private AiDialogueService dialogue;
    @Autowired private AiAgentClient agent;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantProperties tenantProperties;
    @Autowired private ChatAuditService audit;
    @Autowired private ChatMessageRepository repository;
    private Scheduler worker;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_chat_message");
        reset(agent);
        worker = Schedulers.newSingle("audit-tenant-fixture");
        Mono.fromRunnable(() -> {
            TenantContextHolder.setTenantId("prior-worker");
            TenantContextHolder.ignore();
        }).subscribeOn(worker).block(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        worker.dispose();
        TenantContextHolder.clear();
        TenantContextHolder.clearIgnore();
    }

    enum Terminal { SUCCESS, FAILURE, CANCEL }

    @Test
    void missingIdentityAndInvalidTenantFailBeforeAnInsertCanUseTheDefault() {
        TenantContextHolder.setTenantId("ambient-must-not-be-used");
        assertThatThrownBy(() -> audit.recordSuccess(null, "fixture", "chat_stream", "fixture-agent",
                "question", "prompt", "answer", ""))
                .isInstanceOf(SecurityException.class).hasMessage("AI_IDENTITY_REQUIRED");
        assertThatThrownBy(() -> audit.recordFailure(null, "fixture", "chat_stream", "fixture-agent",
                "question", "prompt", "failure", ""))
                .isInstanceOf(SecurityException.class).hasMessage("AI_IDENTITY_REQUIRED");
        for (String tenant : java.util.Arrays.asList(null, "", " ", "\t", "x".repeat(65))) {
            assertThatThrownBy(() -> ChatMessageEntity.success(tenant, "fixture", "chat_stream", "fixture-agent",
                    "question", "prompt", "answer", ""))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_AUDIT_TENANT_INVALID");
            assertThatThrownBy(() -> ChatMessageEntity.failure(tenant, "fixture", "chat_stream", "fixture-agent",
                    "question", "prompt", "failure", ""))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_AUDIT_TENANT_INVALID");
        }
        var overlong = new AiRequestIdentity("verified-user", "x".repeat(65), "synthetic-token");
        assertThatThrownBy(() -> audit.recordSuccess(overlong, "fixture", "chat_stream", "fixture-agent",
                "question", "prompt", "answer", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_AUDIT_TENANT_INVALID");
        assertThatThrownBy(() -> repository.save(new ChatMessageEntity()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chat_message", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Terminal.class)
    void verifiedTenantSurvivesEveryAsyncTerminalWithTenantPluginDisabled(Terminal terminal) {
        assertThat(tenantProperties.isEnabled()).isFalse();
        // 同一执行线程复用两个租户；来源分区和 ambient 上下文均不能替代认证租户。
        for (String tenant : List.of("tenant-a", "0002-tenant-b")) {
            var identity = new AiRequestIdentity("verified-user", tenant, "synthetic-token");
            var request = new AiChatRequest("same-client-conversation", null, null, "source-other",
                    "synthetic-question", null, null, List.of(), List.of());
            Flux<String> output = switch (terminal) {
                case SUCCESS -> Flux.just("synthetic-answer");
                case FAILURE -> Flux.error(new IllegalStateException("synthetic-failure"));
                case CANCEL -> Flux.never();
            };
            when(agent.patientAnalysisStream(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any())).thenReturn(output.publishOn(worker));
            TenantContextHolder.setTenantId("untrusted-entry");
            TenantContextHolder.ignore();
            Flux<String> stream = dialogue.stream(request, null, identity);
            switch (terminal) {
                case SUCCESS -> assertThat(stream.collectList().block(Duration.ofSeconds(2)))
                        .containsExactly("synthetic-answer");
                case FAILURE -> assertThatThrownBy(() -> stream.collectList().block(Duration.ofSeconds(2)))
                        .isInstanceOf(IllegalStateException.class).hasMessage("synthetic-failure");
                case CANCEL -> {
                    var subscription = stream.subscribe();
                    Mono.fromRunnable(subscription::dispose).subscribeOn(worker).block(Duration.ofSeconds(2));
                }
            }
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("untrusted-entry");
            assertThat(TenantContextHolder.isIgnore()).isTrue();
            Mono.fromRunnable(() -> {
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("prior-worker");
                assertThat(TenantContextHolder.isIgnore()).isTrue();
            }).subscribeOn(worker).block(Duration.ofSeconds(2));

            var row = jdbc.queryForMap("SELECT tenant_id, success, assistant_content, error_message, "
                            + "message_type, model, user_message, system_prompt FROM ai_chat_message WHERE conversation_id = ?",
                    identity.conversationKey(request.conversationId()));
            assertThat(row.get("tenant_id")).isEqualTo(tenant);
            assertThat(row.get("message_type")).isEqualTo("chat_stream");
            assertThat(row.get("model")).isEqualTo("patient-analysis-agent");
            assertThat(row.get("user_message")).isEqualTo("synthetic-question");
            assertThat(row.get("system_prompt")).isEqualTo("synthetic-prompt");
            assertThat(row.get("success")).isEqualTo(terminal == Terminal.SUCCESS);
            assertThat(row.get("assistant_content")).isEqualTo(terminal == Terminal.SUCCESS ? "synthetic-answer" : null);
            assertThat(row.get("error_message")).isEqualTo(switch (terminal) {
                case SUCCESS -> null;
                case FAILURE -> "synthetic-failure";
                case CANCEL -> "stream cancelled by client";
            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chat_message", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chat_message WHERE tenant_id = '1'", Integer.class)).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.audit-tenant.fixture", havingValue = "true")
    @EnableConfigurationProperties(TenantProperties.class)
    @Import({ChatAuditService.class, AiDialogueService.class})
    static class Config {
        @Bean DataSource dataSource() {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:audit_tenant_" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
            new JdbcTemplate(dataSource).execute("""
                    CREATE TABLE ai_chat_message (
                        id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        tenant_id varchar(64) NOT NULL DEFAULT '1',
                        conversation_id varchar(128) NOT NULL, message_type varchar(32) NOT NULL,
                        model varchar(128) NOT NULL, user_message text NOT NULL,
                        system_prompt text, assistant_content text, media_summary text,
                        success boolean NOT NULL DEFAULT false, error_message varchar(1024),
                        created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modify_date_time timestamp DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            return dataSource;
        }
        @Bean JdbcTemplate jdbc(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource, TenantProperties tenant) throws Exception {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(ChatMessageRepository.class);
            var global = new GlobalConfig().setMetaObjectHandler(new MyMetaObjectHandler(tenant));
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
            return factory.getObject();
        }
        @Bean ChatMessageRepository repository(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(ChatMessageRepository.class);
        }
        @Bean AiAgentClient agent() { return mock(AiAgentClient.class); }
        @Bean RagRetrievalClient rag() { return mock(RagRetrievalClient.class); }
        @Bean DbChatMemoryService memory() {
            var memory = mock(DbChatMemoryService.class);
            when(memory.shortMemoryContext(any())).thenReturn("");
            return memory;
        }
        @Bean PatientMemoryService patient() {
            var patient = mock(PatientMemoryService.class);
            when(patient.patientContext(any(), any())).thenReturn("");
            return patient;
        }
        @Bean MediaInputValidator media() {
            var media = mock(MediaInputValidator.class);
            when(media.validateUrlMedia(any())).thenReturn(List.of());
            when(media.summarize(any())).thenReturn("");
            return media;
        }
        @Bean AiRolePromptService prompt() {
            var prompt = mock(AiRolePromptService.class);
            when(prompt.resolve(any(), any())).thenReturn(
                    new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic-prompt"));
            return prompt;
        }
        @Bean ChatConversationService conversations() { return mock(ChatConversationService.class); }
        @Bean ChatConversationMessageService messages() { return mock(ChatConversationMessageService.class); }
        @Bean AiModelCallObserver observer() { return mock(AiModelCallObserver.class); }
    }
}

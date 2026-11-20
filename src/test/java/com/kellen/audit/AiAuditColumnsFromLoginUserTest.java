package com.kellen.audit;

import com.ai.support.AgentStub;
import com.ai.support.AgentTokenFixture;
import com.ai.support.FixtureUsers;
import com.ai.support.ResourceServerFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.kellen.agent.AiAgentClient;
import com.kellen.agent.AiAgentRegistryClient;
import com.kellen.agent.AiModelSelectionService;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.config.AiProperties;
import com.kellen.config.mybatis.MyMetaObjectHandler;
import com.kellen.conversation.AiDialogueController;
import com.kellen.conversation.AiDialogueService;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.AiChatMemoryRepository;
import com.kellen.memory.ChatMemorySummaryRepository;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.rag.RagRetrievalClient;
import com.kellen.security.config.TenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计列取登录用户：带 Keycloak 访问令牌发起对话，资源服务器从 user 服务取到用户与部门，
 * 对话审计（ai_chat_message）与模型记忆（ai_chat_memory）的新行记下创建人、更新人与创建部门。
 * 真实 HTTP、资源服务器、对话编排、Agent 客户端（收尾在 Reactor Netty 线程上写库）与 MyBatis-Plus 填充器，H2 存储。
 */
@SpringBootTest(classes = AiAuditColumnsFromLoginUserTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=audit-login-user-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "test.audit-login-user.fixture=true", ResourceServerFixture.ISSUER})
class AiAuditColumnsFromLoginUserTest {

    static final AgentStub AGENT = AgentStub.start();

    @LocalServerPort
    int port;

    @Autowired
    FixtureUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void agent(DynamicPropertyRegistry registry) {
        registry.add("test.agent.stream-url", AGENT::streamUrl);
    }

    @AfterAll
    static void stop() {
        AGENT.close();
    }

    @Test
    void chatAuditRowsRecordLoginUserAndDept() throws Exception {
        users.put("u1", "t1", "d1");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/chat"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Authorization", ResourceServerFixture.bearer("u1"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"audit-fixture\",\"message\":\"synthetic-question\"}")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data:synthetic-answer").doesNotContain("event:error");

        Map<String, Object> audit = jdbc.queryForMap("SELECT create_by, update_by, create_dept, tenant_id, success FROM ai_chat_message");
        assertThat(audit).containsEntry("CREATE_BY", "u1").containsEntry("UPDATE_BY", "u1")
                .containsEntry("CREATE_DEPT", "d1").containsEntry("TENANT_ID", "t1");
        List<Map<String, Object>> memory = jdbc.queryForList(
                "SELECT message_type, create_by, update_by, create_dept FROM ai_chat_memory ORDER BY id");
        assertThat(memory).extracting(row -> row.get("MESSAGE_TYPE")).containsExactly("user", "assistant");
        assertThat(memory).allSatisfy(row -> assertThat(Arrays.asList(row.get("CREATE_BY"), row.get("UPDATE_BY"), row.get("CREATE_DEPT")))
                .containsExactly("u1", "u1", "d1"));
    }

    /**
     * 测试上下文：真实控制器、身份解析、对话编排、审计与记忆服务；提示词、附件、患者记忆与 RAG 换成替身。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.audit-login-user.fixture", havingValue = "true")
    @EnableConfigurationProperties(TenantProperties.class)
    @Import({ResourceServerFixture.class, AiDialogueController.class, AiRequestIdentityResolver.class,
            AiDialogueService.class, ChatAuditService.class})
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class})
    static class Config {

        @Bean
        DataSource dataSource() throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:audit_login_user_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String table : List.of("ai_chat_memory", "ai_chat_memory_summary", "ai_chat_message")) {
                int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
                jdbc.execute(schema.substring(start, schema.indexOf(";", start) + 1));
            }
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbc(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource, TenantProperties tenant) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var global = GlobalConfigUtils.defaults().setMetaObjectHandler(new MyMetaObjectHandler(tenant));
            GlobalConfigUtils.setGlobalConfig(configuration, global);
            configuration.addMapper(ChatMessageRepository.class);
            configuration.addMapper(AiChatMemoryRepository.class);
            configuration.addMapper(ChatMemorySummaryRepository.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(global);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ChatMessageRepository chatMessageRepository(SqlSessionTemplate sessions) {
            return sessions.getMapper(ChatMessageRepository.class);
        }

        @Bean
        AiChatMemoryRepository chatMemoryRepository(SqlSessionTemplate sessions) {
            return sessions.getMapper(AiChatMemoryRepository.class);
        }

        @Bean
        ChatMemorySummaryRepository chatMemorySummaryRepository(SqlSessionTemplate sessions) {
            return sessions.getMapper(ChatMemorySummaryRepository.class);
        }

        @Bean
        DbChatMemoryService dbChatMemoryService(AiChatMemoryRepository memory, ChatMemorySummaryRepository summaries) {
            return new DbChatMemoryService(memory, summaries, new AiProperties(new AiProperties.DialogueProperties(
                    "patient-analysis", "fixture", 4, 20971520, List.of("http", "https"), List.of("image/*", "text/plain"),
                    new AiProperties.MemoryProperties(20, 7))));
        }

        @Bean
        AiAgentClient aiAgentClient(Environment environment) {
            AiAgentRegistryClient registry = operation -> Optional.of(new AiAgentRegistryClient.AgentEndpoint(
                    environment.getRequiredProperty("test.agent.stream-url"), "http", "fixture"));
            AiModelSelectionService selection = mock(AiModelSelectionService.class);
            when(selection.currentProvider()).thenReturn("qwen");
            return new AiAgentClient(new AiAgentClientProperties(Duration.ofSeconds(5), "qwen"), registry, selection,
                    AgentTokenFixture.fixedToken("fixture-agent-token"), environment);
        }

        @Bean
        AiModelCallObserver modelCallObserver() {
            return new AiModelCallObserver(new SimpleMeterRegistry());
        }

        @Bean
        MediaInputValidator mediaInputValidator() {
            MediaInputValidator media = mock(MediaInputValidator.class);
            when(media.validateUrlMedia(any())).thenReturn(List.of());
            when(media.summarize(any())).thenReturn("");
            return media;
        }

        @Bean
        AiRolePromptService rolePromptService() {
            AiRolePromptService prompt = mock(AiRolePromptService.class);
            when(prompt.resolve(any(), any())).thenReturn(new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic-prompt"));
            return prompt;
        }

        @Bean
        PatientMemoryService patientMemoryService() {
            PatientMemoryService patient = mock(PatientMemoryService.class);
            when(patient.patientContext(any(), any())).thenReturn("");
            return patient;
        }

        @Bean
        RagRetrievalClient ragRetrievalClient() {
            return mock(RagRetrievalClient.class);
        }
    }
}

package com.kellen.agent;

import com.ai.support.FixtureUsers;
import com.ai.support.ResourceServerFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.controlplane.AiControlPlaneAdministrator;
import com.kellen.exception.ApiExceptionHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认模型是全局配置：真实 HTTP、资源服务器过滤链、全局 advice 与 MyBatis-Plus/H2 持久化，只放行平台超级管理员且有 user:auth:manage；
 * 角色与权限码只来自 user 服务给的业务上下文，请求头伪造不了。只替换令牌解码、业务上下文与 Agent 边界。
 */
@SpringBootTest(classes = AiModelSelectionAuthorizationHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=model-selection-authorization-isolated",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false", "test.model-selection.authorization.fixture=true",
                ResourceServerFixture.ISSUER})
class AiModelSelectionAuthorizationHttpTest {
    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired AiModelSelectionService service;
    @Autowired FixtureUsers users;
    private JdbcTemplate jdbc;

    @BeforeEach void resetFixture() {
        users.put("platform-admin", "platform", "dept-p", "ROLE_PLATFORM_SUPER_ADMIN", "user:auth:manage");
        users.put("tenant-admin", "tenant-a", "dept-a", "ROLE_TENANT_ADMIN", "user:auth:manage");
        users.put("platform-without-permission", "platform", null, "ROLE_PLATFORM_SUPER_ADMIN");
        users.put("plain-user", "tenant-a", null, "ROLE_NONE");
        users.put("unprefixed-admin", "platform", null, "PLATFORM_SUPER_ADMIN", "user:auth:manage");
        users.putDisabled("disabled-admin", "platform");
        users.failFor("admin-while-user-service-down");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE ai_model_selection SET provider='google', updated_at=TIMESTAMP '2000-01-01 00:00:00'");
        service.initialize();
    }

    @Test void anonymousAndInvalidTokensCannotWrite() throws Exception {
        assertRejected(null, false, 401);
        assertRejected("Bearer invalid", false, 401);
        assertRejected(ResourceServerFixture.bearer("unknown-user"), false, 401);
    }

    @Test void normalUserCannotChangeTheGlobalProvider() throws Exception {
        assertRejected(ResourceServerFixture.bearer("plain-user"), false, 403);
    }

    @Test void tenantAdminEvenWithManagePermissionCannotChangeTheGlobalProvider() throws Exception {
        assertRejected(ResourceServerFixture.bearer("tenant-admin"), false, 403);
    }

    @Test void platformAdminWithoutPermissionCannotWrite() throws Exception {
        assertRejected(ResourceServerFixture.bearer("platform-without-permission"), false, 403);
    }

    @Test void forgedHeadersCannotElevateTheVerifiedContext() throws Exception {
        assertRejected(ResourceServerFixture.bearer("plain-user"), true, 403);
    }

    @Test void adminTypeWithoutTheRolePrefixCannotStandInForTheRole() throws Exception {
        assertRejected(ResourceServerFixture.bearer("unprefixed-admin"), false, 403);
    }

    @Test void disabledAdministratorAndUserServiceFailureFailClosedWithoutPersistence() throws Exception {
        assertRejected(ResourceServerFixture.bearer("disabled-admin"), false, 403);
        assertRejected(ResourceServerFixture.bearer("admin-while-user-service-down"), false, 503);
    }

    @Test void activePlatformAdminWithPermissionCanPersistEvenTheCurrentProvider() throws Exception {
        var response = put(ResourceServerFixture.bearer("platform-admin"), false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT provider FROM ai_model_selection", String.class)).isEqualTo("google");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_model_selection", java.sql.Timestamp.class).toLocalDateTime().getYear()).isGreaterThan(2000);
        assertThat(jdbc.queryForObject("SELECT update_by FROM ai_model_selection", String.class)).isEqualTo("platform-admin");
        assertThat(response.body()).contains("\"provider\":\"google\"").doesNotContain("Bearer", "accessToken",
                ResourceServerFixture.accessToken("platform-admin"));
    }

    @Test void authorizedUnsupportedProviderStillCannotPersist() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/model-selection"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", ResourceServerFixture.bearer("platform-admin"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"provider\":\"other\"}"));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertUnchanged();
    }

    private void assertRejected(String authorization, boolean forgedHeaders, int status) throws Exception {
        var response = put(authorization, forgedHeaders);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("synthetic-private", "platform-admin", "tenant-admin", "plain-user",
                "Exception", "Bearer ", "synthetic-access-token");
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT provider FROM ai_model_selection", String.class)).isEqualTo("google");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_model_selection", java.sql.Timestamp.class).toLocalDateTime().getYear()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_selection", Integer.class)).isEqualTo(1);
        assertThat(service.currentProvider()).isEqualTo("google");
    }

    private HttpResponse<String> put(String authorization, boolean forgedHeaders) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/model-selection"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        if (forgedHeaders) request.header("X-Admin-Type", "PLATFORM_SUPER_ADMIN")
                .header("X-Permissions", "user:auth:manage").header("X-User-Id", "platform-admin");
        return HttpClient.newHttpClient().send(request.PUT(HttpRequest.BodyPublishers.ofString("{\"provider\":\"google\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.model-selection.authorization.fixture", havingValue = "true")
    @Import({ResourceServerFixture.class, AiModelSelectionController.class, AiModelSelectionService.class, ApiExceptionHandler.class,
            AiControlPlaneAdministrator.class, AiRequestIdentityResolver.class})
    @ImportAutoConfiguration({
            org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
            org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
            org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
            org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
            org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class})
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:model_selection_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            var jdbc = new JdbcTemplate(dataSource);
            String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS ai_model_selection (");
            jdbc.execute(schema.substring(start, schema.indexOf(";", start) + 1));
            jdbc.update("INSERT INTO ai_model_selection(config_key,provider) VALUES('default-agent-provider','google')");
            return dataSource;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            var global = com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults()
                    .setMetaObjectHandler(new com.kellen.config.mybatis.MyMetaObjectHandler(new com.kellen.security.config.TenantProperties()));
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config, global);
            config.addMapper(AiModelSelectionRepository.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(config);
            factory.setGlobalConfig(global);
            return factory.getObject();
        }
        @Bean AiModelSelectionRepository repository(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(AiModelSelectionRepository.class); }
        @Bean AiAgentClientProperties properties() { return new AiAgentClientProperties(Duration.ofSeconds(5), "google"); }
        @Bean AiAgentClient agent() {
            var client = mock(AiAgentClient.class);
            when(client.model(any())).thenReturn(new AiAgentClient.AgentModelResult("google", "fixture-model", List.of("google", "deepseek", "qwen")));
            return client;
        }
    }
}

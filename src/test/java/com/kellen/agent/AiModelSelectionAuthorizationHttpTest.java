package com.kellen.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.exception.ApiExceptionHandler;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.security.config.SecurityAuthConfig;
import com.kellen.utils.auth.JwtKeyConfig;
import com.kellen.utils.auth.JwtUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 HTTP、安全过滤器、全局 advice 与 MyBatis-Plus/H2 持久化；仅替换 User RPC 和 Agent 边界。 */
@SpringBootTest(classes = AiModelSelectionAuthorizationHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=model-selection-authorization-isolated",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false", "security.auth.enabled=true",
                "security.auth.header-enabled=false", "test.model-selection.authorization.fixture=true"})
class AiModelSelectionAuthorizationHttpTest {
    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired AiModelSelectionService service;
    @Autowired SessionBoundary sessions;
    private JdbcTemplate jdbc;

    @DynamicPropertySource static void syntheticKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        registry.add("security.jwt.secret", () -> encoded);
    }

    @BeforeEach void resetFixture() {
        sessions.mode = "active"; sessions.calls = 0;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE ai_model_selection SET provider='google', updated_at=TIMESTAMP '2000-01-01 00:00:00'");
        service.initialize();
    }

    @Test void anonymousAndInvalidSignatureCannotWrite() throws Exception {
        assertThat(put(null, false).statusCode()).isIn(401, 403);
        assertThat(put(token("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")) + ".invalid", false).statusCode()).isIn(401, 403);
        assertUnchanged();
    }

    @Test void normalUserCannotChangeTheGlobalProvider() throws Exception {
        assertRejected(token("NONE", List.of()), false, 403);
    }

    @Test void tenantAdminEvenWithManagePermissionCannotChangeTheGlobalProvider() throws Exception {
        assertRejected(token("TENANT_ADMIN", List.of("user:auth:manage")), false, 403);
    }

    @Test void platformAdminWithoutPermissionCannotWrite() throws Exception {
        assertRejected(token("PLATFORM_SUPER_ADMIN", List.of()), false, 403);
    }

    @Test void forgedHeadersCannotElevateVerifiedClaims() throws Exception {
        assertRejected(token("NONE", List.of()), true, 403);
    }

    @Test void permissionStringOrRoleCannotStandInForVerifiedPermissionList() throws Exception {
        assertRejected(token("PLATFORM_SUPER_ADMIN", "user:auth:manage"), false, 403);
    }

    @Test void revokedTokenAndUserRpcFailureFailClosedWithoutPersistence() throws Exception {
        String token = token("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage"));
        for (String mode : List.of("revoked", "rpc-failure")) {
            sessions.mode = mode; assertRejected(token, false, 401);
        }
    }

    @Test void rpcIdentityMustMatchTheSameSignedToken() throws Exception {
        for (String mode : List.of("different-user", "different-tenant")) {
            sessions.mode = mode;
            assertRejected(token("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")), false, 401);
        }
    }

    @Test void activePlatformAdminWithPermissionCanPersistEvenTheCurrentProvider() throws Exception {
        var response = put(token("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")), false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT provider FROM ai_model_selection", String.class)).isEqualTo("google");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_model_selection", java.sql.Timestamp.class).toLocalDateTime().getYear()).isGreaterThan(2000);
        assertThat(sessions.calls).isEqualTo(1);
        assertThat(response.body()).contains("\"provider\":\"google\"").doesNotContain("Bearer", "accessToken");
    }

    @Test void authorizedUnsupportedProviderStillCannotPersist() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/model-selection"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"provider\":\"other\"}"));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertUnchanged();
    }

    private String token(String adminType, Object permissions) {
        return JwtUtils.createJwt(UUID.randomUUID().toString(), "fixture-user", Map.of(
                "userId", "fixture-user", "tenantId", "fixture-tenant", "adminType", adminType,
                "permissions", permissions, "roles", List.of("user:auth:manage")));
    }
    private void assertRejected(String token, boolean forgedHeaders, int status) throws Exception {
        var response = put(token, forgedHeaders);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("synthetic-private", "fixture-user", "fixture-tenant", "Exception", "Bearer");
        assertUnchanged();
    }
    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT provider FROM ai_model_selection", String.class)).isEqualTo("google");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ai_model_selection", java.sql.Timestamp.class).toLocalDateTime().getYear()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_selection", Integer.class)).isEqualTo(1);
        assertThat(service.currentProvider()).isEqualTo("google");
    }
    private HttpResponse<String> put(String token, boolean forgedHeaders) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/model-selection"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (forgedHeaders) request.header("X-Admin-Type", "PLATFORM_SUPER_ADMIN")
                .header("X-Permissions", "user:auth:manage").header("X-Tenant-Id", "forged-tenant")
                .header("X-User-Id", "forged-user");
        return HttpClient.newHttpClient().send(request.PUT(HttpRequest.BodyPublishers.ofString("{\"provider\":\"google\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    static class SessionBoundary {
        volatile String mode = "active";
        volatile int calls;
        AuthSessionIdentityRpcDTO resolve(String token) {
            calls++;
            if ("revoked".equals(mode)) throw new SecurityException("synthetic-private-revoked");
            if ("rpc-failure".equals(mode)) throw new IllegalStateException("synthetic-private-rpc-address");
            var claims = JwtUtils.parseJwt(token);
            var identity = new AuthSessionIdentityRpcDTO();
            identity.setUserId("different-user".equals(mode) ? "other-user" : claims.get("userId", String.class));
            identity.setTenantId("different-tenant".equals(mode) ? "other-tenant" : claims.get("tenantId", String.class));
            return identity;
        }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.model-selection.authorization.fixture", havingValue = "true")
    @Import({AiModelSelectionController.class, AiModelSelectionService.class, ApiExceptionHandler.class, SecurityAuthConfig.class})
    @ImportAutoConfiguration({
            org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
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
            config.addMapper(AiModelSelectionRepository.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean AiModelSelectionRepository repository(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(AiModelSelectionRepository.class); }
        @Bean JwtKeyConfig jwtKeyConfig(Environment environment) { return new JwtKeyConfig(environment); }
        @Bean SessionBoundary sessions() { return new SessionBoundary(); }
        @Bean AiRequestIdentityResolver identities(SessionBoundary sessions) { return new AiRequestIdentityResolver(sessions::resolve); }
        @Bean AiAgentClientProperties properties() { return new AiAgentClientProperties(Duration.ofSeconds(5), "google"); }
        @Bean AiAgentClient agent() {
            var client = mock(AiAgentClient.class);
            when(client.model(any())).thenReturn(new AiAgentClient.AgentModelResult("google", "fixture-model", List.of("google", "deepseek", "qwen")));
            return client;
        }
    }
}

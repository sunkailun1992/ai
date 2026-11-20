package com.kellen.auth;

import com.ai.support.FixtureUsers;
import com.ai.support.ResourceServerFixture;
import com.ai.support.TestIdentities;
import com.kellen.conversation.AiChatResponse;
import com.kellen.conversation.AiDialogueController;
import com.kellen.conversation.AiDialogueService;
import com.kellen.memory.ChatMemoryMessageDto;
import com.kellen.utils.context.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
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
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对话与记忆接口的身份只来自资源服务器：带 Keycloak 访问令牌、user 服务给出业务上下文的用户才进得来；
 * 匿名、无效令牌、伪造的身份请求头、停用用户与选别人的租户都进不来。真实过滤链，令牌解码与业务上下文是替身。
 */
@SpringBootTest(classes = AiIdentityHttpTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.identity.fixture=true", "spring.config.name=isolated-ai-identity", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                ResourceServerFixture.ISSUER})
class AiIdentityHttpTest {
    @LocalServerPort int port;
    @Autowired FixtureUsers users;

    @BeforeEach void users() {
        users.put("user-a", "tenant-a", "dept-a");
        users.putDisabled("user-disabled", "tenant-a");
    }

    HttpResponse<String> send(String authorization, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/chat"))
                .header("Content-Type", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        if (headers.length > 0) request.headers(headers);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
                        "{\"conversationId\":\"client-id\",\"message\":\"synthetic\",\"tenantId\":\"forged\",\"userId\":\"forged\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void anonymousForgedHeadersAndInvalidTokensCannotOpenScopedChat() throws Exception {
        assertThat(send(null, "X-User-Id", "user-a", "X-Tenant-Id", "tenant-a").statusCode()).isEqualTo(401);
        assertThat(send("Bearer invalid").statusCode()).isEqualTo(401);
        assertThat(send("Bearer opaque-wechat-session").statusCode()).isEqualTo(401);
        assertThat(send(ResourceServerFixture.bearer("unknown-user")).statusCode()).isEqualTo(401);
    }

    @Test void disabledUsersAndForeignTenantsAreForbidden() throws Exception {
        assertThat(send(ResourceServerFixture.bearer("user-disabled")).statusCode()).isEqualTo(403);
        assertThat(send(ResourceServerFixture.bearer("user-a"), "X-Tenant-Id", "tenant-other").statusCode()).isEqualTo(403);
    }

    @Test void onlyTheVerifiedIdentityReachesDialogue() throws Exception {
        var response = send(ResourceServerFixture.bearer("user-a"), "X-User-Id", "forged", "X-Admin-Type", "PLATFORM_SUPER_ADMIN");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("verified");
        assertThat(response.body()).doesNotContain(ResourceServerFixture.accessToken("user-a"), "tenant-a", "user-a");
    }

    @Test void userMemoryReadsAndClearStayNamespacedAndRejectAnonymousCalls() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String method : List.of("GET", "DELETE")) {
            var uri = URI.create("http://127.0.0.1:" + port + "/api/ai/memory/client-id");
            var request = HttpRequest.newBuilder(uri).header("Authorization", ResourceServerFixture.bearer("user-a"))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).doesNotContain(ResourceServerFixture.accessToken("user-a"), "tenant-a", "user-a", "fleet_");
            var anonymous = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(anonymous, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.identity.fixture", havingValue = "true")
    @Import({ResourceServerFixture.class, AiDialogueController.class, AiRequestIdentityResolver.class})
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class})
    static class Config {
        @Bean AiDialogueService dialogue() {
            var service = mock(AiDialogueService.class);
            when(service.stream(any(), any())).thenAnswer(call -> {
                AiRequestIdentity identity = call.getArgument(1);
                assertThat(identity.userId()).isEqualTo("user-a");
                assertThat(identity.tenantId()).isEqualTo("tenant-a");
                assertThat(identity.deptId()).isEqualTo("dept-a");
                return Flux.just("verified");
            });
            String key = TestIdentities.of("user-a", "tenant-a").conversationKey("client-id");
            when(service.memoryMessages(any())).thenAnswer(call -> {
                assertThat((String) call.getArgument(0)).isEqualTo(key);
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-a");
                return List.of(new ChatMemoryMessageDto("assistant", "synthetic-memory", LocalDateTime.now()));
            });
            when(service.clearMemory(any())).thenAnswer(call -> {
                assertThat((String) call.getArgument(0)).isEqualTo(key);
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-a");
                return AiChatResponse.ok("patient-analysis-agent", "cleared");
            });
            return service;
        }
    }
}

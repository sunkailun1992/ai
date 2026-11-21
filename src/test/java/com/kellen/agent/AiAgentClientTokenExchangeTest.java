package com.kellen.agent;

import com.ai.support.AgentStub;
import com.ai.support.FixtureUsers;
import com.ai.support.ResourceServerFixture;
import com.kellen.audit.ChatAuditService;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.conversation.AiDialogueController;
import com.kellen.conversation.AiDialogueService;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.rag.RagRetrievalClient;
import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.FleetAuthenticationToken;
import com.kellen.security.oauth2.SecurityContextResult;
import com.kellen.security.oauth2.client.FleetOAuth2ClientAutoConfiguration;
import com.kellen.security.oauth2.client.FleetOAuth2Clients;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ai 调 ai-agent 走 RFC 8693 令牌交换：真实资源服务器、真实 OAuth2 客户端自动配置与 utils 的出站过滤器，
 * Keycloak 令牌端点与 ai-agent 都是本机回环替身。没换到令牌（Keycloak 拒绝或超时）时绝不调用 ai-agent。
 */
@SpringBootTest(classes = AiAgentClientTokenExchangeTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=agent-token-exchange-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "test.agent.token-exchange.fixture=true", ResourceServerFixture.ISSUER,
                "spring.security.oauth2.client.registration.github-ai-agent.provider=keycloak",
                "spring.security.oauth2.client.registration.github-ai-agent.client-id=github-ai",
                "spring.security.oauth2.client.registration.github-ai-agent.client-secret=fixture-ai-secret",
                "spring.security.oauth2.client.registration.github-ai-agent.authorization-grant-type=urn:ietf:params:oauth:grant-type:token-exchange",
                "fleet.security.oauth2.token-exchange.github-ai-agent.audience=github-ai-agent",
                "fleet.security.oauth2.token-endpoint.read-timeout=500ms"})
class AiAgentClientTokenExchangeTest {

    private static final String TOKEN_PATH = "/realms/github-dev/protocol/openid-connect/token";

    static final AgentStub AGENT = AgentStub.start();

    static final List<Map<String, String>> TOKEN_REQUESTS = new CopyOnWriteArrayList<>();

    static final AtomicInteger ISSUED = new AtomicInteger();

    static volatile Failure tokenFailure;

    static final HttpServer KEYCLOAK = keycloak();

    @LocalServerPort
    int port;

    @Autowired
    FixtureUsers users;

    @Autowired
    AiAgentClient client;

    @Autowired
    OAuth2AuthorizedClientService authorizedClients;

    enum Failure { REJECTED, TIMEOUT }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> "http://127.0.0.1:" + KEYCLOAK.getAddress().getPort() + TOKEN_PATH);
        registry.add("test.agent.stream-url", AGENT::streamUrl);
    }

    @AfterAll
    static void stop() {
        AGENT.close();
        KEYCLOAK.stop(0);
    }

    @BeforeEach
    void reset() {
        AGENT.reset();
        TOKEN_REQUESTS.clear();
        tokenFailure = null;
        for (String userId : List.of("u-exchange", "u-rejected", "u-timeout", "u-model")) {
            authorizedClients.removeAuthorizedClient("github-ai-agent", userId);
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentCallUsesExchangedToken() throws Exception {
        users.put("u-exchange", "t1", "d1");
        HttpResponse<String> response = chat("u-exchange");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data:synthetic-answer").doesNotContain("event:error");

        assertThat(TOKEN_REQUESTS).singleElement().satisfies(request -> assertThat(request)
                .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .containsEntry("subject_token", ResourceServerFixture.accessToken("u-exchange"))
                .containsEntry("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .containsEntry("audience", "github-ai-agent")
                .containsEntry("authorization", basic("github-ai", "fixture-ai-secret")));
        assertThat(AGENT.received()).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo(AgentStub.STREAM_PATH);
            assertThat(call.headers().get("authorization")).isEqualTo("Bearer exchanged-" + ISSUED.get());
            assertThat(call.headers().keySet()).noneMatch(name -> name.startsWith("x-open-"));
            // ai-agent 的资源服务器按 X-Tenant-Id 经 user 业务上下文核实活动租户，必须与本轮已验证的租户一致。
            assertThat(call.headers().get("x-tenant-id")).isEqualTo("t1");
            assertThat(call.body()).contains("\"userId\":\"u-exchange\"", "\"tenantId\":\"t1\"")
                    .doesNotContain(ResourceServerFixture.accessToken("u-exchange"));
        });
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void tokenExchangeFailureNeverCallsAgent(Failure failure) throws Exception {
        String userId = failure == Failure.REJECTED ? "u-rejected" : "u-timeout";
        users.put(userId, "t1", "d1");
        tokenFailure = failure;
        long started = System.nanoTime();
        HttpResponse<String> response = chat(userId);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:error", "data:[AI Agent 令牌获取失败] 请稍后重试")
                .doesNotContain("synthetic-answer", "fixture-ai-secret", ResourceServerFixture.accessToken(userId), "127.0.0.1");
        assertThat(TOKEN_REQUESTS).hasSize(1);
        assertThat(AGENT.received()).isEmpty();
    }

    @Test
    void modelLookupAlsoUsesExchangedTokenWithoutSignatureHeaders() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(ResourceServerFixture.accessToken("u-model")).header("alg", "RS256")
                .claim("user_id", "u-model").issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        SecurityUser user = SecurityUser.builder().userId("u-model").tenantId("t1").enabled(true).tenantIds(List.of("t1")).build();
        SecurityContextHolder.getContext().setAuthentication(new FleetAuthenticationToken(jwt, SecurityContextResult.ok(user), List.of(), "u-model"));

        assertThat(client.model("qwen").model()).isEqualTo("synthetic-model");
        assertThat(TOKEN_REQUESTS).singleElement().satisfies(request ->
                assertThat(request).containsEntry("subject_token", ResourceServerFixture.accessToken("u-model")));
        assertThat(AGENT.received()).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/api/agents/model");
            assertThat(call.headers().get("authorization")).startsWith("Bearer exchanged-");
            assertThat(call.headers().keySet()).noneMatch(name -> name.startsWith("x-open-"));
        });
    }

    private HttpResponse<String> chat(String userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/chat"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Authorization", ResourceServerFixture.bearer(userId))
                .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"fixture\",\"message\":\"synthetic-input\"}")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer keycloak() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext(TOKEN_PATH, exchange -> {
                Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                form.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
                TOKEN_REQUESTS.add(form);
                Failure failure = tokenFailure;
                int status = 200;
                String body = "{\"access_token\":\"exchanged-" + ISSUED.incrementAndGet()
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";
                if (failure == Failure.REJECTED) {
                    status = 400;
                    body = "{\"error\":\"invalid_request\",\"error_description\":\"synthetic rejection\"}";
                } else if (failure == Failure.TIMEOUT) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                } catch (java.io.IOException closedByClient) {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (!pair.isEmpty()) {
                String[] parts = pair.split("=", 2);
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return values;
    }

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 测试上下文：真实控制器、身份解析、对话编排与 Agent 客户端；记忆、审计、提示词、附件与 RAG 换成替身。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.agent.token-exchange.fixture", havingValue = "true")
    @Import({ResourceServerFixture.class, AiDialogueController.class, AiRequestIdentityResolver.class})
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
            OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class, FleetOAuth2ClientAutoConfiguration.class})
    static class Config {

        @Bean
        AiAgentClient aiAgentClient(FleetOAuth2Clients oauth2Clients, Environment environment) {
            AiAgentRegistryClient registry = operation -> Optional.of(new AiAgentRegistryClient.AgentEndpoint(
                    environment.getRequiredProperty("test.agent.stream-url"), "http", "fixture"));
            AiModelSelectionService selection = mock(AiModelSelectionService.class);
            when(selection.currentProvider()).thenReturn("qwen");
            return new AiAgentClient(new AiAgentClientProperties(Duration.ofSeconds(5), "qwen"), registry, selection, oauth2Clients, environment);
        }

        @Bean
        AiDialogueService dialogue(AiAgentClient client) {
            DbChatMemoryService memory = mock(DbChatMemoryService.class);
            when(memory.shortMemoryContext(any())).thenReturn("");
            PatientMemoryService patient = mock(PatientMemoryService.class);
            when(patient.patientContext(any(), any())).thenReturn("");
            MediaInputValidator media = mock(MediaInputValidator.class);
            when(media.validateUrlMedia(any())).thenReturn(List.of());
            when(media.summarize(any())).thenReturn("");
            AiRolePromptService prompt = mock(AiRolePromptService.class);
            when(prompt.resolve(any(), any())).thenReturn(new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic"));
            return new AiDialogueService(memory, media, mock(ChatAuditService.class), prompt, patient,
                    new AiModelCallObserver(new SimpleMeterRegistry()), client, mock(RagRetrievalClient.class));
        }
    }
}

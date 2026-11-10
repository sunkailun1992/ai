package com.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.agent.AiAgentClient;
import com.kellen.agent.AiAgentRegistryClient;
import com.kellen.agent.AiAgentRequestSigner;
import com.kellen.agent.AiModelSelectionService;
import com.kellen.config.AiAgentClientAuthProperties;
import com.kellen.config.AiAgentClientProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 Spring 绑定与 WebClient HTTP 合同：桩服务器按收到的原始字节独立验签。
 * 不使用生产身份，不调用模型、数据库、Nacos 或 user 服务。
 */
class AiAgentSignedClientHttpTests {

    // user 的 normalize 仅处理 null；首尾空白必须作为真实密钥字节参与 HMAC。
    private static final String TEST_SECRET = " synthetic-agent-secret ";
    private static final ObjectMapper OBSERVER_JSON = new ObjectMapper();
    private final List<ObservedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> rejection = new AtomicReference<>();
    private final AtomicReference<String> endpoint = new AtomicReference<>();
    private final CountDownLatch firstResponseClosed = new CountDownLatch(1);
    private final CountDownLatch releaseFirstHandler = new CountDownLatch(1);
    private volatile boolean holdFirstHandler;
    private HttpServer server;
    private ExecutorService fixtureExecutor;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 默认 executor 直接占用 HTTP dispatcher；响应完成后仍在清理的 handler 不能阻塞下一次订阅。
        fixtureExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(fixtureExecutor);
        server.createContext("/", exchange -> {
            try {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                ObservedRequest request = new ObservedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                        bytes, exchange.getRequestHeaders().getFirst("X-Open-Client-Id"),
                        exchange.getRequestHeaders().getFirst("X-Open-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Open-Nonce"),
                        exchange.getRequestHeaders().getFirst("X-Open-Signature"),
                        exchange.getRequestHeaders().getFirst("Content-Type"));
                requests.add(request);
                String body = request.uri().getPath().endsWith("/stream")
                        ? "data: signed-fixture\n\n"
                        : "{\"success\":true,\"status\":\"SUCCESS\",\"provider\":\"qwen\",\"model\":\"fixture\",\"content\":\"通过\"}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", request.uri().getPath().endsWith("/stream")
                        ? "text/event-stream" : "application/json");
                if (request.uri().getPath().endsWith("/stream")) {
                    exchange.getResponseHeaders().set("X-AI-Stream-Protocol", "typed-sse-v1");
                }
                // 每次订阅验证真实 HTTP/SSE 与签名，不依赖 JDK HttpServer 的 keep-alive 连接回收时序。
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, response.length);
                // 响应流显式关闭；handler 清理仍由下面的 latch 控制。
                try (OutputStream responseBody = exchange.getResponseBody()) {
                    responseBody.write(response);
                }
                if (holdFirstHandler && requests.size() == 1) {
                    firstResponseClosed.countDown();
                    releaseFirstHandler.await();
                }
            } catch (Throwable error) {
                rejection.set(error);
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint.set(baseUrl() + "/api/agents/patient-analysis/stream");
    }

    @AfterEach void stop() throws Exception {
        releaseFirstHandler.countDown();
        server.stop(0);
        fixtureExecutor.shutdown();
        try {
            assertThat(fixtureExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            fixtureExecutor.shutdownNow();
        }
        assertThat(rejection.get()).isNull();
    }





    @Test void everySseSubscriptionGetsANewSignatureAndNonce() {
        holdFirstHandler = true;
        signedContext().run(context -> {
            AiAgentClient client = context.getBean(AiAgentClient.class);
            Flux<String> stream = client.patientAnalysisStream("fixture-conversation", "qwen", "fixture-prompt",
                    "中文问题", "", "", "", List.of(), "fixture", "user-fixture", "100", "186", "patient-fixture");
            try {
                assertThat(stream.collectList().block(Duration.ofSeconds(5))).contains("signed-fixture");
                assertThat(firstResponseClosed.await(5, TimeUnit.SECONDS)).isTrue();
                // 第一 handler 尚未返回，第二次订阅仍须收到完整 SSE 并终止，不能依赖清理时序。
                assertThat(stream.collectList().block(Duration.ofSeconds(5))).contains("signed-fixture");
            } finally {
                releaseFirstHandler.countDown();
            }
            assertThat(requests).hasSize(2);
            requests.forEach(this::assertSignature);
            assertThat(requests.get(0).nonce()).isNotEqualTo(requests.get(1).nonce());
            assertThat(requests.get(0).signature()).isNotEqualTo(requests.get(1).signature());
        });
    }







    @Test void enabledAuthRejectsMissingSecretBeforeAnyNetworkRequest() {
        context().withPropertyValues("ai.agent-client.auth.enabled=true", "ai.agent-client.auth.client-id=kellen-ai")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("client-id 与 client-secret");
                    assertThat(requests).isEmpty();
                });
    }

    @Test void enabledAuthRejectsMissingOrHeaderUnsafeClientIdAndRedactsSecrets() {
        for (String clientId : List.of("", "invalid\r\nInjected: value")) {
            context().withPropertyValues("ai.agent-client.auth.enabled=true",
                            "ai.agent-client.auth.client-id=" + clientId,
                            "ai.agent-client.auth.client-secret=" + TEST_SECRET)
                    .run(context -> assertThat(context).hasFailed());
        }
        var properties = new AiAgentClientAuthProperties(true, " kellen-ai ", TEST_SECRET);
        assertThat(properties.clientSecret()).isEqualTo(TEST_SECRET);
        assertThat(properties.toString())
                .contains("clientId=kellen-ai", "[REDACTED]").doesNotContain(TEST_SECRET);
        assertThat(requests).isEmpty();
    }





    @Test void registryLookupFailureDoesNotFallBackToAnyLocalAddress() {
        signedContext().run(context -> {
            endpoint.set("");
            assertThatThrownBy(() -> context.getBean(AiAgentClient.class).model("qwen"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Nacos AI Registry");
            assertThat(requests).isEmpty();
        });
    }



    @Test void signsModelGetUsingTheExactQueryAndEmptyBody() {
        signedContext().run(context -> {
            context.getBean(AiAgentClient.class).model("qwen");
            assertThat(requests).hasSize(1);
            assertSignature(requests.get(0));
            assertThat(requests.get(0).uri().getRawQuery()).isEqualTo("provider=qwen");
            assertThat(requests.get(0).body()).isEmpty();
        });
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(TestConfig.class)
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withBean(AiAgentRegistryClient.class, () -> operation -> endpoint.get().isEmpty() ? Optional.empty()
                        : Optional.of(new AiAgentRegistryClient.AgentEndpoint(endpoint.get(), "http", "fixture")));
    }

    private ApplicationContextRunner signedContext() {
        return context().withPropertyValues("ai.agent-client.auth.enabled=true", "ai.agent-client.auth.client-id=kellen-ai",
                "ai.agent-client.auth.client-secret=" + TEST_SECRET)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("signed-fixture-secret", java.util.Map.of(
                                "ai.agent-client.auth.client-secret", TEST_SECRET))));
    }

    private void assertSignature(ObservedRequest request) {
        try {
            assertThat(request.clientId()).isEqualTo("kellen-ai");
            assertThat(Long.parseLong(request.timestamp())).isBetween(System.currentTimeMillis() - 30_000,
                    System.currentTimeMillis() + 1_000);
            assertThat(UUID.fromString(request.nonce()).toString()).isEqualTo(request.nonce());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.body()));
            String canonical = String.join("\n", request.clientId(), request.timestamp(), request.nonce(),
                    request.method(), request.uri().getRawPath(),
                    request.uri().getRawQuery() == null ? "" : request.uri().getRawQuery(), digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(request.signature()).matches("[a-f0-9]{64}")
                    .isEqualTo(HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private record ObservedRequest(String method, URI uri, byte[] body, String clientId,
                                   String timestamp, String nonce, String signature, String contentType) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiAgentClientAuthProperties.class, AiAgentClientProperties.class})
    @Import({AiAgentClient.class, AiAgentRequestSigner.class})
    static class TestConfig {
        @Bean AiModelSelectionService modelSelectionService() {
            var service = mock(AiModelSelectionService.class);
            when(service.currentProvider()).thenReturn("qwen");
            return service;
        }
    }
}

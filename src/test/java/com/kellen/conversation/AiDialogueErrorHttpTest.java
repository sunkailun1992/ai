package com.kellen.conversation;

import com.kellen.agent.AiAgentClient;
import com.kellen.agent.AiAgentRegistryClient;
import com.kellen.agent.AiModelSelectionService;
import com.kellen.audit.ChatAuditService;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.auth.WechatAuthService;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.rag.RagRetrievalClient;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实 AI HTTP/Controller/编排/Client；Agent 用回环 SSE 替身，存储边界可观察。 */
@SpringBootTest(classes = AiDialogueErrorHttpTest.Beans.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=dialogue-error-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "test.dialogue.error.fixture=true"})
class AiDialogueErrorHttpTest {
    @LocalServerPort int port;
    @Autowired Upstream upstream;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired DbChatMemoryService memory;
    @Autowired ChatAuditService audit;
    @Autowired ChatConversationMessageService history;
    private HttpServer agent;

    @BeforeEach void startAgent() throws Exception {
        metrics.clear();
        clearInvocations(memory, audit, history);
        upstream.protocol = "typed-sse-v1";
        upstream.body = "event:error\ndata:synthetic-private-provider-error\n\n";
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            if (upstream.protocol != null) exchange.getResponseHeaders().set("X-AI-Stream-Protocol", upstream.protocol);
            byte[] bytes = upstream.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        agent.start();
        upstream.url = "http://127.0.0.1:" + agent.getAddress().getPort() + "/stream";
    }

    @AfterEach void stopAgent() { agent.stop(0); }

    @Test void providerFailureReachesTheClientAsAnErrorWithoutSuccessPersistence() throws Exception {
        var response = chat();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:error", "data:[AI Agent 调用失败] 请稍后重试")
                .doesNotContain("synthetic-private-provider-error", "synthetic-token");
        assertFailurePersistence();
    }

    @Test void partialOutputCannotTurnALaterProviderFailureIntoSuccess() throws Exception {
        upstream.body = "data:synthetic-partial\n\nevent:error\ndata:synthetic-private-provider-error\n\n";
        var response = chat();
        assertThat(response.body()).containsSubsequence("data:synthetic-partial", "event:error",
                "data:[AI Agent 调用失败] 请稍后重试");
        assertFailurePersistence();
    }

    @Test void oldAgentWithoutProtocolConfirmationFailsClosedRatherThanTrustingItsFallback() throws Exception {
        upstream.protocol = null;
        upstream.body = "data:[AI Agent 调用失败] 请稍后重试\n\n";
        var response = chat();
        assertThat(response.body()).contains("event:error", "data:[模型调用失败]");
        assertFailurePersistence();
    }

    private void assertFailurePersistence() {
        assertThat(metrics.get("ai.model.call.count").tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(metrics.find("ai.model.call.count").tag("outcome", "success").counter()).isNull();
        verify(audit).recordFailure(any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).recordSuccess(any(), any(), any(), any(), any(), any(), any());
        verify(memory, never()).addMessage(any(), eq("assistant"), any());
        verify(history, never()).recordAssistantMessage(any(), any(), any());
    }

    private HttpResponse<String> chat() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/chat"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").header("Authorization", "Bearer synthetic-token")
                .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"fixture\",\"message\":\"synthetic-input\"}"));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static class Upstream { volatile String url, body, protocol; }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.dialogue.error.fixture", havingValue = "true")
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    static class Beans {
        @Bean Upstream upstream() { return new Upstream(); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean DbChatMemoryService memory() {
            var memory = mock(DbChatMemoryService.class);
            when(memory.shortMemoryContext(any())).thenReturn(""); return memory;
        }
        @Bean ChatAuditService audit() { return mock(ChatAuditService.class); }
        @Bean ChatConversationMessageService history() { return mock(ChatConversationMessageService.class); }
        @Bean AiAgentClient client(Upstream upstream) {
            AiAgentRegistryClient registry = operation -> Optional.of(
                    new AiAgentRegistryClient.AgentEndpoint(upstream.url, "http", "fixture"));
            return new AiAgentClient(new AiAgentClientProperties(Duration.ofSeconds(5), "qwen"),
                    registry, mock(AiModelSelectionService.class));
        }
        @Bean AiDialogueService dialogue(DbChatMemoryService memory, ChatAuditService audit,
                                        ChatConversationMessageService history, AiAgentClient client,
                                        SimpleMeterRegistry metrics) {
            var patient = mock(PatientMemoryService.class);
            when(patient.patientContext(any(), any())).thenReturn("");
            var media = mock(MediaInputValidator.class);
            when(media.validateUrlMedia(any())).thenReturn(List.of()); when(media.summarize(any())).thenReturn("");
            var prompt = mock(AiRolePromptService.class);
            when(prompt.resolve(any(), any())).thenReturn(
                    new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic"));
            return new AiDialogueService(memory, media, audit, prompt, mock(ChatConversationService.class), history,
                    patient, new AiModelCallObserver(metrics),
                    client, mock(RagRetrievalClient.class));
        }
        @Bean AiRequestIdentityResolver identities() {
            return new AiRequestIdentityResolver(token -> {
                var identity = new AuthSessionIdentityRpcDTO(); identity.setUserId("synthetic-user");
                identity.setTenantId("synthetic-tenant"); return identity;
            });
        }
        @Bean AiDialogueController controller(AiDialogueService dialogue, AiRequestIdentityResolver identities) {
            return new AiDialogueController(dialogue, mock(WechatAuthService.class), mock(ChatConversationService.class), identities);
        }
    }
}

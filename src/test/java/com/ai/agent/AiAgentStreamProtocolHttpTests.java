package com.ai.agent;

import com.kellen.agent.AiAgentClient;
import com.kellen.agent.AiAgentRegistryClient;
import com.kellen.agent.AiModelSelectionService;
import com.kellen.config.AiAgentClientProperties;
import com.kellen.audit.ChatAuditService;
import com.kellen.auth.AiRequestIdentity;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 真实回环 HTTP 协议回归；仅隔离 Registry，不访问模型、配置中心或业务服务。 */
class AiAgentStreamProtocolHttpTests {

    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> protocol = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private DisposableServer server;

    @AfterEach
    void stop() {
        if (server != null) server.disposeNow(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "text/plain", "text/*", "not-a-media-type"})
    void rejectsSuccessfulHttpResponsesThatDoNotDeclareSse(String contentType) {
        AiAgentClient client = client(200, contentType, "synthetic-private-upstream-content");

        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_STREAM_PROTOCOL_INVALID")
                .hasNoCause();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void requestsSseAndAcceptsItsCharsetWithoutChangingDecodedChunks() {
        AiAgentClient client = client(200, "text/event-stream; charset=UTF-8",
                "event:message\ndata: synthetic-one\n\ndata: synthetic-two\n\n");

        assertThat(stream(client).collectList().block(Duration.ofSeconds(5)))
                .containsExactly("synthetic-one", "synthetic-two");
        assertThat(accept.get()).isEqualTo("text/event-stream");
        assertThat(protocol.get()).isEqualTo("typed-sse-v1");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ": heartbeat-without-data\n\n", "data:\n\n",
            "id: synthetic-id\nevent: message\ndata:\n\n", "id: synthetic-only\nevent: message\n\n"})
    void rejectsAnSseEofThatNeverDeliveredAnOutputEvent(String body) {
        AiAgentClient client = client(200, "text/event-stream", body);

        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_STREAM_EMPTY")
                .hasNoCause();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void skipsEmptyDataWithoutTrimmingOrReorderingWhitespaceChunks() {
        AiAgentClient client = client(200, "text/event-stream",
                "data:  \n\ndata: synthetic-one\n\ndata:\n\ndata: \t\n\n");

        assertThat(stream(client).collectList().block(Duration.ofSeconds(5)))
                .containsExactly(" ", "synthetic-one", "\t");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503})
    void preservesTheHttpErrorStatusInsteadOfReclassifyingItAsAProtocolError(int status) {
        AiAgentClient client = client(status, "application/json", "{\"error\":\"synthetic-rejection\"}");

        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(WebClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 307})
    void rejectsRedirectSseWithoutFollowingTheLocation(int status) {
        AiAgentClient client = client(status, "text/event-stream", "data: synthetic-redirect-output\n\n");

        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_STREAM_PROTOCOL_INVALID")
                .hasNoCause();
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream"})
    void dialogueCountsInvalidProtocolAndNoEventEofAsFailureRatherThanSuccess(String contentType) {
        assertDialogueFailure(client(200, contentType, "synthetic-private-upstream-content"));
    }

    @Test
    void emptyDataEofDoesNotCountAsDialogueSuccess() {
        assertDialogueFailure(client(200, "text/event-stream", "data:\n\n"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text-v0", "typed-sse-v2"})
    void rejectsUnconfirmedProtocolBeforeAcceptingLegacyTextAsSuccess(String version) {
        AiAgentClient client = client(200, "text/event-stream", "data: synthetic-output\n\n", version);
        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_STREAM_PROTOCOL_INVALID");
    }

    @Test
    void typedErrorCannotEscapeAsOrdinaryOutputOrExposeItsUpstreamBody() {
        AiAgentClient client = client(200, "text/event-stream",
                "event:error\ndata:synthetic-private-upstream-content\n\n");
        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_EXECUTION_FAILED")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"event:error\ndata:fixed-friendly-error\n\n",
            "data:synthetic-partial\n\nevent:error\ndata:fixed-friendly-error\n\n"})
    void typedFailureNeverWritesSuccessOrAssistantMemoryEvenAfterPartialOutput(String body) {
        assertDialogueFailure(client(200, "text/event-stream", body));
    }

    @Test
    void normalDataThatLooksLikeAnErrorRemainsModelOutput() {
        AiAgentClient client = client(200, "text/event-stream",
                "data:\ndata:[AI Agent 调用失败] 请稍后重试\n\n");
        assertThat(stream(client).collectList().block(Duration.ofSeconds(5)))
                .containsExactly("\n[AI Agent 调用失败] 请稍后重试");
    }

    @Test
    void rejectsUnknownEventTypesInsteadOfPersistingTheirData() {
        AiAgentClient client = client(200, "text/event-stream",
                "event:synthetic-unknown\ndata:synthetic-private-upstream-content\n\n");
        assertThatThrownBy(() -> stream(client).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_AGENT_STREAM_PROTOCOL_INVALID");
    }

    private void assertDialogueFailure(AiAgentClient client) {
        var memory = mock(DbChatMemoryService.class);
        var patient = mock(PatientMemoryService.class);
        var media = mock(MediaInputValidator.class);
        var prompt = mock(AiRolePromptService.class);
        var audit = mock(ChatAuditService.class);
        var history = mock(ChatConversationMessageService.class);
        when(memory.shortMemoryContext(any())).thenReturn("");
        when(patient.patientContext(any(), any())).thenReturn("");
        when(media.validateUrlMedia(any())).thenReturn(List.of());
        when(media.summarize(any())).thenReturn("");
        when(prompt.resolve(any(), any())).thenReturn(
                new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic"));
        var metrics = new SimpleMeterRegistry();
        try {
            var dialogue = new AiDialogueService(memory, media, audit, prompt,
                    mock(ChatConversationService.class), history,
                    patient,
                    new AiModelCallObserver(metrics), client, mock(RagRetrievalClient.class));

            Flux<String> output = dialogue.stream(new AiChatRequest("fixture", "synthetic-question", null),
                            null, new AiRequestIdentity("synthetic-user", "synthetic-tenant", "synthetic-token"));

            assertThatThrownBy(() -> output.collectList().block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(metrics.get("ai.model.call.count").tag("outcome", "failure").counter().count())
                    .isEqualTo(1);
            assertThat(metrics.find("ai.model.call.count").tag("outcome", "success").counter()).isNull();
            verify(audit, never()).recordSuccess(any(), any(), any(), any(), any(), any(), any(), any());
            verify(memory, never()).addMessage(any(), eq("assistant"), any());
            verify(history, never()).recordAssistantMessage(any(), any(), any());
        } finally {
            metrics.close();
        }
    }

    @Test
    void cancellingAfterAnEventClosesTheActualUpstreamConnection() throws Exception {
        CountDownLatch connectionClosed = new CountDownLatch(1);
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    requests.incrementAndGet();
                    accept.set(request.requestHeaders().get("Accept"));
                    protocol.set(request.requestHeaders().get("X-AI-Stream-Protocol"));
                    request.withConnection(connection -> connection.onDispose(connectionClosed::countDown));
                    return response.header("Content-Type", "text/event-stream")
                            .header("X-AI-Stream-Protocol", "typed-sse-v1")
                            .sendString(Flux.just("data:\n\n", "data: synthetic-first\n\n")
                                    .concatWith(Flux.never()));
                }).bindNow(Duration.ofSeconds(5));

        assertThat(stream(client()).take(1).collectList().block(Duration.ofSeconds(5)))
                .containsExactly("synthetic-first");
        assertThat(connectionClosed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(requests.get()).isEqualTo(1);
    }

    private AiAgentClient client(int status, String contentType, String body) {
        return client(status, contentType, body, "typed-sse-v1");
    }

    private AiAgentClient client(int status, String contentType, String body, String version) {
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    requests.incrementAndGet();
                    accept.set(request.requestHeaders().get("Accept"));
                    protocol.set(request.requestHeaders().get("X-AI-Stream-Protocol"));
                    response.status(status);
                    if (status >= 300 && status < 400) {
                        response.header("Location", "/unexpected-follow");
                    }
                    if (contentType != null) response.header("Content-Type", contentType);
                    if (version != null) response.header("X-AI-Stream-Protocol", version);
                    return response.sendString(Flux.just(body));
                }).bindNow(Duration.ofSeconds(5));
        return client();
    }

    private AiAgentClient client() {
        AiAgentRegistryClient registry = operation -> Optional.of(new AiAgentRegistryClient.AgentEndpoint(
                "http://127.0.0.1:" + server.port() + "/api/agents/patient-analysis/stream", "http", "fixture"));
        return new AiAgentClient(new AiAgentClientProperties(Duration.ofSeconds(5), "qwen"),
                registry, mock(AiModelSelectionService.class));
    }

    private Flux<String> stream(AiAgentClient client) {
        return client.patientAnalysisStream("synthetic-conversation", "qwen", "synthetic-system",
                "synthetic-input", "", "", "", List.of(), "fixture", "synthetic-user",
                "synthetic-tenant", "synthetic-source", "synthetic-patient");
    }
}

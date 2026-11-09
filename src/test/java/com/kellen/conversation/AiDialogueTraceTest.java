package com.kellen.conversation;

import com.kellen.agent.AiAgentClient;
import com.kellen.audit.ChatAuditService;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.questionnaire.ConversationalQuestionnaireService;
import com.kellen.rag.RagRetrievalClient;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际 service 的异步上下文合同；隔离模型、Nacos 和持久化协作者。 */
class AiDialogueTraceTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private AiDialogueService service;
    private AiAgentClient agent;
    private RagRetrievalClient rag;
    private ChatAuditService audit;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
        var memory = mock(DbChatMemoryService.class);
        var patient = mock(PatientMemoryService.class);
        var media = mock(MediaInputValidator.class);
        var prompt = mock(AiRolePromptService.class);
        var questionnaire = mock(ConversationalQuestionnaireService.class);
        when(memory.shortMemoryContext(any())).thenReturn("");
        when(patient.patientContext(any(), any())).thenReturn("");
        when(media.validateUrlMedia(any())).thenReturn(List.of());
        when(media.summarize(any())).thenReturn("");
        when(prompt.resolve(any(), any())).thenReturn(new AiRolePromptService.ResolvedPrompt("patient-analysis", "fixture", "synthetic"));
        agent = mock(AiAgentClient.class);
        rag = mock(RagRetrievalClient.class);
        audit = mock(ChatAuditService.class);
        service = new AiDialogueService(memory, media, audit, prompt,
                mock(ChatConversationService.class), mock(ChatConversationMessageService.class), questionnaire,
                patient, mock(AiModelCallObserver.class), agent, rag);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    private Span parent(String name) {
        return GlobalOpenTelemetry.getTracer("test").spanBuilder(name).setNoParent().startSpan();
    }

    private Flux<String> assemble(Span parent, Flux<String> output) {
        when(agent.patientAnalysisStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(output);
        try (var ignored = parent.makeCurrent()) {
            return service.stream(new AiChatRequest("fixture", "synthetic-question", null));
        }
    }

    private List<SpanData> businessSpans() {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.dialogue.stream")).toList();
    }

    @Test
    void asyncTerminalPersistenceUsesTheDialogueContextAndRestoresTheWorker() {
        doAnswer(invocation -> {
            var child = GlobalOpenTelemetry.getTracer("test").spanBuilder("fixture.audit.write").startSpan();
            child.end();
            return null;
        }).when(audit).recordSuccess(any(), any(), any(), any(), any(), any(), any());
        var worker = reactor.core.scheduler.Schedulers.newSingle("dialogue-terminal-test");
        Span parent = parent("entry");
        try {
            assertEquals(List.of("synthetic-output"), assemble(parent, Flux.just("synthetic-output").publishOn(worker))
                    .collectList().block());
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                    .untilAsserted(() -> assertEquals(1, businessSpans().size()));
            var auditSpan = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("fixture.audit.write"))
                    .findFirst().orElseThrow();
            assertEquals(businessSpans().get(0).getSpanId(), auditSpan.getParentSpanId());
            assertEquals(parent.getSpanContext().getTraceId(), auditSpan.getTraceId());
            assertFalse(reactor.core.publisher.Mono.fromCallable(() -> Span.current().getSpanContext().isValid())
                    .subscribeOn(worker).block());
        } finally {
            worker.dispose();
            parent.end();
        }
    }

    @Test
    void wholeDialogueSpanIncludesTheActualRagPrefetch() {
        when(rag.retrieveContext(any(), any())).thenAnswer(invocation -> {
            var child = GlobalOpenTelemetry.getTracer("test").spanBuilder("fixture.rag.rpc").startSpan();
            child.end();
            return "";
        });
        Span parent = parent("entry");
        assertEquals(List.of("synthetic-output"), assemble(parent, Flux.just("synthetic-output")).collectList().block());
        var dialogue = businessSpans().get(0);
        var child = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("fixture.rag.rpc"))
                .findFirst().orElseThrow();
        var prefetch = exporter.getFinishedSpanItems().stream().filter(span -> span.getSpanId().equals(child.getParentSpanId()))
                .findFirst().orElseThrow();
        assertEquals(dialogue.getTraceId(), child.getTraceId());
        assertTrue(prefetch.getSpanId().equals(dialogue.getSpanId()) || prefetch.getParentSpanId().equals(dialogue.getSpanId()));
        parent.end();
    }

    private void assertParent(SpanData span, Span parent, String outcome) {
        assertEquals(parent.getSpanContext().getTraceId(), span.getTraceId());
        assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
        assertEquals(outcome, span.getAttributes().get(AttributeKey.stringKey("business.outcome")));
        assertEquals(0, span.getEvents().size());
        assertEquals("error".equals(outcome) ? StatusCode.ERROR : StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    void assemblyParentWinsOverDifferentSubscriberAndEachSubscriptionGetsItsOwnSpan() {
        Span a = parent("assembly-A");
        Span b = parent("subscriber-B");
        Flux<String> stream = assemble(a, Flux.just("synthetic-output"));
        try (var ignored = b.makeCurrent()) {
            assertEquals(List.of("synthetic-output"), stream.collectList().block());
            assertEquals(b.getSpanContext(), Span.current().getSpanContext());
            assertEquals(List.of("synthetic-output"), stream.collectList().block());
            assertEquals(b.getSpanContext(), Span.current().getSpanContext());
        }
        assertFalse(Span.current().getSpanContext().isValid());
        assertEquals(2, businessSpans().size());
        businessSpans().forEach(span -> assertParent(span, a, "success"));
        assertNotEquals(businessSpans().get(0).getSpanId(), businessSpans().get(1).getSpanId());
    }

    @Test
    void independentStreamsReuseWorkerWithoutLeakingParent() throws Exception {
        Span a = parent("assembly-A");
        Span b = parent("assembly-B");
        Flux<String> first = assemble(a, Flux.just("synthetic-output"));
        Flux<String> second = assemble(b, Flux.just("synthetic-output"));
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (Flux<String> stream : List.of(first, second)) {
                worker.submit(() -> {
                    assertEquals(Context.root(), Context.current());
                    assertEquals(List.of("synthetic-output"), stream.collectList().block());
                    assertEquals(Context.root(), Context.current());
                }).get(5, TimeUnit.SECONDS);
            }
        } finally {
            worker.shutdownNow();
        }
        assertEquals(2, businessSpans().size());
        assertParent(businessSpans().get(0), a, "success");
        assertParent(businessSpans().get(1), b, "success");
    }

    @Test
    void errorBeforeFallbackEndsExactlyOnceAndKeepsOriginalBusinessResponse() {
        Span a = parent("assembly-error");
        Flux<String> stream = assemble(a, Flux.error(new IllegalStateException("synthetic-failure")));
        assertTrue(stream.collectList().block().get(0).startsWith("\n[模型调用失败]"));
        assertEquals(1, businessSpans().size());
        assertParent(businessSpans().get(0), a, "error");
    }

    @Test
    void cancellationEndsExactlyOnceWithoutSubscriberContextLeak() {
        Span a = parent("assembly-cancel");
        Flux<String> stream = assemble(a, Flux.never());
        var subscription = stream.subscribe();
        assertEquals(Context.root(), Context.current());
        subscription.dispose();
        subscription.dispose();
        assertEquals(1, businessSpans().size());
        assertParent(businessSpans().get(0), a, "cancelled");
    }
}

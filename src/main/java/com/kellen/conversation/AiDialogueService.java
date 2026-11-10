package com.kellen.conversation;

import com.kellen.agent.AiAgentClient;
import com.kellen.auth.AiRequestIdentity;
import com.kellen.audit.ChatAuditService;
import com.kellen.file.MediaInput;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.ChatMemoryMessageDto;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.rag.RagRetrievalClient;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 患者分析 AI 对话编排服务。
 *
 * <p>主 {@code ai} 服务负责入口编排：鉴权后的会话归属、角色 Prompt
 * 解析、记忆裁剪、附件安全校验、审计和历史展示。真实模型执行只通过 Nacos AI Registry
 * 发现的 {@code ai-agent} 完成，本服务不再持有 Spring AI 模型客户端。</p>
 */
@Service
public class AiDialogueService {

    private static final String AGENT_PROVIDER = "nacos-ai-registry";
    private static final String PATIENT_ANALYSIS_AGENT = "patient-analysis-agent";

    private final DbChatMemoryService dbChatMemoryService;
    private final MediaInputValidator mediaInputValidator;
    private final ChatAuditService chatAuditService;
    private final AiRolePromptService aiRolePromptService;
    private final ChatConversationService chatConversationService;
    private final ChatConversationMessageService chatConversationMessageService;
    private final PatientMemoryService patientMemoryService;
    private final AiModelCallObserver modelCallObserver;
    private final AiAgentClient aiAgentClient;
    private final RagRetrievalClient ragRetrievalClient;

    /**
     * 构造器集中接收编排所需的协作组件。
     *
     * @param dbChatMemoryService            数据库对话记忆服务。
     * @param mediaInputValidator            多模态媒体校验器。
     * @param chatAuditService               对话审计服务。
     * @param aiRolePromptService            角色提示词解析服务。
     * @param chatConversationService        会话元数据服务。
     * @param chatConversationMessageService 展示消息服务。
     * @param patientMemoryService           患者长期记忆服务。
     * @param modelCallObserver              模型调用观测服务。
     * @param aiAgentClient                  Agent 执行服务客户端。
     * @param ragRetrievalClient             RAG 知识检索 RPC 客户端。
     */
    public AiDialogueService(DbChatMemoryService dbChatMemoryService,
                             MediaInputValidator mediaInputValidator,
                             ChatAuditService chatAuditService,
                             AiRolePromptService aiRolePromptService,
                             ChatConversationService chatConversationService,
                             ChatConversationMessageService chatConversationMessageService,
                             PatientMemoryService patientMemoryService,
                             AiModelCallObserver modelCallObserver,
                             AiAgentClient aiAgentClient,
                             RagRetrievalClient ragRetrievalClient) {
        this.dbChatMemoryService = dbChatMemoryService;
        this.mediaInputValidator = mediaInputValidator;
        this.chatAuditService = chatAuditService;
        this.aiRolePromptService = aiRolePromptService;
        this.chatConversationService = chatConversationService;
        this.chatConversationMessageService = chatConversationMessageService;
        this.patientMemoryService = patientMemoryService;
        this.modelCallObserver = modelCallObserver;
        this.aiAgentClient = aiAgentClient;
        this.ragRetrievalClient = ragRetrievalClient;
    }

    /**
     * 返回当前执行链路信息，供健康检查或前端展示使用。
     *
     * @return 当前模型执行委托方式。
     */
    public AiChatResponse modelInfo() {
        return AiChatResponse.ok(PATIENT_ANALYSIS_AGENT,
                "model execution is delegated to ai-agent through Nacos AI Registry");
    }

    /**
     * 发起 SSE 流式对话，支持纯文本和远程媒体 URL。
     *
     * @param request 流式对话请求，message 必填，attachments 可选。
     * @return Agent 逐段输出的 Flux。
     */
    public Flux<String> stream(AiChatRequest request) {
        return stream(request, null);
    }

    /**
     * 发起 SSE 流式对话，并在登录用户场景维护会话列表。
     *
     * @param request     流式对话请求。
     * @param ownerUserId 当前微信用户 ID，可空。
     * @return Agent 逐段输出的 Flux。
     */
    public Flux<String> stream(AiChatRequest request, Long ownerUserId) {
        return Flux.error(new SecurityException("AI_IDENTITY_REQUIRED"));
    }

    /** 已认证 User 身份作为不可变值进入异步链；微信 owner 不由 User ID 推定。 */
    public Flux<String> stream(AiChatRequest request, Long ownerUserId, AiRequestIdentity identity) {
        if (identity == null) return Flux.error(new SecurityException("AI_IDENTITY_REQUIRED"));
        Context entryParent = Context.current();
        return Flux.defer(() -> {
            DialogueObservation observation = new DialogueObservation(entryParent, identity);
            Flux<String> execution;
            // 整轮 span 在 RAG/记忆预取前建立，Scope 仅覆盖当前线程的装配与订阅。
            try (Scope ignored = observation.span.makeCurrent(); var tenant = identity.bindTenant()) {
                List<MediaInputValidator.ValidatedMedia> media = mediaInputValidator.validateUrlMedia(request.effectiveAttachments());
                String normalizedConversationId = identity.conversationKey(request.conversationId());
                chatConversationService.touchOnUserMessage(ownerUserId, normalizedConversationId, request.message());
                execution = streamInternal(request.role(), request.message(), request.effectiveInstruction(), request.effectiveAttachments(),
                        media, "chat_stream", ownerUserId, normalizedConversationId, request.patientId(), request.effectiveSourceTenantId(), observation, identity);
            } catch (RuntimeException error) {
                observation.failed();
                observation.finish(reactor.core.publisher.SignalType.ON_ERROR);
                return Flux.error(error);
            }
            Flux<String> source = execution;
            Flux<String> scoped = Flux.from(subscriber -> {
                // 这里只订阅 HTTP 流，不做业务 SQL。避免新建 Reactor worker 继承 TTL 租户；
                // 真正的预取及持久化动作分别在上面的装配 scope 和 observation.run 中显式绑定。
                try (Scope ignored = observation.span.makeCurrent(); var tenant = AiRequestIdentity.suspendTenant()) {
                    source.subscribe(subscriber);
                }
            });
            return scoped.doOnError(error -> observation.failed()).doFinally(observation::finish);
        });
    }

    /**
     * 统一执行一轮流式 Agent 调用，并在终止时写入审计记录。
     *
     * @param role                     请求角色 key。
     * @param rawMessage               原始用户消息。
     * @param instruction              本轮补充要求。
     * @param rawAttachments           原始附件输入，用于小程序历史展示和传给 Agent。
     * @param media                    已通过安全校验的媒体资源摘要。
     * @param messageType              审计中的调用类型。
     * @param ownerUserId              当前微信用户 ID，可空。
     * @param normalizedConversationId 归一化会话 ID。
     * @param patientId                患者 ID，用于检索长期记忆。
     * @param sourceTenantId           来源业务租户 ID。
     * @param observation              整轮编排的观测上下文。
     * @return Agent 逐段输出的 Flux。
     */
    private Flux<String> streamInternal(String role,
                                        String rawMessage,
                                        String instruction,
                                        List<MediaInput> rawAttachments,
                                        List<MediaInputValidator.ValidatedMedia> media,
                                        String messageType,
                                        Long ownerUserId,
                                        String normalizedConversationId,
                                        String patientId,
                                        String sourceTenantId,
                                        DialogueObservation observation,
                                        AiRequestIdentity identity) {
        String message = requiredText(rawMessage, "message");
        String mediaSummary = mediaInputValidator.summarize(media);
        AiRolePromptService.ResolvedPrompt resolvedPrompt = aiRolePromptService.resolve(role, instruction);
        observation.prompt(resolvedPrompt);
        String historyContext = observation.step("memory.context", () -> historyContext(normalizedConversationId, patientId, sourceTenantId));
        String ragContext = observation.step("rag.prefetch", () -> ragRetrievalClient.retrieveContext(message, normalizedConversationId));
        long startedAt = modelCallObserver.start();
        StringBuilder content = new StringBuilder();

        dbChatMemoryService.addMessage(normalizedConversationId, "user", message);
        chatConversationMessageService.recordUserMessage(ownerUserId, normalizedConversationId, message, rawAttachments);

        return aiAgentClient.patientAnalysisStream(
                        normalizedConversationId,
                        null,
                        resolvedPrompt.systemPrompt(),
                        message,
                        instruction,
                        historyContext,
                        ragContext,
                        rawAttachments,
                        "ai",
                        identity.userId(),
                        identity.tenantId(),
                        sourceTenantId,
                        patientId,
                        identity.accessToken())
                .doOnNext(content::append)
                .doOnComplete(() -> observation.run(() -> {
                    String assistantContent = content.toString();
                    modelCallObserver.success(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordSuccess(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), assistantContent, mediaSummary);
                    dbChatMemoryService.addMessage(normalizedConversationId, "assistant", assistantContent);
                    chatConversationMessageService.recordAssistantMessage(ownerUserId, normalizedConversationId, assistantContent);
                }))
                .doOnError(exception -> observation.run(() -> {
                    observation.failed();
                    modelCallObserver.failure(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordFailure(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), rootMessage(exception), mediaSummary);
                }))
                .doOnCancel(() -> observation.run(() -> {
                    modelCallObserver.cancelled(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordFailure(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), "stream cancelled by client", mediaSummary);
                }));
    }

    /**
     * 查询指定会话当前可传给 Agent 的记忆消息。
     *
     * @param conversationId 会话 ID，空值归入 default 会话。
     * @return 按时间正序排列的记忆消息视图。
     */
    public List<ChatMemoryMessageDto> memoryMessages(String conversationId) {
        return dbChatMemoryService.recentEntities(memoryConversationId(conversationId)).stream()
                .map(entity -> new ChatMemoryMessageDto(entity.getMessageType(), entity.getContent(), entity.getCreatedAt()))
                .toList();
    }

    /**
     * 清空指定会话的对话记忆；ai_chat_message 审计记录不受影响。
     *
     * @param conversationId 会话 ID，空值归入 default 会话。
     * @return 清空结果的统一响应，非模型调用因此 model 字段为空。
     */
    public AiChatResponse clearMemory(String conversationId) {
        String normalized = memoryConversationId(conversationId);
        dbChatMemoryService.clear(normalized);
        return AiChatResponse.ok(null, "memory cleared: " + normalized);
    }

    /** 有限编排步骤；不记录用户内容，不把固定调用冒充模型工具。 */
    private static final class DialogueObservation {
        private final io.opentelemetry.api.trace.Tracer tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("fleet.business");
        private final io.opentelemetry.api.trace.Span span;
        private final AiRequestIdentity identity;
        private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean failed;

        private DialogueObservation(Context parent, AiRequestIdentity identity) {
            this.identity = identity;
            span = tracer.spanBuilder("ai.dialogue.stream").setParent(parent).startSpan();
        }

        private void prompt(AiRolePromptService.ResolvedPrompt prompt) {
            span.setAttribute("fleet.ai.prompt.key", metadata(prompt.role()));
            span.setAttribute("fleet.ai.prompt.version", metadata(prompt.version()));
            span.setAttribute("fleet.ai.prompt.source", metadata(prompt.source()));
        }

        private <T> T step(String name, java.util.function.Supplier<T> action) {
            var child = tracer.spanBuilder(name).startSpan();
            try (Scope ignored = child.makeCurrent(); var tenant = identity.bindTenant()) {
                T result = action.get();
                child.setAttribute("business.outcome", result instanceof String text && text.isBlank() ? "empty" : "success");
                return result;
            } catch (RuntimeException error) {
                child.setAttribute("business.outcome", "error");
                child.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                throw error;
            } finally {
                child.end();
            }
        }

        private void run(Runnable action) {
            try (Scope ignored = span.makeCurrent(); var tenant = identity.bindTenant()) {
                action.run();
            }
        }

        private void failed() { failed = true; }

        private void finish(reactor.core.publisher.SignalType signal) {
            if (ended.compareAndSet(false, true)) {
                String outcome = signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : failed ? "error" : "success";
                span.setAttribute("business.outcome", outcome);
                if ("error".equals(outcome)) span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                span.end();
            }
        }

        private static String metadata(String value) {
            return value != null && value.matches("[A-Za-z0-9_.-]{1,128}") ? value : "unknown";
        }
    }

    private String historyContext(String conversationId, String patientId, String sourceTenantId) {
        String shortMemory = dbChatMemoryService.shortMemoryContext(conversationId);
        String longMemory = patientMemoryService.patientContext(patientId, sourceTenantId);
        if (longMemory.isBlank()) {
            return shortMemory;
        }
        if (shortMemory.isBlank()) {
            return "患者长期记忆:\n" + longMemory;
        }
        return "患者长期记忆:\n" + longMemory + "\n\n会话短记忆:\n" + shortMemory;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String memoryConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return "default";
        }
        return conversationId.trim();
    }
}

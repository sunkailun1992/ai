package com.kellen.conversation;

import com.kellen.agent.AiAgentClient;
import com.kellen.audit.ChatAuditService;
import com.kellen.file.MediaInput;
import com.kellen.file.MediaInputValidator;
import com.kellen.memory.ChatMemoryMessageDto;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;
import com.kellen.observability.AiModelCallObserver;
import com.kellen.prompt.AiRolePromptService;
import com.kellen.questionnaire.ConversationalQuestionnaireService;
import com.kellen.questionnaire.QuestionnaireChatResponse;
import com.kellen.rag.RagRetrievalClient;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 患者分析 AI 对话编排服务。
 *
 * <p>主 {@code ai} 服务负责入口编排：鉴权后的会话归属、问卷优先路由、角色 Prompt
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
    private final ConversationalQuestionnaireService questionnaireService;
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
     * @param questionnaireService           问卷状态机服务。
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
                             ConversationalQuestionnaireService questionnaireService,
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
        this.questionnaireService = questionnaireService;
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
        return Flux.defer(() -> {
            List<MediaInputValidator.ValidatedMedia> media = mediaInputValidator.validateUrlMedia(request.effectiveAttachments());
            String normalizedConversationId = memoryConversationId(request.conversationId());
            java.util.Optional<QuestionnaireChatResponse> questionnaire = questionnaireService.continueActiveIfPresent(
                    normalizedConversationId,
                    ownerUserId == null ? "" : String.valueOf(ownerUserId),
                    request.message(),
                    request.effectiveAttachments());
            if (questionnaire.isPresent()) {
                return Flux.just(questionnaire.get().reply());
            }
            chatConversationService.touchOnUserMessage(ownerUserId, normalizedConversationId, request.message());
            return streamInternal(request.role(), request.message(), request.effectiveInstruction(), request.effectiveAttachments(),
                    media, "chat_stream", ownerUserId, normalizedConversationId, request.patientId(), request.effectiveSourceTenantId());
        }).onErrorResume(IllegalArgumentException.class, exception -> Flux.just(safeClientErrorMessage(exception)));
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
                                        String sourceTenantId) {
        String message = requiredText(rawMessage, "message");
        String mediaSummary = mediaInputValidator.summarize(media);
        AiRolePromptService.ResolvedPrompt resolvedPrompt = aiRolePromptService.resolve(role, instruction);
        String historyContext = historyContext(normalizedConversationId, patientId, sourceTenantId);
        String ragContext = ragRetrievalClient.retrieveContext(message, normalizedConversationId);
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
                        ownerUserId == null ? "" : ownerUserId.toString(),
                        "100",
                        sourceTenantId,
                        patientId)
                .doOnNext(content::append)
                .doOnComplete(() -> {
                    String assistantContent = content.toString();
                    modelCallObserver.success(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordSuccess(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), assistantContent, mediaSummary);
                    dbChatMemoryService.addMessage(normalizedConversationId, "assistant", assistantContent);
                    chatConversationMessageService.recordAssistantMessage(ownerUserId, normalizedConversationId, assistantContent);
                })
                .doOnError(exception -> {
                    modelCallObserver.failure(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordFailure(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), rootMessage(exception), mediaSummary);
                })
                .doOnCancel(() -> {
                    modelCallObserver.cancelled(AGENT_PROVIDER, PATIENT_ANALYSIS_AGENT, messageType, startedAt);
                    chatAuditService.recordFailure(normalizedConversationId, messageType, PATIENT_ANALYSIS_AGENT,
                            message, resolvedPrompt.systemPrompt(), "stream cancelled by client", mediaSummary);
                })
                .onErrorResume(exception -> Flux.just(safeStreamErrorMessage(exception)));
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

    private String safeStreamErrorMessage(Throwable exception) {
        String message = rootMessage(exception);
        if (message.contains("Nacos AI Registry")) {
            return "\n[模型调用失败] AI Agent 注册中心未找到可用接口，请检查 Nacos AI 注册中心的 AgentCard 配置。";
        }
        return "\n[模型调用失败] 本轮 AI 响应生成失败，请检查附件类型、附件 URL、Nacos AI Registry 和 ai-agent 服务状态后重试。";
    }

    private String safeClientErrorMessage(IllegalArgumentException exception) {
        return "\n[请求参数错误] " + exception.getMessage();
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

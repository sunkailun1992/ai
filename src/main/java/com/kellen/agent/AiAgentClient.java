package com.kellen.agent;

import com.kellen.config.AiAgentClientProperties;
import com.kellen.file.MediaInput;
import com.kellen.questionnaire.QuestionDefinition;
import com.kellen.questionnaire.QuestionnaireAnswerEntity;
import com.kellen.questionnaire.QuestionnaireDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

/**
 * 主 AI 服务调用独立 Agent 执行服务的 HTTP 客户端。
 *
 * <p>该客户端只通过 Nacos AI Registry 解析 AgentCard 中的接口地址，不再保留本地
 * baseUrl、服务发现兜底或本地模型执行兜底。Registry 配错时要尽早暴露，避免主服务
 * 再次变成模型执行层。</p>
 */
@Service
public class AiAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);

    private final AiAgentClientProperties properties;
    private final AiAgentRegistryClient registryClient;
    private final AiModelSelectionService modelSelectionService;
    private final WebClient webClient;
    private final AiAgentRequestSigner requestSigner;

    /**
     * 构造 Agent HTTP 客户端。
     *
     * @param properties     Agent 调用超时配置。
     * @param registryClient         Nacos AI Agent Registry 客户端。
     * @param modelSelectionService  当前模型选择服务。
     */
    public AiAgentClient(AiAgentClientProperties properties,
                         AiAgentRegistryClient registryClient,
                         AiModelSelectionService modelSelectionService) {
        this(properties, registryClient, modelSelectionService, AiAgentRequestSigner.disabled());
    }

    /** Spring 运行时对全部 Agent 调用使用同一个专用服务签名边界。 */
    @Autowired
    public AiAgentClient(AiAgentClientProperties properties,
                         AiAgentRegistryClient registryClient,
                         AiModelSelectionService modelSelectionService,
                         AiAgentRequestSigner requestSigner) {
        this.properties = properties;
        this.registryClient = registryClient;
        this.modelSelectionService = modelSelectionService;
        this.webClient = WebClient.builder().build();
        this.requestSigner = requestSigner;
    }

    /**
     * 调用患者分析 Agent 流式接口。
     *
     * @param conversationId  会话 ID。
     * @param provider        可选模型供应商覆盖，通常为空，由 Agent 自己配置。
     * @param systemPrompt    主服务解析后的角色系统提示词。
     * @param message         用户本轮输入。
     * @param instruction     本轮补充要求。
     * @param historyContext  主服务裁剪后的历史上下文。
     * @param ragContext      主服务或后续 RAG 工具整理的检索上下文。
     * @param attachments     本轮附件。
     * @param caller          调用方标识。
     * @param userId          操作用户 ID。
     * @param tenantId        当前业务租户 ID。
     * @param sourceTenantId  来源业务租户 ID。
     * @param patientId       患者 ID。
     * @return Agent SSE 文本流。
     */
    public Flux<String> patientAnalysisStream(String conversationId,
                                              String provider,
                                              String systemPrompt,
                                              String message,
                                              String instruction,
                                              String historyContext,
                                              String ragContext,
                                              List<MediaInput> attachments,
                                              String caller,
                                              String userId,
                                              String tenantId,
                                              String sourceTenantId,
                                              String patientId) {
        AgentChatRequest request = new AgentChatRequest(
                text(conversationId),
                effectiveProvider(provider),
                text(systemPrompt),
                text(message),
                text(instruction),
                text(historyContext),
                text(ragContext),
                attachments == null ? List.of() : attachments,
                text(caller),
                text(conversationId),
                text(userId),
                text(tenantId),
                text(sourceTenantId),
                text(patientId)
        );
        String url = endpointUrl(AiAgentRegistryClient.AgentOperation.PATIENT_ANALYSIS,
                "/api/agents/patient-analysis/stream");
        log.debug("通过 Nacos AI Agent Registry 调用患者分析 Agent");
        return Flux.defer(() -> requestSigner.post(webClient, url, request)
                .retrieve()
                .bodyToFlux(String.class));
    }

    /**
     * 调用问卷自然提问 Agent。
     *
     * @param definition      问卷定义。
     * @param currentQuestion 当前题。
     * @param previousAnswers 已采集答案。
     * @param resumed         是否恢复未完成问卷。
     * @return Agent 响应。
     */
    public QuestionnaireReplyResult questionnaireReply(QuestionnaireDefinition definition,
                                                       QuestionDefinition currentQuestion,
                                                       List<QuestionnaireAnswerEntity> previousAnswers,
                                                       boolean resumed) {
        QuestionnaireReplyRequest request = new QuestionnaireReplyRequest(
                effectiveProvider(null),
                definitionPayload(definition),
                questionPayload(currentQuestion),
                answerPayloads(previousAnswers),
                resumed
        );
        return post(AiAgentRegistryClient.AgentOperation.QUESTIONNAIRE_REPLY,
                "/api/agents/questionnaire/reply", request, QuestionnaireReplyResult.class);
    }

    /**
     * 调用问卷答案解释 Agent。
     *
     * @param definition        问卷定义。
     * @param currentQuestion   当前题。
     * @param previousAnswers   已采集答案。
     * @param rawMessage        用户原始回答。
     * @param attachmentSummary 附件摘要。
     * @return Agent 响应。
     */
    public QuestionnaireInterpretResult questionnaireInterpret(QuestionnaireDefinition definition,
                                                               QuestionDefinition currentQuestion,
                                                               List<QuestionnaireAnswerEntity> previousAnswers,
                                                               String rawMessage,
                                                               String attachmentSummary) {
        QuestionnaireInterpretRequest request = new QuestionnaireInterpretRequest(
                effectiveProvider(null),
                definitionPayload(definition),
                questionPayload(currentQuestion),
                answerPayloads(previousAnswers),
                rawMessage,
                attachmentSummary
        );
        return post(AiAgentRegistryClient.AgentOperation.QUESTIONNAIRE_INTERPRET,
                "/api/agents/questionnaire/interpret", request, QuestionnaireInterpretResult.class);
    }

    /**
     * 查询指定 Provider 在 Agent 执行层对应的模型名。
     *
     * @param provider Provider，可空时使用当前默认选择。
     * @return Agent 执行层模型信息。
     */
    public AgentModelResult model(String provider) {
        String expectedPath = "/api/agents/patient-analysis/stream";
        UriComponentsBuilder url = UriComponentsBuilder
                .fromUriString(siblingEndpointUrl(AiAgentRegistryClient.AgentOperation.PATIENT_ANALYSIS,
                        expectedPath, "/api/agents/model"))
                .queryParam("provider", effectiveProvider(provider));
        return requestSigner.get(webClient, url)
                .retrieve()
                .bodyToMono(AgentModelResult.class)
                .timeout(properties.timeout())
                .block();
    }

    private <T> T post(AiAgentRegistryClient.AgentOperation operation,
                       String expectedPath,
                       Object body,
                       Class<T> responseType) {
        String url = endpointUrl(operation, expectedPath);
        log.debug("通过 Nacos AI Agent Registry 调用 Agent，operation={}", operation);
        return requestSigner.post(webClient, url, body)
                .retrieve()
                .bodyToMono(responseType)
                .timeout(properties.timeout())
                .block();
    }

    private String endpointUrl(AiAgentRegistryClient.AgentOperation operation, String expectedPath) {
        AiAgentRegistryClient.AgentEndpoint endpoint = registryClient.endpoint(operation)
                .orElseThrow(() -> new IllegalStateException("Nacos AI Registry 未找到 Agent 接口: " + operation));
        String url = resolvedEndpoint(endpoint.url(), expectedPath);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Nacos AI Registry Agent 接口地址为空: " + operation);
        }
        return url;
    }

    private String resolvedEndpoint(String endpointUrl, String expectedPath) {
        String url = text(endpointUrl);
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (!StringUtils.hasText(path) || "/".equals(path)) {
                return trimTrailingSlash(url) + expectedPath;
            }
            return url;
        } catch (IllegalArgumentException exception) {
            return trimTrailingSlash(url) + expectedPath;
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String siblingEndpointUrl(AiAgentRegistryClient.AgentOperation operation, String expectedPath, String siblingPath) {
        String url = endpointUrl(operation, expectedPath);
        if (url.endsWith(expectedPath)) {
            return url.substring(0, url.length() - expectedPath.length()) + siblingPath;
        }
        URI uri = URI.create(url);
        return UriComponentsBuilder.fromUri(uri)
                .replacePath(siblingPath)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private QuestionnaireDefinitionPayload definitionPayload(QuestionnaireDefinition definition) {
        return new QuestionnaireDefinitionPayload(
                text(definition.questionnaireCode()),
                text(definition.version()),
                text(definition.name()),
                text(definition.scene())
        );
    }

    private QuestionDefinitionPayload questionPayload(QuestionDefinition question) {
        return new QuestionDefinitionPayload(
                text(question.id()),
                text(question.label()),
                text(question.prompt()),
                question.required(),
                question.allowsAttachments()
        );
    }

    private List<QuestionnaireAnswerPayload> answerPayloads(List<QuestionnaireAnswerEntity> previousAnswers) {
        if (previousAnswers == null || previousAnswers.isEmpty()) {
            return List.of();
        }
        return previousAnswers.stream()
                .map(answer -> new QuestionnaireAnswerPayload(
                        text(answer.getQuestionId()),
                        text(answer.getSelectedText()),
                        text(answer.getRawText()),
                        text(answer.getAttachmentSummary())
                ))
                .toList();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String effectiveProvider(String provider) {
        String requested = text(provider);
        return StringUtils.hasText(requested) ? requested : modelSelectionService.currentProvider();
    }

    private record AgentChatRequest(
            String conversationId,
            String provider,
            String systemPrompt,
            String message,
            String instruction,
            String historyContext,
            String ragContext,
            List<MediaInput> attachments,
            String caller,
            String traceId,
            String userId,
            String tenantId,
            String sourceTenantId,
            String patientId
    ) {
    }

    private record QuestionnaireReplyRequest(
            String provider,
            QuestionnaireDefinitionPayload definition,
            QuestionDefinitionPayload currentQuestion,
            List<QuestionnaireAnswerPayload> previousAnswers,
            boolean resumed
    ) {
    }

    private record QuestionnaireInterpretRequest(
            String provider,
            QuestionnaireDefinitionPayload definition,
            QuestionDefinitionPayload currentQuestion,
            List<QuestionnaireAnswerPayload> previousAnswers,
            String rawMessage,
            String attachmentSummary
    ) {
    }

    private record QuestionnaireDefinitionPayload(
            String questionnaireCode,
            String version,
            String name,
            String scene
    ) {
    }

    private record QuestionDefinitionPayload(
            String id,
            String label,
            String prompt,
            boolean required,
            boolean allowsAttachments
    ) {
    }

    private record QuestionnaireAnswerPayload(
            String questionId,
            String selectedText,
            String rawText,
            String attachmentSummary
    ) {
    }

    /**
     * 问卷自然提问 Agent 响应。
     *
     * @param provider Agent 实际使用的模型供应商。
     * @param model    Agent 实际使用的模型名。
     * @param reply    当前题自然提问文案。
     */
    public record QuestionnaireReplyResult(
            String provider,
            String model,
            String reply
    ) {
    }

    /**
     * 问卷答案解释 Agent 响应。
     *
     * @param provider         Agent 实际使用的模型供应商。
     * @param model            Agent 实际使用的模型名。
     * @param rawText          用户原始输入。
     * @param selectedCode     选项编码。
     * @param selectedText     标准化答案文本。
     * @param value            保留细节的答案值。
     * @param confidence       置信度。
     * @param needFollowUp     是否需要继续追问。
     * @param followUpQuestion 追问文案。
     */
    public record QuestionnaireInterpretResult(
            String provider,
            String model,
            String rawText,
            String selectedCode,
            String selectedText,
            String value,
            double confidence,
            boolean needFollowUp,
            String followUpQuestion
    ) {
    }

    /**
     * Agent 执行层模型信息。
     *
     * @param provider           Provider。
     * @param model              模型名。
     * @param supportedProviders 支持的 Provider 列表。
     */
    public record AgentModelResult(
            String provider,
            String model,
            List<String> supportedProviders
    ) {
    }
}

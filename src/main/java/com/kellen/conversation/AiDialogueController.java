package com.kellen.conversation;

import com.kellen.agent.AiAgentExecutionException;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.memory.ChatMemoryMessageDto;
import com.kellen.rag.RagRetrievalClient;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

/**
 * AI 对话 REST 接口控制器。
 *
 * <p>只负责 HTTP 出入口：入参绑定、路径映射和 SSE 返回值序列化。
 * 对话类接口统一使用流式输出，全部业务委托 {@link AiDialogueService}，
 * Controller 不直接拼 Prompt、不接触模型 SDK，也不提供本地模型 Provider 切换能力。</p>
 */
@RestController // 声明 REST Controller，返回值自动序列化为 JSON。
@RequestMapping("/api/ai") // AI 对话接口统一路径前缀。
public class AiDialogueController {

    private final AiDialogueService dialogueService;
    private final AiRequestIdentityResolver identities;

    public AiDialogueController(AiDialogueService dialogueService, AiRequestIdentityResolver identities) {
        this.dialogueService = dialogueService;
        this.identities = identities;
    }

    /**
     * 查询当前模型执行链路信息。
     *
     * @return 当前 Agent 委托方式。
     */
    @GetMapping("/model") // 模型信息查询接口。
    public AiChatResponse model() {
        return dialogueService.modelInfo(); // 委托 Service 生成模型信息响应。
    }

    /**
     * SSE 流式对话，支持纯文本和远程媒体 URL。
     *
     * @param authorization User 中心签发的有效 Bearer access JWT。
     * @param request       用户文本、可选会话 ID、角色、本轮补充要求和远程附件 URL。
     * @return 模型逐段输出的文本流。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody AiChatRequest request) {
        try {
            var identity = identities.requireIdentity(authorization);
            return dialogueService.stream(request, identity)
                    .map(text -> ServerSentEvent.builder(text).build())
                    .onErrorResume(error -> Flux.just(streamError(error)));
        } catch (IllegalArgumentException exception) {
            return Flux.just(streamError(exception));
        }
    }

    /** 友好文本只在 HTTP 边界生成，内部失败不能触发成功审计或记忆写入。 */
    private ServerSentEvent<String> streamError(Throwable error) {
        String message;
        if (error instanceof AiAgentExecutionException) {
            message = "\n[AI Agent 调用失败] 请稍后重试";
        } else if (error instanceof RagRetrievalClient.CanaryUnavailableException) {
            message = "\n[灰度 RAG 检索不可用] 请稍后重试";
        } else if (error instanceof IllegalArgumentException) {
            message = "\n[请求参数错误] 请求参数无效，请检查后重试。";
        } else {
            Throwable root = error;
            while (root.getCause() != null) root = root.getCause();
            message = root.getMessage() != null && root.getMessage().contains("Nacos AI Registry")
                    ? "\n[模型调用失败] AI Agent 注册中心未找到可用接口，请检查 Nacos AI 注册中心的 AgentCard 配置。"
                    : "\n[模型调用失败] 本轮 AI 响应生成失败，请检查附件类型、附件 URL、Nacos AI Registry 和 ai-agent 服务状态后重试。";
        }
        return ServerSentEvent.<String>builder().event("error").data(message).build();
    }

    /**
     * 查询指定会话当前可注入模型的记忆消息。
     *
     * @param conversationId 路径中的会话 ID。
     * @return 按时间正序排列的记忆消息列表。
     */
    @GetMapping("/memory/{conversationId}") // 记忆查询接口。
    public List<ChatMemoryMessageDto> memory(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String conversationId) {
        var identity = identities.requireIdentity(authorization);
        try (var tenant = identity.bindTenant()) {
            return dialogueService.memoryMessages(identity.conversationKey(conversationId));
        }
    }

    /**
     * 清空指定会话的对话记忆；审计记录不受影响。
     *
     * @param conversationId 路径中的会话 ID。
     * @return 清空结果的统一响应。
     */
    @DeleteMapping("/memory/{conversationId}") // 记忆清空接口。
    public AiChatResponse clearMemory(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String conversationId) {
        var identity = identities.requireIdentity(authorization);
        try (var tenant = identity.bindTenant()) {
            return dialogueService.clearMemory(identity.conversationKey(conversationId));
        }
    }

}

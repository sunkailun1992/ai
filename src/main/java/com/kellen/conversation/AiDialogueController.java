package com.kellen.conversation;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.auth.WechatAuthService;
import com.kellen.auth.AiRequestIdentityResolver;
import org.springframework.beans.factory.annotation.Autowired;
import com.kellen.memory.ChatMemoryMessageDto;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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

    private final AiDialogueService dialogueService; // 对话编排服务，隔离 Controller 与 Spring AI 细节。
    private final WechatAuthService authService; // 微信认证服务，用于小程序会话归属。
    private final ChatConversationService conversationService; // 会话元数据服务，用于恢复和切换对话。
    private final AiRequestIdentityResolver identities;

    /**
     * 构造器注入对话服务，依赖显式化便于测试。
     *
     * @param dialogueService     对话编排服务。
     * @param authService         微信认证服务。
     * @param conversationService 会话元数据服务。
     */
    public AiDialogueController(AiDialogueService dialogueService,
                                WechatAuthService authService,
                                ChatConversationService conversationService) {
        this(dialogueService, authService, conversationService, null);
    }

    @Autowired
    public AiDialogueController(AiDialogueService dialogueService, WechatAuthService authService,
                                ChatConversationService conversationService, AiRequestIdentityResolver identities) {
        this.dialogueService = dialogueService; // 保存对话服务实例。
        this.authService = authService; // 保存微信认证服务实例。
        this.conversationService = conversationService; // 保存会话元数据服务实例。
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
     * @param authorization User 中心签发的有效 Bearer access JWT；微信 opaque 登录态不代表租户授权。
     * @param request       用户文本、可选会话 ID、角色、本轮补充要求和远程附件 URL。
     * @return 模型逐段输出的文本流。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody AiChatRequest request) {
        try {
            // fleet subject 与微信 owner 属于不同身份空间，不能把同值 ID 当成同一个微信用户。
            if (identities == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_IDENTITY_UNAVAILABLE");
            return dialogueService.stream(request, null, identities.requireIdentity(authorization));
        } catch (IllegalArgumentException exception) {
            return Flux.just("\n[请求参数错误] " + exception.getMessage()); // SSE 请求直接返回文本错误。
        }
    }

    /**
     * 新建当前登录用户的对话会话。
     *
     * @param authorization Authorization Header。
     * @param request       可选标题。
     * @return 新会话。
     */
    @PostMapping(value = "/conversations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationResponse createConversation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestBody(required = false) ConversationCreateRequest request) {
        AiWechatUserEntity user = currentUser(authorization); // 会话列表必须绑定登录用户。
        String title = request == null ? null : request.title(); // 标题可空。
        String conversationId = request == null ? null : request.conversationId(); // 可接管小程序本地缓存会话 ID。
        return conversationService.create(user.getId(), title, conversationId); // 创建并返回会话元数据。
    }

    /**
     * 查询当前登录用户的会话列表。
     *
     * @param authorization Authorization Header。
     * @return 会话列表，按最近更新时间倒序。
     */
    @GetMapping(value = "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ConversationResponse> conversations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AiWechatUserEntity user = currentUser(authorization); // 会话列表必须绑定登录用户。
        return conversationService.list(user.getId()); // 返回当前用户会话列表。
    }

    /**
     * 查询当前登录用户指定会话的展示消息。
     *
     * @param authorization  Authorization Header。
     * @param conversationId 会话 ID。
     * @return 展示消息列表。
     */
    @GetMapping(value = "/conversations/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ChatMemoryMessageDto> conversationMessages(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @PathVariable String conversationId) {
        AiWechatUserEntity user = currentUser(authorization); // 消息展示必须校验会话归属。
        return conversationService.messages(user.getId(), conversationId); // 返回该会话最近消息。
    }

    /**
     * 删除当前登录用户指定会话。
     *
     * @param authorization  Authorization Header。
     * @param conversationId 会话 ID。
     */
    @DeleteMapping(value = "/conversations/{conversationId}")
    public void deleteConversation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String conversationId) {
        AiWechatUserEntity user = currentUser(authorization); // 删除会话必须校验会话归属。
        conversationService.delete(user.getId(), conversationId); // 软删除会话并清理记忆、展示消息。
    }

    /**
     * 重命名当前登录用户指定会话。
     *
     * @param authorization  Authorization Header。
     * @param conversationId 会话 ID。
     * @param request        新标题请求体。
     * @return 更新后的会话。
     */
    @PostMapping(value = "/conversations/{conversationId}/rename", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationResponse renameConversation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String conversationId,
                                                   @RequestBody ConversationRenameRequest request) {
        AiWechatUserEntity user = currentUser(authorization); // 重命名必须校验会话归属。
        return conversationService.rename(user.getId(), conversationId, request == null ? null : request.title());
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

    private AiWechatUserEntity currentUser(String authorization) {
        try {
            return authService.currentUser(authorization);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }
}

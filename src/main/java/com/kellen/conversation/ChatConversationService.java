package com.kellen.conversation;

import com.kellen.memory.ChatMemoryMessageDto;
import com.kellen.memory.DbChatMemoryService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * AI 对话会话服务。
 *
 * <p>负责小程序会话列表、新建会话、切换会话前的归属校验，以及发送消息时更新标题和排序时间。
 * 模型可回放消息仍由 {@link DbChatMemoryService} 管理，避免会话元数据和模型记忆混在一起。</p>
 */
@Service // 注册为 Spring Service，供 Controller 和对话编排服务调用。
public class ChatConversationService {

    private static final DateTimeFormatter CONVERSATION_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ChatConversationRepository conversationRepository; // 会话元数据 Repository。
    private final DbChatMemoryService chatMemoryService; // 记忆服务，用于读取页面展示消息。
    private final ChatConversationMessageService conversationMessageService; // 展示消息服务，用于恢复附件历史。

    /**
     * 构造器注入依赖。
     *
     * @param conversationRepository 会话元数据 Repository。
     * @param chatMemoryService       数据库记忆服务。
     * @param conversationMessageService 展示消息服务。
     */
    public ChatConversationService(ChatConversationRepository conversationRepository,
                                   DbChatMemoryService chatMemoryService,
                                   ChatConversationMessageService conversationMessageService) {
        this.conversationRepository = conversationRepository;
        this.chatMemoryService = chatMemoryService;
        this.conversationMessageService = conversationMessageService;
    }

    /**
     * 新建用户会话。
     *
     * @param ownerUserId 所属微信用户 ID。
     * @param title       可选标题。
     * @return 新会话响应。
     */
    @Transactional // 新建会话需要写库。
    public ConversationResponse create(Long ownerUserId, String title) {
        return create(ownerUserId, title, null);
    }

    /**
     * 新建用户会话，可选择接管调用方已缓存的会话 ID。
     *
     * @param ownerUserId             所属微信用户 ID。
     * @param title                   可选标题。
     * @param requestedConversationId 可选会话 ID，用于升级旧小程序本地会话。
     * @return 新会话响应。
     */
    @Transactional // 新建会话需要写库。
    public ConversationResponse create(Long ownerUserId, String title, String requestedConversationId) {
        Long safeOwnerUserId = requiredOwnerUserId(ownerUserId);
        String conversationId = StringUtils.hasText(requestedConversationId) ? safeConversationId(requestedConversationId) : newConversationId(safeOwnerUserId);
        ChatConversationEntity existing = conversationRepository.findByConversationId(conversationId).orElse(null);
        if (existing != null) {
            if (!safeOwnerUserId.equals(existing.getOwnerUserId())) {
                throw new IllegalArgumentException("无权访问该会话");
            }
            return ConversationResponse.from(existing);
        }
        String safeTitle = StringUtils.hasText(title) ? ChatConversationEntity.titleFrom(title) : "新对话";
        ChatConversationEntity entity = conversationRepository.save(ChatConversationEntity.create(safeOwnerUserId, conversationId, safeTitle));
        return ConversationResponse.from(entity);
    }

    /**
     * 查询用户会话列表。
     *
     * @param ownerUserId 所属微信用户 ID。
     * @return 会话响应列表。
     */
    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long ownerUserId) {
        Long safeOwnerUserId = requiredOwnerUserId(ownerUserId);
        return conversationRepository.findByOwnerUserIdAndStatusOrderByUpdatedAtDesc(safeOwnerUserId, ChatConversationEntity.STATUS_ACTIVE)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    /**
     * 读取用户会话的展示消息。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     * @return 展示消息列表。
     */
    @Transactional // 展示消息读取可能刷新附件展示 token。
    public List<ChatMemoryMessageDto> messages(Long ownerUserId, String conversationId) {
        ChatConversationEntity conversation = ownedConversation(ownerUserId, conversationId);
        List<ChatMemoryMessageDto> displayMessages = conversationMessageService.messages(ownerUserId, conversation.getConversationId(), 100);
        if (!displayMessages.isEmpty()) {
            return displayMessages;
        }
        return chatMemoryService.displayEntities(conversation.getConversationId(), 100)
                .stream()
                .map(entity -> new ChatMemoryMessageDto(entity.getMessageType(), entity.getContent(), entity.getCreatedAt()))
                .toList();
    }

    /**
     * 删除当前用户指定会话。
     *
     * <p>会话元数据采用软删除；模型记忆和小程序展示消息同步清理，避免删除后仍影响后续上下文。</p>
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     */
    @Transactional // 删除会话需要同时更新元数据、清理记忆和展示消息。
    public void delete(Long ownerUserId, String conversationId) {
        ChatConversationEntity conversation = ownedConversation(ownerUserId, conversationId);
        conversation.markDeleted();
        conversationRepository.save(conversation);
        chatMemoryService.clear(conversation.getConversationId());
        conversationMessageService.deleteMessages(ownerUserId, conversation.getConversationId());
    }

    /**
     * 重命名当前用户指定会话。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     * @param title          新标题。
     * @return 更新后的会话响应。
     */
    @Transactional // 重命名会话需要校验归属并写库。
    public ConversationResponse rename(Long ownerUserId, String conversationId, String title) {
        ChatConversationEntity conversation = ownedConversation(ownerUserId, conversationId);
        conversation.rename(requiredText(title, "title"));
        return ConversationResponse.from(conversationRepository.save(conversation));
    }

    /**
     * 发送消息时更新或创建会话元数据。
     *
     * @param ownerUserId    所属微信用户 ID，可空；空值表示非小程序登录调用，不登记会话元数据。
     * @param conversationId 会话 ID。
     * @param userMessage    用户消息。
     */
    @Transactional // 本轮发送时需要更新最近消息时间和可能的标题。
    public void touchOnUserMessage(Long ownerUserId, String conversationId, String userMessage) {
        if (ownerUserId == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        String safeConversationId = safeConversationId(conversationId);
        ChatConversationEntity conversation = conversationRepository.findByOwnerUserIdAndConversationId(ownerUserId, safeConversationId)
                .orElseGet(() -> createOwnedConversation(ownerUserId, safeConversationId, userMessage));
        conversation.touchWithUserMessage(userMessage);
        conversationRepository.save(conversation);
    }

    private ChatConversationEntity createOwnedConversation(Long ownerUserId, String conversationId, String userMessage) {
        conversationRepository.findByConversationId(conversationId).ifPresent(existing -> {
            if (!ownerUserId.equals(existing.getOwnerUserId())) {
                throw new IllegalArgumentException("无权访问该会话");
            }
        });
        return ChatConversationEntity.create(ownerUserId, conversationId, ChatConversationEntity.titleFrom(userMessage));
    }

    private String newConversationId(Long ownerUserId) {
        return "wx-user-" + ownerUserId + "-" + LocalDateTime.now().format(CONVERSATION_TIME_FORMATTER)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String safeConversationId(String conversationId) {
        String value = requiredText(conversationId, "conversationId");
        if (value.length() > 128) {
            throw new IllegalArgumentException("conversationId 长度不能超过128");
        }
        return value;
    }

    private ChatConversationEntity ownedConversation(Long ownerUserId, String conversationId) {
        Long safeOwnerUserId = requiredOwnerUserId(ownerUserId);
        String safeConversationId = requiredText(conversationId, "conversationId");
        return conversationRepository.findByOwnerUserIdAndConversationId(safeOwnerUserId, safeConversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
    }

    private Long requiredOwnerUserId(Long ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return ownerUserId;
    }

    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }
}

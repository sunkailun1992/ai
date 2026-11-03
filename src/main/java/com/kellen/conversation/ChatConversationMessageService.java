package com.kellen.conversation;

import com.kellen.file.MediaInput;
import com.kellen.file.OssFileService;
import com.kellen.memory.ChatMemoryMessageDto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * AI 对话展示消息服务。
 *
 * <p>负责保存小程序聊天历史和附件预览信息。它不参与模型上下文回放，
 * 避免把图片 URL、展示 token 或附件元数据塞进 ai_chat_memory。</p>
 */
@Service // 注册为 Spring Service，供对话编排和会话服务调用。
public class ChatConversationMessageService {

    private static final TypeReference<List<ChatMessageAttachmentDto>> ATTACHMENT_LIST_TYPE = new TypeReference<>() {
    };

    private final ChatConversationMessageRepository messageRepository; // 展示消息 Repository。
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化组件，避免依赖 Web 自动配置。
    private final OssFileService ossFileService; // 文件服务，用于刷新历史附件展示 token。

    /**
     * 构造器注入依赖。
     *
     * @param messageRepository 展示消息 Repository。
     * @param ossFileService    文件服务。
     */
    public ChatConversationMessageService(ChatConversationMessageRepository messageRepository,
                                          OssFileService ossFileService) {
        this.messageRepository = messageRepository;
        this.ossFileService = ossFileService;
    }

    /**
     * 保存用户展示消息。
     *
     * @param ownerUserId    所属微信用户 ID，可空；空值不保存展示消息。
     * @param conversationId 会话 ID。
     * @param content        用户消息。
     * @param attachments    本轮附件。
     */
    @Transactional // 保存展示消息需要写库。
    public void recordUserMessage(Long ownerUserId, String conversationId, String content, List<MediaInput> attachments) {
        record(ownerUserId, conversationId, "user", content, serializeAttachments(attachments));
    }

    /**
     * 保存助手展示消息。
     *
     * @param ownerUserId    所属微信用户 ID，可空；空值不保存展示消息。
     * @param conversationId 会话 ID。
     * @param content        AI 回复。
     */
    @Transactional // 保存展示消息需要写库。
    public void recordAssistantMessage(Long ownerUserId, String conversationId, String content) {
        record(ownerUserId, conversationId, "assistant", content, null);
    }

    /**
     * 查询展示消息。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     * @param limitCount     最大条数。
     * @return 按时间正序排列的展示消息。
     */
    @Transactional // 历史附件需要刷新展示 token，因此不是只读事务。
    public List<ChatMemoryMessageDto> messages(Long ownerUserId, String conversationId, int limitCount) {
        int safeLimit = Math.max(1, Math.min(limitCount, 200));
        List<ChatConversationMessageEntity> entities = messageRepository.findByOwnerUserIdAndConversationId(
                ownerUserId, conversationId, safeLimit);
        Collections.reverse(entities);
        return entities.stream()
                .map(entity -> new ChatMemoryMessageDto(entity.getMessageType(), entity.getContent(), entity.getCreatedAt(),
                        refreshedAttachments(ownerUserId, entity.getAttachmentsJson())))
                .toList();
    }

    /**
     * 删除当前用户指定会话的展示消息。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     */
    @Transactional // 删除展示消息需要写库。
    public void deleteMessages(Long ownerUserId, String conversationId) {
        if (ownerUserId == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        messageRepository.deleteByOwnerUserIdAndConversationId(ownerUserId, conversationId.trim());
    }

    private void record(Long ownerUserId, String conversationId, String messageType, String content, String attachmentsJson) {
        if (ownerUserId == null || !StringUtils.hasText(conversationId) || !StringUtils.hasText(content)) {
            return;
        }
        messageRepository.save(ChatConversationMessageEntity.create(ownerUserId, conversationId.trim(), messageType, content, attachmentsJson));
    }

    private String serializeAttachments(List<MediaInput> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<ChatMessageAttachmentDto> values = attachments.stream()
                .filter(input -> input != null && StringUtils.hasText(input.url()) && StringUtils.hasText(input.mimeType()))
                .map(ChatMessageAttachmentDto::from)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("聊天附件序列化失败", exception);
        }
    }

    private List<ChatMessageAttachmentDto> refreshedAttachments(Long ownerUserId, String attachmentsJson) {
        if (!StringUtils.hasText(attachmentsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(attachmentsJson, ATTACHMENT_LIST_TYPE)
                    .stream()
                    .map(attachment -> refreshAttachment(ownerUserId, attachment))
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private ChatMessageAttachmentDto refreshAttachment(Long ownerUserId, ChatMessageAttachmentDto attachment) {
        if (attachment.fileId() == null) {
            return attachment;
        }
        try {
            OssFileService.CompletedFile completed = ossFileService.refreshDisplayToken(ownerUserId, attachment.fileId());
            return attachment.withDisplayUrl("/api/v1/files/" + completed.entity().getId() + "/view?token=" + completed.displayToken());
        } catch (RuntimeException exception) {
            return attachment;
        }
    }
}

package com.kellen.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 对话展示消息实体，对应 ai_chat_conversation_message 表。
 *
 * <p>本表服务小程序历史展示，保存用户/助手消息和题图等附件摘要。
 * 模型上下文仍由 ai_chat_memory 维护，审计仍由 ai_chat_message 维护。</p>
 */
@TableName("ai_chat_conversation_message")
public class ChatConversationMessageEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 展示消息 ID。

    private Long ownerUserId; // 所属微信用户 ID。

    private String conversationId; // 会话 ID。

    private String messageType; // user / assistant。

    private String content; // 展示文本内容。

    private String attachmentsJson; // 展示附件 JSON。

    private LocalDateTime createdAt; // 创建时间。

    /**
     * 创建展示消息。
     *
     * @param ownerUserId     所属微信用户 ID。
     * @param conversationId  会话 ID。
     * @param messageType     消息角色。
     * @param content         消息内容。
     * @param attachmentsJson 附件 JSON，可空。
     * @return 展示消息实体。
     */
    public static ChatConversationMessageEntity create(Long ownerUserId,
                                                       String conversationId,
                                                       String messageType,
                                                       String content,
                                                       String attachmentsJson) {
        ChatConversationMessageEntity entity = new ChatConversationMessageEntity();
        entity.ownerUserId = ownerUserId;
        entity.conversationId = conversationId;
        entity.messageType = messageType;
        entity.content = content;
        entity.attachmentsJson = attachmentsJson;
        return entity;
    }

    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public String getAttachmentsJson() {
        return attachmentsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

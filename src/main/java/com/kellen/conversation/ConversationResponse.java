package com.kellen.conversation;


import java.time.LocalDateTime;

/**
 * AI 对话会话响应。
 *
 * @param conversationId 会话 ID。
 * @param title          会话标题。
 * @param lastMessageAt  最近消息时间。
 * @param createdAt      创建时间。
 */
public record ConversationResponse(
        String conversationId,
        String title,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {

    /**
     * 从实体构造响应。
     *
     * @param entity 会话实体。
     * @return 会话响应。
     */
    public static ConversationResponse from(ChatConversationEntity entity) {
        return new ConversationResponse(entity.getConversationId(), entity.getTitle(), entity.getLastMessageAt(), entity.getCreatedAt());
    }
}

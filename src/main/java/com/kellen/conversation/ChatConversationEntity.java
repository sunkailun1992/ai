package com.kellen.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 对话会话实体，对应 ai_chat_conversation 表。
 *
 * <p>本表只保存会话元数据和所属微信用户，用于小程序恢复历史、切换会话和新建对话；
 * 真正回放给模型的消息仍保存在 ai_chat_memory 表。</p>
 */
@TableName("ai_chat_conversation")
public class ChatConversationEntity {

    public static final String STATUS_ACTIVE = "ACTIVE"; // 正常可见会话状态。
    public static final String STATUS_DELETED = "DELETED"; // 用户删除后的隐藏状态。

    @TableId(type = IdType.AUTO)
    private Long id; // 会话元数据 ID。

    private Long ownerUserId; // 所属微信用户 ID。

    private String conversationId; // 模型记忆使用的会话 ID。

    private String title; // 会话标题。

    private String status = STATUS_ACTIVE; // 会话状态。

    private LocalDateTime lastMessageAt; // 最近消息时间。

    private LocalDateTime createdAt; // 创建时间。

    private LocalDateTime updatedAt; // 更新时间。

    /**
     * 创建会话元数据。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 模型记忆会话 ID。
     * @param title          会话标题。
     * @return 会话实体。
     */
    public static ChatConversationEntity create(Long ownerUserId, String conversationId, String title) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.ownerUserId = ownerUserId;
        entity.conversationId = conversationId;
        entity.title = title;
        entity.status = STATUS_ACTIVE;
        entity.lastMessageAt = LocalDateTime.now();
        return entity;
    }

    /**
     * 记录本轮用户消息，必要时用首条用户消息更新默认标题。
     *
     * @param userMessage 用户消息。
     */
    public void touchWithUserMessage(String userMessage) {
        if ("新对话".equals(title) && userMessage != null && !userMessage.isBlank()) {
            title = titleFrom(userMessage);
        }
        lastMessageAt = LocalDateTime.now();
    }

    /**
     * 标记会话已删除。
     *
     * <p>采用软删除，避免误删审计链路；模型记忆和展示消息由服务层同步清理。</p>
     */
    public void markDeleted() {
        status = STATUS_DELETED;
        lastMessageAt = LocalDateTime.now();
    }

    /**
     * 重命名会话标题。
     *
     * @param newTitle 用户输入的新标题。
     */
    public void rename(String newTitle) {
        String normalized = newTitle == null ? "" : newTitle.replaceAll("\\s+", " ").trim();
        title = normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    /**
     * 从用户消息生成短标题。
     *
     * @param userMessage 用户消息。
     * @return 标题。
     */
    public static String titleFrom(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "新对话";
        }
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (lastMessageAt == null) {
            lastMessageAt = now;
        }
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

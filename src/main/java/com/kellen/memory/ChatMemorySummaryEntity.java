package com.kellen.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 会话短记忆滚动摘要，对应 ai_chat_memory_summary 表。
 *
 * <p>主流短记忆通常由“压缩摘要 + 最近若干条原文消息”组成。该表保存被裁剪出
 * lossless 窗口的旧消息摘要，ai_chat_memory 继续保存最近原文消息。</p>
 */
@TableName("ai_chat_memory_summary")
public class ChatMemorySummaryEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 摘要主键。

    private String conversationId; // 会话 ID。

    private String summaryText; // 该会话旧消息滚动摘要。

    private LocalDateTime summarizedAt; // 最近压缩时间。

    private LocalDateTime createdAt; // 创建时间。

    private LocalDateTime updatedAt; // 更新时间。

    /**
     * 创建新的会话摘要。
     *
     * @param conversationId 会话 ID。
     * @param summaryText    摘要正文。
     * @return 摘要实体。
     */
    public static ChatMemorySummaryEntity create(String conversationId, String summaryText) {
        ChatMemorySummaryEntity entity = new ChatMemorySummaryEntity();
        entity.conversationId = conversationId;
        entity.summaryText = summaryText;
        entity.summarizedAt = LocalDateTime.now();
        return entity;
    }

    /**
     * 更新摘要正文。
     *
     * @param summaryText 新摘要正文。
     */
    public void updateSummary(String summaryText) {
        this.summaryText = summaryText;
        this.summarizedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (summarizedAt == null) {
            summarizedAt = now;
        }
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getSummaryText() {
        return summaryText;
    }
}

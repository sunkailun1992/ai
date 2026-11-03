package com.kellen.memory;

import com.kellen.conversation.ChatMessageAttachmentDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话记忆消息视图，用于记忆查询接口，避免直接暴露数据库实体。
 *
 * @param messageType 消息角色：user / assistant / system。
 * @param content     消息文本内容。
 * @param createdAt   消息入库时间。
 * @param attachments 展示附件列表；模型记忆查询通常为空。
 */
public record ChatMemoryMessageDto(
        String messageType, // 消息角色。
        String content, // 消息文本内容。
        LocalDateTime createdAt, // 消息入库时间。
        List<ChatMessageAttachmentDto> attachments // 展示附件列表。
) {

    /**
     * 兼容纯文本消息构造。
     *
     * @param messageType 消息角色。
     * @param content     消息文本内容。
     * @param createdAt   消息入库时间。
     */
    public ChatMemoryMessageDto(String messageType, String content, LocalDateTime createdAt) {
        this(messageType, content, createdAt, List.of());
    }
}

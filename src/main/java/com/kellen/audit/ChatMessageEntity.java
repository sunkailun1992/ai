package com.kellen.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 对话审计记录实体，对应 ai_chat_message 表。
 *
 * <p>每轮模型调用一行，完整保存请求、模型、响应或错误摘要，面向问题排查和调用追溯。
 * 与 ai_chat_memory 记忆表是两条独立链路：审计永久保留，记忆可被调用方清空。</p>
 */
@TableName("ai_chat_message")
public class ChatMessageEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 审计记录主键。

    private String conversationId; // 会话 ID，调用方未传时归一化为 default。

    private String messageType; // 调用类型，当前对话统一为 chat_stream。

    private String model; // 本轮实际调用的模型名，必须与响应中的模型名一致。

    private String userMessage; // 用户本轮输入。

    private String systemPrompt; // 本轮实际发送给模型的角色系统提示词。

    private String assistantContent; // 模型返回内容，失败时为空。

    private String mediaSummary; // 多模态媒体 URL 和 MIME 摘要，纯文本调用为空。

    private boolean success; // 本轮模型调用是否成功。

    private String errorMessage; // 失败时记录错误摘要，成功时为空。

    private LocalDateTime createdAt; // 记录创建时间。

    /**
     * 构建一条成功的审计记录。
     *
     * @param conversationId   会话 ID，可空。
     * @param messageType      调用类型，当前对话统一为 chat_stream。
     * @param model            本轮实际调用的模型名。
     * @param userMessage      用户本轮输入。
     * @param systemPrompt     本轮实际发送给模型的角色系统提示词，可空。
     * @param assistantContent 模型返回内容。
     * @param mediaSummary     媒体摘要，可空。
     * @return 已填充成功状态的实体。
     */
    public static ChatMessageEntity success(String conversationId, String messageType, String model, String userMessage, String systemPrompt, String assistantContent, String mediaSummary) {
        ChatMessageEntity entity = new ChatMessageEntity(); // 创建实体实例。
        entity.conversationId = normalizeConversationId(conversationId); // 归一化会话 ID。
        entity.messageType = messageType; // 保存调用类型。
        entity.model = model; // 保存模型名。
        entity.userMessage = userMessage; // 保存用户输入。
        entity.systemPrompt = systemPrompt; // 保存实际角色系统提示词。
        entity.assistantContent = assistantContent; // 保存 AI 输出。
        entity.mediaSummary = mediaSummary; // 保存媒体摘要。
        entity.success = true; // 标记成功。
        return entity;
    }

    /**
     * 构建一条失败的审计记录，保证失败调用也可追溯。
     *
     * @param conversationId 会话 ID，可空。
     * @param messageType    调用类型，当前对话统一为 chat_stream。
     * @param model          本轮实际调用的模型名。
     * @param userMessage    用户本轮输入。
     * @param systemPrompt   本轮实际发送给模型的角色系统提示词，可空。
     * @param errorMessage   失败错误摘要。
     * @param mediaSummary   媒体摘要，可空。
     * @return 已填充失败状态的实体。
     */
    public static ChatMessageEntity failure(String conversationId, String messageType, String model, String userMessage, String systemPrompt, String errorMessage, String mediaSummary) {
        ChatMessageEntity entity = new ChatMessageEntity(); // 创建实体实例。
        entity.conversationId = normalizeConversationId(conversationId); // 归一化会话 ID。
        entity.messageType = messageType; // 保存调用类型。
        entity.model = model; // 保存模型名。
        entity.userMessage = userMessage; // 保存用户输入。
        entity.systemPrompt = systemPrompt; // 保存实际角色系统提示词。
        entity.errorMessage = errorMessage; // 保存错误摘要。
        entity.mediaSummary = mediaSummary; // 保存媒体摘要。
        entity.success = false; // 标记失败。
        return entity;
    }

    /**
     * MyBatis-Plus 写入前自动初始化创建时间。
     */
    void prePersist() {
        if (createdAt == null) { // 只在未显式赋值时设置。
            createdAt = LocalDateTime.now(); // 使用当前服务端时间。
        }
    }

    /**
     * 统一处理会话 ID，与记忆服务的归组口径保持一致。
     *
     * @param conversationId 调用方传入的会话 ID，可空。
     * @return 非空会话 ID；未传时统一归入 default 会话。
     */
    private static String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) { // 调用方未传会话 ID。
            return "default"; // 默认归入 default 会话。
        }
        return conversationId.trim(); // 去除首尾空白后保存。
    }
}

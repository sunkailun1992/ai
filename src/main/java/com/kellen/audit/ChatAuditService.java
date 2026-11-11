package com.kellen.audit;

import com.kellen.auth.AiRequestIdentity;
import org.springframework.stereotype.Service;

/**
 * 对话审计服务。
 *
 * <p>负责把每轮模型调用的请求、模型名、响应或错误摘要写入 ai_chat_message 审计表。
 * 审计与对话记忆（ai_chat_memory）是两条独立链路：审计面向排查问题和调用追溯，
 * 永远保留成功/失败状态；记忆面向模型上下文回放，可被调用方清空。</p>
 */
@Service // 注册为 Spring Service，供对话服务构造器注入。
public class ChatAuditService {

    private final ChatMessageRepository chatMessageRepository; // 审计表数据库访问接口。

    /**
     * 构造器注入审计表 Repository。
     *
     * @param chatMessageRepository ai_chat_message 表访问接口。
     */
    public ChatAuditService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository; // 保存 Repository。
    }

    /**
     * 记录一次成功的模型调用。
     *
     * @param identity         User 认证中心验证的本轮身份。
     * @param conversationId   会话 ID，空值由实体归一化为 default。
     * @param messageType      调用类型，当前对话统一为 chat_stream。
     * @param model            本轮实际调用的模型名。
     * @param userMessage      用户本轮输入。
     * @param systemPrompt     本轮实际发送给模型的角色系统提示词，可空。
     * @param assistantContent 模型返回内容。
     * @param mediaSummary     多模态媒体摘要，纯文本调用为空。
     */
    public void recordSuccess(AiRequestIdentity identity, String conversationId, String messageType, String model, String userMessage, String systemPrompt, String assistantContent, String mediaSummary) {
        chatMessageRepository.save(ChatMessageEntity.success(requiredTenant(identity), conversationId, messageType, model, userMessage, systemPrompt, assistantContent, mediaSummary)); // 写入成功审计记录。
    }

    /**
     * 记录一次失败的模型调用，保证失败也可追溯。
     *
     * @param identity       User 认证中心验证的本轮身份。
     * @param conversationId 会话 ID，空值由实体归一化为 default。
     * @param messageType    调用类型，当前对话统一为 chat_stream。
     * @param model          本轮实际调用的模型名。
     * @param userMessage    用户本轮输入。
     * @param systemPrompt   本轮实际发送给模型的角色系统提示词，可空。
     * @param errorMessage   失败错误摘要。
     * @param mediaSummary   多模态媒体摘要，纯文本调用为空。
     */
    public void recordFailure(AiRequestIdentity identity, String conversationId, String messageType, String model, String userMessage, String systemPrompt, String errorMessage, String mediaSummary) {
        chatMessageRepository.save(ChatMessageEntity.failure(requiredTenant(identity), conversationId, messageType, model, userMessage, systemPrompt, errorMessage, mediaSummary)); // 写入失败审计记录。
    }

    private static String requiredTenant(AiRequestIdentity identity) {
        if (identity == null) {
            throw new SecurityException("AI_IDENTITY_REQUIRED");
        }
        return identity.tenantId();
    }
}

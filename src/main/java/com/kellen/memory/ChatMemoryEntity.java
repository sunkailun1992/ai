package com.kellen.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 对话记忆实体，对应 ai_chat_memory 表。
 *
 * <p>按角色（user / assistant / system）逐条保存会话上下文，一条消息一行，
 * 供 DbChatMemoryService 回放给模型实现多轮记忆。与 ai_chat_message 审计表
 * 互相独立：记忆可被调用方清空，审计永久保留。</p>
 */
@TableName("ai_chat_memory")
public class ChatMemoryEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 记忆记录主键。

    private String conversationId; // 会话 ID，多轮对话依赖它取回各自历史。

    private String messageType; // 消息角色：user / assistant / system，与 Spring AI MessageType 取值对齐。

    private String content; // 该轮消息的文本内容；多模态请求只保留文本部分。

    private LocalDateTime createdAt; // 记录入库时间，记忆窗口和排序都基于该时间。

    /**
     * 构建一条记忆记录。
     *
     * @param conversationId 会话 ID。
     * @param messageType    消息角色：user / assistant / system。
     * @param content        消息文本内容。
     * @return 已填充字段的实体，创建时间由入库回调补齐。
     */
    public static ChatMemoryEntity of(String conversationId, String messageType, String content) {
        ChatMemoryEntity entity = new ChatMemoryEntity(); // 创建实体实例。
        entity.conversationId = conversationId; // 保存会话 ID。
        entity.messageType = messageType; // 保存消息角色。
        entity.content = content; // 保存消息内容。
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

    /** @return 会话 ID。 */
    public String getConversationId() {
        return conversationId;
    }

    /** @return 记忆记录主键。 */
    public Long getId() {
        return id;
    }

    /** @return 消息角色：user / assistant / system。 */
    public String getMessageType() {
        return messageType;
    }

    /** @return 消息文本内容。 */
    public String getContent() {
        return content;
    }

    /** @return 消息入库时间。 */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

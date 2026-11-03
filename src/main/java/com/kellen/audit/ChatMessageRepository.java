package com.kellen.audit;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;

/**
 * AI 对话审计记录 Repository，负责 ai_chat_message 表的数据访问。
 */
@Mapper
public interface ChatMessageRepository extends AiBaseRepository<ChatMessageEntity> {

    /**
     * 按会话查询最近 50 条审计记录，供后续排查或历史展示使用。
     *
     * @param conversationId 会话 ID。
     * @return 按创建时间倒序的审计记录列表。
     */
    default List<ChatMessageEntity> findTop50ByConversationIdOrderByCreatedAtDesc(String conversationId) {
        return selectList(new QueryWrapper<ChatMessageEntity>()
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT 50"));
    }
}

package com.kellen.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * AI 会话短记忆摘要 Repository。
 */
@Mapper
public interface ChatMemorySummaryRepository extends AiBaseRepository<ChatMemorySummaryEntity> {

    /**
     * 按会话 ID 查询滚动摘要。
     *
     * @param conversationId 会话 ID。
     * @return 摘要实体。
     */
    default Optional<ChatMemorySummaryEntity> findByConversationId(String conversationId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<ChatMemorySummaryEntity>()
                .eq("conversation_id", conversationId)));
    }

    /**
     * 删除会话摘要。
     *
     * @param conversationId 会话 ID。
     */
    default void deleteByConversationId(String conversationId) {
        delete(new QueryWrapper<ChatMemorySummaryEntity>().eq("conversation_id", conversationId));
    }
}

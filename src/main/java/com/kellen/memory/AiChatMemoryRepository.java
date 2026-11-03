package com.kellen.memory;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 对话记忆 Repository，负责 ai_chat_memory 表的数据访问。
 *
 * <p>命名带 Ai 前缀是有意为之：Spring AI 自动配置自带一个名为
 * chatMemoryRepository 的内存版 Bean，同名会导致 Bean 定义冲突。</p>
 */
@Mapper
public interface AiChatMemoryRepository extends AiBaseRepository<ChatMemoryEntity> {

    /**
     * 按会话查询时间窗口内的记忆，按创建时间倒序限制返回条数。
     *
     * @param conversationId 会话 ID。
     * @param createdAt      时间窗口起点，只返回该时间之后的记录。
     * @param limit          返回条数上限。
     * @return 窗口内的记忆实体列表。
     */
    default List<ChatMemoryEntity> findByConversationIdAndCreatedAtAfter(String conversationId, LocalDateTime createdAt, int limit) {
        return selectList(new QueryWrapper<ChatMemoryEntity>()
                .eq("conversation_id", conversationId)
                .gt("created_at", createdAt)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 按会话查询记忆消息，按创建时间倒序取最近 N 条。
     *
     * @param conversationId 会话 ID。
     * @param limit          返回条数上限。
     * @return 匹配会话的记忆实体列表。
     */
    default List<ChatMemoryEntity> findByConversationId(String conversationId, int limit) {
        return selectList(new QueryWrapper<ChatMemoryEntity>()
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 查询会话最早的若干条记忆，用于滚动摘要压缩。
     *
     * @param conversationId 会话 ID。
     * @param limit          返回条数上限。
     * @return 最早的记忆实体列表。
     */
    default List<ChatMemoryEntity> findOldestByConversationId(String conversationId, int limit) {
        return selectList(new QueryWrapper<ChatMemoryEntity>()
                .eq("conversation_id", conversationId)
                .orderByAsc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 统计会话短记忆消息数。
     *
     * @param conversationId 会话 ID。
     * @return 消息数。
     */
    default long countByConversationId(String conversationId) {
        return selectCount(new QueryWrapper<ChatMemoryEntity>().eq("conversation_id", conversationId));
    }

    /**
     * 批量删除指定记忆记录。
     *
     * @param ids 记忆主键列表。
     */
    default void deleteMemoryIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        deleteBatchIds(ids);
    }

    /**
     * 物理删除指定会话的全部记忆，供清空记忆接口使用。
     *
     * @param conversationId 会话 ID。
     */
    default void deleteByConversationId(String conversationId) {
        delete(new QueryWrapper<ChatMemoryEntity>().eq("conversation_id", conversationId));
    }
}

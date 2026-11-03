package com.kellen.conversation;

import com.kellen.memory.AiChatMemoryRepository;
import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Optional;

/**
 * AI 对话会话 Repository。
 *
 * <p>会话元数据只用于小程序恢复和切换对话，模型记忆消息仍由
 * {@link AiChatMemoryRepository} 管理。</p>
 */
@Mapper
public interface ChatConversationRepository extends AiBaseRepository<ChatConversationEntity> {

    /**
     * 查询用户自己的可见会话列表。
     *
     * @param ownerUserId 所属微信用户 ID。
     * @param status      会话状态。
     * @return 按最近更新时间倒序排列的会话列表。
     */
    default List<ChatConversationEntity> findByOwnerUserIdAndStatusOrderByUpdatedAtDesc(Long ownerUserId, String status) {
        return selectList(new QueryWrapper<ChatConversationEntity>()
                .eq("owner_user_id", ownerUserId)
                .eq("status", status)
                .orderByDesc("updated_at"));
    }

    /**
     * 按所属用户和会话 ID 查询会话。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     * @return 匹配会话。
     */
    default Optional<ChatConversationEntity> findByOwnerUserIdAndConversationId(Long ownerUserId, String conversationId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<ChatConversationEntity>()
                .eq("owner_user_id", ownerUserId)
                .eq("conversation_id", conversationId)
                .last("LIMIT 1")));
    }

    /**
     * 按会话 ID 查询会话，用于防止不同用户复用同一个 conversationId。
     *
     * @param conversationId 会话 ID。
     * @return 匹配会话。
     */
    default Optional<ChatConversationEntity> findByConversationId(String conversationId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<ChatConversationEntity>()
                .eq("conversation_id", conversationId)
                .last("LIMIT 1")));
    }
}

package com.kellen.conversation;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;

/**
 * AI 对话展示消息 Repository。
 *
 * <p>只服务小程序历史展示，不参与模型记忆回放和审计查询。</p>
 */
@Mapper
public interface ChatConversationMessageRepository extends AiBaseRepository<ChatConversationMessageEntity> {

    /**
     * 查询当前用户指定会话的最近展示消息。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     * @param limit          返回条数上限。
     * @return 展示消息列表。
     */
    default List<ChatConversationMessageEntity> findByOwnerUserIdAndConversationId(Long ownerUserId, String conversationId, int limit) {
        return selectList(new QueryWrapper<ChatConversationMessageEntity>()
                .eq("owner_user_id", ownerUserId)
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 删除当前用户指定会话的展示消息。
     *
     * @param ownerUserId    所属微信用户 ID。
     * @param conversationId 会话 ID。
     */
    default void deleteByOwnerUserIdAndConversationId(Long ownerUserId, String conversationId) {
        delete(new QueryWrapper<ChatConversationMessageEntity>()
                .eq("owner_user_id", ownerUserId)
                .eq("conversation_id", conversationId));
    }
}

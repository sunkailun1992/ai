package com.kellen.auth;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.Optional;

/**
 * AI 微信小程序登录会话 Repository，负责 ai_wechat_session 表的数据访问。
 */
@Mapper
public interface AiWechatSessionRepository extends AiBaseRepository<AiWechatSessionEntity> {

    /**
     * 按 token 哈希查询登录态。
     *
     * @param tokenHash Bearer token 的 SHA-256 哈希。
     * @return 匹配登录态，不存在时为空。
     */
    default Optional<AiWechatSessionEntity> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiWechatSessionEntity>()
                .eq("token_hash", tokenHash)
                .last("LIMIT 1")));
    }
}

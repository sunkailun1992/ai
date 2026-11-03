package com.kellen.auth;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.Optional;

/**
 * AI 微信小程序用户 Repository，负责 ai_wechat_user 表的数据访问。
 */
@Mapper
public interface AiWechatUserRepository extends AiBaseRepository<AiWechatUserEntity> {

    /**
     * 按微信 openid 查询用户。
     *
     * @param openId 微信小程序 openid。
     * @return 匹配用户，不存在时为空。
     */
    default Optional<AiWechatUserEntity> findByOpenId(String openId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiWechatUserEntity>()
                .eq("open_id", openId)
                .last("LIMIT 1")));
    }
}

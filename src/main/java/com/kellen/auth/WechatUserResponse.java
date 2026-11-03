package com.kellen.auth;


import java.time.LocalDateTime;

/**
 * 微信小程序用户响应。
 *
 * @param id          用户 ID。
 * @param nickname    用户昵称。
 * @param avatarUrl   头像 URL。
 * @param gender      性别，0 未知、1 男、2 女。
 * @param status      账号状态，1 正常、0 禁用。
 * @param lastLoginAt 最近登录时间。
 */
public record WechatUserResponse(
        Long id,
        String nickname,
        String avatarUrl,
        Integer gender,
        Integer status,
        LocalDateTime lastLoginAt
) {

    /**
     * 从实体构建脱敏用户响应，不返回 openid 和 unionid。
     *
     * @param entity 微信用户实体。
     * @return 脱敏用户响应。
     */
    public static WechatUserResponse from(AiWechatUserEntity entity) {
        return new WechatUserResponse(
                entity.getId(),
                entity.getNickname(),
                entity.getAvatarUrl(),
                entity.getGender(),
                entity.getStatus(),
                entity.getLastLoginAt()
        );
    }
}

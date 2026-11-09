package com.kellen.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 微信小程序登录会话实体，对应 ai_wechat_session 表。
 *
 * <p>小程序端持有随机 Bearer token，数据库只保存 SHA-256 哈希；
 * 退出登录或过期后该 token 不再可用。</p>
 */
@TableName("ai_wechat_session")
public class AiWechatSessionEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 会话 ID。

    private Long userId; // 关联用户 ID。

    private String tokenHash; // Bearer token 的 SHA-256 哈希。

    private LocalDateTime expiresAt; // token 过期时间。

    private boolean revoked; // 是否已失效。

    private LocalDateTime lastUsedAt; // 最近使用时间。

    private LocalDateTime createdAt; // 会话创建时间。

    private LocalDateTime updatedAt; // 会话更新时间。

    /**
     * 创建登录会话。
     *
     * @param userId    用户 ID。
     * @param tokenHash token 哈希。
     * @param expiresAt 过期时间。
     * @return 登录会话实体。
     */
    public static AiWechatSessionEntity create(Long userId, String tokenHash, LocalDateTime expiresAt) {
        AiWechatSessionEntity entity = new AiWechatSessionEntity();
        entity.userId = userId;
        entity.tokenHash = tokenHash;
        entity.expiresAt = expiresAt;
        entity.revoked = false;
        entity.lastUsedAt = LocalDateTime.now();
        return entity;
    }

    /**
     * 判断当前登录态是否仍可用。
     *
     * @param now 当前时间。
     * @return true 表示未吊销且未过期。
     */
    public boolean activeAt(LocalDateTime now) {
        return !revoked && expiresAt.isAfter(now);
    }

    /**
     * 标记登录态最近使用时间。
     */
    public void touchLastUsedAt() {
        this.lastUsedAt = LocalDateTime.now();
    }

    /**
     * 主动吊销登录态。
     */
    public void revoke() {
        this.revoked = true;
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }
}

package com.kellen.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 微信小程序用户实体，对应 ai_wechat_user 表。
 *
 * <p>微信登录通过 openid 唯一识别用户；昵称和头像来自小程序授权资料，
 * 仅作为 AI 小程序展示和后续患者档案绑定的轻量账户信息。</p>
 */
@TableName("ai_wechat_user")
public class AiWechatUserEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 用户 ID。

    private String openId; // 微信小程序用户唯一标识。

    private String unionId; // 微信开放平台统一用户 ID，可空。

    private String nickname; // 用户昵称。

    private String avatarUrl; // 用户头像 URL。

    private Integer gender; // 用户性别。

    private Integer status = 1; // 默认正常。

    private LocalDateTime lastLoginAt; // 最近登录时间。

    private LocalDateTime createdAt; // 用户注册时间。

    private LocalDateTime updatedAt; // 用户资料最近更新时间。

    /**
     * 创建微信小程序用户。
     *
     * @param openId  微信 openid。
     * @param unionId 微信 unionid，可空。
     * @return 新用户实体。
     */
    public static AiWechatUserEntity create(String openId, String unionId) {
        AiWechatUserEntity entity = new AiWechatUserEntity();
        entity.openId = openId;
        entity.unionId = unionId;
        entity.status = 1;
        return entity;
    }

    /**
     * 更新微信授权资料。
     *
     * @param nickname  昵称，可空。
     * @param avatarUrl 头像 URL，可空。
     * @param gender    性别，可空。
     */
    public void updateProfile(String nickname, String avatarUrl, Integer gender) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname.trim();
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            this.avatarUrl = avatarUrl.trim();
        }
        if (gender != null) {
            this.gender = gender;
        }
    }

    /**
     * 标记用户已登录。
     */
    public void markLoggedIn() {
        this.lastLoginAt = LocalDateTime.now();
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (lastLoginAt == null) {
            lastLoginAt = now;
        }
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getOpenId() {
        return openId;
    }

    public String getUnionId() {
        return unionId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Integer getGender() {
        return gender;
    }

    public Integer getStatus() {
        return status;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }
}

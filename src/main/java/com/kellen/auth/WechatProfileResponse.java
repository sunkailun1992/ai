package com.kellen.auth;

import java.time.LocalDateTime;

/**
 * 微信小程序通用资料响应。
 *
 * @param success   是否成功。
 * @param userInfo  脱敏用户信息。
 * @param error     错误摘要。
 * @param timestamp 响应时间。
 */
public record WechatProfileResponse(
        boolean success,
        WechatUserResponse userInfo,
        String error,
        LocalDateTime timestamp
) {

    /**
     * 构建成功响应。
     *
     * @param userInfo 脱敏用户信息。
     * @return 成功响应。
     */
    public static WechatProfileResponse ok(WechatUserResponse userInfo) {
        return new WechatProfileResponse(true, userInfo, null, LocalDateTime.now());
    }

    /**
     * 构建失败响应。
     *
     * @param error 错误摘要。
     * @return 失败响应。
     */
    public static WechatProfileResponse error(String error) {
        return new WechatProfileResponse(false, null, error, LocalDateTime.now());
    }
}

package com.kellen.auth;

import java.time.LocalDateTime;

/**
 * 微信小程序登录响应。
 *
 * @param success   是否成功。
 * @param token     Bearer token 明文，仅登录成功时返回一次。
 * @param tokenType token 类型，固定 Bearer。
 * @param userInfo  脱敏用户信息。
 * @param error     错误摘要。
 * @param timestamp 响应时间。
 */
public record WechatLoginResponse(
        boolean success,
        String token,
        String tokenType,
        WechatUserResponse userInfo,
        String error,
        LocalDateTime timestamp
) {

    /**
     * 构建登录成功响应。
     *
     * @param token    Bearer token 明文。
     * @param userInfo 脱敏用户信息。
     * @return 成功响应。
     */
    public static WechatLoginResponse ok(String token, WechatUserResponse userInfo) {
        return new WechatLoginResponse(true, token, "Bearer", userInfo, null, LocalDateTime.now());
    }

    /**
     * 构建失败响应。
     *
     * @param error 错误摘要。
     * @return 失败响应。
     */
    public static WechatLoginResponse error(String error) {
        return new WechatLoginResponse(false, null, "Bearer", null, error, LocalDateTime.now());
    }
}

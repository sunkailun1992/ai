package com.kellen.auth;

/**
 * 微信小程序登录请求。
 *
 * @param code      小程序端 {@code wx.login} 返回的临时凭证。
 * @param nickName  微信授权昵称，可空。
 * @param avatarUrl 微信授权头像 URL，可空。
 * @param gender    微信授权性别，0 未知、1 男、2 女，可空。
 */
public record WechatLoginRequest(
        String code,
        String nickName,
        String avatarUrl,
        Integer gender
) {
}

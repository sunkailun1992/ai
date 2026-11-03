package com.kellen.auth;

import com.kellen.config.AiProperties;

import com.kellen.config.wechat.CommonWechatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 微信小程序登录服务。
 *
 * <p>对齐 renting-house 小程序的登录体验：前端通过 {@code wx.login} 拿 code，
 * 后端换取 openid 并自动注册用户，然后返回 Bearer token。与示例项目不同的是，
 * 本服务不使用硬编码 JWT 密钥，而是生成随机 token 并只保存哈希。</p>
 */
@Service // 注册为 Spring Service，供认证 Controller 调用。
public class WechatAuthService {

    private static final String WECHAT_CODE_SESSION_PATH = "/sns/jscode2session";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AiProperties properties; // 微信 appId、appSecret 和 token 有效期配置。
    private final CommonWechatProperties commonWechatProperties; // 公共配置中心顶层 wechat.* 兜底配置。
    private final AiWechatUserRepository userRepository; // 微信用户表访问接口。
    private final AiWechatSessionRepository sessionRepository; // 微信登录会话表访问接口。
    private final RestClient wechatRestClient; // 微信 API HTTP 客户端。
    private final ObjectMapper objectMapper = new ObjectMapper(); // 微信 JSON 响应解析器。

    /**
     * 构造器注入微信登录依赖。
     *
     * @param properties        AI 配置对象。
     * @param userRepository    微信用户 Repository。
     * @param sessionRepository 微信会话 Repository。
     */
    public WechatAuthService(AiProperties properties,
                             CommonWechatProperties commonWechatProperties,
                             AiWechatUserRepository userRepository,
                             AiWechatSessionRepository sessionRepository) {
        this.properties = properties;
        this.commonWechatProperties = commonWechatProperties;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.wechatRestClient = RestClient.builder().baseUrl("https://api.weixin.qq.com").build();
    }

    /**
     * 微信小程序登录并返回 Bearer token。
     *
     * @param request 小程序登录请求。
     * @return 登录 token 和脱敏用户信息。
     */
    @Transactional // 用户注册、资料更新和登录态写入必须原子完成。
    public WechatLoginResponse login(WechatLoginRequest request) {
        if (request == null || !StringUtils.hasText(request.code())) {
            throw new IllegalArgumentException("code 不能为空");
        }
        WechatCodeSession codeSession = exchangeCode(request.code().trim());
        AiWechatUserEntity user = userRepository.findByOpenId(codeSession.openId())
                .orElseGet(() -> AiWechatUserEntity.create(codeSession.openId(), codeSession.unionId()));
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        user.updateProfile(request.nickName(), request.avatarUrl(), request.gender());
        user.markLoggedIn();
        AiWechatUserEntity savedUser = userRepository.save(user);

        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(Math.max(1, configuredTokenTtlDays()));
        sessionRepository.save(AiWechatSessionEntity.create(savedUser.getId(), tokenHash(token), expiresAt));
        return WechatLoginResponse.ok(token, WechatUserResponse.from(savedUser));
    }

    /**
     * 根据 Authorization Header 查询当前登录用户。
     *
     * @param authorization Authorization Header，格式为 Bearer token。
     * @return 当前登录用户实体。
     */
    @Transactional // touch 登录态最近使用时间需要写库。
    public AiWechatUserEntity currentUser(String authorization) {
        String token = bearerToken(authorization);
        AiWechatSessionEntity session = sessionRepository.findByTokenHash(tokenHash(token))
                .orElseThrow(() -> new IllegalArgumentException("未登录或登录已过期"));
        if (!session.activeAt(LocalDateTime.now())) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        session.touchLastUsedAt();
        sessionRepository.save(session);
        AiWechatUserEntity user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        return user;
    }

    /**
     * 主动退出登录，吊销当前 Bearer token。
     *
     * @param authorization Authorization Header。
     */
    @Transactional // 吊销状态需要立即写库。
    public void logout(String authorization) {
        String token = bearerToken(authorization);
        sessionRepository.findByTokenHash(tokenHash(token)).ifPresent(session -> {
            session.revoke();
            sessionRepository.save(session);
        });
    }

    private WechatCodeSession exchangeCode(String code) {
        AiProperties.WechatProperties wechat = properties.wechat();
        String appId = firstText(wechat.appId(), commonWechatProperties.appId());
        String appSecret = firstText(wechat.appSecret(), commonWechatProperties.appSecret());
        if (!StringUtils.hasText(appId)) {
            throw new IllegalStateException("微信 appId 未配置");
        }
        if (!StringUtils.hasText(appSecret)) {
            throw new IllegalStateException("微信 appSecret 未配置，无法完成真实微信登录");
        }
        try {
            String body = wechatRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path(WECHAT_CODE_SESSION_PATH)
                            .queryParam("appid", appId)
                            .queryParam("secret", appSecret)
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            String openId = text(root.path("openid").asText(""));
            if (!StringUtils.hasText(openId)) {
                String errMsg = text(root.path("errmsg").asText("微信登录失败"));
                throw new IllegalArgumentException(errMsg);
            }
            return new WechatCodeSession(openId, text(root.path("unionid").asText("")));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("微信登录请求失败: " + exception.getMessage(), exception);
        }
    }

    private String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return token;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to hash token", exception);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private long configuredTokenTtlDays() {
        long aiTtl = properties.wechat().tokenTtlDays();
        if (aiTtl > 0) {
            return aiTtl;
        }
        return commonWechatProperties.tokenTtlDays();
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : text(fallback);
    }

    private record WechatCodeSession(String openId, String unionId) {
    }
}

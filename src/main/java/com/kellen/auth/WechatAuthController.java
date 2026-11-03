package com.kellen.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信小程序认证接口控制器。
 *
 * <p>接口路径对齐小程序常见登录模式：{@code wx.login} code 提交到后端，
 * 后端返回 Bearer token；小程序后续访问 {@code /me} 或 AI 接口时统一携带
 * {@code Authorization: Bearer <token>}。</p>
 */
@RestController // 声明 REST Controller，返回 JSON。
@RequestMapping({"/api/v1/auth", "/api/auth"}) // 兼容参考小程序版本化路径和当前项目简短路径。
public class WechatAuthController {

    private final WechatAuthService authService; // 微信登录业务服务。

    /**
     * 构造器注入微信认证服务。
     *
     * @param authService 微信认证服务。
     */
    public WechatAuthController(WechatAuthService authService) {
        this.authService = authService;
    }

    /**
     * 微信小程序登录接口。
     *
     * @param request 小程序端 wx.login 获取到的 code 和可选头像昵称。
     * @return Bearer token 和脱敏用户信息。
     */
    @PostMapping(value = "/wx-login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WechatLoginResponse> login(@RequestBody WechatLoginRequest request) {
        try {
            return ResponseEntity.ok(authService.login(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(WechatLoginResponse.error(exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(WechatLoginResponse.error(exception.getMessage()));
        }
    }

    /**
     * 查询当前登录用户。
     *
     * @param authorization Authorization Header。
     * @return 脱敏用户信息。
     */
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WechatProfileResponse> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            AiWechatUserEntity user = authService.currentUser(authorization);
            return ResponseEntity.ok(WechatProfileResponse.ok(WechatUserResponse.from(user)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(WechatProfileResponse.error(exception.getMessage()));
        }
    }

    /**
     * 退出登录并吊销当前 token。
     *
     * @param authorization Authorization Header。
     * @return 退出结果。
     */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            authService.logout(authorization);
            return ResponseEntity.ok(Map.of("success", true, "message", "退出成功"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", exception.getMessage()));
        }
    }

    /**
     * 小程序域名连通性测试接口。
     *
     * @return 固定成功响应。
     */
    @GetMapping(value = "/test-domain", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> testDomain() {
        return Map.of("success", true, "message", "AI backend is reachable");
    }
}

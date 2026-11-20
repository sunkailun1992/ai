package com.kellen.auth;

import com.kellen.security.oauth2.FleetAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 取本轮请求的已验证身份：资源服务器（utils 的 {@code FleetResourceServerAutoConfiguration}）已校验 Keycloak 访问令牌，
 * 并经 user 服务取到业务上下文；这里只读安全上下文里的认证结果，不再自己解析 Authorization 头或令牌。
 */
@Service
public class AiRequestIdentityResolver {

    /**
     * 当前请求的身份。
     *
     * @return 已验证的用户、活动租户与部门
     * @throws ResponseStatusException 401：没有可用的用户身份（机器令牌、业务上下文不可用或标识不合法）
     */
    public AiRequestIdentity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof FleetAuthenticationToken token) || !token.isActive() || token.getUser() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REQUIRED");
        }
        try {
            return new AiRequestIdentity(token.getUser());
        } catch (IllegalArgumentException invalid) {
            // 不把业务上下文里的标识原文放进 HTTP 错误与日志。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REJECTED");
        }
    }
}

package com.kellen.controlplane;

import com.kellen.auth.AiRequestIdentity;
import com.kellen.auth.AiRequestIdentityResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 业务控制面（存储配置、模型目录、默认模型）共用的全局管理门禁：只放行平台超级管理员且具有 {@code user:auth:manage} 的用户。
 *
 * <p>角色与权限码来自 user 服务给出的业务上下文（资源服务器写入认证结果的权限集合：权限码与 {@code ROLE_} 角色），
 * 不信任任何请求头。</p>
 */
@Service
public class AiControlPlaneAdministrator {
    private static final AuthorizationManager<Object> PLATFORM_ADMINISTRATOR = AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole("PLATFORM_SUPER_ADMIN"),
            AuthorityAuthorizationManager.hasAuthority("user:auth:manage"));

    private final AiRequestIdentityResolver identities;

    public AiControlPlaneAdministrator(AiRequestIdentityResolver identities) {
        this.identities = identities;
    }

    /**
     * 要求当前用户是平台管理员。
     *
     * @return 当前用户的已验证身份
     * @throws ResponseStatusException 401：没有用户身份；403：不是平台超级管理员或缺少 {@code user:auth:manage}
     */
    public AiRequestIdentity require() {
        AiRequestIdentity identity = identities.current();
        var decision = PLATFORM_ADMINISTRATOR.authorize(() -> SecurityContextHolder.getContext().getAuthentication(), this);
        if (decision == null || !decision.isGranted()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_CONTROL_PLANE_ADMIN_REQUIRED");
        }
        return identity;
    }
}

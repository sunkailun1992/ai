package com.kellen.controlplane;

import com.kellen.auth.AiRequestIdentity;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.utils.auth.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/** AI 业务控制面共用的全局管理门禁；仅信任已验证的活跃 User 身份与签名声明。 */
@Service
public class AiControlPlaneAdministrator {
    private final AiRequestIdentityResolver identities;

    public AiControlPlaneAdministrator(AiRequestIdentityResolver identities) {
        this.identities = identities;
    }

    public AiRequestIdentity require(String authorization) {
        AiRequestIdentity identity = identities.requireIdentity(authorization);
        Claims claims;
        try {
            claims = JwtUtils.parseJwt(identity.accessToken());
            if (!identity.userId().equals(claims.get("userId"))
                    || !identity.tenantId().equals(claims.get("tenantId"))) {
                throw new SecurityException("AI_IDENTITY_REJECTED");
            }
        } catch (RuntimeException rejected) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REJECTED");
        }
        if (!"PLATFORM_SUPER_ADMIN".equals(claims.get("adminType"))
                || !(claims.get("permissions") instanceof Collection<?> permissions)
                || !permissions.contains("user:auth:manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_CONTROL_PLANE_ADMIN_REQUIRED");
        }
        return identity;
    }
}

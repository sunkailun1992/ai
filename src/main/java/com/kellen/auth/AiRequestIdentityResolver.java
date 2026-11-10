package com.kellen.auth;

import com.kellen.rpc.auth.AuthSessionRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** AI scoped 能力使用认证中心 User JWT；微信 opaque 会话仍由既有微信服务维护归属。 */
@Service
public class AiRequestIdentityResolver {
    private final AuthSessionRpcService sessions;

    @Autowired
    public AiRequestIdentityResolver(ObjectProvider<AiSessionRpcClient> clients) {
        this.sessions = token -> {
            var client = clients.getIfAvailable();
            if (client == null) throw new IllegalStateException("AI_IDENTITY_UNAVAILABLE");
            return client.resolveAccessIdentity(token);
        };
    }
    public AiRequestIdentityResolver(AuthSessionRpcService sessions) { this.sessions = sessions; }

    public AiRequestIdentity requireIdentity(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() > 16391 || authorization.substring(7).isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REQUIRED");
        }
        String token = authorization.substring(7);
        try {
            var verified = sessions.resolveAccessIdentity(token);
            if (verified == null) throw new SecurityException("AI_IDENTITY_REJECTED");
            return new AiRequestIdentity(verified.getUserId(), verified.getTenantId(), token);
        } catch (RuntimeException rejected) {
            // 不把 RPC、token 或 JWT claim 原文放入 HTTP 错误与日志。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REJECTED");
        }
    }
}

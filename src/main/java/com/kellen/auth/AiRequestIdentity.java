package com.kellen.auth;

import com.kellen.utils.context.TenantContextHolder;

/** 已经 User 认证中心验证的不可变本轮身份；token 仅用于转发，禁止进入日志与正文。 */
public final class AiRequestIdentity {
    private final String userId;
    private final String tenantId;
    private final String accessToken;

    public AiRequestIdentity(String userId, String tenantId, String accessToken) {
        if (userId == null || !userId.matches("[A-Za-z0-9_.:@-]{1,128}")
                || tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("AI_IDENTITY_INVALID");
        }
        this.userId = userId; this.tenantId = tenantId; this.accessToken = accessToken;
    }

    public String userId() { return userId; }
    public String tenantId() { return tenantId; }
    public String accessToken() { return accessToken; }
    @Override public String toString() { return "AiRequestIdentity[verified]"; }

    /** User 与微信身份不共用会话键；请求不能猜出另一个 User 的记忆命名空间。 */
    public String conversationKey(String requested) {
        String id = requested == null || requested.isBlank() ? "default" : requested.trim();
        if (id.length() > 256) throw new IllegalArgumentException("conversationId too long");
        try {
            byte[] value = (tenantId + "\n" + userId + "\n" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return "fleet_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Scope 只覆盖当前执行线程；异步信号每次重新绑定并恢复，不能长时间持有线程状态。 */
    public TenantScope bindTenant() {
        return new TenantScope(tenantId);
    }

    /** 纯异步订阅阶段不携带 ambient 租户，防止新 worker 继承未经验证的请求头上下文。 */
    public static TenantScope suspendTenant() {
        return new TenantScope(null);
    }

    public static final class TenantScope implements AutoCloseable {
        private final String previous = TenantContextHolder.getTenantId();
        private final boolean previousIgnore = TenantContextHolder.isIgnore();
        private TenantScope(String tenantId) {
            TenantContextHolder.clearIgnore();
            TenantContextHolder.setTenantId(tenantId);
        }
        @Override public void close() {
            TenantContextHolder.setTenantId(previous);
            if (previousIgnore) TenantContextHolder.ignore(); else TenantContextHolder.clearIgnore();
        }
    }
}

package com.kellen.auth;

import com.kellen.security.SecurityUser;
import com.kellen.security.UserContextHolder;
import com.kellen.utils.context.TenantContextHolder;

/**
 * 本轮请求已验证的身份：资源服务器校验 Keycloak 访问令牌后，由 user 服务给出的业务上下文（用户、活动租户、部门）。
 *
 * <p>只保存业务上下文快照，不保存令牌；调用 ai-agent 用令牌交换（utils 的 {@code FleetOAuth2Clients}）。
 * 异步信号上写库前用 {@link #bindTenant()}、{@link #bindUser()} 把本轮身份显式绑定到当前线程，关闭时恢复原值。</p>
 */
public final class AiRequestIdentity {
    private final SecurityUser user;

    /**
     * 由业务上下文构造本轮身份。
     *
     * @param user user 服务给出的业务上下文
     * @throws IllegalArgumentException 用户、租户或部门标识不合法
     */
    public AiRequestIdentity(SecurityUser user) {
        if (user == null || user.getUserId() == null || !user.getUserId().matches("[A-Za-z0-9_.:@-]{1,128}")
                || user.getTenantId() == null || !user.getTenantId().matches("[A-Za-z0-9_-]{1,128}")
                || (user.getDeptId() != null && !user.getDeptId().isBlank() && !user.getDeptId().matches("[A-Za-z0-9_.:-]{1,64}"))) {
            throw new IllegalArgumentException("AI_IDENTITY_INVALID");
        }
        this.user = user;
    }

    public String userId() { return user.getUserId(); }
    public String tenantId() { return user.getTenantId(); }
    /** 部门 ID，用户没有部门时为空；审计列 create_dept 由它填写。 */
    public String deptId() { return user.getDeptId(); }
    @Override public String toString() { return "AiRequestIdentity[verified]"; }

    /** 按已验证的租户与 User 身份隔离会话键；请求不能猜出另一身份的记忆命名空间。 */
    public String conversationKey(String requested) {
        String id = requested == null || requested.isBlank() ? "default" : requested.trim();
        if (id.length() > 256) throw new IllegalArgumentException("conversationId too long");
        try {
            byte[] value = (tenantId() + "\n" + userId() + "\n" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return "fleet_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Scope 只覆盖当前执行线程；异步信号每次重新绑定并恢复，不能长时间持有线程状态。 */
    public TenantScope bindTenant() {
        return new TenantScope(tenantId());
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

    /** 异步信号写库前绑定本轮登录用户（审计列的创建人、更新人与创建部门取它），关闭时恢复该线程原值。 */
    public UserScope bindUser() {
        return new UserScope(user);
    }

    /** 纯异步订阅阶段不携带入口用户，防止此时新建的 worker 按 TTL 继承它，之后替别人的请求写库。 */
    public static UserScope suspendUser() {
        return new UserScope(null);
    }

    public static final class UserScope implements AutoCloseable {
        private final SecurityUser previous = UserContextHolder.get();
        private UserScope(SecurityUser user) {
            apply(user);
        }
        @Override public void close() {
            apply(previous);
        }
        private static void apply(SecurityUser user) {
            if (user == null) UserContextHolder.clear(); else UserContextHolder.set(user);
        }
    }
}

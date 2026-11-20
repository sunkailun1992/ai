package com.ai.support;

import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.SecurityContextResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * user 服务业务上下文的测试替身：按用户 ID 登记用户，规则与 user 服务一致（不存在、停用、无权访问所请求的租户）。
 * utils 会把成功结果缓存 30 秒，同一用户 ID 在测试里不要换权限，换场景就换用户 ID。
 */
public class FixtureUsers {

    private final Map<String, SecurityUser> users = new ConcurrentHashMap<>();

    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();

    /**
     * 登记启用的用户，所属租户只有活动租户。
     *
     * @param userId      用户 ID
     * @param tenantId    活动租户 ID
     * @param deptId      部门 ID，可空
     * @param authorities 权限码与 ROLE_ 角色
     * @return 登记的用户
     */
    public SecurityUser put(String userId, String tenantId, String deptId, String... authorities) {
        SecurityUser user = TestIdentities.user(userId, tenantId, deptId, authorities);
        users.put(userId, user);
        return user;
    }

    /**
     * 登记停用的用户。
     *
     * @param userId   用户 ID
     * @param tenantId 活动租户 ID
     */
    public void putDisabled(String userId, String tenantId) {
        users.put(userId, put(userId, tenantId, null).toBuilder().enabled(false).build());
    }

    /**
     * 让 user 服务对这个用户的查询失败（模拟 user 服务暂时不可用），失败原文带私有标记，响应里不能出现。
     *
     * @param userId 用户 ID
     */
    public void failFor(String userId) {
        unavailable.add(userId);
    }

    SecurityContextResult resolve(String userId, String requestedTenantId) {
        if (unavailable.contains(userId)) {
            throw new IllegalStateException("synthetic-private-rpc-address");
        }
        SecurityUser user = users.get(userId);
        if (user == null) {
            return SecurityContextResult.of(SecurityContextResult.Status.USER_NOT_FOUND);
        }
        if (!user.isEnabled()) {
            return SecurityContextResult.of(SecurityContextResult.Status.DISABLED);
        }
        if (requestedTenantId != null && !user.getTenantIds().contains(requestedTenantId)) {
            return SecurityContextResult.of(SecurityContextResult.Status.TENANT_DENIED);
        }
        return SecurityContextResult.ok(user);
    }
}

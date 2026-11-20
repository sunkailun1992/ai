package com.ai.support;

import com.kellen.auth.AiRequestIdentity;
import com.kellen.security.SecurityUser;

import java.util.List;

/**
 * 测试里构造本轮已验证身份：与资源服务器从 user 服务取到的业务上下文同形（启用、所属租户只有活动租户）。
 */
public final class TestIdentities {

    private TestIdentities() {
    }

    /**
     * 没有部门的身份。
     *
     * @param userId   用户 ID
     * @param tenantId 活动租户 ID
     * @return 身份
     */
    public static AiRequestIdentity of(String userId, String tenantId) {
        return of(userId, tenantId, null);
    }

    /**
     * 带部门的身份。
     *
     * @param userId   用户 ID
     * @param tenantId 活动租户 ID
     * @param deptId   部门 ID，可空
     * @return 身份
     */
    public static AiRequestIdentity of(String userId, String tenantId, String deptId) {
        return new AiRequestIdentity(user(userId, tenantId, deptId));
    }

    /**
     * 业务上下文。
     *
     * @param userId      用户 ID
     * @param tenantId    活动租户 ID
     * @param deptId      部门 ID，可空
     * @param authorities 权限码与 ROLE_ 角色
     * @return 业务上下文
     */
    public static SecurityUser user(String userId, String tenantId, String deptId, String... authorities) {
        return SecurityUser.builder().userId(userId).username(userId).tenantId(tenantId).deptId(deptId)
                .dataScope("SELF").dataScopeDeptIds(List.of()).authorities(List.of(authorities))
                .enabled(true).tenantIds(tenantId == null ? List.of() : List.of(tenantId)).build();
    }
}

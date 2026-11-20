package com.kellen.controlplane;

import com.ai.support.TestIdentities;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.security.oauth2.FleetAuthenticationToken;
import com.kellen.security.oauth2.SecurityContextResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 控制面管理门禁：只认资源服务器写入的业务上下文权限（user 服务给的权限码与 ROLE_ 角色），平台超级管理员且有 user:auth:manage 才放行。 */
class AiControlPlaneAdministratorTest {
    private final AiControlPlaneAdministrator administrator = new AiControlPlaneAdministrator(new AiRequestIdentityResolver());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void onlyActivePlatformAdministratorWithManagePermissionPasses() {
        signIn(SecurityContextResult.Status.OK, "ROLE_PLATFORM_SUPER_ADMIN", "user:auth:manage", "ROLE_manager");
        var identity = administrator.require();
        assertThat(identity.userId()).isEqualTo("fixture-user");
        assertThat(identity.tenantId()).isEqualTo("fixture-tenant");
        assertThat(identity.toString()).doesNotContain("Bearer", "fixture-user", "fixture-tenant");
    }

    @Test void tenantAdministratorWithTheSamePermissionCannotManageGlobalConfiguration() {
        signIn(SecurityContextResult.Status.OK, "ROLE_TENANT_ADMIN", "user:auth:manage");
        rejected(HttpStatus.FORBIDDEN);
    }

    @Test void platformAdministratorWithoutManagePermissionCannotPass() {
        signIn(SecurityContextResult.Status.OK, "ROLE_PLATFORM_SUPER_ADMIN");
        rejected(HttpStatus.FORBIDDEN);
    }

    @Test void normalUserCannotPass() {
        signIn(SecurityContextResult.Status.OK, "ROLE_NONE", "user:auth:resources");
        rejected(HttpStatus.FORBIDDEN);
    }

    @Test void adminTypeWithoutTheRolePrefixIsNotARole() {
        signIn(SecurityContextResult.Status.OK, "PLATFORM_SUPER_ADMIN", "user:auth:manage");
        rejected(HttpStatus.FORBIDDEN);
    }

    @Test void missingOrInactiveIdentityIsUnauthenticated() {
        rejected(HttpStatus.UNAUTHORIZED);
        signIn(SecurityContextResult.Status.DISABLED, "ROLE_PLATFORM_SUPER_ADMIN", "user:auth:manage");
        rejected(HttpStatus.UNAUTHORIZED);
    }

    private static void signIn(SecurityContextResult.Status status, String... authorities) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic-access-token").header("alg", "RS256").claim("user_id", "fixture-user")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        var user = TestIdentities.user("fixture-user", "fixture-tenant", "fixture-dept", authorities);
        var result = status == SecurityContextResult.Status.OK ? SecurityContextResult.ok(user) : SecurityContextResult.of(status);
        var granted = status == SecurityContextResult.Status.OK ? AuthorityUtils.createAuthorityList(authorities) : AuthorityUtils.NO_AUTHORITIES;
        SecurityContextHolder.getContext().setAuthentication(new FleetAuthenticationToken(jwt, result, granted, "fixture-user"));
    }

    private void rejected(HttpStatus status) {
        assertThatThrownBy(administrator::require)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
    }
}

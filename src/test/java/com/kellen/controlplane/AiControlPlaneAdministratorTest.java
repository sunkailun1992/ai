package com.kellen.controlplane;

import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.utils.auth.JwtKeyConfig;
import com.kellen.utils.auth.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 JWT 签验与身份解析，只替换 User RPC；不访问真实认证中心或配置。 */
class AiControlPlaneAdministratorTest {
    private AiControlPlaneAdministrator administrator;
    private String resolvedUser;
    private String resolvedTenant;
    private boolean revoked;

    @BeforeEach void initialize() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        new JwtKeyConfig(new MockEnvironment().withProperty("security.jwt.secret", Base64.getEncoder().encodeToString(bytes)));
        resolvedUser = "fixture-user"; resolvedTenant = "fixture-tenant"; revoked = false;
        administrator = new AiControlPlaneAdministrator(new AiRequestIdentityResolver(token -> {
            if (revoked) throw new SecurityException("synthetic-private-revoked");
            JwtUtils.parseJwt(token);
            var identity = new AuthSessionIdentityRpcDTO();
            identity.setUserId(resolvedUser); identity.setTenantId(resolvedTenant);
            return identity;
        }));
    }

    @Test void onlyActivePlatformAdministratorWithManagePermissionPasses() {
        var identity = administrator.require(bearer("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")));
        assertThat(identity.userId()).isEqualTo("fixture-user");
        assertThat(identity.tenantId()).isEqualTo("fixture-tenant");
        assertThat(identity.toString()).doesNotContain("Bearer", "fixture-user", "fixture-tenant");
    }

    @Test void tenantAdministratorWithTheSamePermissionCannotManageGlobalConfiguration() {
        rejected(bearer("TENANT_ADMIN", List.of("user:auth:manage")), HttpStatus.FORBIDDEN);
    }

    @Test void platformAdministratorWithoutManagePermissionCannotPass() {
        rejected(bearer("PLATFORM_SUPER_ADMIN", List.of()), HttpStatus.FORBIDDEN);
    }

    @Test void normalUserCannotPassEvenWithManageRoleName() {
        rejected(bearer("NONE", List.of()), HttpStatus.FORBIDDEN);
    }

    @Test void permissionStringIsNotARecognizedPermissionCollection() {
        rejected(bearer("PLATFORM_SUPER_ADMIN", "user:auth:manage"), HttpStatus.FORBIDDEN);
    }

    @Test void signedClaimsMustMatchTheActivelyResolvedUserAndTenant() {
        String authorization = bearer("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage"));
        resolvedUser = "other-user"; rejected(authorization, HttpStatus.UNAUTHORIZED);
        resolvedUser = "fixture-user"; resolvedTenant = "other-tenant";
        rejected(authorization, HttpStatus.UNAUTHORIZED);
    }

    @Test void revokedIdentityCannotPassAnOtherwiseValidSignedToken() {
        String authorization = bearer("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage"));
        revoked = true; rejected(authorization, HttpStatus.UNAUTHORIZED);
    }

    @Test void missingAndInvalidSignedTokensCannotPass() {
        rejected(null, HttpStatus.UNAUTHORIZED);
        rejected(bearer("PLATFORM_SUPER_ADMIN", List.of("user:auth:manage")) + ".invalid", HttpStatus.UNAUTHORIZED);
    }

    private String bearer(String adminType, Object permissions) {
        return "Bearer " + JwtUtils.createJwt(UUID.randomUUID().toString(), "fixture-user", Map.of(
                "userId", "fixture-user", "tenantId", "fixture-tenant", "adminType", adminType,
                "permissions", permissions, "roles", List.of("user:auth:manage")));
    }

    private void rejected(String authorization, HttpStatus status) {
        assertThatThrownBy(() -> administrator.require(authorization))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
    }
}

package com.kellen.auth;

import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.FleetAuthenticationToken;
import com.kellen.security.oauth2.SecurityContextResult;
import com.kellen.utils.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class AiRequestIdentityTest {
    @AfterEach void clean() { TenantContextHolder.clear(); }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    @Test void identityComesOnlyFromTheVerifiedBusinessContextAndStaysOutOfStringRepresentation() {
        signIn(com.ai.support.TestIdentities.user("user-a", "tenant-a", "dept-a"), SecurityContextResult.Status.OK);
        var identity = new AiRequestIdentityResolver().current();
        assertThat(identity.userId()).isEqualTo("user-a");
        assertThat(identity.tenantId()).isEqualTo("tenant-a");
        assertThat(identity.deptId()).isEqualTo("dept-a");
        assertThat(identity.toString()).doesNotContain("user-a", "tenant-a", "dept-a", "synthetic");
    }
    @Test void anonymousMachineAndInactiveContextsHaveNoIdentity() {
        var resolver = new AiRequestIdentityResolver();
        assertThat(rejection(resolver)).isEqualTo(HttpStatus.UNAUTHORIZED);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("github-rag", null, "SCOPE_api"));
        assertThat(rejection(resolver)).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (var status : java.util.List.of(SecurityContextResult.Status.DISABLED, SecurityContextResult.Status.TENANT_DENIED)) {
            signIn(com.ai.support.TestIdentities.user("user-a", "tenant-a", null), status);
            assertThat(rejection(resolver)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
    @Test void malformedIdentifiersFromTheContextAreRejectedWithoutEchoingThem() {
        var resolver = new AiRequestIdentityResolver();
        for (var user : java.util.List.of(com.ai.support.TestIdentities.user("user a", "tenant-a", null),
                com.ai.support.TestIdentities.user("user-a", "tenant/a", null),
                com.ai.support.TestIdentities.user("user-a", "tenant-a", "dept a"))) {
            signIn(user, SecurityContextResult.Status.OK);
            assertThatThrownBy(resolver::current).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(failure.getReason()).isEqualTo("AI_IDENTITY_REJECTED");
            });
        }
        signIn(com.ai.support.TestIdentities.user("user-a", "tenant-a", " "), SecurityContextResult.Status.OK);
        assertThat(resolver.current().deptId()).isBlank();
    }
    private static void signIn(SecurityUser user, SecurityContextResult.Status status) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic-access-token").header("alg", "RS256").claim("user_id", user.getUserId())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        var result = status == SecurityContextResult.Status.OK ? SecurityContextResult.ok(user) : SecurityContextResult.of(status);
        SecurityContextHolder.getContext().setAuthentication(new FleetAuthenticationToken(jwt, result, java.util.List.of(), user.getUserId()));
    }
    private static org.springframework.http.HttpStatusCode rejection(AiRequestIdentityResolver resolver) {
        try {
            resolver.current();
            throw new AssertionError("identity unexpectedly resolved");
        } catch (ResponseStatusException rejected) {
            return rejected.getStatusCode();
        }
    }
    @Test void conversationsRemainStableAcrossTurnsAndCannotAliasAnotherIdentityOrWechatOwner() {
        var a = com.ai.support.TestIdentities.of("user-a", "tenant-a");
        var b = com.ai.support.TestIdentities.of("user-b", "tenant-a");
        var c = com.ai.support.TestIdentities.of("user-a", "tenant-b");
        String first = a.conversationKey("client-conversation");
        assertThat(a.conversationKey("client-conversation")).isEqualTo(first);
        assertThat(b.conversationKey("client-conversation")).isNotEqualTo(first);
        assertThat(c.conversationKey("client-conversation")).isNotEqualTo(first);
        assertThat(a.conversationKey(first)).isNotEqualTo(first);
        assertThat(b.conversationKey(first)).isNotEqualTo(first);
        assertThat(first).matches("fleet_[0-9a-f]{64}").isNotEqualTo("client-conversation");
    }
    @Test void everyWorkerBindingRestoresPreviousTenantEvenWhenOperationFails() {
        var worker = Schedulers.newSingle("identity-isolation");
        try {
            for (String tenant : java.util.List.of("tenant-a","tenant-b")) {
                String observed = Mono.fromCallable(() -> {
                    TenantContextHolder.setTenantId("prior-worker"); TenantContextHolder.ignore();
                    var identity = com.ai.support.TestIdentities.of("user-a", tenant);
                    try {
                        try (var scope = identity.bindTenant()) {
                            assertThat(TenantContextHolder.isIgnore()).isFalse();
                            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenant);
                            throw new IllegalStateException("synthetic-operation-failed");
                        }
                    } catch (IllegalStateException expected) {
                        assertThat(TenantContextHolder.isIgnore()).isTrue();
                        return TenantContextHolder.getTenantId();
                    } finally { TenantContextHolder.clear(); }
                }).subscribeOn(worker).block();
                assertThat(observed).isEqualTo("prior-worker");
                assertThat(Mono.fromCallable(TenantContextHolder::hasTenantId).subscribeOn(worker).block()).isFalse();
            }
        } finally { worker.dispose(); }
    }
}

package com.kellen.auth;

import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.rpc.auth.AuthSessionRpcService;
import com.kellen.utils.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiRequestIdentityTest {
    @AfterEach void clean() { TenantContextHolder.clear(); }
    @Test void onlyVerifiedIdsBecomeAuthorityAndCredentialsStayOutOfStringRepresentation() {
        AuthSessionRpcService auth = mock(AuthSessionRpcService.class);
        var dto = new AuthSessionIdentityRpcDTO(); dto.setUserId("user-a"); dto.setTenantId("tenant-a");
        when(auth.resolveAccessIdentity("synthetic-token")).thenReturn(dto);
        var resolver = new AiRequestIdentityResolver(auth);
        var identity = resolver.requireIdentity("Bearer synthetic-token");
        assertThat(identity.userId()).isEqualTo("user-a"); assertThat(identity.tenantId()).isEqualTo("tenant-a");
        assertThat(identity.toString()).doesNotContain("synthetic-token", "user-a", "tenant-a");
        assertThatThrownBy(() -> resolver.requireIdentity(null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> resolver.requireIdentity("forged-tenant-header")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(auth,times(1)).resolveAccessIdentity(any());
    }
    @Test void conversationsRemainStableAcrossTurnsAndCannotAliasAnotherIdentityOrWechatOwner() {
        var a = new AiRequestIdentity("user-a","tenant-a","synthetic-token");
        var b = new AiRequestIdentity("user-b","tenant-a","synthetic-token");
        var c = new AiRequestIdentity("user-a","tenant-b","synthetic-token");
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
                    var identity = new AiRequestIdentity("user-a",tenant,"synthetic-token");
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

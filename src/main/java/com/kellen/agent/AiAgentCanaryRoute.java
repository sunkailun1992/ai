package com.kellen.agent;

import com.kellen.traffic.CanaryContext;
import com.kellen.traffic.CanaryGrant;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 只向配置的内部网关换发患者分析的目标服务授权。 */
final class AiAgentCanaryRoute {

    static final String PATIENT_ANALYSIS_PATH = "/api/agents/patient-analysis/stream";

    private final String gateway;
    private final Set<String> tags;
    private final String signingKey;

    AiAgentCanaryRoute(Environment environment) {
        gateway = property(environment, "ai.agent-client.canary.gateway-base-url");
        tags = Arrays.stream(property(environment, "ai.agent-client.canary.tags").split(","))
                .map(String::trim).filter(CanaryGrant::validLabel).collect(Collectors.toUnmodifiableSet());
        signingKey = property(environment, "traffic.governance.canary.signing-key");
    }

    String url(CanaryContext.Snapshot canary, boolean businessSigningEnabled) {
        if (!businessSigningEnabled || !eligible(canary.grant()) || gateway.isEmpty()) {
            throw unavailable();
        }
        try {
            URI uri = URI.create(gateway);
            String host = uri.getHost();
            boolean protectedTransport = "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(host));
            if (!protectedTransport || host == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw unavailable();
            }
            return uri.resolve(PATIENT_ANALYSIS_PATH).toASCIIString();
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    String authorize(CanaryContext.Snapshot canary) {
        CanaryGrant verified = CanaryGrant.verify(canary.token(), signingKey, Instant.now());
        if (verified == null || !eligible(verified) || !verified.equals(canary.grant())) {
            throw unavailable();
        }
        return CanaryGrant.issue("ai-agent", verified.tag(), verified.expiresAt(), signingKey);
    }

    private boolean eligible(CanaryGrant grant) {
        return "ai".equals(grant.service()) && tags.contains(grant.tag()) && CanaryGrant.validKey(signingKey);
    }

    private static String property(Environment environment, String name) {
        return environment == null ? "" : environment.getProperty(name, "").trim();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("AI_AGENT_CANARY_UNAVAILABLE");
    }
}

package com.kellen.agent;

import com.kellen.config.AiAgentClientProperties;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.utils.auth.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主 AI 服务的默认 Agent Provider 选择服务。
 *
 * <p>模型执行仍在 {@code ai-agent}，本服务只保存和校验当前默认 Provider，并在
 * 调用 Agent 时通过请求级 Provider 覆盖传递给执行层。</p>
 */
@Service
public class AiModelSelectionService {

    private static final Logger log = LoggerFactory.getLogger(AiModelSelectionService.class);
    private static final String CONFIG_KEY = "default-agent-provider";
    private static final Set<String> SUPPORTED_PROVIDERS = new LinkedHashSet<>(List.of("google", "deepseek", "qwen"));

    private final AiAgentClientProperties properties;
    private final AiModelSelectionRepository repository;
    private final AiRequestIdentityResolver identities;
    private volatile String currentProvider;
    private volatile LocalDateTime updatedAt;

    public AiModelSelectionService(AiAgentClientProperties properties,
                                   AiModelSelectionRepository repository,
                                   AiRequestIdentityResolver identities) {
        this.properties = properties;
        this.repository = repository;
        this.identities = identities;
        this.currentProvider = normalizeOrFallback(properties.defaultProvider());
    }

    @PostConstruct
    public void initialize() {
        try {
            repository.findByConfigKey(CONFIG_KEY).ifPresentOrElse(entity -> {
                currentProvider = normalizeOrFallback(entity.getProvider());
                updatedAt = entity.getUpdatedAt();
            }, () -> {
                AiModelSelectionEntity entity = AiModelSelectionEntity.create(CONFIG_KEY, currentProvider);
                repository.save(entity);
                updatedAt = entity.getUpdatedAt();
            });
        } catch (BadSqlGrammarException exception) {
            log.warn("AI 模型选择表不可用，已使用配置默认 Provider 启动: provider={}", currentProvider, exception);
            updatedAt = null;
        }
    }

    /**
     * 当前默认 Provider。
     *
     * @return 当前 Provider。
     */
    public String currentProvider() {
        return currentProvider;
    }

    /**
     * 更新默认 Provider。
     *
     * @param provider Provider。
     * @param authorization User 认证中心签发的 Bearer JWT。
     * @return 更新后的 Provider。
     */
    public String updateDefaultProvider(String provider, String authorization) {
        requireGlobalModelAdministrator(authorization);
        String normalized = normalizeOrThrow(provider);
        AiModelSelectionEntity entity = repository.findByConfigKey(CONFIG_KEY)
                .orElseGet(() -> AiModelSelectionEntity.create(CONFIG_KEY, normalized));
        entity.setProvider(normalized);
        repository.save(entity);
        currentProvider = normalized;
        updatedAt = entity.getUpdatedAt();
        return normalized;
    }

    /** 全局配置只允许认证中心确认仍活跃、同时具有管理权限的平台管理员写入。 */
    private void requireGlobalModelAdministrator(String authorization) {
        var identity = identities.requireIdentity(authorization);
        Claims claims;
        try {
            claims = JwtUtils.parseJwt(identity.accessToken());
            if (!identity.userId().equals(claims.get("userId"))
                    || !identity.tenantId().equals(claims.get("tenantId"))) {
                throw new SecurityException("AI_IDENTITY_REJECTED");
            }
        } catch (RuntimeException rejected) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI_IDENTITY_REJECTED");
        }
        if (!"PLATFORM_SUPER_ADMIN".equals(claims.get("adminType"))
                || !(claims.get("permissions") instanceof Collection<?> permissions)
                || !permissions.contains("user:auth:manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_MODEL_ADMIN_REQUIRED");
        }
    }

    public List<String> supportedProviders() {
        return List.copyOf(SUPPORTED_PROVIDERS);
    }

    public AiModelSelectionResponse response(String provider, String model, List<String> supportedProviders) {
        return new AiModelSelectionResponse(
                normalizeOrFallback(provider),
                StringUtils.hasText(model) ? model.trim() : "",
                supportedProviders == null || supportedProviders.isEmpty() ? supportedProviders() : supportedProviders,
                updatedAt
        );
    }

    private String normalizeOrThrow(String provider) {
        String normalized = normalize(provider);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported ai provider: " + provider);
        }
        return normalized;
    }

    private String normalizeOrFallback(String provider) {
        String normalized = normalize(provider);
        return SUPPORTED_PROVIDERS.contains(normalized) ? normalized : "google";
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}

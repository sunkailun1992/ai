package com.kellen.agent;

import com.kellen.config.NacosAiAgentRegistryProperties;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos AI Agent Registry 运行时读取客户端。
 *
 * <p>该客户端使用 Nacos 3.2 官方 {@link AiService} 读取 AgentCard，只消费已发布
 * Agent 元数据，不修改 Nacos 中的资源。读取失败时返回空，由调用方显式失败暴露
 * Registry 配置问题，不再回退本地模型或普通服务发现地址。</p>
 */
@Service
public class NacosAiAgentRegistryClient implements AiAgentRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiAgentRegistryClient.class);

    private final NacosAiAgentRegistryProperties properties;
    private final ConcurrentHashMap<AgentOperation, CachedEndpoint> cache = new ConcurrentHashMap<>();
    private volatile AiService aiService;
    private volatile String aiServiceKey = "";

    public NacosAiAgentRegistryClient(NacosAiAgentRegistryProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<AgentEndpoint> endpoint(AgentOperation operation) {
        if (!properties.enabled() || operation == null || !StringUtils.hasText(properties.serverAddr())) {
            return Optional.empty();
        }
        CachedEndpoint cached = cache.get(operation);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cached.endpoint());
        }
        Optional<AgentEndpoint> fetched = fetchEndpoint(operation);
        fetched.ifPresent(endpoint -> cache.put(operation, new CachedEndpoint(endpoint, cacheExpireAt())));
        return fetched;
    }

    private Optional<AgentEndpoint> fetchEndpoint(AgentOperation operation) {
        String agentName = agentName(operation);
        if (!StringUtils.hasText(agentName)) {
            return Optional.empty();
        }
        try {
            AgentCardDetailInfo agentCard = aiService().getAgentCard(agentName);
            return resolveEndpoint(agentCard, operation, agentName);
        } catch (NacosException | RuntimeException exception) {
            log.warn("读取 Nacos AI AgentCard 失败，operation={}, agentName={}, message={}",
                    operation, agentName, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AgentEndpoint> resolveEndpoint(AgentCardDetailInfo agentCard,
                                                    AgentOperation operation,
                                                    String agentName) {
        if (agentCard == null) {
            return Optional.empty();
        }
        Optional<AgentEndpoint> matched = firstMatchingInterface(agentCard.getSupportedInterfaces(), operation, true);
        if (matched.isPresent()) {
            return matched;
        }
        matched = firstMatchingInterface(agentCard.getAdditionalInterfaces(), operation, true);
        if (matched.isPresent()) {
            return matched;
        }
        if (AgentOperation.PATIENT_ANALYSIS.equals(operation)) {
            matched = firstMatchingInterface(agentCard.getSupportedInterfaces(), operation, false);
            if (matched.isPresent()) {
                return matched;
            }
            matched = firstMatchingInterface(agentCard.getAdditionalInterfaces(), operation, false);
            if (matched.isPresent()) {
                return matched;
            }
        }
        if (StringUtils.hasText(agentCard.getUrl())
                && (AgentOperation.PATIENT_ANALYSIS.equals(operation) || matches(agentCard.getUrl(), operation))) {
            return Optional.of(new AgentEndpoint(
                    agentCard.getUrl().trim(),
                    firstText(agentCard.getPreferredTransport()),
                    firstText(agentCard.getProtocolVersion(), agentCard.getVersion())
            ));
        }
        log.warn("Nacos AI AgentCard 未找到匹配接口，operation={}, agentName={}, version={}",
                operation, agentName, agentCard.getVersion());
        return Optional.empty();
    }

    private Optional<AgentEndpoint> firstMatchingInterface(List<AgentInterface> interfaces,
                                                          AgentOperation operation,
                                                          boolean requireMatch) {
        if (interfaces == null || interfaces.isEmpty()) {
            return Optional.empty();
        }
        for (AgentInterface agentInterface : interfaces) {
            if (agentInterface == null || !StringUtils.hasText(agentInterface.getUrl())) {
                continue;
            }
            if (requireMatch && !matches(agentInterface.getUrl(), operation)) {
                continue;
            }
            return Optional.of(new AgentEndpoint(
                    agentInterface.getUrl().trim(),
                    firstText(agentInterface.getProtocolBinding(), agentInterface.getTransport()),
                    agentInterface.getProtocolVersion()
            ));
        }
        return Optional.empty();
    }

    private boolean matches(String text, AgentOperation operation) {
        String searchable = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return switch (operation) {
            case PATIENT_ANALYSIS -> containsAny(searchable, "patient-analysis", "patient", "report-analysis", "report");
            case QUESTIONNAIRE_REPLY -> containsAny(searchable, "questionnaire/reply", "reply", "conversational-questionnaire");
            case QUESTIONNAIRE_INTERPRET -> containsAny(searchable, "questionnaire/interpret", "interpret", "answer-interpretation");
        };
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private AiService aiService() throws NacosException {
        String currentKey = serviceKey();
        AiService current = aiService;
        if (current != null && currentKey.equals(aiServiceKey)) {
            return current;
        }
        synchronized (this) {
            if (aiService != null && currentKey.equals(aiServiceKey)) {
                return aiService;
            }
            shutdownQuietly(aiService);
            aiService = AiFactory.createAiService(nacosProperties());
            aiServiceKey = currentKey;
            cache.clear();
            return aiService;
        }
    }

    private Properties nacosProperties() {
        Properties nacosProperties = new Properties();
        nacosProperties.setProperty(PropertyKeyConst.SERVER_ADDR, normalizeServerAddr(properties.serverAddr()));
        if (StringUtils.hasText(properties.namespace())) {
            nacosProperties.setProperty(PropertyKeyConst.NAMESPACE, properties.namespace().trim());
        }
        if (StringUtils.hasText(properties.username())) {
            nacosProperties.setProperty(PropertyKeyConst.USERNAME, properties.username().trim());
        }
        if (StringUtils.hasText(properties.password())) {
            nacosProperties.setProperty(PropertyKeyConst.PASSWORD, properties.password());
        }
        if (StringUtils.hasText(properties.contextPath())) {
            nacosProperties.setProperty(PropertyKeyConst.CONTEXT_PATH, normalizeContextPath(properties.contextPath()));
        }
        return nacosProperties;
    }

    private String normalizeServerAddr(String value) {
        String serverAddr = text(value);
        if (!StringUtils.hasText(serverAddr)) {
            return serverAddr;
        }
        if (serverAddr.startsWith("http://") || serverAddr.startsWith("https://")) {
            URI uri = URI.create(serverAddr);
            int port = uri.getPort();
            return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
        }
        return trimTrailingSlash(serverAddr);
    }

    private String normalizeContextPath(String value) {
        String contextPath = text(value);
        while (contextPath.startsWith("/")) {
            contextPath = contextPath.substring(1);
        }
        return trimTrailingSlash(contextPath);
    }

    private String serviceKey() {
        List<String> values = new ArrayList<>();
        values.add(normalizeServerAddr(properties.serverAddr()));
        values.add(text(properties.namespace()));
        values.add(text(properties.username()));
        values.add(text(properties.contextPath()));
        return String.join("|", values);
    }

    private String agentName(AgentOperation operation) {
        return switch (operation) {
            case PATIENT_ANALYSIS -> properties.patientAnalysisAgentName();
            case QUESTIONNAIRE_REPLY, QUESTIONNAIRE_INTERPRET -> properties.questionnaireAgentName();
        };
    }

    private Instant cacheExpireAt() {
        return Instant.now().plusSeconds(Math.max(1, properties.cacheTtlSeconds()));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void shutdown() {
        shutdownQuietly(aiService);
    }

    private void shutdownQuietly(AiService service) {
        if (service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (NacosException exception) {
            log.debug("关闭 Nacos AI Registry SDK 客户端失败，message={}", exception.getMessage());
        }
    }

    private record CachedEndpoint(AgentEndpoint endpoint, Instant expiresAt) {
    }
}

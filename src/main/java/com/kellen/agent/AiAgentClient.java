package com.kellen.agent;

import com.kellen.config.AiAgentClientProperties;
import com.kellen.security.oauth2.client.FleetOAuth2Clients;
import com.kellen.traffic.CanaryContext;
import com.kellen.traffic.CanaryGrant;
import com.kellen.file.MediaInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 主 AI 服务调用独立 Agent 执行服务的 HTTP 客户端。
 *
 * <p>普通请求通过 Nacos AI Registry 解析 AgentCard，已授权的患者分析灰度请求使用专用 Gateway 入口。
 * 每个请求都带 RFC 8693 令牌交换换来的访问令牌（客户端注册 {@value #AGENT_REGISTRATION}：当前用户的令牌换成受众
 * {@code github-ai-agent} 的令牌）；没换到令牌就报错，不调用 ai-agent。目标地址只允许无嵌入凭据的 HTTP(S) 与两个已知接口路径，
 * 注册中心里被篡改的地址拿不到令牌。</p>
 */
@Service
public class AiAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);

    /** 调用 ai-agent 的出站客户端注册 ID（Nacos ai-spring.yaml 的 spring.security.oauth2.client.registration.github-ai-agent）。 */
    public static final String AGENT_REGISTRATION = "github-ai-agent";

    private static final String MODEL_PATH = "/api/agents/model";
    private static final Set<String> MODEL_QUERY_KEYS = Set.of("provider");

    private final AiAgentClientProperties properties;
    private final AiAgentRegistryClient registryClient;
    private final AiModelSelectionService modelSelectionService;
    private final WebClient webClient;
    private final AiAgentCanaryRoute canaryRoute;

    /**
     * 构造 Agent HTTP 客户端。
     *
     * @param properties            Agent 调用超时配置。
     * @param registryClient        Nacos AI Agent Registry 客户端。
     * @param modelSelectionService 当前模型选择服务。
     * @param oauth2Clients         utils 的出站令牌工具，按 {@value #AGENT_REGISTRATION} 注册做令牌交换。
     * @param environment           灰度入口配置来源。
     */
    public AiAgentClient(AiAgentClientProperties properties,
                         AiAgentRegistryClient registryClient,
                         AiModelSelectionService modelSelectionService,
                         FleetOAuth2Clients oauth2Clients,
                         Environment environment) {
        this.properties = properties;
        this.registryClient = registryClient;
        this.modelSelectionService = modelSelectionService;
        this.webClient = WebClient.builder().filter(oauth2Clients.webClientFilter(AGENT_REGISTRATION)).build();
        this.canaryRoute = new AiAgentCanaryRoute(environment);
    }

    /**
     * 调用患者分析 Agent 流式接口。令牌交换以订阅时安全上下文里的当前用户为主体：要在 servlet 请求线程里订阅。
     *
     * @param conversationId  会话 ID。
     * @param provider        可选模型供应商覆盖，通常为空，由 Agent 自己配置。
     * @param systemPrompt    主服务解析后的角色系统提示词。
     * @param message         用户本轮输入。
     * @param instruction     本轮补充要求。
     * @param historyContext  主服务裁剪后的历史上下文。
     * @param ragContext      主服务或后续 RAG 工具整理的检索上下文。
     * @param attachments     本轮附件。
     * @param caller          调用方标识。
     * @param userId          操作用户 ID。
     * @param tenantId        当前业务租户 ID。
     * @param sourceTenantId  来源业务租户 ID。
     * @param patientId       患者 ID。
     * @return Agent SSE 文本流。
     */
    public Flux<String> patientAnalysisStream(String conversationId,
                                              String provider,
                                              String systemPrompt,
                                              String message,
                                              String instruction,
                                              String historyContext,
                                              String ragContext,
                                              List<MediaInput> attachments,
                                              String caller,
                                              String userId,
                                              String tenantId,
                                              String sourceTenantId,
                                              String patientId) {
        AgentChatRequest request = new AgentChatRequest(
                text(conversationId),
                effectiveProvider(provider),
                text(systemPrompt),
                text(message),
                text(instruction),
                text(historyContext),
                text(ragContext),
                attachments == null ? List.of() : attachments,
                text(caller),
                text(conversationId),
                text(userId),
                text(tenantId),
                text(sourceTenantId),
                text(patientId)
        );
        CanaryContext.Snapshot canary = CanaryContext.get();
        String url = canary == null ? endpointUrl(AiAgentRegistryClient.AgentOperation.PATIENT_ANALYSIS,
                AiAgentCanaryRoute.PATIENT_ANALYSIS_PATH) : canaryRoute.url(canary);
        return Flux.defer(() -> {
            var outgoing = webClient.post().uri(validatedUri(url, AiAgentCanaryRoute.PATIENT_ANALYSIS_PATH, Set.of()))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request);
            if (canary != null) {
                outgoing.header(CanaryGrant.HEADER, canaryRoute.authorize(canary));
            }
            return outgoing.header("X-AI-Stream-Protocol", "typed-sse-v1")
                    .accept(MediaType.TEXT_EVENT_STREAM).exchangeToFlux(response -> {
                if (response.statusCode().isError()) {
                    return response.createException().flatMapMany(Flux::error);
                }
                if (!response.statusCode().is2xxSuccessful()) {
                    return Flux.error(new IllegalStateException("AI_AGENT_STREAM_PROTOCOL_INVALID"));
                }
                final MediaType contentType;
                try {
                    contentType = response.headers().contentType().orElse(null);
                } catch (IllegalArgumentException error) {
                    return Flux.error(new IllegalStateException("AI_AGENT_STREAM_PROTOCOL_INVALID"));
                }
                if (contentType == null || !"text".equalsIgnoreCase(contentType.getType())
                        || !"event-stream".equalsIgnoreCase(contentType.getSubtype())
                        || !List.of("typed-sse-v1").equals(response.headers().header("X-AI-Stream-Protocol"))) {
                    return Flux.error(new IllegalStateException("AI_AGENT_STREAM_PROTOCOL_INVALID"));
                }
                // HTTP 200 本身不是输出成功；空 EOF 不能触发上层成功审计。取消不会走此分支。
                return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                        .<String>handle((event, sink) -> {
                            if ("error".equals(event.event())) {
                                sink.error(new AiAgentExecutionException());
                            } else if (event.event() != null && !event.event().isEmpty()
                                    && !"message".equals(event.event())) {
                                sink.error(new IllegalStateException("AI_AGENT_STREAM_PROTOCOL_INVALID"));
                            } else if (event.data() != null && !event.data().isEmpty()) {
                                sink.next(event.data());
                            }
                        })
                        .switchIfEmpty(Flux.error(new IllegalStateException("AI_AGENT_STREAM_EMPTY")));
            });
        });
    }

    /**
     * 查询指定 Provider 在 Agent 执行层对应的模型名。
     *
     * @param provider Provider，可空时使用当前默认选择。
     * @return Agent 执行层模型信息。
     */
    public AgentModelResult model(String provider) {
        UriComponentsBuilder url = UriComponentsBuilder
                .fromUriString(siblingEndpointUrl(AiAgentRegistryClient.AgentOperation.PATIENT_ANALYSIS,
                        AiAgentCanaryRoute.PATIENT_ANALYSIS_PATH, MODEL_PATH))
                .queryParam("provider", effectiveProvider(provider));
        return webClient.get().uri(validatedUri(url.encode().build().toUriString(), MODEL_PATH, MODEL_QUERY_KEYS))
                .retrieve()
                .bodyToMono(AgentModelResult.class)
                .timeout(properties.timeout())
                .block();
    }

    /** 令牌只发往无嵌入凭据的 HTTP(S) 地址上的已知接口；查询参数只许名单里的键。 */
    private static URI validatedUri(String endpointUrl, String path, Set<String> queryKeys) {
        final URI uri;
        try {
            uri = URI.create(endpointUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Agent Registry 接口地址无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("Agent Registry 接口仅允许无嵌入凭据的 HTTP(S) 地址");
        }
        if (!path.equals(uri.getRawPath())) {
            throw new IllegalStateException("Agent Registry 接口路径不在允许范围");
        }
        if (uri.getRawQuery() != null) {
            for (String parameter : uri.getRawQuery().split("&", -1)) {
                if (!queryKeys.contains(parameter.split("=", 2)[0])) {
                    throw new IllegalStateException("Agent Registry 查询参数不在允许范围");
                }
            }
        }
        return uri;
    }

    private String endpointUrl(AiAgentRegistryClient.AgentOperation operation, String expectedPath) {
        AiAgentRegistryClient.AgentEndpoint endpoint = registryClient.endpoint(operation)
                .orElseThrow(() -> new IllegalStateException("Nacos AI Registry 未找到 Agent 接口: " + operation));
        String url = resolvedEndpoint(endpoint.url(), expectedPath);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Nacos AI Registry Agent 接口地址为空: " + operation);
        }
        return url;
    }

    private String resolvedEndpoint(String endpointUrl, String expectedPath) {
        String url = text(endpointUrl);
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (!StringUtils.hasText(path) || "/".equals(path)) {
                return trimTrailingSlash(url) + expectedPath;
            }
            return url;
        } catch (IllegalArgumentException exception) {
            return trimTrailingSlash(url) + expectedPath;
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String siblingEndpointUrl(AiAgentRegistryClient.AgentOperation operation, String expectedPath, String siblingPath) {
        String url = endpointUrl(operation, expectedPath);
        if (url.endsWith(expectedPath)) {
            return url.substring(0, url.length() - expectedPath.length()) + siblingPath;
        }
        URI uri = URI.create(url);
        return UriComponentsBuilder.fromUri(uri)
                .replacePath(siblingPath)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String effectiveProvider(String provider) {
        String requested = text(provider);
        return StringUtils.hasText(requested) ? requested : modelSelectionService.currentProvider();
    }

    private record AgentChatRequest(
            String conversationId,
            String provider,
            String systemPrompt,
            String message,
            String instruction,
            String historyContext,
            String ragContext,
            List<MediaInput> attachments,
            String caller,
            String traceId,
            String userId,
            String tenantId,
            String sourceTenantId,
            String patientId
    ) {
    }

    /**
     * Agent 执行层模型信息。
     *
     * @param provider           Provider。
     * @param model              模型名。
     * @param supportedProviders 支持的 Provider 列表。
     */
    public record AgentModelResult(
            String provider,
            String model,
            List<String> supportedProviders
    ) {
    }
}

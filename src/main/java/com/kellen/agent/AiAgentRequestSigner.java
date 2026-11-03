package com.kellen.agent;

import com.kellen.config.AiAgentClientAuthProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 为 Registry 已解析的执行层请求生成 user 外部服务认证签名。
 *
 * <p>签名绑定方法、原始路径、原始查询串和实际发送的正文；JSON 只编码一次，
 * 防止客户端与执行层验签字节不同。关闭时保持旧部署的 JSON 编码路径。</p>
 */
@Component
public class AiAgentRequestSigner {

    private static final Set<String> POST_PATHS = Set.of(
            "/api/agents/patient-analysis/stream", "/api/agents/questionnaire/reply",
            "/api/agents/questionnaire/interpret");
    private static final Set<String> MODEL_QUERY_KEYS = Set.of("provider");
    private final AiAgentClientAuthProperties properties;
    private final JsonMapper jsonMapper;

    /** 创建签名适配器；凭据来自固定服务配置，不从请求身份或 AgentCard 获取。 */
    public AiAgentRequestSigner(AiAgentClientAuthProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** 仅供旧的手工构造客户端使用；Spring 运行时始终注入实际配置的 Bean。 */
    static AiAgentRequestSigner disabled() {
        return new AiAgentRequestSigner(new AiAgentClientAuthProperties(false, "", ""), JsonMapper.builder().build());
    }

    /** 准备 POST；启用后拒绝非法目标，且不允许签名失败时无签名重试。 */
    WebClient.RequestHeadersSpec<?> post(WebClient client, String endpointUrl, Object body) {
        if (!properties.enabled()) {
            return client.post().uri(endpointUrl).bodyValue(body);
        }
        URI uri = validatedUri("POST", endpointUrl);
        final byte[] encoded;
        try {
            encoded = jsonMapper.writeValueAsBytes(body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Agent 签名请求 JSON 编码失败");
        }
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> sign(headers, "POST", uri, encoded))
                .bodyValue(encoded);
    }

    /** 准备模型查询 GET；空正文参与签名，查询串保持实际传输的编码。 */
    WebClient.RequestHeadersSpec<?> get(WebClient client, UriComponentsBuilder endpoint) {
        if (!properties.enabled()) {
            return client.get().uri(endpoint.build().toUriString());
        }
        URI uri = validatedUri("GET", endpoint.encode().build().toUriString());
        return client.get().uri(uri).headers(headers -> sign(headers, "GET", uri, new byte[0]));
    }

    private URI validatedUri(String method, String endpointUrl) {
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
        if ("POST".equals(method)) {
            if (!POST_PATHS.contains(uri.getRawPath()) || uri.getRawQuery() != null) {
                throw new IllegalStateException("Agent Registry POST 接口路径不在允许范围");
            }
        } else {
            if (!"/api/agents/model".equals(uri.getRawPath())) {
                throw new IllegalStateException("Agent Registry GET 接口路径不在允许范围");
            }
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&", -1)) {
                    if (!MODEL_QUERY_KEYS.contains(parameter.split("=", 2)[0])) {
                        throw new IllegalStateException("Agent Registry GET 查询参数不在允许范围");
                    }
                }
            }
        }
        return uri;
    }

    private void sign(HttpHeaders headers, String method, URI uri, byte[] body) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String envelope = method + "\n" + uri.getRawPath() + "\n"
                + (uri.getRawQuery() == null ? "" : uri.getRawQuery()) + "\n" + sha256(body);
        String canonical = properties.clientId() + "\n" + timestamp + "\n" + nonce + "\n" + envelope.trim();
        headers.set("X-Open-Client-Id", properties.clientId());
        headers.set("X-Open-Timestamp", timestamp);
        headers.set("X-Open-Nonce", nonce);
        headers.set("X-Open-Signature", hmac(canonical));
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Agent 服务签名摘要不可用");
        }
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.clientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Agent 服务签名不可用");
        }
    }
}

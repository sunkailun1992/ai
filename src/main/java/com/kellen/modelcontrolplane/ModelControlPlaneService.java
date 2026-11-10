package com.kellen.modelcontrolplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 从 LiteLLM 已部署目录读取安全元数据，不获取凭据、不执行健康推理或配置写入。 */
@Service
public class ModelControlPlaneService {
    private static final Logger log = LoggerFactory.getLogger(ModelControlPlaneService.class);
    private static final int MAX_MODELS = 2000;
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._/:@ \\-]{0,199}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_\\-]{0,127}");
    private static final Pattern SECRET_SHAPED = Pattern.compile("sk-[A-Za-z0-9_-]{12,}");
    private final ModelControlPlaneProperties properties;
    private final WebClient client;
    private final ObjectMapper json = new ObjectMapper();

    public ModelControlPlaneService(ModelControlPlaneProperties properties) {
        this.properties = properties;
        json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(32768).build());
        HttpClient transport = HttpClient.create().disableRetry(true).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(3));
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
    }

    /** 每次为一次有界只读请求；拒绝重定向、自动重试和未知响应投影。 */
    public ModelControlPlaneResponse current() {
        String console = consoleUrl(properties.getConsoleBaseUrl());
        URI base = safeBase(properties.getApiBaseUrl());
        String key = properties.getApiKey();
        if (base == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            return response(ModelControlPlaneResponse.Status.NOT_CONFIGURED, console, null, List.of());
        }
        Instant checked = Instant.now();
        try {
            URI endpoint = URI.create(base.toASCIIString().replaceAll("/+$", "") + "/v1/model/info");
            String payload = client.get().uri(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .exchangeToMono(upstream -> upstream.statusCode().value() == 200
                            ? upstream.bodyToMono(String.class)
                            : upstream.releaseBody().then(Mono.error(new CatalogUnavailable())))
                    .timeout(Duration.ofSeconds(3)).block();
            JsonNode root = json.readTree(payload);
            return response(ModelControlPlaneResponse.Status.AVAILABLE, console, checked, deployments(root));
        } catch (Exception rejected) {
            // 原始 SDK/HTTP 异常可能包含地址、正文或凭据，不记录或转发其 message/cause。
            log.warn("AI_MODEL_CATALOG_UNREACHABLE");
            return response(ModelControlPlaneResponse.Status.UNREACHABLE, console, checked, List.of());
        }
    }

    private ModelControlPlaneResponse response(ModelControlPlaneResponse.Status status, String console,
            Instant checked, List<ModelControlPlaneResponse.Deployment> rows) {
        // 固定部署版本由运维另行核验；不能从 Swagger 的静态版本或配置猜测运行包版本。
        // 现有业务默认选择继续走 model-selection，未实现的任务映射不伪造。
        return new ModelControlPlaneResponse(status == ModelControlPlaneResponse.Status.AVAILABLE,
                status, null, console, checked, rows, List.of());
    }

    private List<ModelControlPlaneResponse.Deployment> deployments(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("data").isArray()
                || root.path("data").size() > MAX_MODELS) throw new CatalogUnavailable();
        List<ModelControlPlaneResponse.Deployment> result = new ArrayList<>();
        Set<String> catalogRows = new HashSet<>();
        for (JsonNode row : root.path("data")) {
            JsonNode info = row.path("model_info");
            JsonNode params = row.path("litellm_params");
            String id = requiredText(info.path("id"), ID);
            String name = requiredText(row.path("model_name"), IDENTIFIER);
            String model = optionalText(params.path("model"), IDENTIFIER);
            // LiteLLM 的合法别名行保留同一个 deployment id；目录行由 id 与公开别名共同标识。
            if (!catalogRows.add(id + "\n" + name)) throw new CatalogUnavailable();
            String provider = optionalText(params.path("custom_llm_provider"), IDENTIFIER);
            if (provider == null) provider = optionalText(info.path("litellm_provider"), IDENTIFIER);
            if (provider == null && model != null && model.contains("/")) provider = model.substring(0, model.indexOf('/'));
            ModelControlPlaneResponse.Capability capability = capability(info.path("mode"));
            JsonNode context = info.path("max_input_tokens");
            Long window = context.isIntegralNumber() && context.canConvertToLong()
                    && context.longValue() > 0 && context.longValue() <= 100_000_000L ? context.longValue() : null;
            result.add(new ModelControlPlaneResponse.Deployment(id, name, provider, model,
                    List.of(capability), window, ModelControlPlaneResponse.Verification.NOT_CHECKED, null));
        }
        return List.copyOf(result);
    }

    private static ModelControlPlaneResponse.Capability capability(JsonNode mode) {
        if (!mode.isTextual()) return ModelControlPlaneResponse.Capability.OTHER;
        return switch (mode.textValue().toLowerCase(Locale.ROOT)) {
            case "chat", "completion", "responses" -> ModelControlPlaneResponse.Capability.CHAT;
            case "embedding" -> ModelControlPlaneResponse.Capability.EMBEDDING;
            case "rerank" -> ModelControlPlaneResponse.Capability.RERANK;
            case "ocr" -> ModelControlPlaneResponse.Capability.OCR;
            case "audio_transcription", "transcription" -> ModelControlPlaneResponse.Capability.AUDIO_TRANSCRIPTION;
            case "audio_speech", "speech" -> ModelControlPlaneResponse.Capability.AUDIO_SPEECH;
            case "image", "image_generation", "image_edit" -> ModelControlPlaneResponse.Capability.IMAGE;
            case "video", "video_generation" -> ModelControlPlaneResponse.Capability.VIDEO;
            default -> ModelControlPlaneResponse.Capability.OTHER;
        };
    }

    private static String requiredText(JsonNode node, Pattern pattern) {
        String value = optionalText(node, pattern);
        if (value == null) throw new CatalogUnavailable();
        return value;
    }

    private static String optionalText(JsonNode node, Pattern pattern) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual() || !pattern.matcher(node.textValue()).matches() || node.textValue().contains("://")
                || SECRET_SHAPED.matcher(node.textValue()).find()) throw new CatalogUnavailable();
        return node.textValue();
    }

    static URI safeBase(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.normalize().equals(uri) || uri.getRawPath().contains("%")) return null;
            return uri;
        } catch (RuntimeException invalid) { return null; }
    }

    static String consoleUrl(String value) {
        URI uri = safeBase(value);
        if (uri == null) return null;
        String text = uri.toASCIIString().replaceAll("/+$", "");
        return text.endsWith("/ui") ? text : null;
    }

    private static final class CatalogUnavailable extends RuntimeException { }
}

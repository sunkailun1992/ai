package com.kellen.modelcontrolplane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 loopback HTTP：只读协议、白名单投影、有界读取和拒绝重试/重定向。 */
class ModelControlPlaneServiceTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void emptyConfigurationMakesNoProbeAndDoesNotClaimHealth() {
        var result = new ModelControlPlaneService(new ModelControlPlaneProperties()).current();
        assertThat(result.status()).isEqualTo(ModelControlPlaneResponse.Status.NOT_CONFIGURED);
        assertThat(result.available()).isFalse();
        assertThat(result.checkedAt()).isNull();
        assertThat(result.modelCatalog()).isEmpty();
    }

    @Test void onlyDeployedMetadataIsProjectedAndCapabilitiesAreTyped() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        String[] modes = {"chat", "embedding", "rerank", "ocr", "audio_transcription", "audio_speech", "image_generation", "video_generation", "future"};
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) rows.append(',');
            rows.append("{\"model_name\":\"alias-").append(i)
                    .append("\",\"litellm_params\":{\"model\":\"openai/fixture-model\",\"api_key\":\"private-payload-canary\",\"api_base\":\"https://private.invalid\"},\"model_info\":{\"id\":\"deployment-")
                    .append(i).append("\",\"mode\":\"").append(modes[i])
                    .append("\",\"max_input_tokens\":8192,\"verification_status\":\"VERIFIED\",\"headers\":{\"Authorization\":\"private-payload-canary\"}}}");
        }
        var service = fixture("/root/v1/model/info", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            answer(exchange, 200, "{\"data\":[" + rows + "]}");
        }, "/root");
        var result = service.current();
        assertThat(result.status()).isEqualTo(ModelControlPlaneResponse.Status.AVAILABLE);
        assertThat(result.platformVersion()).isNull();
        assertThat(result.modelCatalog()).hasSize(9);
        assertThat(result.modelCatalog()).extracting(row -> row.capabilities().get(0))
                .containsExactly(ModelControlPlaneResponse.Capability.values());
        assertThat(result.modelCatalog()).allSatisfy(row -> {
            assertThat(row.verificationStatus()).isEqualTo(ModelControlPlaneResponse.Verification.NOT_CHECKED);
            assertThat(row.verifiedAt()).isNull();
            assertThat(row.contextWindow()).isEqualTo(8192L);
        });
        assertThat(result.toString()).doesNotContain("private-payload-canary", "private.invalid", "Authorization");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-catalog-fixture");
        assertThat(query.get()).isNull();
        assertThat(result.taskBindings()).isEmpty();
    }

    @Test void emptyDeployedCatalogIsAvailableWithoutInventingModels() throws Exception {
        var result = fixture("/v1/model/info", exchange -> answer(exchange, 200, "{\"data\":[]}"), "").current();
        assertThat(result.available()).isTrue();
        assertThat(result.modelCatalog()).isEmpty();
    }

    @Test void unicodeAliasAndProviderArnRemainValidMetadata() throws Exception {
        String body = "{\"data\":[{\"model_name\":\"文档识别模型\",\"litellm_params\":{\"model\":\"bedrock/arn:aws:bedrock:region:000000000000:inference-profile/example\"},\"model_info\":{\"id\":\"fixture-id\",\"mode\":\"ocr\"}}]}";
        var result = fixture("/v1/model/info", exchange -> answer(exchange, 200, body), "").current();
        assertThat(result.available()).isTrue();
        assertThat(result.modelCatalog().get(0).modelName()).isEqualTo("文档识别模型");
        assertThat(result.modelCatalog().get(0).provider()).isEqualTo("bedrock");
    }

    @Test void nativeDeploymentAliasesMayShareTheSameDeploymentId() throws Exception {
        String body = "{\"data\":[{\"model_name\":\"original\",\"litellm_params\":{\"model\":\"openai/fixture\"},\"model_info\":{\"id\":\"same-deployment\",\"mode\":\"chat\"}},{\"model_name\":\"business-alias\",\"litellm_params\":{\"model\":\"openai/fixture\"},\"model_info\":{\"id\":\"same-deployment\",\"mode\":\"chat\"}}]}";
        var result = fixture("/v1/model/info", exchange -> answer(exchange, 200, body), "").current();
        assertThat(result.available()).isTrue();
        assertThat(result.modelCatalog()).extracting(ModelControlPlaneResponse.Deployment::id)
                .containsExactly("same-deployment", "same-deployment");
        assertThat(result.modelCatalog()).extracting(ModelControlPlaneResponse.Deployment::modelName)
                .containsExactly("original", "business-alias");
    }

    @Test void nativeResponsesAndImageEditModesAndProviderMetadataArePreserved() throws Exception {
        String body = "{\"data\":[{\"model_name\":\"native-responses\",\"litellm_params\":{\"model\":\"fixture-model\"},\"model_info\":{\"id\":\"responses-id\",\"mode\":\"responses\",\"litellm_provider\":\"openai\"}},{\"model_name\":\"edit\",\"model_info\":{\"id\":\"edit-id\",\"mode\":\"image_edit\"}}]}";
        var result = fixture("/v1/model/info", exchange -> answer(exchange, 200, body), "").current();
        assertThat(result.available()).isTrue();
        assertThat(result.modelCatalog().get(0).capabilities()).containsExactly(ModelControlPlaneResponse.Capability.CHAT);
        assertThat(result.modelCatalog().get(0).provider()).isEqualTo("openai");
        assertThat(result.modelCatalog().get(1).capabilities()).containsExactly(ModelControlPlaneResponse.Capability.IMAGE);
    }

    @Test void upstreamFailureIsNotRetriedOrReturnedToTheBrowser() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var result = fixture("/v1/model/info", exchange -> {
            attempts.incrementAndGet(); answer(exchange, 503, "private-upstream-error-canary");
        }, "").current();
        assertThat(attempts.get()).isEqualTo(1);
        assertUnavailable(result);
        assertThat(result.toString()).doesNotContain("private-upstream-error-canary");
    }

    @Test void redirectDoesNotForwardTheReadOnlyCredential() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        var service = fixture("/v1/model/info", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            answer(exchange, 302, "");
        }, "");
        server.createContext("/redirected", exchange -> { forwarded.incrementAndGet(); answer(exchange, 200, "{\"data\":[]}"); });
        assertUnavailable(service.current());
        assertThat(forwarded.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":{}}", "{\"data\":[],\"data\":[]}", "{\"data\":[{}]}",
            "{\"data\":[{\"model_name\":\"same-alias\",\"model_info\":{\"id\":\"same\"}},{\"model_name\":\"same-alias\",\"model_info\":{\"id\":\"same\"}}]}",
            "{\"data\":[{\"model_name\":\"https://credentials.invalid/?key=private\",\"model_info\":{\"id\":\"id\"}}]}"})
    void malformedCatalogIsUnavailableRatherThanSilentlyOmitted(String body) throws Exception {
        assertUnavailable(fixture("/v1/model/info", exchange -> answer(exchange, 200, body), "").current());
    }

    @Test void oversizedPayloadIsBounded() throws Exception {
        String body = "{\"data\":[],\"unused\":\"" + "x".repeat(1100 * 1024) + "\"}";
        assertUnavailable(fixture("/v1/model/info", exchange -> answer(exchange, 200, body), "").current());
    }

    @Test void streamingBodyTimeoutIsBoundedAndNotRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var service = fixture("/v1/model/info", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(4500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        }, "");
        long started = System.nanoTime();
        assertUnavailable(service.current());
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4200);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user@localhost/ui", "http://@localhost/ui", "http://localhost/ui?", "http://localhost/ui#", "http://localhost/a/../ui", "javascript:alert(1)", "https://localhost/%75i", "https://localhost/not-ui"})
    void consoleRejectsCredentialQueryFragmentTraversalAndNonUiPaths(String value) {
        assertThat(ModelControlPlaneService.consoleUrl(value)).isNull();
    }

    @Test void consolePreservesApprovedServerPrefixAndPropertiesNeverPrintSecrets() {
        assertThat(ModelControlPlaneService.consoleUrl("https://example.invalid/gateway/ui/"))
                .isEqualTo("https://example.invalid/gateway/ui");
        var properties = new ModelControlPlaneProperties(); properties.setApiKey("private-property-canary");
        assertThat(properties.toString()).doesNotContain("private-property-canary");
    }

    private ModelControlPlaneService fixture(String path, com.sun.net.httpserver.HttpHandler handler, String prefix) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> { Thread worker = new Thread(task); worker.setDaemon(true); return worker; }));
        server.createContext(path, handler); server.start();
        var properties = new ModelControlPlaneProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + prefix);
        properties.setApiKey("synthetic-catalog-fixture");
        properties.setConsoleBaseUrl("https://example.invalid/gateway/ui");
        return new ModelControlPlaneService(properties);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try { if (bytes.length > 0) exchange.getResponseBody().write(bytes); }
        catch (java.io.IOException closedConsumer) { /* 过大响应/超时取消后不输出原始传输错误。 */ }
        finally { exchange.close(); }
    }

    private static void assertUnavailable(ModelControlPlaneResponse response) {
        assertThat(response.available()).isFalse();
        assertThat(response.status()).isEqualTo(ModelControlPlaneResponse.Status.UNREACHABLE);
        assertThat(response.modelCatalog()).isEmpty();
        assertThat(response.checkedAt()).isNotNull();
    }
}

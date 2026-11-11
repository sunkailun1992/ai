package com.kellen.modelcontrolplane;

import com.kellen.controlplane.AiControlPlaneAdministrator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 loopback HTTP：只读协议、白名单投影、有界读取和拒绝重试/重定向。 */
class ModelControlPlaneServiceTest {
    private static final String CATALOG_FIXTURE = "{\"data\":[" +
            catalogRow("deployment-a", "chat-alias", "openai/fixture", "chat") + "]}";
    private static final String EVIDENCE_FIXTURE = evidenceRow(
            "deployment-a", "chat-alias", "openai/fixture", "CHAT", "VERIFIED");
    private HttpServer server;
    @TempDir Path evidenceDirectory;

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

    @Test void verificationHistoryMatchesDeploymentAliasPhysicalModelAndCapability() throws Exception {
        String body = "{\"data\":[" + String.join(",",
                catalogRow("deployment-a", "chat-alias", "openai/fixture", "chat"),
                catalogRow("deployment-a", "other-alias", "openai/fixture", "chat"),
                catalogRow("deployment-b", "chat-alias", "openai/fixture", "chat"),
                catalogRow("deployment-a", "changed-model", "openai/new-fixture", "chat"),
                catalogRow("deployment-a", "changed-capability", "openai/fixture", "embedding"),
                catalogRow("deployment-a", "failed-alias", "openai/fixture", "chat"),
                catalogRow("deployment-a", "unknown-alias", "openai/fixture", "chat")) + "]}";
        Path evidence = evidenceFile("{\"entries\":[" + String.join(",",
                EVIDENCE_FIXTURE,
                evidenceRow("deployment-a", "changed-model", "openai/fixture", "CHAT", "VERIFIED"),
                evidenceRow("deployment-a", "changed-capability", "openai/fixture", "CHAT", "VERIFIED"),
                evidenceRow("deployment-a", "failed-alias", "openai/fixture", "CHAT", "FAILED"),
                evidenceRow("deployment-a", "unknown-alias", "openai/fixture", "CHAT", "UNKNOWN")) + "]}");
        AtomicInteger catalogRequests = new AtomicInteger();
        var service = fixture("/v1/model/info", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getRawQuery()).isNull();
            catalogRequests.incrementAndGet();
            answer(exchange, 200, body);
        }, "", evidence);

        var result = service.current();

        assertThat(result.available()).isTrue();
        assertThat(result.modelCatalog()).extracting(ModelControlPlaneResponse.Deployment::verificationStatus)
                .containsExactly(ModelControlPlaneResponse.Verification.VERIFIED,
                        ModelControlPlaneResponse.Verification.NOT_CHECKED,
                        ModelControlPlaneResponse.Verification.NOT_CHECKED,
                        ModelControlPlaneResponse.Verification.NOT_CHECKED,
                        ModelControlPlaneResponse.Verification.NOT_CHECKED,
                        ModelControlPlaneResponse.Verification.FAILED,
                        ModelControlPlaneResponse.Verification.UNKNOWN);
        assertThat(result.modelCatalog().get(0).verifiedAt()).isEqualTo(Instant.parse("2025-06-01T09:00:00Z"));
        assertThat(result.modelCatalog().subList(1, 5)).allSatisfy(row -> assertThat(row.verifiedAt()).isNull());
        assertThat(result.toString()).doesNotContain(evidence.toString());
        assertThat(catalogRequests.get()).isEqualTo(1);

        var mvc = MockMvcBuilders.standaloneSetup(
                new ModelControlPlaneController(mock(AiControlPlaneAdministrator.class), service)).build();
        mvc.perform(get("/api/ai/model-control-plane").header("Authorization", "Bearer synthetic-user-fixture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelCatalog[0].verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.modelCatalog[0].verifiedAt").value("2025-06-01T09:00:00Z"))
                .andExpect(jsonPath("$.data.modelCatalog[1].verificationStatus").value("NOT_CHECKED"));
        assertThat(catalogRequests.get()).isEqualTo(2);
    }

    @Test void badOrDuplicateHistoryNeverOverridesAvailableCatalog() throws Exception {
        Path evidence = evidenceFile("{\"entries\":[" + EVIDENCE_FIXTURE + "]}");
        var service = fixture("/v1/model/info", exchange -> answer(exchange, 200, CATALOG_FIXTURE), "", evidence);
        assertThat(service.current().modelCatalog().get(0).verificationStatus())
                .isEqualTo(ModelControlPlaneResponse.Verification.VERIFIED);

        String[] invalid = {
                "{\"entries\":[" + EVIDENCE_FIXTURE + "," + EVIDENCE_FIXTURE + "]}",
                "{\"entries\":[" + EVIDENCE_FIXTURE + "]} {}",
                "{\"entries\":[],\"entries\":[" + EVIDENCE_FIXTURE + "]}",
                "{\"entries\":[" + EVIDENCE_FIXTURE.replace("2025-06-01T09:00:00Z", "not-a-timestamp") + "]}",
                "{\"entries\":[" + EVIDENCE_FIXTURE.replace("VERIFIED", "NOT_CHECKED") + "]}",
                "{\"entries\":[" + EVIDENCE_FIXTURE + "],\"extra\":\"private-evidence-canary\"}",
                "{\"entries\":[" + EVIDENCE_FIXTURE + "]}" + " ".repeat(256 * 1024)
        };
        for (String body : invalid) {
            Files.writeString(evidence, body);
            assertAvailableWithoutVerification(service.current());
        }
    }

    @Test void inaccessibleHistoryFallsBackWithoutExposingFileMetadata() throws Exception {
        Path evidence = evidenceFile("{\"entries\":[" + EVIDENCE_FIXTURE + "]}");
        var service = fixture("/v1/model/info", exchange -> answer(exchange, 200, CATALOG_FIXTURE), "", evidence);
        assertThat(service.current().modelCatalog().get(0).verificationStatus())
                .isEqualTo(ModelControlPlaneResponse.Verification.VERIFIED);

        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rw-r--r--"));
        assertAvailableWithoutVerification(service.current());
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rw-------"));
        Path hardLink = Files.createLink(evidence.resolveSibling("hard-link.json"), evidence);
        assertAvailableWithoutVerification(service.current());
        Files.delete(hardLink);
        Path moved = Files.move(evidence, evidence.resolveSibling("moved.json"));
        Files.createSymbolicLink(evidence, moved);
        assertAvailableWithoutVerification(service.current());
        Files.delete(evidence);
        assertAvailableWithoutVerification(service.current());
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
        return fixture(path, handler, prefix, null);
    }

    private ModelControlPlaneService fixture(String path, com.sun.net.httpserver.HttpHandler handler, String prefix,
            Path verificationFile) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> { Thread worker = new Thread(task); worker.setDaemon(true); return worker; }));
        server.createContext(path, handler); server.start();
        var properties = new ModelControlPlaneProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + prefix);
        properties.setApiKey("synthetic-catalog-fixture");
        properties.setConsoleBaseUrl("https://example.invalid/gateway/ui");
        if (verificationFile != null) {
            new Binder(new MapConfigurationPropertySource(Map.of(
                    "ai.model-control-plane.verification-file", verificationFile.toString())))
                    .bind("ai.model-control-plane", Bindable.ofInstance(properties));
        }
        return new ModelControlPlaneService(properties);
    }

    private Path evidenceFile(String contents) throws Exception {
        Path path = Files.createFile(evidenceDirectory.toRealPath().resolve("verification-history.json"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.writeString(path, contents);
    }

    private static String catalogRow(String id, String alias, String model, String mode) {
        return """
                {"model_name":"%s","litellm_params":{"model":"%s"},"model_info":{"id":"%s","mode":"%s"}}
                """.formatted(alias, model, id, mode);
    }

    private static String evidenceRow(String id, String alias, String model, String capability, String verificationStatus) {
        return """
                {"deploymentId":"%s","modelName":"%s","model":"%s","capability":"%s","verificationStatus":"%s","verifiedAt":"2025-06-01T09:00:00Z"}
                """.formatted(id, alias, model, capability, verificationStatus);
    }

    private static void assertAvailableWithoutVerification(ModelControlPlaneResponse response) {
        assertThat(response.available()).isTrue();
        assertThat(response.modelCatalog()).singleElement().satisfies(row -> {
            assertThat(row.verificationStatus()).isEqualTo(ModelControlPlaneResponse.Verification.NOT_CHECKED);
            assertThat(row.verifiedAt()).isNull();
        });
        assertThat(response.toString()).doesNotContain("private-evidence-canary", "verification-history.json");
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

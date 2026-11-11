package com.kellen.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产 Advice 经真实 HTTP 的未知异常隐私回归；所有敏感标记均为本地合成文本。 */
@SpringBootTest(classes = ApiExceptionPrivacyHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.exception-privacy.fixture=true", "spring.config.name=isolated-exception-privacy",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false"})
class ApiExceptionPrivacyHttpTest {
    private static final String SYNTHETIC_FAILURE = "credential=synthetic-private-credential "
            + "http://127.0.0.1:19876/internal/private?key=synthetic-private-credential "
            + "SELECT token_hash FROM synthetic_sessions WHERE id='synthetic-sql-marker'";
    @LocalServerPort int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest @ValueSource(strings = {"/fixture/unexpected", "/fixture/wrapped"})
    void unknownFailuresRemain500WithoutAnyRawExceptionDetails(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(500);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").asText()).isEqualTo("AI_REQUEST_FAILED");
        assertThat(body.path("model").isNull()).isTrue();
        assertThat(body.path("content").isNull()).isTrue();
        assertThat(response.body()).doesNotContain(path, "synthetic-private-credential", "127.0.0.1:19876",
                "SELECT", "synthetic_sessions", "synthetic-sql-marker", "IllegalStateException", "SQLException");
    }

    @Test void existingPublicBadRequestMessageAndStatusRemainUnchanged() throws Exception {
        var response = get("/fixture/validation");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("error").asText())
                .isEqualTo("synthetic public input validation");
    }

    @RestController
    @ConditionalOnProperty(name = "test.exception-privacy.fixture", havingValue = "true")
    static class ThrowingController {
        @GetMapping("/fixture/unexpected") String unexpected() {
            throw new IllegalStateException(SYNTHETIC_FAILURE);
        }
        @GetMapping("/fixture/wrapped") String wrapped() {
            throw new IllegalStateException(SYNTHETIC_FAILURE, new SQLException(SYNTHETIC_FAILURE));
        }
        @GetMapping("/fixture/validation") String validation() {
            throw new IllegalArgumentException("synthetic public input validation");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.exception-privacy.fixture", havingValue = "true")
    @Import(ApiExceptionHandler.class)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    static class Config {
        @Bean ThrowingController throwingController() { return new ThrowingController(); }
    }
}

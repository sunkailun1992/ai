package com.kellen.auth;

import com.kellen.conversation.AiDialogueService;
import com.kellen.file.AiFileObjectEntity;
import com.kellen.file.OssFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 扫描真实 Controller，经回环 HTTP 证明退役路径消失，通用 token 展示继续可用。 */
@SpringBootTest(classes = RetiredClientEndpointsHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.retired-client.fixture=true", "spring.config.name=isolated-retired-client",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false"})
class RetiredClientEndpointsHttpTest {
    @LocalServerPort int port;
    record Route(String method, String path) { }

    static Stream<Route> retiredRoutes() {
        var routes = new ArrayList<Route>();
        for (String prefix : List.of("/api/auth", "/api/v1/auth")) {
            routes.add(new Route("POST", prefix + "/wx-login"));
            routes.add(new Route("GET", prefix + "/me"));
            routes.add(new Route("POST", prefix + "/logout"));
            routes.add(new Route("GET", prefix + "/test-domain"));
        }
        routes.add(new Route("GET", "/api/ai/conversations"));
        routes.add(new Route("POST", "/api/ai/conversations"));
        routes.add(new Route("GET", "/api/ai/conversations/fixture/messages"));
        routes.add(new Route("DELETE", "/api/ai/conversations/fixture"));
        routes.add(new Route("POST", "/api/ai/conversations/fixture/rename"));
        for (String prefix : List.of("/api/files", "/api/v1/files")) {
            routes.add(new Route("GET", prefix + "/upload-policy"));
            routes.add(new Route("POST", prefix + "/complete"));
            routes.add(new Route("GET", prefix + "/7"));
            routes.add(new Route("POST", prefix + "/7/view-token"));
        }
        return routes.stream();
    }

    @ParameterizedTest @MethodSource("retiredRoutes")
    void retiredRoutesAreNotRegistered(Route route) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route.path()))
                .header("Authorization", "Bearer retired-opaque-session")
                .header("Content-Type", "application/json")
                .method(route.method(), HttpRequest.BodyPublishers.ofString("{}"));
        assertThat(HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("%s %s", route.method(), route.path()).isEqualTo(404);
    }

    @Test void tokenViewGetAndHeadRemainAvailableWithoutLegacyLogin() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String prefix : List.of("/api/files", "/api/v1/files")) {
            var uri = URI.create("http://127.0.0.1:" + port + prefix + "/7/view?token=synthetic-display-token");
            var head = client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.headers().firstValue("Content-Type")).contains("image/png");
            var get = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(get.statusCode()).isEqualTo(302);
            assertThat(get.headers().firstValue("Location")).contains("https://files.example.invalid/private/signed");
        }
    }

    @Test void retiredClassesAreAbsentFromTheRuntimeClasspath() {
        for (String name : List.of("auth.WechatAuthController", "auth.WechatAuthService", "auth.AiWechatUserEntity",
                "auth.AiWechatSessionEntity", "auth.AiWechatUserRepository", "auth.AiWechatSessionRepository",
                "auth.WechatLoginRequest", "auth.WechatLoginResponse", "auth.WechatProfileResponse", "auth.WechatUserResponse",
                "conversation.ChatConversationService", "conversation.ChatConversationRepository", "conversation.ChatConversationEntity",
                "conversation.ChatConversationMessageService", "conversation.ChatConversationMessageRepository",
                "conversation.ChatConversationMessageEntity", "conversation.ConversationCreateRequest",
                "conversation.ConversationRenameRequest", "conversation.ConversationResponse",
                "file.FileUploadCompleteRequest", "file.FileUploadPolicyResponse", "file.FileObjectResponse")) {
            assertThat(getClass().getClassLoader().getResource("com/kellen/" + name.replace('.', '/') + ".class"))
                    .as(name).isNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.retired-client.fixture", havingValue = "true")
    @ComponentScan(basePackages = {"com.kellen.auth", "com.kellen.conversation", "com.kellen.file"},
            useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class))
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    static class Config {
        // 原实现的协作者只在类仍存在时注册，以便同一 HTTP 回归在删除前真实失败。
        @Bean static BeanFactoryPostProcessor legacyCollaborators() {
            return factory -> {
                for (String type : List.of("com.kellen.auth.WechatAuthService", "com.kellen.conversation.ChatConversationService")) {
                    try { factory.registerSingleton(type, mock(Class.forName(type))); }
                    catch (ClassNotFoundException retired) { /* 删除后不再创建兼容 Bean。 */ }
                }
            };
        }
        @Bean AiDialogueService dialogue() { return mock(AiDialogueService.class); }
        @Bean AiRequestIdentityResolver identities() { return mock(AiRequestIdentityResolver.class); }
        @Bean OssFileService files() {
            var service = mock(OssFileService.class);
            var file = new AiFileObjectEntity();
            ReflectionTestUtils.setField(file, "mimeType", "image/png");
            ReflectionTestUtils.setField(file, "fileSizeBytes", 12L);
            when(service.viewableFile(7L, "synthetic-display-token")).thenReturn(file);
            when(service.signedViewUri(file)).thenReturn(URI.create("https://files.example.invalid/private/signed"));
            return service;
        }
    }
}

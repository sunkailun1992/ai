package com.ai.file;

import com.kellen.config.AiProperties;

import com.kellen.file.MediaInput;
import com.kellen.file.MediaInputValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MediaInputValidator 单元测试。
 *
 * <p>覆盖安全规范要求的多模态入参场景：数量超限、非法协议、非法 MIME、
 * 远程 Content-Type 校验，以及合法 URL 的正常转换。</p>
 */
class MediaInputValidatorTests {

    private final MediaInputValidator validator = new MediaInputValidator(testProperties(2)); // 上限设小，便于触发数量限制。

    /**
     * 验证空媒体列表按纯文本对话处理。
     */
    @Test
    void acceptsEmptyMediaAsTextOnlyChat() {
        assertEquals(0, validator.validateUrlMedia(List.of()).size()); // 空列表表示本轮没有媒体。
    }

    /**
     * 验证媒体数量超过配置上限被拒绝。
     */
    @Test
    void rejectsMediaOverLimit() {
        List<MediaInput> media = List.of( // 构造 3 条媒体，超过上限 2。
                new MediaInput("https://example.com/1.png", "image/png"),
                new MediaInput("https://example.com/2.png", "image/png"),
                new MediaInput("https://example.com/3.png", "image/png"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrlMedia(media)); // 超限必须抛出入参错误。
    }

    /**
     * 验证白名单外协议（file 等本地协议）被拒绝，防止 SSRF 和本地文件读取。
     */
    @Test
    void rejectsDisallowedScheme() {
        List<MediaInput> media = List.of(new MediaInput("file:///etc/passwd", "image/png")); // file 协议不在白名单。
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrlMedia(media)); // 必须抛出入参错误。
    }

    /**
     * 验证 URL 媒体 MIME 不在白名单时被拒绝。
     */
    @Test
    void rejectsUnsupportedUrlMimeType() {
        List<MediaInput> media = List.of(new MediaInput("https://example.com/a.exe", "application/x-msdownload")); // 可执行文件 MIME 不在白名单。
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrlMedia(media)); // 必须抛出入参错误。
    }

    /**
     * 验证合法远程媒体通过校验并正确转换 MIME 类型。
     */
    @Test
    void acceptsValidRemoteMedia() throws IOException {
        HttpServer server = contentTypeServer("image/png", "fake-png-bytes"); // 本地服务避免单测依赖公网。
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a.png"; // 合法输入。
            List<MediaInputValidator.ValidatedMedia> validated = validator.validateUrlMedia(List.of(new MediaInput(url, "image/png"))); // 校验 URL 媒体。

            assertEquals(1, validated.size()); // 一条媒体通过校验。
            assertEquals("image/png", validated.get(0).mimeType().toString()); // MIME 类型解析正确。
            assertNotNull(validated.get(0).resource()); // 图片由后端读取为字节资源，模型不再自行访问 URL。
            assertEquals("a.png", validated.get(0).resource().getFilename()); // 保留文件名，帮助模型 SDK 判断资源类型。
            assertEquals(14, validated.get(0).sizeBytes()); // 审计摘要可记录真实读取大小。
        } finally {
            server.stop(0); // 释放本地端口。
        }
    }

    /**
     * 验证后端 token URL 这类 302 跳转地址会被跟随并读取真实图片内容。
     */
    @Test
    void acceptsRedirectedRemoteMedia() throws IOException {
        HttpServer server = redirectingContentServer("image/png", "redirect-png-bytes"); // 模拟 /view?token=... 跳转到真实文件。
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"; // 先返回 302 的媒体地址。
            List<MediaInputValidator.ValidatedMedia> validated = validator.validateUrlMedia(List.of(new MediaInput(url, "image/png"))); // 校验跳转媒体。

            assertEquals(1, validated.size()); // 一条媒体通过校验。
            assertNotNull(validated.get(0).resource()); // 跳转后读取为字节资源。
            assertEquals("attachment.png", validated.get(0).resource().getFilename()); // 起始 URL 无扩展名时用 MIME 生成文件名。
            assertEquals(18, validated.get(0).sizeBytes()); // 读取到最终文件内容，而不是 302 空响应。
        } finally {
            server.stop(0); // 释放本地端口。
        }
    }

    /**
     * 验证远程 URL 返回 HTML 但调用方声明为图片时被拒绝。
     */
    @Test
    void rejectsRemoteContentTypeMismatch() throws IOException {
        HttpServer server = contentTypeServer("text/html; charset=utf-8", "share page"); // 模拟 CleanShot 分享页这类 HTML 页面。
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/share"; // 实际不是图片直链。
            assertThrows(IllegalArgumentException.class, () -> validator.validateUrlMedia(List.of(new MediaInput(url, "image/png")))); // MIME 不一致必须拒绝。
        } finally {
            server.stop(0); // 释放本地端口。
        }
    }

    /**
     * 验证 JSON 远程附件会作为文本上下文处理，便于结构化材料参与分析。
     */
    @Test
    void acceptsJsonUrlAsTextAttachment() throws IOException {
        HttpServer server = contentTypeServer("application/json", "{\"form\":\"demo\"}"); // 模拟文件服务上的 JSON 材料。
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/form.json"; // 合法 JSON URL。
            List<MediaInputValidator.ValidatedMedia> validated = validator.validateUrlMedia(List.of(new MediaInput(url, "application/json"))); // 校验远程 JSON。

            assertEquals("application/json", validated.get(0).mimeType().toString()); // MIME 类型解析正确。
            assertEquals("{\"form\":\"demo\"}", validated.get(0).textContent()); // JSON 内容进入文本上下文。
            assertNull(validated.get(0).resource()); // 文本附件不再按 media 传给模型。
        } finally {
            server.stop(0); // 释放本地端口。
        }
    }

    /**
     * 构造只关心多模态限制的测试配置。
     *
     * @param maxMediaCount 媒体数量上限。
     * @return 其余字段取默认值的配置对象。
     */
    private static AiProperties testProperties(int maxMediaCount) {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "测试提示词", maxMediaCount, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain", "application/json"), new AiProperties.MemoryProperties(20, 7)));
    }

    /**
     * 启动返回指定 Content-Type 和正文的本地 HTTP 服务。
     *
     * @param contentType 响应 Content-Type。
     * @param body        响应正文。
     * @return 已启动的 HTTP 服务。
     */
    private static HttpServer contentTypeServer(String contentType, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * 启动先 302 跳转再返回指定 Content-Type 和正文的本地 HTTP 服务。
     *
     * @param contentType 最终文件响应 Content-Type。
     * @param body        最终文件响应正文。
     * @return 已启动的 HTTP 服务。
     */
    private static HttpServer redirectingContentServer(String contentType, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/a.png");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/a.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}

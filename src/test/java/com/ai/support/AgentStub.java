package com.ai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机回环上的 ai-agent 替身：患者分析流式接口回一段 typed-sse-v1 文本，模型查询接口回固定模型；记下每个请求的方法、路径、请求头与正文。
 */
public final class AgentStub implements AutoCloseable {

    /**
     * 流式接口路径（与 AgentCard 登记的一致）。
     */
    public static final String STREAM_PATH = "/api/agents/patient-analysis/stream";

    private final HttpServer server;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private volatile String answer = "synthetic-answer";

    private AgentStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext(STREAM_PATH, exchange -> respond(exchange, "text/event-stream", "data:" + answer + "\n\n", true));
        server.createContext("/api/agents/model", exchange -> respond(exchange, "application/json",
                "{\"provider\":\"qwen\",\"model\":\"synthetic-model\",\"supportedProviders\":[\"qwen\"]}", false));
        server.start();
    }

    /**
     * 启动替身。
     *
     * @return 已启动的替身
     */
    public static AgentStub start() {
        try {
            return new AgentStub();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 流式接口地址。
     *
     * @return 地址
     */
    public String streamUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + STREAM_PATH;
    }

    /**
     * 收到的请求（按到达顺序）。
     *
     * @return 请求列表
     */
    public List<Received> received() {
        return received;
    }

    /**
     * 清空记录，恢复缺省回答。
     */
    public void reset() {
        received.clear();
        answer = "synthetic-answer";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void respond(HttpExchange exchange, String contentType, String body, boolean stream) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, requestBody));
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (stream) {
            exchange.getResponseHeaders().set("X-AI-Stream-Protocol", "typed-sse-v1");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * 一个收到的请求。
     *
     * @param method  方法
     * @param path    路径
     * @param headers 请求头（名称小写）
     * @param body    正文
     */
    public record Received(String method, String path, Map<String, String> headers, String body) {
    }
}

package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * AI Agent 调用超时配置。
 *
 * <p>Agent 地址不在这里配置，必须从 Nacos AI Registry 的 AgentCard 中读取。
 * 这样主 {@code ai} 服务不会保存本地兜底地址，也不会绕过 AI 注册中心调用执行层。</p>
 *
 * @param timeout         单次非流式 Agent 调用超时时间。
 * @param defaultProvider 默认 Agent 模型供应商。
 */
@ConfigurationProperties(prefix = "ai.agent-client")
public record AiAgentClientProperties(
        @DefaultValue("8s") Duration timeout,
        @DefaultValue("google") String defaultProvider
) {
}

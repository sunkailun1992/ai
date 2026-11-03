package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Nacos AI Agent Registry 读取配置。
 *
 * <p>主 AI 服务通过该配置从 Nacos AI 注册中心读取 Agent 元数据和接口地址。
 * 凭据必须通过环境变量或 Nacos 配置中心注入，不写入测试资源和仓库明文。</p>
 */
@ConfigurationProperties(prefix = "ai.agent-registry")
public record NacosAiAgentRegistryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serverAddr,
        @DefaultValue("") String namespace,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("60") long cacheTtlSeconds,
        @DefaultValue("/nacos") String contextPath,
        @DefaultValue("patient-analysis-agent") String patientAnalysisAgentName,
        @DefaultValue("questionnaire-agent") String questionnaireAgentName
) {
}

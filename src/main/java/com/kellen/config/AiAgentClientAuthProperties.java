package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 主 AI 调用执行层的专用服务身份；不使用患者、租户或终端用户提供的凭据。
 *
 * <p>兼容旧内网部署时默认关闭。切换网关域名前，先在 user 配置对应服务身份，
 * 再通过 Nacos 或环境变量注入密钥并启用；启用但缺少配置时启动失败。</p>
 *
 * @param enabled 是否对所有 Agent HTTP 请求签名。
 * @param clientId user 外部服务认证中登记的专用 client ID。
 * @param clientSecret 服务密钥，只通过受控配置注入，不写入日志。
 */
@ConfigurationProperties(prefix = "ai.agent-client.auth")
public record AiAgentClientAuthProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret
) {

    public AiAgentClientAuthProperties {
        clientId = clientId == null ? "" : clientId.trim();
        clientSecret = clientSecret == null ? "" : clientSecret;
        if (enabled && (!clientId.matches("[A-Za-z0-9._-]{1,128}") || clientSecret.isBlank())) {
            throw new IllegalArgumentException("启用 Agent 服务签名必须配置有效的 client-id 与 client-secret");
        }
    }

    @Override
    public String toString() {
        return "AiAgentClientAuthProperties[enabled=" + enabled + ", clientId=" + clientId
                + ", clientSecret=[REDACTED]]";
    }
}

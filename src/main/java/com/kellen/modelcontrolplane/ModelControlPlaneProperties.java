package com.kellen.modelcontrolplane;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 仅服务端持有的 LiteLLM 只读连接；模型配置仍归原生控制台。 */
@Component
@ConfigurationProperties(prefix = "ai.model-control-plane")
public class ModelControlPlaneProperties {
    private String apiBaseUrl = "";
    private String apiKey = "";
    private String consoleBaseUrl = "";

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String value) { apiBaseUrl = value == null ? "" : value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value == null ? "" : value; }
    public String getConsoleBaseUrl() { return consoleBaseUrl; }
    public void setConsoleBaseUrl(String value) { consoleBaseUrl = value == null ? "" : value; }

    @Override public String toString() { return "ModelControlPlaneProperties[server-only]"; }
}

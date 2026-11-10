package com.kellen.agent;

import java.util.Optional;

/**
 * AI Agent 注册中心读取接口。
 */
public interface AiAgentRegistryClient {

    /**
     * 按操作类型解析 Agent 接口地址。
     *
     * @param operation Agent 操作类型。
     * @return 可调用接口地址。
     */
    Optional<AgentEndpoint> endpoint(AgentOperation operation);

    /**
     * Agent 操作类型。
     */
    enum AgentOperation {
        PATIENT_ANALYSIS
    }

    /**
     * Agent 接口元数据。
     *
     * @param url      可调用地址。
     * @param protocol 传输协议。
     * @param version  协议版本。
     */
    record AgentEndpoint(String url, String protocol, String version) {
    }
}

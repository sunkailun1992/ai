package com.kellen.agent;

/** Agent 已通过 typed SSE 明确报告执行失败；不保留上游错误正文或原始异常。 */
public final class AiAgentExecutionException extends IllegalStateException {
    public AiAgentExecutionException() {
        super("AI_AGENT_EXECUTION_FAILED");
    }
}

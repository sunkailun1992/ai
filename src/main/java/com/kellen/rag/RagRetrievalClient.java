package com.kellen.rag;

import com.kellen.rpc.rag.RagRetrievalRpcDTO;
import com.kellen.rpc.rag.RagRetrievalRpcRequest;
import com.kellen.rpc.rag.RagRetrievalRpcService;
import com.kellen.traffic.CanaryContext;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RAG 知识检索 RPC 客户端。
 *
 * <p>主 AI 服务只消费 rag 服务整理后的上下文，不在本地接入 VectorStore、embedding
 * 模型或 Qdrant 查询链。普通流量在检索不可用时仍可继续，灰度 RPC 不可用时终止本轮。</p>
 */
@Service
public class RagRetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalClient.class);

    @DubboReference(check = false, timeout = 5000, retries = 0, lazy = true, init = false,
            parameters = {"traffic.canary.enabled", "true"})
    private RagRetrievalRpcService ragRetrievalRpcService;

    /**
     * 查询可传给患者分析 Agent 的 RAG 上下文。
     *
     * @param query          用户本轮问题
     * @param conversationId 会话 ID
     * @return 可拼入 Agent 请求的上下文；无资料时为空
     * @throws CanaryUnavailableException 已选灰度时无法完成检索 RPC
     */
    public String retrieveContext(String query, String conversationId) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        boolean canary = CanaryContext.get() != null;
        if (ragRetrievalRpcService == null) {
            return unavailable(canary);
        }
        RagRetrievalRpcDTO response;
        try {
            response = ragRetrievalRpcService.retrieve(toRequest(query, conversationId));
        } catch (Exception exception) {
            log.warn("Failed to retrieve RAG context through RPC, error_type={}", exception.getClass().getSimpleName());
            return unavailable(canary);
        }
        if (response == null) {
            return unavailable(canary);
        }
        if (!response.isMatched() || !StringUtils.hasText(response.getContext())) {
            return "";
        }
        return response.getContext().trim();
    }

    private String unavailable(boolean canary) {
        if (canary) {
            throw new CanaryUnavailableException();
        }
        return "";
    }

    public static final class CanaryUnavailableException extends RuntimeException {
        public CanaryUnavailableException() {
            super("RAG_CANARY_UNAVAILABLE");
        }
    }

    private RagRetrievalRpcRequest toRequest(String query, String conversationId) {
        RagRetrievalRpcRequest request = new RagRetrievalRpcRequest();
        request.setQuery(query.trim());
        request.setConversationId(StringUtils.hasText(conversationId) ? conversationId.trim() : "");
        return request;
    }
}

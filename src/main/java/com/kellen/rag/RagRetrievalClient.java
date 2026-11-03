package com.kellen.rag;

import com.kellen.rpc.rag.RagRetrievalRpcDTO;
import com.kellen.rpc.rag.RagRetrievalRpcRequest;
import com.kellen.rpc.rag.RagRetrievalRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RAG 知识检索 RPC 客户端。
 *
 * <p>主 AI 服务只消费 rag 服务整理后的上下文，不在本地接入 VectorStore、embedding
 * 模型或 Qdrant 查询链。rag 不可用或未命中时返回空字符串，普通 Agent 调用继续执行。</p>
 */
@Service
public class RagRetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalClient.class);

    @DubboReference(check = false, timeout = 5000, retries = 0, lazy = true, init = false)
    private RagRetrievalRpcService ragRetrievalRpcService;

    /**
     * 查询可传给患者分析 Agent 的 RAG 上下文。
     *
     * @param query          用户本轮问题
     * @param conversationId 会话 ID
     * @return 可拼入 Agent 请求的上下文；无资料时为空
     */
    public String retrieveContext(String query, String conversationId) {
        if (!StringUtils.hasText(query) || ragRetrievalRpcService == null) {
            return "";
        }
        try {
            RagRetrievalRpcDTO response = ragRetrievalRpcService.retrieve(toRequest(query, conversationId));
            if (response == null || !response.isMatched() || !StringUtils.hasText(response.getContext())) {
                return "";
            }
            return response.getContext().trim();
        } catch (Exception exception) {
            log.warn("Failed to retrieve RAG context through RPC, conversationId={}, message={}",
                    conversationId, exception.getMessage());
            return "";
        }
    }

    private RagRetrievalRpcRequest toRequest(String query, String conversationId) {
        RagRetrievalRpcRequest request = new RagRetrievalRpcRequest();
        request.setQuery(query.trim());
        request.setConversationId(StringUtils.hasText(conversationId) ? conversationId.trim() : "");
        return request;
    }
}

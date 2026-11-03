package com.ai.rag;

import com.kellen.rag.RagRetrievalClient;
import com.kellen.rpc.rag.RagRetrievalRpcDTO;
import com.kellen.rpc.rag.RagRetrievalRpcRequest;
import com.kellen.rpc.rag.RagRetrievalRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalClientTests {

    @Test
    void shouldReturnContextWhenRagMatches() {
        RagRetrievalRpcService rpcService = mock(RagRetrievalRpcService.class);
        RagRetrievalRpcDTO dto = new RagRetrievalRpcDTO();
        dto.setMatched(true);
        dto.setContext("  reference context  ");
        when(rpcService.retrieve(any(RagRetrievalRpcRequest.class))).thenReturn(dto);
        RagRetrievalClient client = new RagRetrievalClient();
        ReflectionTestUtils.setField(client, "ragRetrievalRpcService", rpcService);

        String context = client.retrieveContext("患者问题", "conversation-1");

        assertEquals("reference context", context);
    }

    @Test
    void shouldReturnEmptyContextWhenRagUnavailable() {
        RagRetrievalClient client = new RagRetrievalClient();

        String context = client.retrieveContext("患者问题", "conversation-1");

        assertEquals("", context);
    }
}

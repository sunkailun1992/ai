package com.ai.rag;

import com.kellen.rag.RagRetrievalClient;
import com.kellen.rpc.rag.RagRetrievalRpcDTO;
import com.kellen.rpc.rag.RagRetrievalRpcRequest;
import com.kellen.rpc.rag.RagRetrievalRpcService;
import com.kellen.traffic.CanaryContext;
import com.kellen.traffic.CanaryGrant;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalClientTests {

    @AfterEach
    void clearCanary() {
        CanaryContext.clear();
    }

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

    @Test
    void rpcUnavailabilityFailsOnlyForCanaryTraffic() {
        RagRetrievalRpcService rpcService = mock(RagRetrievalRpcService.class);
        when(rpcService.retrieve(any(RagRetrievalRpcRequest.class)))
                .thenThrow(new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "synthetic-private-rpc-details"));
        RagRetrievalClient client = new RagRetrievalClient();
        ReflectionTestUtils.setField(client, "ragRetrievalRpcService", rpcService);

        assertEquals("", client.retrieveContext("患者问题", "conversation-1"));
        bindCanary();
        var error = assertThrows(RagRetrievalClient.CanaryUnavailableException.class,
                () -> client.retrieveContext("患者问题", "conversation-1"));
        assertEquals("RAG_CANARY_UNAVAILABLE", error.getMessage());
    }

    @Test
    void canaryRequiresRpcResponseButAllowsSuccessfulNoMatch() {
        RagRetrievalClient client = new RagRetrievalClient();
        bindCanary();
        assertThrows(RagRetrievalClient.CanaryUnavailableException.class,
                () -> client.retrieveContext("患者问题", "conversation-1"));

        RagRetrievalRpcService rpcService = mock(RagRetrievalRpcService.class);
        ReflectionTestUtils.setField(client, "ragRetrievalRpcService", rpcService);
        assertThrows(RagRetrievalClient.CanaryUnavailableException.class,
                () -> client.retrieveContext("患者问题", "conversation-1"));

        RagRetrievalRpcDTO noMatch = new RagRetrievalRpcDTO();
        noMatch.setEnabled(true);
        when(rpcService.retrieve(any(RagRetrievalRpcRequest.class))).thenReturn(noMatch);
        assertEquals("", client.retrieveContext("患者问题", "conversation-1"));
    }

    private void bindCanary() {
        CanaryContext.set(new CanaryContext.Snapshot(
                new CanaryGrant("ai", "blue", Instant.parse("2099-01-01T00:00:00Z")), "synthetic-grant"));
    }
}

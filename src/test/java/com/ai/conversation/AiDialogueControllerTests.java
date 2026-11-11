package com.ai.conversation;


import com.kellen.conversation.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiDialogueController 单元测试。
 *
 * <p>使用 Mock Service 隔离真实模型调用，验证 Controller 对响应的透传行为。</p>
 */
class AiDialogueControllerTests {

    /**
     * 验证模型信息接口正确透传 Service 返回的 Provider 和模型名。
     */
    @Test
    void modelReturnsConfiguredProvider() {
        AiDialogueService dialogueService = mock(AiDialogueService.class); // Mock 业务服务，隔离真实模型调用。
        AiDialogueController controller = new AiDialogueController(dialogueService, mock(com.kellen.auth.AiRequestIdentityResolver.class)); // 直接构造 Controller，保持测试轻量。

        when(dialogueService.modelInfo()) // 指定模型信息方法的返回值。
                .thenReturn(new AiChatResponse(true, "gemini-test", "provider=google, model=gemini-test", null, LocalDateTime.now()));

        AiChatResponse response = controller.model(); // 调用 Controller 方法。

        assertTrue(response.success()); // 响应成功。
        assertEquals("gemini-test", response.model()); // 模型名透传正确。
    }

    /**
     * 验证主对话入口返回流式内容并委托给 Service。
     */
    @Test
    void chatReturnsStreamedContent() {
        AiDialogueService dialogueService = mock(AiDialogueService.class); // Mock 业务服务，避免真实模型调用。
        var identities = mock(com.kellen.auth.AiRequestIdentityResolver.class);
        var identity = new com.kellen.auth.AiRequestIdentity("synthetic-user", "tenant-a", "synthetic-token");
        when(identities.requireIdentity("Bearer synthetic-token")).thenReturn(identity);
        AiDialogueController controller = new AiDialogueController(dialogueService, identities);
        AiChatRequest request = new AiChatRequest("demo", "你好", null); // 构造纯文本流式请求。

        when(dialogueService.stream(request, identity)).thenReturn(Flux.just("你", "好"));

        String content = controller.chat("Bearer synthetic-token", request).map(org.springframework.http.codec.ServerSentEvent::data).collectList().block().stream().reduce("", String::concat);

        assertEquals("你好", content); // Controller 不改写流式内容。
        verify(dialogueService).stream(request, identity);
    }

}

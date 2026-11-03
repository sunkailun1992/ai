package com.ai.conversation;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.auth.WechatAuthService;

import com.kellen.conversation.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
        WechatAuthService authService = mock(WechatAuthService.class); // Mock 登录服务，当前用例不触发登录逻辑。
        ChatConversationService conversationService = mock(ChatConversationService.class); // Mock 会话服务，当前用例不触发会话逻辑。
        AiDialogueController controller = new AiDialogueController(dialogueService, authService, conversationService); // 直接构造 Controller，保持测试轻量。

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
        WechatAuthService authService = mock(WechatAuthService.class); // Mock 登录服务，当前用例按未登录 Postman 场景测试。
        ChatConversationService conversationService = mock(ChatConversationService.class); // Mock 会话服务，当前用例不直接访问。
        AiDialogueController controller = new AiDialogueController(dialogueService, authService, conversationService); // 直接构造 Controller，保持测试轻量。
        AiChatRequest request = new AiChatRequest("demo", "你好", null); // 构造纯文本流式请求。

        when(dialogueService.stream(request, null)).thenReturn(Flux.just("你", "好")); // 指定流式返回片段。

        String content = controller.chat(null, request).collectList().block().stream().reduce("", String::concat); // 收集片段验证透传。

        assertEquals("你好", content); // Controller 不改写流式内容。
        verify(dialogueService).stream(request, null); // 确认委托给 Service 的统一流式入口。
    }

    /**
     * 验证重命名会话接口会校验当前登录用户并委托会话服务。
     */
    @Test
    void renameConversationDelegatesWithCurrentUser() {
        AiDialogueService dialogueService = mock(AiDialogueService.class); // Mock 业务服务，当前用例不触发模型调用。
        WechatAuthService authService = mock(WechatAuthService.class); // Mock 登录服务，用于返回当前用户。
        ChatConversationService conversationService = mock(ChatConversationService.class); // Mock 会话服务，验证重命名委托参数。
        AiDialogueController controller = new AiDialogueController(dialogueService, authService, conversationService); // 直接构造 Controller。
        AiWechatUserEntity user = AiWechatUserEntity.create("openid-test", ""); // 构造当前登录用户。
        ReflectionTestUtils.setField(user, "id", 7L); // 单元测试中手动设置持久化主键。
        ConversationResponse expected = new ConversationResponse("c1", "新的标题", LocalDateTime.now(), LocalDateTime.now());

        when(authService.currentUser("Bearer token")).thenReturn(user); // 指定当前用户。
        when(conversationService.rename(7L, "c1", "新的标题")).thenReturn(expected); // 指定重命名结果。

        ConversationResponse response = controller.renameConversation("Bearer token", "c1", new ConversationRenameRequest("新的标题"));

        assertEquals(expected, response); // 响应透传服务层结果。
        verify(conversationService).rename(7L, "c1", "新的标题"); // 确认使用当前用户 ID 校验归属。
    }

}

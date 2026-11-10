package com.ai.memory;

import com.kellen.config.AiProperties;

import com.kellen.memory.AiChatMemoryRepository;
import com.kellen.memory.ChatMemorySummaryRepository;
import com.kellen.memory.ChatMemoryEntity;
import com.kellen.memory.DbChatMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbChatMemoryService 单元测试。
 *
 * <p>使用 Mock Repository 隔离真实数据库，验证记忆三个核心契约：
 * 时间正序回放、按角色写入、按会话清空。</p>
 */
class DbChatMemoryServiceTests {

    private final AiChatMemoryRepository repository = mock(AiChatMemoryRepository.class); // Mock 记忆 Repository。
    private final ChatMemorySummaryRepository summaryRepository = mock(ChatMemorySummaryRepository.class); // Mock 摘要 Repository。
    private final DbChatMemoryService service = new DbChatMemoryService(repository, summaryRepository, testProperties(20, 7)); // 直接构造被测服务，窗口参数与默认配置一致。

    /**
     * 验证倒序查询结果被反转为时间正序。
     */
    @Test
    void recentEntitiesReturnsChronologicalMessages() {
        List<ChatMemoryEntity> stored = new ArrayList<>(); // 模拟数据库倒序查询结果（最新在前）。
        stored.add(ChatMemoryEntity.of("demo", "assistant", "你好，有什么可以帮你？")); // 较新的 AI 回复。
        stored.add(ChatMemoryEntity.of("demo", "user", "你好")); // 较旧的用户消息。
        when(repository.findByConversationIdAndCreatedAtAfter(eq("demo"), any(LocalDateTime.class), eq(20))) // 指定窗口查询的返回值。
                .thenReturn(stored);

        List<ChatMemoryEntity> messages = service.recentEntities("demo"); // 调用被测的记忆读取方法。

        assertEquals(2, messages.size()); // 两条记忆都被回放。
        assertEquals("user", messages.get(0).getMessageType()); // 反转后第一条应是较旧的用户消息。
        assertEquals("你好", messages.get(0).getContent()); // 用户消息内容还原正确。
        assertEquals("assistant", messages.get(1).getMessageType()); // 反转后第二条应是较新的 AI 回复。
    }

    /**
     * 验证消息按角色字符串入库。
     */
    @Test
    void addMessagePersistsRoleValue() {
        service.addMessage("demo", "user", "问题"); // 写入用户提问。

        ArgumentCaptor<ChatMemoryEntity> captor = ArgumentCaptor.forClass(ChatMemoryEntity.class); // 捕获保存的实体。
        verify(repository, times(1)).save(captor.capture()); // 消息保存一次。
        assertEquals("user", captor.getValue().getMessageType()); // 按 user 角色保存。
        assertEquals("问题", captor.getValue().getContent()); // 用户消息内容保存正确。
        assertEquals("demo", captor.getValue().getConversationId()); // 会话 ID 关联正确。
    }

    /**
     * 验证清空操作按会话 ID 委托给 Repository 物理删除。
     */
    @Test
    void clearDeletesConversation() {
        service.clear("demo"); // 调用被测的清空方法。
        verify(repository).deleteByConversationId("demo"); // 确认按会话 ID 执行删除。
        verify(summaryRepository).deleteByConversationId("demo"); // 摘要也同步清理。
    }

    /**
     * 构造只关心记忆窗口参数的测试配置。
     *
     * @param maxMessages 最大历史条数。
     * @param windowDays  时间窗口天数。
     * @return 其余字段取默认值的配置对象。
     */
    private static AiProperties testProperties(int maxMessages, int windowDays) {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "测试提示词", 4, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain"), new AiProperties.MemoryProperties(maxMessages, windowDays)),
                new AiProperties.WechatProperties("wx-test", "", 30), // 微信登录不参与记忆窗口测试。
                new AiProperties.OssProperties("oss-cn-hangzhou.aliyuncs.com", "", "", "", "ai-miniapp", 60, 1800, 52428800)); // OSS 不参与记忆窗口测试。
    }
}

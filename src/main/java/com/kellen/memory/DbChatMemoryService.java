package com.kellen.memory;

import com.kellen.config.AiProperties;
import com.kellen.conversation.AiDialogueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库持久化对话记忆服务。
 *
 * <p>主 {@code ai} 服务只保存和裁剪文本上下文，不再实现 Spring AI {@code ChatMemory}
 * 接口。调用模型前由 {@link AiDialogueService} 把窗口内文本整理成上下文传给
 * {@code ai-agent}，避免执行层内部提示词或实现细节污染普通对话记忆。</p>
 */
@Service
public class DbChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(DbChatMemoryService.class);
    private static final int DEFAULT_SUMMARY_MAX_CHARS = 2400;

    private final AiChatMemoryRepository chatMemoryRepository;
    private final ChatMemorySummaryRepository summaryRepository;
    private final int maxMessages;
    private final int windowDays;

    /**
     * 构造器接收 Repository 和记忆窗口配置。
     *
     * @param chatMemoryRepository ai_chat_memory 表访问接口。
     * @param summaryRepository    ai_chat_memory_summary 表访问接口。
     * @param properties           ai.* 配置绑定对象。
     */
    public DbChatMemoryService(AiChatMemoryRepository chatMemoryRepository,
                               ChatMemorySummaryRepository summaryRepository,
                               AiProperties properties) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.summaryRepository = summaryRepository;
        this.maxMessages = Math.max(properties.dialogue().memory().maxMessages(), 1);
        this.windowDays = Math.max(properties.dialogue().memory().windowDays(), 1);
    }

    /**
     * 保存单条可回放给 Agent 的文本记忆。
     *
     * @param conversationId 会话唯一标识。
     * @param messageType    消息角色，通常为 user 或 assistant。
     * @param content        消息文本正文。
     */
    @Transactional
    public void addMessage(String conversationId, String messageType, String content) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(content)) {
            return;
        }
        String normalizedType = StringUtils.hasText(messageType) ? messageType.trim().toLowerCase() : "user";
        chatMemoryRepository.save(ChatMemoryEntity.of(conversationId.trim(), normalizedType, content.trim()));
        compactIfNecessary(conversationId.trim());
    }

    /**
     * 查询窗口内最近的记忆实体，供 Agent 上下文拼装和查询接口复用。
     *
     * @param conversationId 会话唯一标识。
     * @return 按时间正序（较旧在前）排列的记忆实体列表。
     */
    public List<ChatMemoryEntity> recentEntities(String conversationId) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(windowDays);
        List<ChatMemoryEntity> entities = chatMemoryRepository.findByConversationIdAndCreatedAtAfter(
                conversationId, windowStart, maxMessages);
        Collections.reverse(entities);
        return entities;
    }

    /**
     * 读取主流短记忆上下文：滚动摘要 + 最近 lossless 消息窗口。
     *
     * @param conversationId 会话唯一标识。
     * @return 可注入 Agent 的短记忆上下文。
     */
    public String shortMemoryContext(String conversationId) {
        String summary = summaryRepository.findByConversationId(conversationId)
                .map(ChatMemorySummaryEntity::getSummaryText)
                .orElse("");
        String recent = recentEntities(conversationId).stream()
                .map(entity -> entity.getMessageType() + ": " + entity.getContent())
                .collect(Collectors.joining("\n"));
        if (summary.isBlank()) {
            return recent;
        }
        if (recent.isBlank()) {
            return "会话摘要:\n" + summary;
        }
        return "会话摘要:\n" + summary + "\n\n最近对话:\n" + recent;
    }

    /**
     * 将业务事件摘要合并进当前会话短记忆摘要。
     *
     * @param conversationId 会话 ID。
     * @param summaryText    业务摘要。
     */
    @Transactional
    public void mergeSummary(String conversationId, String summaryText) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(summaryText)) {
            return;
        }
        String normalizedConversationId = conversationId.trim();
        String merged = mergeSummaryText(
                summaryRepository.findByConversationId(normalizedConversationId)
                        .map(ChatMemorySummaryEntity::getSummaryText)
                        .orElse(""),
                summaryText.trim()
        );
        ChatMemorySummaryEntity entity = summaryRepository.findByConversationId(normalizedConversationId)
                .orElseGet(() -> ChatMemorySummaryEntity.create(normalizedConversationId, merged));
        entity.updateSummary(merged);
        summaryRepository.save(entity);
    }

    /**
     * 查询用于页面展示的最近记忆消息。
     *
     * @param conversationId 会话唯一标识。
     * @param limitCount     最大消息条数。
     * @return 按时间正序排列的记忆实体列表。
     */
    public List<ChatMemoryEntity> displayEntities(String conversationId, int limitCount) {
        int safeLimit = Math.max(1, Math.min(limitCount, 200));
        List<ChatMemoryEntity> entities = chatMemoryRepository.findByConversationId(conversationId, safeLimit);
        Collections.reverse(entities);
        return entities;
    }

    /**
     * 清空指定会话的全部记忆；审计表不受影响。
     *
     * @param conversationId 会话唯一标识。
     */
    @Transactional
    public void clear(String conversationId) {
        log.warn("正在清空会话 [{}] 的所有记忆", conversationId);
        chatMemoryRepository.deleteByConversationId(conversationId);
        summaryRepository.deleteByConversationId(conversationId);
    }

    private void compactIfNecessary(String conversationId) {
        long count = chatMemoryRepository.countByConversationId(conversationId);
        if (count <= maxMessages) {
            return;
        }
        int overflow = (int) Math.min(count - maxMessages, maxMessages);
        List<ChatMemoryEntity> oldest = chatMemoryRepository.findOldestByConversationId(conversationId, overflow);
        if (oldest.isEmpty()) {
            return;
        }
        String compacted = oldest.stream()
                .map(entity -> entity.getMessageType() + ": " + entity.getContent())
                .collect(Collectors.joining("\n"));
        mergeSummary(conversationId, compacted);
        chatMemoryRepository.deleteMemoryIds(oldest.stream().map(ChatMemoryEntity::getId).toList());
    }

    private String mergeSummaryText(String existing, String addition) {
        String value;
        if (StringUtils.hasText(existing)) {
            value = existing.trim() + "\n" + addition.trim();
        } else {
            value = addition.trim();
        }
        if (value.length() <= DEFAULT_SUMMARY_MAX_CHARS) {
            return value;
        }
        return value.substring(value.length() - DEFAULT_SUMMARY_MAX_CHARS);
    }
}

package com.kellen.questionnaire;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话式问卷响应。
 *
 * @param sessionId             问卷会话 ID。
 * @param conversationId        小程序 AI 对话会话 ID。
 * @param questionnaireCode     问卷编码。
 * @param mode                  当前处理模式，固定 QUESTIONNAIRE。
 * @param status                问卷状态。
 * @param currentQuestionId     当前题目 ID；完成后为空。
 * @param reply                 返回给用户的自然语言回复。
 * @param savedQuestionId       本轮保存的题目 ID。
 * @param savedAttachmentCount  本轮保存的附件数量。
 * @param outboxEventId         完成问卷后写入的外部推送事件 ID。
 * @param answers               已保存的结构化答案摘要。
 * @param createdAt             响应生成时间。
 */
public record QuestionnaireChatResponse(
        Long sessionId,
        String conversationId,
        String questionnaireCode,
        String mode,
        String status,
        String currentQuestionId,
        String reply,
        String savedQuestionId,
        int savedAttachmentCount,
        Long outboxEventId,
        List<AnswerSnapshot> answers,
        LocalDateTime createdAt
) {

    /**
     * 已保存答案摘要。
     *
     * @param questionId        题目 ID。
     * @param selectedCode      标准选项编码，模板无选项时为空。
     * @param selectedText      标准化展示答案。
     * @param value             保留细节的答案值。
     * @param confidence        AI 解释或兜底规则置信度。
     * @param rawText           用户原始回答。
     * @param attachmentSummary 附件摘要。
     */
    public record AnswerSnapshot(String questionId,
                                 String selectedCode,
                                 String selectedText,
                                 String value,
                                 double confidence,
                                 String rawText,
                                 String attachmentSummary) {
    }
}

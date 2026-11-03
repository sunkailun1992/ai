package com.kellen.questionnaire;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户问卷会话详情响应。
 *
 * @param sessionId         问卷会话 ID。
 * @param conversationId    关联 AI 对话会话 ID。
 * @param questionnaireCode 问卷编码。
 * @param questionnaireName 问卷名称。
 * @param templateVersion   模板版本。
 * @param status            问卷状态。
 * @param currentQuestionId 当前题目 ID。
 * @param answers           已保存问卷答案明细。
 * @param createdAt         创建时间。
 * @param updatedAt         最近更新时间。
 */
public record QuestionnaireSessionDetailResponse(
        Long sessionId,
        String conversationId,
        String questionnaireCode,
        String questionnaireName,
        String templateVersion,
        String status,
        String currentQuestionId,
        List<AnswerDetail> answers,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 问卷单题答案详情。
     *
     * @param questionId        题目 ID。
     * @param label             题目名称。
     * @param prompt            AI 提问/模板题面。
     * @param rawText           用户原始回答。
     * @param selectedText      标准化答案。
     * @param value             详细答案值。
     * @param confidence        AI 解释置信度。
     * @param attachmentSummary 附件摘要。
     * @param createdAt         答案保存时间。
     */
    public record AnswerDetail(
            String questionId,
            String label,
            String prompt,
            String rawText,
            String selectedText,
            String value,
            double confidence,
            String attachmentSummary,
            LocalDateTime createdAt
    ) {
    }
}

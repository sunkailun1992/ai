package com.kellen.questionnaire;

import java.time.LocalDateTime;

/**
 * 当前用户问卷会话摘要响应。
 *
 * @param sessionId         问卷会话 ID。
 * @param conversationId    关联 AI 对话会话 ID。
 * @param questionnaireCode 问卷编码。
 * @param questionnaireName 问卷名称。
 * @param templateVersion   模板版本。
 * @param status            问卷状态。
 * @param currentQuestionId 当前题目 ID。
 * @param answeredCount     已保存答案数量。
 * @param createdAt         创建时间。
 * @param updatedAt         最近更新时间。
 */
public record QuestionnaireSessionSummaryResponse(
        Long sessionId,
        String conversationId,
        String questionnaireCode,
        String questionnaireName,
        String templateVersion,
        String status,
        String currentQuestionId,
        int answeredCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

package com.kellen.questionnaire;

/**
 * 小程序问卷列表摘要响应。
 *
 * @param id                模板主键。
 * @param questionnaireCode 问卷编码，启动问卷时传回后端。
 * @param version           模板版本。
 * @param name              问卷名称。
 * @param scene             使用场景。
 */
public record QuestionnaireTemplateSummaryResponse(
        Long id,
        String questionnaireCode,
        String version,
        String name,
        String scene
) {
}

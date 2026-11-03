package com.kellen.questionnaire;

/**
 * 对话式问卷模板响应。
 *
 * @param id                模板主键。
 * @param questionnaireCode 问卷编码。
 * @param version           模板版本。
 * @param name              问卷名称。
 * @param scene             使用场景。
 * @param schemaJson        完整模板 JSON。
 */
public record QuestionnaireTemplateResponse(
        Long id,
        String questionnaireCode,
        String version,
        String name,
        String scene,
        String schemaJson
) {
}

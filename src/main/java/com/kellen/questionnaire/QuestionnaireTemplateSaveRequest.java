package com.kellen.questionnaire;

/**
 * 对话式问卷模板保存请求。
 *
 * @param schemaJson 完整问卷模板 JSON。questionnaireCode/version/name/questions 从 JSON 中解析。
 * @param enabled    是否启用；为空时默认启用。
 */
public record QuestionnaireTemplateSaveRequest(
        String schemaJson,
        Boolean enabled
) {
}

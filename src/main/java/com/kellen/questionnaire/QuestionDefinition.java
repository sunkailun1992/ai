package com.kellen.questionnaire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 对话式问卷的单题运行时定义。
 *
 * <p>AI 解释答案时只能拿到当前题和有限上下文；是否保存、是否允许附件、
 * 是否进入下一题仍由后端根据该定义控制。</p>
 *
 * @param id                题目稳定 ID。
 * @param label             题目业务名称。
 * @param prompt            展示给患者的当前题提示语。
 * @param required          是否必答。
 * @param allowsAttachments 当前题是否允许上传附件。
 */
@JsonIgnoreProperties(ignoreUnknown = true) // 允许模板中的题目携带额外配置。
public record QuestionDefinition(
        String id,
        String label,
        String prompt,
        boolean required,
        boolean allowsAttachments
) {
}

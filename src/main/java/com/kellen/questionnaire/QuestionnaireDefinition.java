package com.kellen.questionnaire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 数据库问卷模板 JSON 的运行时定义。
 *
 * <p>该类型只描述后端可控问卷流程需要的稳定字段。模板可以继续扩展 UI、映射和规则字段，
 * 但服务层只读取这里声明的字段，避免模型或前端自由决定问卷结构。</p>
 *
 * @param questionnaireCode 问卷编码，用于区分不同问卷。
 * @param version           问卷模板版本。
 * @param name              问卷名称。
 * @param scene             问卷适用场景。
 * @param targetSystem      完成后推送的目标系统。
 * @param completionMessage 问卷完成时返回给用户的提示。
 * @param questions         题目列表，按顺序推进。
 */
@JsonIgnoreProperties(ignoreUnknown = true) // 允许模板保留前端 UI 或外部映射扩展字段。
public record QuestionnaireDefinition(
        String questionnaireCode,
        String version,
        String name,
        String scene,
        String targetSystem,
        String completionMessage,
        List<QuestionDefinition> questions
) {
}

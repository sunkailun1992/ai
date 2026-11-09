package com.kellen.questionnaire;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话式问卷答案实体，对应 ai_questionnaire_answer 表。
 *
 * <p>用户可以用自然语言回答，但保存时必须落成题目级结构化答案。
 * 附件在问卷模式下归属当前题目，而不是切换到普通报告分析流程。</p>
 */
@TableName("ai_questionnaire_answer")
public class QuestionnaireAnswerEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 答案记录 ID。

    private Long sessionId; // 当前答案所属问卷会话。

    private String questionId; // 当前答案对应的题目 ID。

    private String rawText; // 保存用户原话，便于审计和后续重新解析。

    private String selectedCode; // 模板存在选项时保存选项 code。

    private String selectedText; // 给列表和外部系统使用的短答案。

    @TableField("value_text")
    private String answerValue; // 适合对外推送和后续总结的详细答案。

    private double confidence; // 0 到 1，后续人工审核可参考。

    private String attachmentSummary; // 当前题关联的附件 URL/MIME 摘要。

    private LocalDateTime createdAt; // 答案保存时间。

    /**
     * 创建未经 AI 解释器结构化的原始答案。
     *
     * <p>用于解释器不可用或用户回答无需映射选项的路径，仍然按题目 ID 落库，
     * 避免把问卷回答混入普通聊天记忆。</p>
     *
     * @param sessionId         问卷会话 ID
     * @param questionId        模板题目 ID
     * @param rawText           用户原始回答
     * @param attachmentSummary 当前题附件摘要
     * @return 待保存的答案实体
     */
    public static QuestionnaireAnswerEntity of(Long sessionId, String questionId, String rawText, String attachmentSummary) {
        String normalizedText = rawText == null ? "" : rawText.trim();
        double confidence = normalizedText.isBlank() && (attachmentSummary == null || attachmentSummary.isBlank()) ? 0.5 : 1.0;
        return ofInterpreted(sessionId, questionId, normalizedText, "", normalizedText, normalizedText, confidence, attachmentSummary);
    }

    /**
     * 创建已经由无状态 AI 解释器解析后的结构化答案。
     *
     * <p>解释器只提供候选选项和值，最终题目归属、置信度范围和保存动作仍由后端控制；
     * 置信度会限制在 0 到 1，避免异常模型输出污染后续汇总。</p>
     *
     * @param sessionId         问卷会话 ID
     * @param questionId        模板题目 ID
     * @param rawText           用户原始回答
     * @param selectedCode      模板选项编码，可空
     * @param selectedText      面向列表展示的短答案
     * @param value             面向汇总和外部推送的答案值
     * @param confidence        解释器置信度
     * @param attachmentSummary 当前题附件摘要
     * @return 待保存的答案实体
     */
    public static QuestionnaireAnswerEntity ofInterpreted(Long sessionId,
                                                          String questionId,
                                                          String rawText,
                                                          String selectedCode,
                                                          String selectedText,
                                                          String value,
                                                          double confidence,
                                                          String attachmentSummary) {
        QuestionnaireAnswerEntity entity = new QuestionnaireAnswerEntity();
        String normalizedText = rawText == null ? "" : rawText.trim();
        entity.sessionId = sessionId;
        entity.questionId = questionId;
        entity.rawText = normalizedText;
        entity.selectedCode = selectedCode == null ? "" : selectedCode.trim();
        entity.selectedText = selectedText == null ? "" : selectedText.trim();
        entity.answerValue = value == null ? "" : value.trim();
        entity.confidence = Math.max(0.0, Math.min(1.0, confidence));
        entity.attachmentSummary = attachmentSummary;
        return entity;
    }

    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public String getRawText() {
        return rawText;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public String getSelectedCode() {
        return selectedCode;
    }

    public String getValue() {
        return answerValue;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getAttachmentSummary() {
        return attachmentSummary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

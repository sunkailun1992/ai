package com.kellen.questionnaire;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话式问卷会话实体，对应 ai_questionnaire_session 表。
 *
 * <p>该表是小程序统一 AI 对话框下的问卷状态机，不和普通聊天记忆混用。
 * 进行中的会话优先接管后续自然语言输入，直到用户暂停退出或问卷完成。</p>
 */
@TableName("ai_questionnaire_session")
public class QuestionnaireSessionEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 问卷会话 ID。

    private String conversationId; // 用于恢复同一个用户对话框下的问卷状态。

    private String patientId; // 后续外部系统推送和患者归档使用。

    private String userId; // 小程序当前登录用户或家属账号。

    private String questionnaireCode; // 当前会话使用的问卷模板编码。

    private String templateVersion; // 当前会话锁定的模板版本。

    private String currentQuestionId; // 未完成状态下指向下一次要回答的题目。

    private String status; // IN_PROGRESS / COMPLETED / EXITED，EXITED 表示暂停退出但未作废。

    private LocalDateTime createdAt; // 会话创建时间。

    private LocalDateTime updatedAt; // 会话最近推进或退出时间。

    /**
     * 启动新的问卷会话。
     *
     * <p>问卷会话会锁定模板编码和版本，后续即使模板更新也不会改变当前会话流程；
     * conversationId 为空时归入 default，保证老客户端也能继续问卷状态。</p>
     *
     * @param conversationId  当前 AI 对话会话 ID
     * @param patientId       患者或外部系统患者标识
     * @param userId          当前小程序用户标识
     * @param template        已解析的问卷模板
     * @param firstQuestionId 首题 ID
     * @return 处于进行中状态的问卷会话实体
     */
    public static QuestionnaireSessionEntity start(String conversationId, String patientId, String userId, QuestionnaireTemplateEntity template, String firstQuestionId) {
        QuestionnaireSessionEntity entity = new QuestionnaireSessionEntity();
        entity.conversationId = normalizeConversationId(conversationId);
        entity.patientId = patientId;
        entity.userId = userId;
        entity.questionnaireCode = template.getQuestionnaireCode();
        entity.templateVersion = template.getVersion();
        entity.currentQuestionId = firstQuestionId;
        entity.status = "IN_PROGRESS";
        return entity;
    }

    /**
     * 推进到下一道题。
     *
     * @param nextQuestionId 下一题 ID；为空时应由调用方改走完成流程
     */
    public void moveTo(String nextQuestionId) {
        this.currentQuestionId = nextQuestionId;
        this.status = "IN_PROGRESS";
    }

    /**
     * 将未完成问卷恢复到当前小程序对话框。
     *
     * @param conversationId 当前小程序 AI 对话会话 ID。
     * @param userId         当前操作用户 ID，可空。
     */
    public void resumeInConversation(String conversationId, String userId) {
        this.conversationId = normalizeConversationId(conversationId);
        this.userId = userId;
        this.status = "IN_PROGRESS";
    }

    /**
     * 标记问卷完成。
     *
     * <p>完成后清空当前题，后续再次进入同类问卷时不应按 RESUME_UNFINISHED 继续该记录。</p>
     */
    public void complete() {
        this.currentQuestionId = null;
        this.status = "COMPLETED";
    }

    /**
     * 暂停退出问卷。
     *
     * <p>这里不能清空 currentQuestionId。小程序中的“退出”表示离开问卷模式，
     * 用户稍后点击同一问卷时必须能回到原题继续。</p>
     */
    public void exit() {
        this.status = "EXITED";
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private static String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "default";
        }
        return conversationId.trim();
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getUserId() {
        return userId;
    }

    public String getQuestionnaireCode() {
        return questionnaireCode;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public String getCurrentQuestionId() {
        return currentQuestionId;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

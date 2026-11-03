package com.kellen.questionnaire;

import com.kellen.file.MediaInput;

import java.util.List;

/**
 * 对话式问卷请求。
 *
 * @param conversationId    小程序 AI 对话会话 ID。
 * @param patientId         患者 ID，问卷会话和后续外部推送使用。
 * @param userId            当前操作用户 ID，可空。
 * @param questionnaireCode 问卷编码，例如 symptom_triage_v1。
 * @param action            显式动作，例如 START_QUESTIONNAIRE / CONTINUE_QUESTIONNAIRE / EXIT_QUESTIONNAIRE。
 * @param message           用户本轮自然语言输入。
 * @param attachments       当前题目的远程附件 URL 列表。
 */
public record QuestionnaireChatRequest(
        String conversationId,
        String patientId,
        String userId,
        String questionnaireCode,
        String action,
        String message,
        List<MediaInput> attachments
) {

    /**
     * 获取非空附件列表，简化 Service 调用。
     *
     * @return 附件列表。
     */
    public List<MediaInput> effectiveAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}

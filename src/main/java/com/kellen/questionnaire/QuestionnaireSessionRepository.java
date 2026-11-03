package com.kellen.questionnaire;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Optional;

/**
 * 对话式问卷会话 Repository。
 */
@Mapper
public interface QuestionnaireSessionRepository extends AiBaseRepository<QuestionnaireSessionEntity> {

    /**
     * 查询指定对话会话下最近一条指定状态的问卷会话。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @param status         问卷状态。
     * @return 最近一条匹配状态的问卷会话。
     */
    default Optional<QuestionnaireSessionEntity> findFirstByConversationIdAndStatusOrderByUpdatedAtDesc(String conversationId, String status) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("conversation_id", conversationId)
                .eq("status", status)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询指定对话会话下全部指定状态的问卷会话。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @param status         问卷状态。
     * @return 按最近更新时间倒序排列的匹配会话。
     */
    default List<QuestionnaireSessionEntity> findByConversationIdAndStatusOrderByUpdatedAtDesc(String conversationId, String status) {
        return selectList(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("conversation_id", conversationId)
                .eq("status", status)
                .orderByDesc("updated_at"));
    }

    /**
     * 查询指定患者指定问卷最近一条进行中会话，用于 RESUME_UNFINISHED 策略。
     *
     * @param patientId         患者 ID。
     * @param questionnaireCode 问卷编码。
     * @param status            问卷状态。
     * @return 最近一条进行中问卷会话。
     */
    default Optional<QuestionnaireSessionEntity> findFirstByPatientIdAndQuestionnaireCodeAndStatusOrderByUpdatedAtDesc(String patientId, String questionnaireCode, String status) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("patient_id", patientId)
                .eq("questionnaire_code", questionnaireCode)
                .eq("status", status)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询指定患者指定问卷最近一条未完成会话，用于恢复进行中或已暂停问卷。
     *
     * @param patientId         患者 ID。
     * @param questionnaireCode 问卷编码。
     * @param statuses          未完成状态集合，例如 IN_PROGRESS/EXITED。
     * @return 最近一条未完成问卷会话。
     */
    default Optional<QuestionnaireSessionEntity> findFirstByPatientIdAndQuestionnaireCodeAndStatusInOrderByUpdatedAtDesc(String patientId, String questionnaireCode, List<String> statuses) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("patient_id", patientId)
                .eq("questionnaire_code", questionnaireCode)
                .in("status", statuses)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询指定对话会话下最近一次问卷会话，用于刷新页面后恢复状态。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @return 最近一次问卷会话。
     */
    default Optional<QuestionnaireSessionEntity> findFirstByConversationIdOrderByUpdatedAtDesc(String conversationId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("conversation_id", conversationId)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询当前用户填写过的问卷会话列表。
     *
     * @param userId 操作用户 ID。
     * @return 按最近更新时间倒序排列的问卷会话。
     */
    default List<QuestionnaireSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId) {
        return selectList(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("user_id", userId)
                .orderByDesc("updated_at"));
    }

    /**
     * 查询当前用户指定问卷会话详情。
     *
     * @param id     问卷会话 ID。
     * @param userId 操作用户 ID。
     * @return 匹配问卷会话。
     */
    default Optional<QuestionnaireSessionEntity> findByIdAndUserId(Long id, String userId) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireSessionEntity>()
                .eq("id", id)
                .eq("user_id", userId)
                .last("LIMIT 1")));
    }
}

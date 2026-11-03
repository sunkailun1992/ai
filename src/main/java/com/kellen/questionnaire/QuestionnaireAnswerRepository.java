package com.kellen.questionnaire;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;

/**
 * 对话式问卷答案 Repository。
 */
@Mapper
public interface QuestionnaireAnswerRepository extends AiBaseRepository<QuestionnaireAnswerEntity> {

    /**
     * 查询指定问卷会话下已经保存的答案，按采集时间正序返回。
     *
     * @param sessionId 问卷会话 ID。
     * @return 已保存答案列表。
     */
    default List<QuestionnaireAnswerEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId) {
        return selectList(new QueryWrapper<QuestionnaireAnswerEntity>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at"));
    }
}

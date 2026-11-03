package com.kellen.questionnaire;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Optional;

/**
 * 对话式问卷模板 Repository。
 */
@Mapper
public interface QuestionnaireTemplateRepository extends AiBaseRepository<QuestionnaireTemplateEntity> {

    /**
     * 判断指定问卷编码和版本是否已经存在。
     *
     * @param questionnaireCode 问卷编码。
     * @param version           模板版本。
     * @return 存在返回 true。
     */
    default boolean existsByQuestionnaireCodeAndVersion(String questionnaireCode, String version) {
        return selectCount(new QueryWrapper<QuestionnaireTemplateEntity>()
                .eq("questionnaire_code", questionnaireCode)
                .eq("template_version", version)) > 0;
    }

    /**
     * 查询指定问卷编码和版本的模板。
     *
     * @param questionnaireCode 问卷编码。
     * @param version           模板版本。
     * @return 匹配的模板。
     */
    default Optional<QuestionnaireTemplateEntity> findByQuestionnaireCodeAndVersion(String questionnaireCode, String version) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireTemplateEntity>()
                .eq("questionnaire_code", questionnaireCode)
                .eq("template_version", version)
                .last("LIMIT 1")));
    }

    /**
     * 查询指定问卷编码下最新启用模板。
     *
     * @param questionnaireCode 问卷编码。
     * @return 最新启用模板。
     */
    default Optional<QuestionnaireTemplateEntity> findFirstByQuestionnaireCodeAndEnabledTrueOrderByVersionDesc(String questionnaireCode) {
        return Optional.ofNullable(selectOne(new QueryWrapper<QuestionnaireTemplateEntity>()
                .eq("questionnaire_code", questionnaireCode)
                .eq("enabled", true)
                .orderByDesc("template_version")
                .last("LIMIT 1")));
    }

    /**
     * 查询所有启用中的问卷模板，供小程序抽屉展示问卷列表。
     *
     * @return 按更新时间倒序排列的启用模板。
     */
    default List<QuestionnaireTemplateEntity> findByEnabledTrueOrderByUpdatedAtDesc() {
        return selectList(new QueryWrapper<QuestionnaireTemplateEntity>()
                .eq("enabled", true)
                .orderByDesc("updated_at"));
    }
}

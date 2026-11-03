package com.kellen.questionnaire;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 当前用户问卷记录查询服务。
 *
 * <p>只提供“我的”页面查看用户已填写问卷的摘要和详情。
 * 问卷采集、答案解释和外部推送仍由 {@link ConversationalQuestionnaireService} 控制。</p>
 */
@Service // 注册为 Spring Service，供问卷 Controller 调用。
public class QuestionnaireRecordService {

    private final QuestionnaireSessionRepository sessionRepository; // 问卷会话 Repository。
    private final QuestionnaireAnswerRepository answerRepository; // 问卷答案 Repository。
    private final QuestionnaireTemplateRepository templateRepository; // 问卷模板 Repository。
    private final ObjectMapper objectMapper = new ObjectMapper(); // 模板 JSON 解析器。

    /**
     * 构造器注入查询依赖。
     *
     * @param sessionRepository  问卷会话 Repository。
     * @param answerRepository   问卷答案 Repository。
     * @param templateRepository 问卷模板 Repository。
     */
    public QuestionnaireRecordService(QuestionnaireSessionRepository sessionRepository,
                                      QuestionnaireAnswerRepository answerRepository,
                                      QuestionnaireTemplateRepository templateRepository) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.templateRepository = templateRepository;
    }

    /**
     * 查询当前用户填写过的问卷列表。
     *
     * @param userId 当前用户业务 ID，例如 wx-1。
     * @return 问卷会话摘要列表。
     */
    @Transactional(readOnly = true) // 列表查询不写库。
    public List<QuestionnaireSessionSummaryResponse> list(String userId) {
        String safeUserId = requiredText(userId, "userId");
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(safeUserId).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查询当前用户指定问卷会话详情。
     *
     * @param userId    当前用户业务 ID，例如 wx-1。
     * @param sessionId 问卷会话 ID。
     * @return 问卷会话详情。
     */
    @Transactional(readOnly = true) // 详情查询不写库。
    public QuestionnaireSessionDetailResponse detail(String userId, Long sessionId) {
        String safeUserId = requiredText(userId, "userId");
        QuestionnaireSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, safeUserId)
                .orElseThrow(() -> new IllegalArgumentException("问卷记录不存在或无权访问"));
        QuestionnaireDefinition definition = definition(session);
        Map<String, QuestionDefinition> questions = definition.questions().stream()
                .collect(Collectors.toMap(QuestionDefinition::id, Function.identity(), (left, right) -> left));
        List<QuestionnaireSessionDetailResponse.AnswerDetail> answers = answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(answer -> toAnswerDetail(answer, questions.get(answer.getQuestionId())))
                .toList();
        return new QuestionnaireSessionDetailResponse(
                session.getId(),
                session.getConversationId(),
                session.getQuestionnaireCode(),
                questionnaireName(session),
                session.getTemplateVersion(),
                session.getStatus(),
                session.getCurrentQuestionId(),
                answers,
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private QuestionnaireSessionSummaryResponse toSummary(QuestionnaireSessionEntity session) {
        return new QuestionnaireSessionSummaryResponse(
                session.getId(),
                session.getConversationId(),
                session.getQuestionnaireCode(),
                questionnaireName(session),
                session.getTemplateVersion(),
                session.getStatus(),
                session.getCurrentQuestionId(),
                answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).size(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private QuestionnaireSessionDetailResponse.AnswerDetail toAnswerDetail(QuestionnaireAnswerEntity answer, QuestionDefinition question) {
        return new QuestionnaireSessionDetailResponse.AnswerDetail(
                answer.getQuestionId(),
                question == null ? answer.getQuestionId() : text(question.label()),
                question == null ? "" : text(question.prompt()),
                text(answer.getRawText()),
                text(answer.getSelectedText()),
                text(answer.getValue()),
                answer.getConfidence(),
                text(answer.getAttachmentSummary()),
                answer.getCreatedAt()
        );
    }

    private String questionnaireName(QuestionnaireSessionEntity session) {
        return templateRepository.findByQuestionnaireCodeAndVersion(session.getQuestionnaireCode(), session.getTemplateVersion())
                .map(QuestionnaireTemplateEntity::getName)
                .filter(StringUtils::hasText)
                .orElse(session.getQuestionnaireCode());
    }

    private QuestionnaireDefinition definition(QuestionnaireSessionEntity session) {
        return templateRepository.findByQuestionnaireCodeAndVersion(session.getQuestionnaireCode(), session.getTemplateVersion())
                .map(template -> parse(template.getSchemaJson()))
                .orElseGet(() -> new QuestionnaireDefinition(
                        session.getQuestionnaireCode(),
                        session.getTemplateVersion(),
                        session.getQuestionnaireCode(),
                        "",
                        "",
                        "",
                        List.of()
                ));
    }

    private QuestionnaireDefinition parse(String schemaJson) {
        try {
            return objectMapper.readValue(schemaJson, QuestionnaireDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid questionnaire template json", exception);
        }
    }

    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}

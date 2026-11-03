package com.kellen.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.questionnaire.QuestionDefinition;
import com.kellen.questionnaire.QuestionnaireAnswerEntity;
import com.kellen.questionnaire.QuestionnaireDefinition;
import com.kellen.questionnaire.QuestionnaireSessionEntity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 患者长期记忆服务。
 *
 * <p>主流 Agent 长期记忆以用户/患者维度保存结构化事实，检索时只注入和当前患者相关的
 * 小量事实。这里以 MySQL 作为权威库，Qdrant/Redis 仅作为后续检索和缓存层。</p>
 */
@Service
public class PatientMemoryService {

    private static final int DEFAULT_LONG_MEMORY_LIMIT = 12;
    private static final int DEFAULT_LONG_MEMORY_CONTEXT_CHARS = 2400;
    private static final String DEFAULT_SOURCE_TENANT_ID = "100";
    private static final String SOURCE_QUESTIONNAIRE_SESSION = "QUESTIONNAIRE_SESSION";

    private final PatientMemoryRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造患者长期记忆服务。
     *
     * @param repository 患者长期记忆 Repository。
     */
    public PatientMemoryService(PatientMemoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 将已完成问卷沉淀为患者长期记忆，并返回当前会话可用的短摘要。
     *
     * @param session    问卷会话。
     * @param definition 问卷定义。
     * @param answers    已保存答案。
     * @return 问卷完成摘要，可写入短记忆。
     */
    @Transactional
    public String rememberQuestionnaireCompletion(QuestionnaireSessionEntity session,
                                                  QuestionnaireDefinition definition,
                                                  List<QuestionnaireAnswerEntity> answers) {
        if (session == null || !StringUtils.hasText(session.getPatientId()) || definition == null) {
            return "";
        }
        String summary = questionnaireSummary(definition, answers);
        upsertMemory(
                session,
                "questionnaire_summary",
                "questionnaire:" + definition.questionnaireCode() + ":summary",
                definition.name() + "摘要",
                summary,
                factsJson(Map.of(
                        "questionnaireCode", definition.questionnaireCode(),
                        "questionnaireName", definition.name(),
                        "sessionId", session.getId(),
                        "answerCount", answers == null ? 0 : answers.size()
                )),
                averageConfidence(answers)
        );
        for (QuestionnaireAnswerEntity answer : answers == null ? List.<QuestionnaireAnswerEntity>of() : answers) {
            QuestionDefinition question = questionById(definition, answer.getQuestionId());
            String title = question == null ? answer.getQuestionId() : safeQuestionLabel(question);
            String content = title + "：" + answerText(answer);
            upsertMemory(
                    session,
                    "questionnaire_answer",
                    "questionnaire:" + definition.questionnaireCode() + ":" + answer.getQuestionId(),
                    title,
                    content,
                    answerFactsJson(definition, question, answer, session),
                    answer.getConfidence()
            );
        }
        return summary;
    }

    /**
     * 读取患者长期记忆上下文，供普通咨询注入模型。
     *
     * @param patientId       患者 ID。
     * @param sourceTenantId  来源租户 ID，可空。
     * @return 可注入模型的患者记忆上下文。
     */
    @Transactional(readOnly = true)
    public String patientContext(String patientId, String sourceTenantId) {
        if (!StringUtils.hasText(patientId)) {
            return "";
        }
        String context = repository.findActiveByPatient(patientId.trim(), sourceTenantId, DEFAULT_LONG_MEMORY_LIMIT)
                .stream()
                .map(memory -> "- " + memory.getTitle() + "：" + memory.getContent())
                .collect(Collectors.joining("\n"));
        if (context.length() <= DEFAULT_LONG_MEMORY_CONTEXT_CHARS) {
            return context;
        }
        return context.substring(0, DEFAULT_LONG_MEMORY_CONTEXT_CHARS);
    }

    private void upsertMemory(QuestionnaireSessionEntity session,
                              String memoryType,
                              String memoryKey,
                              String title,
                              String content,
                              String factsJson,
                              double confidence) {
        PatientMemoryEntity entity = repository.findActiveByPatientAndKey(
                        session.getPatientId(),
                        sourceTenantId(session),
                        memoryKey)
                .orElseGet(() -> PatientMemoryEntity.create(
                        session.getPatientId(),
                        sourceTenantId(session),
                        memoryType,
                        memoryKey,
                        title,
                        content,
                        factsJson,
                        SOURCE_QUESTIONNAIRE_SESSION,
                        String.valueOf(session.getId()),
                        confidence,
                        LocalDateTime.now()
                ));
        entity.refresh(
                title,
                content,
                factsJson,
                SOURCE_QUESTIONNAIRE_SESSION,
                String.valueOf(session.getId()),
                confidence,
                LocalDateTime.now()
        );
        repository.save(entity);
    }

    private String sourceTenantId(QuestionnaireSessionEntity session) {
        return DEFAULT_SOURCE_TENANT_ID;
    }

    private String questionnaireSummary(QuestionnaireDefinition definition, List<QuestionnaireAnswerEntity> answers) {
        String details = (answers == null ? List.<QuestionnaireAnswerEntity>of() : answers).stream()
                .map(answer -> {
                    QuestionDefinition question = questionById(definition, answer.getQuestionId());
                    String label = question == null ? answer.getQuestionId() : safeQuestionLabel(question);
                    return label + "：" + answerText(answer);
                })
                .collect(Collectors.joining("；"));
        if (!StringUtils.hasText(details)) {
            return "用户已完成" + definition.name() + "。";
        }
        return "用户已完成" + definition.name() + "，主要结果：" + details + "。";
    }

    private String answerText(QuestionnaireAnswerEntity answer) {
        if (answer == null) {
            return "";
        }
        if (StringUtils.hasText(answer.getValue())) {
            return answer.getValue().trim();
        }
        if (StringUtils.hasText(answer.getSelectedText())) {
            return answer.getSelectedText().trim();
        }
        if (StringUtils.hasText(answer.getRawText())) {
            return answer.getRawText().trim();
        }
        return "";
    }

    private String answerFactsJson(QuestionnaireDefinition definition,
                                   QuestionDefinition question,
                                   QuestionnaireAnswerEntity answer,
                                   QuestionnaireSessionEntity session) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("questionnaireCode", definition.questionnaireCode());
        facts.put("questionnaireName", definition.name());
        facts.put("sessionId", session.getId());
        facts.put("questionId", answer.getQuestionId());
        facts.put("questionLabel", question == null ? answer.getQuestionId() : safeQuestionLabel(question));
        facts.put("rawText", text(answer.getRawText()));
        facts.put("selectedCode", text(answer.getSelectedCode()));
        facts.put("selectedText", text(answer.getSelectedText()));
        facts.put("value", text(answer.getValue()));
        facts.put("confidence", answer.getConfidence());
        facts.put("attachmentSummary", text(answer.getAttachmentSummary()));
        return factsJson(facts);
    }

    private String factsJson(Map<String, Object> facts) {
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize patient memory facts", exception);
        }
    }

    private QuestionDefinition questionById(QuestionnaireDefinition definition, String questionId) {
        if (definition.questions() == null) {
            return null;
        }
        return definition.questions().stream()
                .filter(question -> question.id().equals(questionId))
                .findFirst()
                .orElse(null);
    }

    private String safeQuestionLabel(QuestionDefinition question) {
        if (StringUtils.hasText(question.label())) {
            return question.label().trim();
        }
        if (StringUtils.hasText(question.prompt())) {
            return question.prompt().trim();
        }
        return question.id();
    }

    private double averageConfidence(List<QuestionnaireAnswerEntity> answers) {
        if (answers == null || answers.isEmpty()) {
            return 0.8;
        }
        return answers.stream().mapToDouble(QuestionnaireAnswerEntity::getConfidence).average().orElse(0.8);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.kellen.questionnaire;

import com.kellen.file.MediaInput;
import com.kellen.file.MediaInputValidator;
import com.kellen.integration.IntegrationOutboxEntity;
import com.kellen.integration.IntegrationOutboxRepository;
import com.kellen.memory.DbChatMemoryService;
import com.kellen.memory.PatientMemoryService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对话式问卷流程服务。
 *
 * <p>本服务是小程序统一 AI 对话框下的问卷处理器：用户看到自然对话，
 * AI 在当前题范围内解释患者自然语言并给出追问建议；后端用问卷模板推进状态机、
 * 校验附件归属、保存结构化答案，并在完成后写 outbox 等待外部系统异步推送。</p>
 */
@Service // 注册为 Spring Service，供 Controller 构造器注入。
public class ConversationalQuestionnaireService {

    private static final String DEFAULT_QUESTIONNAIRE_CODE = "symptom_triage_v1";
    private static final String MODE = "QUESTIONNAIRE";

    private final QuestionnaireTemplateRepository templateRepository; // 问卷模板数据库访问接口。
    private final QuestionnaireSessionRepository sessionRepository; // 问卷会话数据库访问接口。
    private final QuestionnaireAnswerRepository answerRepository; // 问卷答案数据库访问接口。
    private final IntegrationOutboxRepository outboxRepository; // 外部推送 outbox 数据库访问接口。
    private final MediaInputValidator mediaInputValidator; // 多模态附件 URL 安全校验器。
    private final QuestionnaireAnswerInterpreterService answerInterpreter; // 受控 AI 答案解释器。
    private final QuestionnaireReplyService replyService; // 受控 AI 自然提问生成服务。
    private final DbChatMemoryService chatMemoryService; // 当前会话短记忆服务。
    private final PatientMemoryService patientMemoryService; // 患者长期记忆服务。
    private final ObjectMapper objectMapper = new ObjectMapper(); // 问卷模板和 outbox payload 的 JSON 处理器。

    /**
     * 构造器注入对话式问卷流程依赖。
     *
     * @param templateRepository 问卷模板数据库访问接口。
     * @param sessionRepository  问卷会话数据库访问接口。
     * @param answerRepository   问卷答案数据库访问接口。
     * @param outboxRepository   外部推送 outbox 数据库访问接口。
     * @param mediaInputValidator 附件 URL 安全校验器。
     * @param answerInterpreter   受控 AI 答案解释器。
     * @param replyService        受控 AI 自然提问生成服务。
     * @param chatMemoryService   当前会话短记忆服务。
     * @param patientMemoryService 患者长期记忆服务。
     */
    public ConversationalQuestionnaireService(QuestionnaireTemplateRepository templateRepository,
                                              QuestionnaireSessionRepository sessionRepository,
                                              QuestionnaireAnswerRepository answerRepository,
                                              IntegrationOutboxRepository outboxRepository,
                                              MediaInputValidator mediaInputValidator,
                                              QuestionnaireAnswerInterpreterService answerInterpreter,
                                              QuestionnaireReplyService replyService,
                                              DbChatMemoryService chatMemoryService,
                                              PatientMemoryService patientMemoryService) {
        this.templateRepository = templateRepository; // 保存问卷模板数据库访问接口。
        this.sessionRepository = sessionRepository; // 保存问卷会话数据库访问接口。
        this.answerRepository = answerRepository; // 保存问卷答案数据库访问接口。
        this.outboxRepository = outboxRepository; // 保存外部推送 outbox 数据库访问接口。
        this.mediaInputValidator = mediaInputValidator; // 保存多模态附件校验器。
        this.answerInterpreter = answerInterpreter; // 保存受控 AI 答案解释器。
        this.replyService = replyService; // 保存受控 AI 自然提问生成服务。
        this.chatMemoryService = chatMemoryService; // 保存短记忆服务。
        this.patientMemoryService = patientMemoryService; // 保存长期记忆服务。
    }

    /**
     * 处理一轮对话式问卷请求。
     *
     * @param request 小程序统一 AI 对话框传来的问卷请求。
     * @return 当前问卷状态和下一句回复。
     */
    @Transactional // 保证一轮问卷状态变更、答案保存和 outbox 写入原子完成。
    public QuestionnaireChatResponse chat(QuestionnaireChatRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        if ("EXIT_QUESTIONNAIRE".equalsIgnoreCase(text(request.action()))) {
            return exit(request, conversationId);
        }
        boolean resumed = false;
        QuestionnaireSessionEntity session;
        if (shouldStartNew(request)) {
            SessionStartResult startResult = startOrResumeSession(request, conversationId);
            session = startResult.session();
            resumed = startResult.resumed();
        } else {
            session = activeSession(conversationId).orElseGet(() -> startOrResumeSession(request, conversationId).session());
        }
        QuestionnaireDefinition definition = loadDefinition(session.getQuestionnaireCode());
        if (shouldStartNew(request) || promptOnly(request)) {
            return response(session, definition, currentQuestionReply(session, definition, resumed), null, 0, null);
        }
        return answerAndMove(request, session, definition);
    }

    /**
     * 普通 AI 对话入口的保护性路由：当前会话存在进行中问卷时，转入问卷状态机。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @param userId         当前操作用户 ID，可空。
     * @param message        用户本轮输入。
     * @param attachments    本轮附件。
     * @return 存在进行中问卷时返回问卷响应，否则返回空。
     */
    @Transactional // 可能保存本轮答案并推进问卷状态。
    public Optional<QuestionnaireChatResponse> continueActiveIfPresent(String conversationId,
                                                                       String userId,
                                                                       String message,
                                                                       List<MediaInput> attachments) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        return activeSession(normalizedConversationId)
                .map(session -> chat(new QuestionnaireChatRequest(
                        normalizedConversationId,
                        session.getPatientId(),
                        userId,
                        session.getQuestionnaireCode(),
                        "CONTINUE_QUESTIONNAIRE",
                        message,
                        attachments
                )));
    }

    /**
     * 查询最近一次问卷会话状态，便于 Postman 或小程序刷新页面时恢复。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @return 最近一次问卷状态；普通会话没有问卷时返回空。
     */
    @Transactional(readOnly = true) // 只读事务，避免刷新页面查询状态时触发无意义写入。
    public Optional<QuestionnaireChatResponse> snapshot(String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        Optional<QuestionnaireSessionEntity> latestSession = activeSession(normalizedConversationId)
                .or(() -> sessionRepository.findFirstByConversationIdOrderByUpdatedAtDesc(normalizedConversationId));
        if (latestSession.isEmpty()) {
            return Optional.empty();
        }
        QuestionnaireSessionEntity session = latestSession.get();
        QuestionnaireDefinition definition = loadDefinition(session.getQuestionnaireCode());
        String reply = switch (session.getStatus()) {
            case "COMPLETED" -> "问卷已完成。";
            case "EXITED" -> "问卷已暂停。点击同一问卷可以继续上次进度。";
            default -> currentQuestionReply(session, definition, true);
        };
        return Optional.of(response(session, definition, reply, null, 0, null));
    }

    private QuestionnaireChatResponse answerAndMove(QuestionnaireChatRequest request,
                                                    QuestionnaireSessionEntity session,
                                                    QuestionnaireDefinition definition) {
        QuestionDefinition currentQuestion = questionById(definition, session.getCurrentQuestionId());
        List<MediaInputValidator.ValidatedMedia> attachments = mediaInputValidator.validateUrlMedia(request.effectiveAttachments());
        if (!attachments.isEmpty() && !currentQuestion.allowsAttachments()) {
            String reply = "当前问题暂不需要附件。要暂停问卷先分析报告，还是把附件作为补充资料保存到当前问题？";
            return response(session, definition, reply, null, attachments.size(), null);
        }
        String attachmentSummary = mediaInputValidator.summarize(attachments);
        List<QuestionnaireAnswerEntity> previousAnswers = answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        QuestionnaireAnswerInterpreterService.InterpretedAnswer interpreted = answerInterpreter.interpret(
                definition,
                currentQuestion,
                previousAnswers,
                request.message(),
                attachmentSummary
        );
        if (interpreted.needFollowUp()) {
            return response(session, definition, interpreted.followUpQuestion(), null, attachments.size(), null);
        }
        QuestionnaireAnswerEntity answer = answerRepository.save(QuestionnaireAnswerEntity.ofInterpreted(
                session.getId(),
                currentQuestion.id(),
                interpreted.rawText(),
                interpreted.selectedCode(),
                interpreted.selectedText(),
                interpreted.value(),
                interpreted.confidence(),
                attachmentSummary
        ));
        Optional<QuestionDefinition> next = nextQuestion(definition, currentQuestion.id());
        if (next.isPresent()) {
            session.moveTo(next.get().id());
            sessionRepository.save(session);
            return response(session, definition, currentQuestionReply(session, definition, false), answer.getQuestionId(), attachments.size(), null);
        }
        session.complete();
        sessionRepository.save(session);
        IntegrationOutboxEntity outbox = writeCompletedOutbox(session, definition);
        rememberCompletedQuestionnaire(session, definition);
        return response(session, definition, completionReply(definition), answer.getQuestionId(), attachments.size(), outbox.getId());
    }

    private void rememberCompletedQuestionnaire(QuestionnaireSessionEntity session, QuestionnaireDefinition definition) {
        List<QuestionnaireAnswerEntity> answers = answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        String summary = patientMemoryService.rememberQuestionnaireCompletion(session, definition, answers);
        chatMemoryService.mergeSummary(session.getConversationId(), summary);
    }

    private SessionStartResult startOrResumeSession(QuestionnaireChatRequest request, String conversationId) {
        String questionnaireCode = requiredQuestionnaireCode(request.questionnaireCode());
        Optional<QuestionnaireSessionEntity> unfinished = unfinishedSession(text(request.patientId()), questionnaireCode);
        if (unfinished.isPresent()) {
            QuestionnaireSessionEntity session = unfinished.get();
            if (!ensureResumableCurrentQuestion(session)) {
                return new SessionStartResult(startSession(request, conversationId, questionnaireCode), false);
            }
            session.resumeInConversation(conversationId, text(request.userId()));
            return new SessionStartResult(sessionRepository.save(session), true);
        }
        return new SessionStartResult(startSession(request, conversationId, questionnaireCode), false);
    }

    private QuestionnaireSessionEntity startSession(QuestionnaireChatRequest request, String conversationId, String questionnaireCode) {
        QuestionnaireTemplateEntity template = template(questionnaireCode);
        QuestionnaireDefinition definition = parseDefinition(template.getSchemaJson());
        QuestionnaireSessionEntity session = QuestionnaireSessionEntity.start(
                conversationId,
                text(request.patientId()),
                text(request.userId()),
                template,
                firstQuestion(definition).id()
        );
        return sessionRepository.save(session);
    }

    private QuestionnaireChatResponse exit(QuestionnaireChatRequest request, String conversationId) {
        List<QuestionnaireSessionEntity> activeSessions = sessionRepository.findByConversationIdAndStatusOrderByUpdatedAtDesc(conversationId, "IN_PROGRESS");
        QuestionnaireSessionEntity session = activeSessions.stream().findFirst()
                .or(() -> unfinishedSession(text(request.patientId()), requiredQuestionnaireCode(request.questionnaireCode())))
                .orElseThrow(() -> new IllegalArgumentException("active questionnaire session not found: " + conversationId));
        if (activeSessions.isEmpty()) {
            session.exit();
            sessionRepository.save(session);
        } else {
            for (QuestionnaireSessionEntity activeSession : activeSessions) {
                activeSession.exit();
                sessionRepository.save(activeSession);
            }
        }
        QuestionnaireDefinition definition = loadDefinition(session.getQuestionnaireCode());
        return response(session, definition, "已暂停当前问卷。你可以继续普通咨询，或稍后点击同一问卷继续上次进度。", null, 0, null);
    }

    private boolean ensureResumableCurrentQuestion(QuestionnaireSessionEntity session) {
        if (StringUtils.hasText(session.getCurrentQuestionId())) {
            return true;
        }
        QuestionnaireDefinition definition = loadDefinition(session.getQuestionnaireCode());
        Optional<String> nextQuestionId = nextUnansweredQuestionId(session, definition);
        if (nextQuestionId.isEmpty()) {
            session.complete();
            sessionRepository.save(session);
            return false;
        }
        session.moveTo(nextQuestionId.get());
        return true;
    }

    private Optional<String> nextUnansweredQuestionId(QuestionnaireSessionEntity session, QuestionnaireDefinition definition) {
        Set<String> answeredQuestionIds = new HashSet<>();
        for (QuestionnaireAnswerEntity answer : answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())) {
            if (StringUtils.hasText(answer.getQuestionId())) {
                answeredQuestionIds.add(answer.getQuestionId());
            }
        }
        for (QuestionDefinition question : definition.questions()) {
            if (!answeredQuestionIds.contains(question.id())) {
                return Optional.of(question.id());
            }
        }
        return Optional.empty();
    }

    private IntegrationOutboxEntity writeCompletedOutbox(QuestionnaireSessionEntity session, QuestionnaireDefinition definition) {
        String payload = payloadJson(session, definition, answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()));
        String targetSystem = StringUtils.hasText(definition.targetSystem()) ? definition.targetSystem() : "test-system";
        IntegrationOutboxEntity candidate = IntegrationOutboxEntity.questionnaireCompleted(session.getId(), targetSystem, payload);
        return outboxRepository.findByIdempotencyKey("QUESTIONNAIRE_COMPLETED:" + session.getId() + ":" + targetSystem)
                .orElseGet(() -> outboxRepository.save(candidate));
    }

    private String payloadJson(QuestionnaireSessionEntity session, QuestionnaireDefinition definition, List<QuestionnaireAnswerEntity> answers) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "sessionId", session.getId(),
                    "conversationId", session.getConversationId(),
                    "patientId", session.getPatientId() == null ? "" : session.getPatientId(),
                    "questionnaireCode", session.getQuestionnaireCode(),
                    "questionnaireName", definition.name(),
                    "answers", answers.stream()
                            .map(answer -> Map.of(
                                    "questionId", answer.getQuestionId(),
                                    "rawText", answer.getRawText() == null ? "" : answer.getRawText(),
                                    "selectedCode", answer.getSelectedCode() == null ? "" : answer.getSelectedCode(),
                                    "selectedText", answer.getSelectedText() == null ? "" : answer.getSelectedText(),
                                    "value", answer.getValue() == null ? "" : answer.getValue(),
                                    "confidence", answer.getConfidence(),
                                    "attachmentSummary", answer.getAttachmentSummary() == null ? "" : answer.getAttachmentSummary()
                            ))
                            .toList()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize questionnaire outbox payload", exception);
        }
    }

    private QuestionnaireChatResponse response(QuestionnaireSessionEntity session,
                                               QuestionnaireDefinition definition,
                                               String reply,
                                               String savedQuestionId,
                                               int savedAttachmentCount,
                                               Long outboxEventId) {
        List<QuestionnaireChatResponse.AnswerSnapshot> answers = answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(answer -> new QuestionnaireChatResponse.AnswerSnapshot(
                        answer.getQuestionId(),
                        answer.getSelectedCode(),
                        answer.getSelectedText(),
                        answer.getValue(),
                        answer.getConfidence(),
                        answer.getRawText(),
                        answer.getAttachmentSummary()
                ))
                .toList();
        return new QuestionnaireChatResponse(
                session.getId(),
                session.getConversationId(),
                definition.questionnaireCode(),
                MODE,
                session.getStatus(),
                session.getCurrentQuestionId(),
                reply,
                savedQuestionId,
                savedAttachmentCount,
                outboxEventId,
                answers,
                LocalDateTime.now()
        );
    }

    private boolean shouldStartNew(QuestionnaireChatRequest request) {
        return "START_QUESTIONNAIRE".equalsIgnoreCase(text(request.action()));
    }

    private boolean promptOnly(QuestionnaireChatRequest request) {
        return !StringUtils.hasText(request.message()) && request.effectiveAttachments().isEmpty();
    }

    private Optional<QuestionnaireSessionEntity> activeSession(String conversationId) {
        return sessionRepository.findFirstByConversationIdAndStatusOrderByUpdatedAtDesc(conversationId, "IN_PROGRESS");
    }

    private Optional<QuestionnaireSessionEntity> unfinishedSession(String patientId, String questionnaireCode) {
        if (!StringUtils.hasText(patientId)) {
            return Optional.empty();
        }
        return sessionRepository.findFirstByPatientIdAndQuestionnaireCodeAndStatusInOrderByUpdatedAtDesc(
                patientId,
                questionnaireCode,
                List.of("IN_PROGRESS", "EXITED")
        );
    }

    private String currentQuestionReply(QuestionnaireSessionEntity session, QuestionnaireDefinition definition, boolean resumed) {
        return replyService.questionReply(
                definition,
                questionById(definition, session.getCurrentQuestionId()),
                answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()),
                resumed
        );
    }

    private QuestionnaireTemplateEntity template(String questionnaireCode) {
        return templateRepository.findFirstByQuestionnaireCodeAndEnabledTrueOrderByVersionDesc(questionnaireCode)
                .orElseThrow(() -> new IllegalArgumentException("questionnaire template not found: " + questionnaireCode));
    }

    private QuestionnaireDefinition loadDefinition(String questionnaireCode) {
        return parseDefinition(template(questionnaireCode).getSchemaJson());
    }

    private QuestionnaireDefinition parseDefinition(String schemaJson) {
        try {
            QuestionnaireDefinition definition = objectMapper.readValue(schemaJson, QuestionnaireDefinition.class);
            if (definition.questions() == null || definition.questions().isEmpty()) {
                throw new IllegalArgumentException("questionnaire template must contain questions");
            }
            return definition;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid questionnaire template json", exception);
        }
    }

    private QuestionDefinition firstQuestion(QuestionnaireDefinition definition) {
        return definition.questions().get(0);
    }

    private QuestionDefinition questionById(QuestionnaireDefinition definition, String questionId) {
        return definition.questions().stream()
                .filter(question -> question.id().equals(questionId))
                .findFirst()
                .orElseGet(() -> firstQuestion(definition));
    }

    private Optional<QuestionDefinition> nextQuestion(QuestionnaireDefinition definition, String currentQuestionId) {
        List<QuestionDefinition> questions = definition.questions();
        for (int index = 0; index < questions.size(); index++) {
            if (questions.get(index).id().equals(currentQuestionId) && index + 1 < questions.size()) {
                return Optional.of(questions.get(index + 1));
            }
        }
        return Optional.empty();
    }

    private String completionReply(QuestionnaireDefinition definition) {
        if (StringUtils.hasText(definition.completionMessage())) {
            return definition.completionMessage();
        }
        return "问卷已经完成，我已保存结构化答案，并生成待推送事件。";
    }

    private String requiredQuestionnaireCode(String questionnaireCode) {
        return StringUtils.hasText(questionnaireCode) ? questionnaireCode.trim() : DEFAULT_QUESTIONNAIRE_CODE;
    }

    private String normalizeConversationId(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId.trim() : "default";
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record SessionStartResult(QuestionnaireSessionEntity session, boolean resumed) {
    }
}

package com.ai.questionnaire;

import com.kellen.file.MediaInput;
import com.kellen.integration.IntegrationOutboxEntity;
import com.kellen.integration.IntegrationOutboxRepository;

import com.kellen.questionnaire.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话式问卷服务测试。
 *
 * <p>覆盖小程序统一对话框下的问卷状态机：显式开始问卷、逐题保存自然语言答案、
 * 当前题附件归属、问卷完成后写入 outbox，避免问卷源数据混入普通聊天记忆。</p>
 */
@SpringBootTest(properties = { // 启动完整 Spring 上下文，使用 H2 和禁用问卷 AI 解析保证测试稳定。
        "spring.profiles.active=dev",
        "spring.config.name=ai-isolated-test", // 独立测试配置名，不加载含远程 import 的部署入口。
        "spring.cloud.nacos.config.import-check.enabled=false",
        "ai.bootstrap.seed-demo-content=true", // 仅测试显式加载仓库中的历史演示种子。
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "seata.enabled=false",
        "spring.datasource.dynamic.seata=false",
        "ai.ddl.enabled=false",
        "ai.questionnaire.ai-interpreter-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:questionnaire-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mysql.url=jdbc:h2:mem:questionnaire-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mysql.username=sa",
        "mysql.password=",
        "mysql.driverClassName=org.h2.Driver",
        "mysql-gray.url=jdbc:h2:mem:questionnaire-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mysql-gray.username=sa",
        "mysql-gray.password=",
        "mysql-gray.driverClassName=org.h2.Driver",
        "logging.file.path=build/logs",
        "logging.file.name=build/logs/questionnaire-test.log"
})
class ConversationalQuestionnaireServiceTests {

    @Autowired
    private ConversationalQuestionnaireService service;

    @Autowired
    private QuestionnaireTemplateService templateService;

    @Autowired
    private QuestionnaireAnswerRepository answerRepository;

    @Autowired
    private IntegrationOutboxRepository outboxRepository;

    @Autowired
    private QuestionnaireSessionRepository sessionRepository;

    /**
     * 验证症状初筛按对话方式逐题推进，完成后写入待推送 outbox。
     */
    @Test
    void symptomTriageFlowCompletesAndWritesOutbox() {
        seedTemplates();
        String conversationId = "miniapp-q-001";

        QuestionnaireChatResponse started = service.chat(new QuestionnaireChatRequest(
                conversationId, "p1001", "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));
        assertEquals("IN_PROGRESS", started.status());
        assertEquals("EMERGENCY_SIGNS", started.currentQuestionId());

        service.chat(new QuestionnaireChatRequest(conversationId, "p1001", "u1001", "symptom_triage_v1", null, "没有这些急症", List.of()));
        service.chat(new QuestionnaireChatRequest(conversationId, "p1001", "u1001", "symptom_triage_v1", null, "主要是头晕", List.of()));
        service.chat(new QuestionnaireChatRequest(conversationId, "p1001", "u1001", "symptom_triage_v1", null, "三天前开始，一阵一阵的", List.of()));
        QuestionnaireChatResponse completed = service.chat(new QuestionnaireChatRequest(
                conversationId, "p1001", "u1001", "symptom_triage_v1", null, "没有报告", List.of()));

        assertEquals("COMPLETED", completed.status());
        assertEquals(4, completed.answers().size());
        assertNotNull(completed.outboxEventId());
        IntegrationOutboxEntity outbox = outboxRepository.findById(completed.outboxEventId()).orElseThrow();
        assertEquals("QUESTIONNAIRE_COMPLETED", outbox.getEventType());
        assertEquals("PENDING", outbox.getStatus());
        assertTrue(outbox.getPayloadJson().contains("主要是头晕"));
    }

    /**
     * 验证问卷当前题允许附件时，附件归属该题答案，而不是切到普通报告分析。
     */
    @Test
    void questionnaireAttachmentBelongsToCurrentQuestion() throws IOException {
        seedTemplates();
        HttpServer server = contentTypeServer("image/png");
        try {
            String conversationId = "miniapp-q-attach-001";
            service.chat(new QuestionnaireChatRequest(
                    conversationId, "p2001", "u2001", "report_supplement_v1", "START_QUESTIONNAIRE", "", List.of()));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/report.png";

            QuestionnaireChatResponse response = service.chat(new QuestionnaireChatRequest(
                    conversationId,
                    "p2001",
                    "u2001",
                    "report_supplement_v1",
                    null,
                    "这是血常规报告",
                    List.of(new MediaInput(url, "image/png"))));

            assertEquals("REPORT_ATTACHMENT", response.savedQuestionId());
            assertEquals(1, response.savedAttachmentCount());
            List<QuestionnaireAnswerEntity> answers = answerRepository.findBySessionIdOrderByCreatedAtAsc(response.sessionId());
            assertEquals(1, answers.size());
            assertTrue(answers.get(0).getAttachmentSummary().contains("image/png|url:"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证 RESUME_UNFINISHED 策略：同一患者同一问卷存在进行中会话时，重复开始会恢复原会话。
     */
    @Test
    void startQuestionnaireResumesUnfinishedSessionForPatient() {
        seedTemplates();
        String patientId = "p-resume-001";

        QuestionnaireChatResponse first = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-resume-001", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));
        QuestionnaireChatResponse resumed = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-resume-002", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));

        assertEquals(first.sessionId(), resumed.sessionId());
        assertEquals("miniapp-q-resume-002", resumed.conversationId());
        assertTrue(resumed.reply().startsWith("我们继续刚才的问卷。"));
        long inProgressCount = sessionRepository.findAll().stream()
                .filter(session -> patientId.equals(session.getPatientId()))
                .filter(session -> "symptom_triage_v1".equals(session.getQuestionnaireCode()))
                .filter(session -> "IN_PROGRESS".equals(session.getStatus()))
                .count();
        assertEquals(1, inProgressCount);
    }

    /**
     * 验证用户暂停退出后，同一患者同一问卷再次点击会继续原会话。
     */
    @Test
    void exitAllowsPatientToResumeSameSession() {
        seedTemplates();
        String patientId = "p-exit-reopen-001";

        QuestionnaireChatResponse first = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-001", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));
        service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-001", patientId, "u1001", "symptom_triage_v1", null, "没有这些急症", List.of()));
        QuestionnaireChatResponse exited = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-001", patientId, "u1001", "symptom_triage_v1", "EXIT_QUESTIONNAIRE", "", List.of()));
        QuestionnaireChatResponse reopened = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-002", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));

        assertEquals("EXITED", exited.status());
        assertEquals(first.sessionId(), reopened.sessionId());
        assertEquals("IN_PROGRESS", reopened.status());
        assertEquals("MAIN_SYMPTOM", reopened.currentQuestionId());
        assertEquals("miniapp-q-exit-002", reopened.conversationId());
        assertTrue(reopened.reply().startsWith("我们继续刚才的问卷。"));
    }

    /**
     * 验证兼容旧数据：已暂停会话如果 currentQuestionId 为空，可以根据已保存答案恢复下一题。
     */
    @Test
    void resumeExitedLegacySessionWithoutCurrentQuestionId() {
        seedTemplates();
        String patientId = "p-exit-legacy-001";

        QuestionnaireChatResponse first = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-legacy-001", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));
        service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-legacy-001", patientId, "u1001", "symptom_triage_v1", null, "没有这些急症", List.of()));
        service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-legacy-001", patientId, "u1001", "symptom_triage_v1", "EXIT_QUESTIONNAIRE", "", List.of()));
        QuestionnaireSessionEntity legacySession = sessionRepository.findById(first.sessionId()).orElseThrow();
        ReflectionTestUtils.setField(legacySession, "currentQuestionId", null);
        sessionRepository.saveAndFlush(legacySession);

        QuestionnaireChatResponse resumed = service.chat(new QuestionnaireChatRequest(
                "miniapp-q-exit-legacy-002", patientId, "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));

        assertEquals(first.sessionId(), resumed.sessionId());
        assertEquals("IN_PROGRESS", resumed.status());
        assertEquals("MAIN_SYMPTOM", resumed.currentQuestionId());
    }

    /**
     * 验证普通聊天入口的保护性路由：进行中问卷优先，回答不会落入普通聊天记忆链路。
     */
    @Test
    void continueActiveIfPresentRoutesAnswerToQuestionnaire() {
        seedTemplates();
        String conversationId = "miniapp-q-guard-001";
        service.chat(new QuestionnaireChatRequest(
                conversationId, "p-guard-001", "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));

        Optional<QuestionnaireChatResponse> guarded = service.continueActiveIfPresent(
                conversationId, "u1001", "没有这些急症", List.of());

        assertTrue(guarded.isPresent());
        assertEquals("EMERGENCY_SIGNS", guarded.get().savedQuestionId());
        assertEquals("MAIN_SYMPTOM", guarded.get().currentQuestionId());
    }

    /**
     * 验证异常残留多个进行中会话时，退出会清理同一 conversationId 下所有进行中记录，
     * 避免前端刷新显示已退出但普通聊天仍被旧问卷接管。
     */
    @Test
    void exitClearsAllActiveSessionsForConversation() {
        seedTemplates();
        String conversationId = "miniapp-q-duplicate-active-001";

        QuestionnaireChatResponse first = service.chat(new QuestionnaireChatRequest(
                conversationId, "p-duplicate-001", "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));
        QuestionnaireChatResponse second = service.chat(new QuestionnaireChatRequest(
                conversationId, "p-duplicate-002", "u1001", "symptom_triage_v1", "START_QUESTIONNAIRE", "", List.of()));

        assertNotEquals(first.sessionId(), second.sessionId());
        QuestionnaireChatResponse exited = service.chat(new QuestionnaireChatRequest(
                conversationId, "p-duplicate-002", "u1001", "symptom_triage_v1", "EXIT_QUESTIONNAIRE", "", List.of()));

        assertEquals("EXITED", exited.status());
        long activeCount = sessionRepository.findAll().stream()
                .filter(session -> conversationId.equals(session.getConversationId()))
                .filter(session -> "IN_PROGRESS".equals(session.getStatus()))
                .count();
        assertEquals(0, activeCount);
        assertTrue(service.continueActiveIfPresent(conversationId, "u1001", "你是什么模型", List.of()).isEmpty());
    }

    /**
     * 验证普通对话刷新问卷状态时，没有问卷会话应返回空，而不是抛出 400。
     */
    @Test
    void snapshotReturnsEmptyForConversationWithoutQuestionnaire() {
        seedTemplates();

        Optional<QuestionnaireChatResponse> snapshot = service.snapshot("normal-chat-without-questionnaire");

        assertTrue(snapshot.isEmpty());
    }

    private void seedTemplates() {
        templateService.save(new QuestionnaireTemplateSaveRequest(SYMPTOM_TRIAGE_TEMPLATE, true));
        templateService.save(new QuestionnaireTemplateSaveRequest(REPORT_SUPPLEMENT_TEMPLATE, true));
    }

    private static HttpServer contentTypeServer(String contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final String SYMPTOM_TRIAGE_TEMPLATE = """
            {
              "questionnaireCode": "symptom_triage_v1",
              "version": "1.0.0",
              "name": "症状初筛问卷",
              "scene": "symptom_triage",
              "targetSystem": "test-system",
              "completionMessage": "症状初筛已完成。我已保存结构化答案，并生成待推送事件，后续可以继续分析病情或整理就医问题。",
              "questions": [
                {"id": "EMERGENCY_SIGNS", "label": "急危信号筛查", "prompt": "我先确认有没有急症风险。你现在有没有胸痛、呼吸困难、意识模糊、单侧肢体无力、剧烈头痛、明显出血或严重过敏？", "required": true, "allowsAttachments": false},
                {"id": "MAIN_SYMPTOM", "label": "主要不适", "prompt": "目前最主要的不舒服是什么？可以直接描述部位、感觉和严重程度。", "required": true, "allowsAttachments": false},
                {"id": "ONSET_TIME", "label": "起病时间", "prompt": "这种不舒服大概从什么时候开始？是持续的，还是一阵一阵的？", "required": true, "allowsAttachments": false},
                {"id": "RELATED_REPORT", "label": "相关报告", "prompt": "如果你有相关检查报告，可以上传报告图片或 PDF；如果没有，也可以直接说没有报告。", "required": false, "allowsAttachments": true}
              ]
            }
            """;

    private static final String REPORT_SUPPLEMENT_TEMPLATE = """
            {
              "questionnaireCode": "report_supplement_v1",
              "version": "1.0.0",
              "name": "报告补充问卷",
              "scene": "report_supplement",
              "targetSystem": "test-system",
              "completionMessage": "报告补充问卷已完成。我已保存报告附件和补充说明，并生成待推送事件。",
              "questions": [
                {"id": "REPORT_ATTACHMENT", "label": "报告附件", "prompt": "请上传需要分析的报告图片或 PDF，并简单说明这是什么报告。", "required": true, "allowsAttachments": true},
                {"id": "REPORT_DATE", "label": "检查时间", "prompt": "这份报告大概是什么时间检查的？如果报告上有日期，也可以直接说报告日期。", "required": false, "allowsAttachments": false},
                {"id": "USER_CONCERN", "label": "关注问题", "prompt": "你最想了解这份报告里的哪类问题？例如异常项、复查建议、需要问医生的问题。", "required": true, "allowsAttachments": false}
              ]
            }
            """;
}

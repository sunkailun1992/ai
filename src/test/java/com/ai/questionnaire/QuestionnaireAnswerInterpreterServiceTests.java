package com.ai.questionnaire;

import com.kellen.agent.AiAgentClient;
import com.kellen.config.AiProperties;

import com.kellen.questionnaire.QuestionDefinition;
import com.kellen.questionnaire.QuestionnaireAnswerInterpreterService;
import com.kellen.questionnaire.QuestionnaireDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 问卷答案 AI 解释服务测试。
 *
 * <p>主服务只负责调用 {@code questionnaire-agent} 并把结果交给状态机校验保存，不再
 * 在本地执行模型解释。</p>
 */
class QuestionnaireAnswerInterpreterServiceTests {

    /**
     * 验证 Agent 要求追问时，主服务停留当前题而不是保存并推进。
     */
    @Test
    void agentFollowUpKeepsCurrentQuestion() {
        AiAgentClient agentClient = mock(AiAgentClient.class);
        when(agentClient.questionnaireInterpret(symptomDefinition(), emergencyQuestion(), List.of(), "暂时没有检查报告。", ""))
                .thenReturn(new AiAgentClient.QuestionnaireInterpretResult(
                        "google",
                        "gemini",
                        "暂时没有检查报告。",
                        "",
                        "",
                        "",
                        0.4,
                        true,
                        "我还需要确认有没有胸痛或呼吸困难？"));
        QuestionnaireAnswerInterpreterService service = new QuestionnaireAnswerInterpreterService(testProperties(true), agentClient);

        QuestionnaireAnswerInterpreterService.InterpretedAnswer answer = service.interpret(
                symptomDefinition(),
                emergencyQuestion(),
                List.of(),
                "暂时没有检查报告。",
                ""
        );

        assertTrue(answer.needFollowUp());
        assertEquals("", answer.selectedText());
        assertTrue(answer.followUpQuestion().contains("胸痛"));
    }

    private static QuestionnaireDefinition symptomDefinition() {
        return new QuestionnaireDefinition(
                "symptom_triage_v1",
                "1.0.0",
                "症状初筛问卷",
                "symptom_triage",
                "test-system",
                "完成",
                List.of(emergencyQuestion())
        );
    }

    private static QuestionDefinition emergencyQuestion() {
        return new QuestionDefinition(
                "EMERGENCY_SIGNS",
                "急危信号筛查",
                "我先确认有没有急症风险。你现在有没有胸痛、呼吸困难、意识模糊、单侧肢体无力、剧烈头痛、明显出血或严重过敏？",
                true,
                false
        );
    }

    private static AiProperties testProperties(boolean aiInterpreterEnabled) {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "兜底患者分析提示词", 4, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain"), new AiProperties.MemoryProperties(20, 7)),
                new AiProperties.QuestionnaireProperties(aiInterpreterEnabled),
                new AiProperties.WechatProperties("wx-test", "", 30),
                new AiProperties.OssProperties("oss-cn-hangzhou.aliyuncs.com", "", "", "", "ai-miniapp", 60, 1800, 52428800));
    }
}

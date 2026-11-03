package com.kellen.questionnaire;

import com.kellen.agent.AiAgentClient;
import com.kellen.config.AiProperties;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话式问卷答案 AI 解释服务。
 *
 * <p>本服务负责把患者自然语言输入交给 {@code questionnaire-agent} 解释成当前题的
 * 结构化候选答案和追问建议。模型只参与理解，不直接写数据库、不决定外部系统推送；
 * 最终保存仍由 {@link ConversationalQuestionnaireService} 在校验题目状态后完成。</p>
 */
@Service
public class QuestionnaireAnswerInterpreterService {

    private final AiProperties properties;
    private final AiAgentClient aiAgentClient;

    /**
     * 构造器注入问卷答案解释所需依赖。
     *
     * @param properties    AI 配置对象。
     * @param aiAgentClient AI Agent 执行服务客户端。
     */
    public QuestionnaireAnswerInterpreterService(AiProperties properties, AiAgentClient aiAgentClient) {
        this.properties = properties;
        this.aiAgentClient = aiAgentClient;
    }

    /**
     * 解释当前题回答，生成后端可校验和保存的候选答案。
     *
     * @param definition        当前问卷模板定义。
     * @param currentQuestion   当前题定义。
     * @param previousAnswers   当前问卷已保存答案。
     * @param rawMessage        用户本轮原始输入。
     * @param attachmentSummary 当前题附件摘要。
     * @return Agent 解释后的候选答案。
     */
    public InterpretedAnswer interpret(QuestionnaireDefinition definition,
                                       QuestionDefinition currentQuestion,
                                       List<QuestionnaireAnswerEntity> previousAnswers,
                                       String rawMessage,
                                       String attachmentSummary) {
        if (!properties.questionnaire().aiInterpreterEnabled()) {
            return InterpretedAnswer.fallback(rawMessage, attachmentSummary);
        }
        return fromAgent(aiAgentClient.questionnaireInterpret(
                definition, currentQuestion, previousAnswers, rawMessage, attachmentSummary),
                rawMessage, attachmentSummary, currentQuestion);
    }

    private InterpretedAnswer fromAgent(AiAgentClient.QuestionnaireInterpretResult result,
                                        String rawMessage,
                                        String attachmentSummary,
                                        QuestionDefinition currentQuestion) {
        if (result == null) {
            throw new IllegalStateException("questionnaire-agent returned empty interpretation");
        }
        if (result.needFollowUp()) {
            String followUpQuestion = text(result.followUpQuestion());
            return InterpretedAnswer.followUp(rawMessage,
                    StringUtils.hasText(followUpQuestion) ? followUpQuestion : retryPrompt(currentQuestion));
        }
        String selectedText = firstText(result.selectedText(), result.value());
        String value = firstText(result.value(), result.selectedText());
        boolean hasAnswer = StringUtils.hasText(selectedText) || StringUtils.hasText(value) || StringUtils.hasText(attachmentSummary);
        return new InterpretedAnswer(
                firstText(result.rawText(), rawMessage),
                text(result.selectedCode()),
                StringUtils.hasText(selectedText) ? selectedText : text(rawMessage),
                StringUtils.hasText(value) ? value : text(rawMessage),
                clamp(result.confidence(), hasAnswer ? 0.85 : 0.4),
                false,
                ""
        );
    }

    private String retryPrompt(QuestionDefinition currentQuestion) {
        return "我还没有准确确认这个问题。" + currentQuestion.prompt();
    }

    private String firstText(String first, String second) {
        String firstValue = text(first);
        return StringUtils.hasText(firstValue) ? firstValue : text(second);
    }

    private double clamp(Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * AI 解释后的当前题候选答案。
     *
     * @param rawText          用户原始输入。
     * @param selectedCode     标准选项编码，模板无选项时为空。
     * @param selectedText     标准化答案文本。
     * @param value            保留细节的答案值。
     * @param confidence       AI 解释置信度。
     * @param needFollowUp     当前题是否需要继续追问。
     * @param followUpQuestion 需要追问时返回给患者的问题。
     */
    public record InterpretedAnswer(
            String rawText,
            String selectedCode,
            String selectedText,
            String value,
            double confidence,
            boolean needFollowUp,
            String followUpQuestion
    ) {

        /**
         * 生成不依赖模型的测试候选答案。
         *
         * @param rawMessage        用户本轮原始输入。
         * @param attachmentSummary 当前题附件摘要。
         * @return 兜底候选答案。
         */
        public static InterpretedAnswer fallback(String rawMessage, String attachmentSummary) {
            String normalizedText = rawMessage == null ? "" : rawMessage.trim();
            double confidence = normalizedText.isBlank() && (attachmentSummary == null || attachmentSummary.isBlank()) ? 0.5 : 1.0;
            return new InterpretedAnswer(normalizedText, "", normalizedText, normalizedText, confidence, false, "");
        }

        /**
         * 生成继续追问的候选结果，不保存当前答案。
         *
         * @param rawMessage       用户本轮原始输入。
         * @param followUpQuestion 继续追问文案。
         * @return 追问候选结果。
         */
        public static InterpretedAnswer followUp(String rawMessage, String followUpQuestion) {
            String normalizedText = rawMessage == null ? "" : rawMessage.trim();
            return new InterpretedAnswer(normalizedText, "", "", "", 0.0, true, followUpQuestion);
        }
    }
}

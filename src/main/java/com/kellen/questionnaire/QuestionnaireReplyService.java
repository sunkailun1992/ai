package com.kellen.questionnaire;

import com.kellen.agent.AiAgentClient;
import com.kellen.config.AiProperties;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话式问卷自然回复生成服务。
 *
 * <p>本服务只负责把问卷状态机上下文交给 {@code questionnaire-agent}，并返回当前题
 * 的自然追问文案。题目 ID、问卷推进、答案保存仍由
 * {@link ConversationalQuestionnaireService} 控制，避免模型自由改题或改变落库字段。</p>
 */
@Service
public class QuestionnaireReplyService {

    private final AiProperties properties;
    private final AiAgentClient aiAgentClient;

    /**
     * 构造器注入自然回复生成依赖。
     *
     * @param properties    AI 业务配置。
     * @param aiAgentClient AI Agent 执行服务客户端。
     */
    public QuestionnaireReplyService(AiProperties properties, AiAgentClient aiAgentClient) {
        this.properties = properties;
        this.aiAgentClient = aiAgentClient;
    }

    /**
     * 生成当前题自然提问文案。
     *
     * @param definition      当前问卷模板定义。
     * @param currentQuestion 当前题定义。
     * @param previousAnswers 已采集答案。
     * @param resumed         是否是恢复未完成问卷。
     * @return 给用户展示的自然提问文案。
     */
    public String questionReply(QuestionnaireDefinition definition,
                                QuestionDefinition currentQuestion,
                                List<QuestionnaireAnswerEntity> previousAnswers,
                                boolean resumed) {
        if (!properties.questionnaire().aiInterpreterEnabled()) {
            return fallback(currentQuestion, resumed);
        }
        AiAgentClient.QuestionnaireReplyResult result = aiAgentClient.questionnaireReply(
                definition, currentQuestion, previousAnswers, resumed);
        String reply = text(result == null ? null : result.reply());
        if (!StringUtils.hasText(reply)) {
            throw new IllegalStateException("questionnaire-agent returned empty reply");
        }
        return reply;
    }

    private String fallback(QuestionDefinition currentQuestion, boolean resumed) {
        if (resumed) {
            return "我们继续刚才的问卷。" + currentQuestion.prompt();
        }
        return currentQuestion.prompt();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}

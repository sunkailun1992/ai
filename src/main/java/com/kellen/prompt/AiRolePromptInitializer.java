package com.kellen.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 角色 Prompt 启动种子。
 *
 * <p>只在数据库缺少默认患者分析角色时初始化一条记录。已有记录不覆盖，
 * 确保数据库里的运营配置优先于代码内置种子。</p>
 */
@ConditionalOnProperty(prefix = "ai.bootstrap", name = "seed-demo-content", havingValue = "true")
@Component
public class AiRolePromptInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiRolePromptInitializer.class);

    private final AiRolePromptRepository aiRolePromptRepository; // 角色 Prompt 数据访问入口。

    /**
     * 构造器注入 Repository。
     *
     * @param aiRolePromptRepository 角色 Prompt Repository。
     */
    public AiRolePromptInitializer(AiRolePromptRepository aiRolePromptRepository) {
        this.aiRolePromptRepository = aiRolePromptRepository;
    }

    /**
     * 应用启动后写入默认患者分析角色种子。
     *
     * @param args 启动参数，本逻辑不使用。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            if (aiRolePromptRepository.existsByRoleKey(DefaultPatientAnalysisPrompt.ROLE_KEY)) {
                return;
            }
            aiRolePromptRepository.save(AiRolePromptEntity.create(
                    DefaultPatientAnalysisPrompt.ROLE_KEY,
                    DefaultPatientAnalysisPrompt.ROLE_NAME,
                    DefaultPatientAnalysisPrompt.SYSTEM_PROMPT,
                    true,
                    DefaultPatientAnalysisPrompt.VERSION,
                    DefaultPatientAnalysisPrompt.DESCRIPTION));
        } catch (BadSqlGrammarException exception) {
            log.warn("AI 角色 Prompt 表不可用，已跳过启动种子导入；清空数据库后请先执行 db/ai-init.sql", exception);
        }
    }
}

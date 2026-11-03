package com.kellen.questionnaire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 问卷模板启动种子。
 *
 * <p>启动时扫描 classpath:/questionnaires 下的默认模板。
 * 只有数据库缺少同 questionnaireCode + version 时才插入，已有模板不覆盖，
 * 保证运行时仍以 ai_questionnaire_template 表中的运营配置为准。</p>
 */
@ConditionalOnProperty(prefix = "ai.bootstrap", name = "seed-demo-content", havingValue = "true")
@Component // 注册为启动初始化组件。
public class QuestionnaireTemplateInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireTemplateInitializer.class); // 初始化日志。

    private static final String DEFAULT_TEMPLATE_PATTERN = "classpath*:/questionnaires/*.json"; // 默认模板资源路径。

    private final QuestionnaireTemplateService templateService; // 问卷模板服务。
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver(); // classpath 资源扫描器。

    /**
     * 构造器注入模板服务。
     *
     * @param templateService 问卷模板服务。
     */
    public QuestionnaireTemplateInitializer(QuestionnaireTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 应用启动后导入缺失的默认问卷模板。
     *
     * @param args 启动参数，本逻辑不使用。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Resource[] resources = resourceResolver.getResources(DEFAULT_TEMPLATE_PATTERN);
            for (Resource resource : resources) {
                seedResource(resource);
            }
        } catch (BadSqlGrammarException exception) {
            log.warn("问卷模板表不可用，已跳过启动种子导入；清空数据库后请先执行 db/ai-init.sql", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load default questionnaire templates", exception);
        }
    }

    private void seedResource(Resource resource) throws IOException {
        String schemaJson = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        QuestionnaireTemplateSummaryResponse seeded = templateService.seedIfAbsent(schemaJson);
        if (seeded != null) {
            log.info("默认问卷模板已初始化: questionnaireCode={}, version={}", seeded.questionnaireCode(), seeded.version());
        }
    }
}

package com.kellen.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

/**
 * 对话式问卷模板配置服务。
 *
 * <p>问卷模板运行时以 ai_questionnaire_template 数据库表为准。
 * JSON 文件只能作为导入样例或测试载荷，不再作为应用启动后的权威来源。</p>
 */
@Service // 注册为 Spring Service，供 Controller 和测试构造器注入。
public class QuestionnaireTemplateService {

    private final QuestionnaireTemplateRepository repository; // 问卷模板数据库访问接口。
    private final ObjectMapper objectMapper = new ObjectMapper(); // 模板 JSON 校验和字段提取器。

    public QuestionnaireTemplateService(QuestionnaireTemplateRepository repository) {
        this.repository = repository; // 保存模板数据库访问接口。
    }

    /**
     * 保存或更新问卷模板。
     *
     * @param request 模板保存请求。
     * @return 保存后的模板信息。
     */
    @Transactional // 模板新增或更新必须在同一事务内完成。
    public QuestionnaireTemplateResponse save(QuestionnaireTemplateSaveRequest request) {
        TemplateMetadata metadata = parseAndValidate(request.schemaJson());
        boolean enabled = request.enabled() == null || request.enabled();
        QuestionnaireTemplateEntity entity = repository.findByQuestionnaireCodeAndVersion(metadata.questionnaireCode(), metadata.version())
                .map(existing -> {
                    existing.update(metadata.name(), metadata.scene(), request.schemaJson(), enabled);
                    return existing;
                })
                .orElseGet(() -> QuestionnaireTemplateEntity.create(
                        metadata.questionnaireCode(),
                        metadata.version(),
                        metadata.name(),
                        metadata.scene(),
                        request.schemaJson()
                ));
        if (!enabled && entity.getId() == null) {
            entity.update(metadata.name(), metadata.scene(), request.schemaJson(), false);
        }
        return toResponse(repository.save(entity));
    }

    /**
     * 启动种子写入模板：仅当同编码同版本不存在时插入，存在时不覆盖数据库配置。
     *
     * @param schemaJson 默认模板 JSON。
     * @return 插入成功返回模板摘要，已存在返回空。
     */
    @Transactional // 启动初始化需要保证 exists 与 save 在同一事务语义下执行。
    public QuestionnaireTemplateSummaryResponse seedIfAbsent(String schemaJson) {
        TemplateMetadata metadata = parseAndValidate(schemaJson);
        if (repository.existsByQuestionnaireCodeAndVersion(metadata.questionnaireCode(), metadata.version())) {
            return null;
        }
        QuestionnaireTemplateEntity entity = QuestionnaireTemplateEntity.create(
                metadata.questionnaireCode(),
                metadata.version(),
                metadata.name(),
                metadata.scene(),
                schemaJson
        );
        return toSummaryResponse(repository.save(entity));
    }

    /**
     * 查询当前启用模板。
     *
     * @param questionnaireCode 问卷编码。
     * @return 当前启用模板。
     */
    @Transactional(readOnly = true) // 只读事务，查询当前启用模板不触发写入。
    public QuestionnaireTemplateResponse current(String questionnaireCode) {
        QuestionnaireTemplateEntity entity = repository.findFirstByQuestionnaireCodeAndEnabledTrueOrderByVersionDesc(questionnaireCode)
                .orElseThrow(() -> new IllegalArgumentException("questionnaire template not found: " + questionnaireCode));
        return toResponse(entity);
    }

    /**
     * 查询启用中的问卷模板摘要列表。
     *
     * @return 小程序抽屉使用的问卷模板摘要。
     */
    @Transactional(readOnly = true) // 只读事务，列表接口不返回完整 schemaJson。
    public List<QuestionnaireTemplateSummaryResponse> enabledSummaries() {
        return repository.findByEnabledTrueOrderByUpdatedAtDesc().stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    private TemplateMetadata parseAndValidate(String schemaJson) {
        if (!StringUtils.hasText(schemaJson)) {
            throw new IllegalArgumentException("schemaJson must not be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            if (!root.has("questions") || !root.get("questions").isArray() || root.get("questions").isEmpty()) {
                throw new IllegalArgumentException("questionnaire template must contain non-empty questions array");
            }
            return new TemplateMetadata(
                    required(root, "questionnaireCode"),
                    required(root, "version"),
                    required(root, "name"),
                    text(root, "scene")
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid questionnaire template json", exception);
        }
    }

    private QuestionnaireTemplateResponse toResponse(QuestionnaireTemplateEntity entity) {
        return new QuestionnaireTemplateResponse(
                entity.getId(),
                entity.getQuestionnaireCode(),
                entity.getVersion(),
                entity.getName(),
                entity.getScene(),
                entity.getSchemaJson()
        );
    }

    private QuestionnaireTemplateSummaryResponse toSummaryResponse(QuestionnaireTemplateEntity entity) {
        return new QuestionnaireTemplateSummaryResponse(
                entity.getId(),
                entity.getQuestionnaireCode(),
                entity.getVersion(),
                entity.getName(),
                entity.getScene()
        );
    }

    private String required(JsonNode root, String fieldName) {
        String value = text(root, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException("questionnaire template field must not be blank: " + fieldName);
        }
        return value;
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() ? "" : node.asText().trim();
    }

    private record TemplateMetadata(String questionnaireCode, String version, String name, String scene) {
    }
}

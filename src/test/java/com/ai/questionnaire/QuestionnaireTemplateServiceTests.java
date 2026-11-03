package com.ai.questionnaire;

import com.kellen.questionnaire.QuestionnaireTemplateEntity;
import com.kellen.questionnaire.QuestionnaireTemplateRepository;
import com.kellen.questionnaire.QuestionnaireTemplateService;
import com.kellen.questionnaire.QuestionnaireTemplateSummaryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问卷模板服务测试。
 */
class QuestionnaireTemplateServiceTests {

    @Test
    void seedIfAbsentSkipsExistingTemplate() {
        QuestionnaireTemplateRepository repository = mock(QuestionnaireTemplateRepository.class);
        when(repository.existsByQuestionnaireCodeAndVersion("symptom_triage_v1", "1.0.0")).thenReturn(true);
        QuestionnaireTemplateService service = new QuestionnaireTemplateService(repository);

        QuestionnaireTemplateSummaryResponse result = service.seedIfAbsent(schemaJson());

        assertThat(result).isNull();
        verify(repository, never()).save(any(QuestionnaireTemplateEntity.class));
    }

    @Test
    void seedIfAbsentCreatesMissingTemplate() {
        QuestionnaireTemplateRepository repository = mock(QuestionnaireTemplateRepository.class);
        when(repository.existsByQuestionnaireCodeAndVersion("symptom_triage_v1", "1.0.0")).thenReturn(false);
        when(repository.save(any(QuestionnaireTemplateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        QuestionnaireTemplateService service = new QuestionnaireTemplateService(repository);

        QuestionnaireTemplateSummaryResponse result = service.seedIfAbsent(schemaJson());

        assertThat(result).isNotNull();
        assertThat(result.questionnaireCode()).isEqualTo("symptom_triage_v1");
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.name()).isEqualTo("症状初筛问卷");
    }

    private String schemaJson() {
        return """
                {
                  "questionnaireCode": "symptom_triage_v1",
                  "version": "1.0.0",
                  "name": "症状初筛问卷",
                  "scene": "symptom_triage",
                  "questions": [
                    {
                      "id": "Q01",
                      "label": "急危信号",
                      "prompt": "有没有胸痛或呼吸困难？",
                      "required": true,
                      "allowsAttachments": false
                    }
                  ]
                }
                """;
    }
}

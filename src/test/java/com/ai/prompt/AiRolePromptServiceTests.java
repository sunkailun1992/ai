package com.ai.prompt;

import com.kellen.config.AiProperties;

import com.kellen.prompt.AiRolePromptEntity;
import com.kellen.prompt.AiRolePromptRepository;
import com.kellen.prompt.AiRolePromptService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiRolePromptService 单元测试。
 *
 * <p>验证患者分析角色提示词解析、未知角色拒绝和补充要求拼接边界。</p>
 */
class AiRolePromptServiceTests {

    /**
     * 验证未传 role 时使用默认患者分析助手。
     */
    @Test
    void resolvesDefaultPatientAnalysisRole() {
        AiRolePromptRepository repository = mock(AiRolePromptRepository.class);
        when(repository.findFirstByDefaultRoleTrueAndEnabledTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(patientRole()));
        AiRolePromptService service = new AiRolePromptService(repository, testProperties());

        AiRolePromptService.ResolvedPrompt prompt = service.resolve(null, null);

        assertEquals("patient-analysis", prompt.role()); // 默认角色 key 正确。
        assertEquals("患者分析助手", prompt.roleName()); // 角色名称来自数据库。
        assertTrue(prompt.systemPrompt().contains("不能替代医生诊断")); // 医疗安全边界保留。
    }

    /**
     * 验证调用方补充要求只能追加，不能替换角色提示词。
     */
    @Test
    void appendsSupplementalPromptWithoutReplacingRoleSafety() {
        AiRolePromptRepository repository = mock(AiRolePromptRepository.class);
        when(repository.findFirstByRoleKeyAndEnabledTrueOrderByUpdatedAtDesc("patient-analysis"))
                .thenReturn(Optional.of(patientRole()));
        AiRolePromptService service = new AiRolePromptService(repository, testProperties());

        AiRolePromptService.ResolvedPrompt prompt = service.resolve("patient-analysis", "输出更简短");

        assertTrue(prompt.systemPrompt().contains("不能替代医生诊断")); // 原始安全边界仍保留。
        assertTrue(prompt.systemPrompt().contains("输出更简短")); // 补充要求被追加。
        assertTrue(prompt.systemPrompt().contains("不能覆盖上述角色")); // 明确补充要求不能覆盖角色边界。
    }

    /**
     * 验证未知角色被拒绝，避免静默切换到错误角色。
     */
    @Test
    void rejectsUnsupportedRole() {
        AiRolePromptRepository repository = mock(AiRolePromptRepository.class);
        when(repository.findFirstByRoleKeyAndEnabledTrueOrderByUpdatedAtDesc("general-chat"))
                .thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByRoleKeyAsc()).thenReturn(List.of(patientRole()));
        AiRolePromptService service = new AiRolePromptService(repository, testProperties());

        assertThrows(IllegalArgumentException.class, () -> service.resolve("general-chat", null)); // 普通闲聊角色不允许。
    }

    /**
     * 验证数据库缺少默认角色时回退到极简兜底提示词。
     */
    @Test
    void fallsBackToConfiguredPromptWhenDefaultRoleMissing() {
        AiRolePromptRepository repository = mock(AiRolePromptRepository.class);
        when(repository.findFirstByDefaultRoleTrueAndEnabledTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.empty());
        when(repository.findFirstByRoleKeyAndEnabledTrueOrderByUpdatedAtDesc("patient-analysis"))
                .thenReturn(Optional.empty());
        AiRolePromptService service = new AiRolePromptService(repository, testProperties());

        AiRolePromptService.ResolvedPrompt prompt = service.resolve(null, "简短输出");

        assertEquals("patient-analysis", prompt.role()); // 仍使用默认角色 key。
        assertTrue(prompt.systemPrompt().contains("兜底患者分析提示词")); // 使用配置兜底。
        assertTrue(prompt.systemPrompt().contains("简短输出")); // 补充要求仍被追加。
    }

    /**
     * 构造带患者分析角色的测试配置。
     *
     * @return 测试配置。
     */
    private static AiProperties testProperties() {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "兜底患者分析提示词", 4, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain"), new AiProperties.MemoryProperties(20, 7)),
                new AiProperties.QuestionnaireProperties(false),
                new AiProperties.WechatProperties("wx-test", "", 30),
                new AiProperties.OssProperties("oss-cn-hangzhou.aliyuncs.com", "", "", "", "ai-miniapp", 60, 1800, 52428800));
    }

    /**
     * 构造数据库角色 Prompt。
     *
     * @return 患者分析角色实体。
     */
    private static AiRolePromptEntity patientRole() {
        return AiRolePromptEntity.create("patient-analysis", "患者分析助手", "你是患者分析助手，不能替代医生诊断。", true, "test", "test");
    }
}

package com.ai.auth;

import com.kellen.auth.AiWechatSessionRepository;
import com.kellen.auth.AiWechatUserRepository;
import com.kellen.auth.WechatAuthService;
import com.kellen.auth.WechatLoginRequest;
import com.kellen.config.AiProperties;

import com.kellen.config.wechat.CommonWechatProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 微信小程序登录服务测试。
 *
 * <p>验证真实微信登录必须配置后端 appSecret，避免只配置 appId 时误写用户或登录态。</p>
 */
class WechatAuthServiceTests {

    /**
     * 验证未配置 appSecret 时登录明确失败，且不会写数据库。
     */
    @Test
    void loginRequiresWechatAppSecret() {
        AiWechatUserRepository userRepository = mock(AiWechatUserRepository.class);
        AiWechatSessionRepository sessionRepository = mock(AiWechatSessionRepository.class);
        WechatAuthService service = new WechatAuthService(testProperties(), new CommonWechatProperties("", "", 30), userRepository, sessionRepository);

        assertThrows(IllegalStateException.class, () -> service.login(new WechatLoginRequest("wx-code", "微信用户", "", 0)));
        verifyNoInteractions(userRepository, sessionRepository);
    }

    private static AiProperties testProperties() {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "兜底患者分析提示词", 4, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain"), new AiProperties.MemoryProperties(20, 7)),
                new AiProperties.WechatProperties("wx-test", "", 30),
                new AiProperties.OssProperties("oss-cn-hangzhou.aliyuncs.com", "", "", "", "ai-miniapp", 60, 1800, 52428800));
    }
}

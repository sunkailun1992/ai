package com.kellen.auth;

import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 本机灰度通道内校验访问令牌也必须走灰度 user；未启用灰度时只找稳定提供方，本机只有灰度 user 会导致 401。 */
class AiSessionRpcClientCanaryReferenceTest {

    @Test
    void sessionReferenceParticipatesInCanaryRouting() throws Exception {
        DubboReference reference = AiSessionRpcClient.class.getDeclaredField("sessions")
                .getAnnotation(DubboReference.class);
        assertThat(reference).isNotNull();
        assertThat(Arrays.asList(reference.parameters())).containsSequence("traffic.canary.enabled", "true");
    }
}

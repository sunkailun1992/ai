package com.ai.prompt;

import com.kellen.prompt.AiRolePromptInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/** 新个人环境默认不能通过启动组件重新写入历史业务演示内容。 */
class DemoContentBootstrapSafetyTest {
    @Test void demoSeedRunnersAreAbsentUnlessExplicitlyEnabled() {
        new ApplicationContextRunner().withUserConfiguration(AiRolePromptInitializer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AiRolePromptInitializer.class);
        });
    }
}

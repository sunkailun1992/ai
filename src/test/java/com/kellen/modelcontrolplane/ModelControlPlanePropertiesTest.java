package com.kellen.modelcontrolplane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证实际 Spring 环境变量绑定命名，不依赖测试机上的凭据。 */
class ModelControlPlanePropertiesTest {
    @Test void serverOnlyEnvironmentNamesBindWithoutNacosModelDefinitions() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("ai.model-control-plane.binding-fixture=true")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "AI_MODELCONTROLPLANE_APIBASEURL", "https://gateway.example.invalid/prefix",
                                "AI_MODELCONTROLPLANE_APIKEY", "synthetic-read-only-catalog-fixture",
                                "AI_MODELCONTROLPLANE_CONSOLEBASEURL", "https://console.example.invalid/ui"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ModelControlPlaneProperties.class);
                    assertThat(properties.getApiBaseUrl()).isEqualTo("https://gateway.example.invalid/prefix");
                    assertThat(properties.getApiKey()).isEqualTo("synthetic-read-only-catalog-fixture");
                    assertThat(properties.getConsoleBaseUrl()).isEqualTo("https://console.example.invalid/ui");
                    assertThat(properties.toString()).doesNotContain("synthetic-read-only-catalog-fixture");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.model-control-plane.binding-fixture", havingValue = "true")
    @EnableConfigurationProperties(ModelControlPlaneProperties.class)
    static class Config { }
}

package com.kellen.auth;

import org.springframework.boot.test.context.SpringBootTest;

/** 关闭静态资源映射时，同一生产 Advice 也必须保留 NoHandlerFound 的 404。 */
@SpringBootTest(classes = RetiredClientEndpointsHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.retired-client.fixture=true", "spring.config.name=isolated-retired-client-no-resources",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false", "spring.web.resources.add-mappings=false"})
class RetiredClientWithoutResourceMappingsHttpTest extends RetiredClientEndpointsHttpTest {
}

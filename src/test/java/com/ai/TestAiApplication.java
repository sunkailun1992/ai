package com.ai;

import com.kellen.config.AiAgentClientProperties;
import com.kellen.config.AiProperties;
import com.kellen.config.NacosAiAgentRegistryProperties;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePoolMetricsAutoConfiguration;
import mybatis.mate.autoconfigure.MpmAutoConfiguration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.seata.spring.boot.autoconfigure.SeataAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataCoreAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataDataSourceAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataHttpAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataSagaAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataSpringFenceAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * AI 服务测试启动配置。
 *
 * <p>生产启动类完整接入 utils 动态数据源和外部基础设施；测试只验证 AI 业务上下文，
 * 因此排除 Seata 数据源代理、Flyway 迁移（PostgreSQL 专用，H2 用测试 schema.sql）与 Redis 组件，使用 H2 内存库完成业务测试。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        MpmAutoConfiguration.class,
        DynamicDataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        DynamicDataSourcePoolMetricsAutoConfiguration.class,
        SeataAutoConfiguration.class,
        SeataCoreAutoConfiguration.class,
        SeataDataSourceAutoConfiguration.class,
        SeataHttpAutoConfiguration.class,
        SeataSagaAutoConfiguration.class,
        SeataSpringFenceAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedissonAutoConfigurationV4.class
})
@ComponentScan(
        basePackages = "com.kellen",
        // 与 @SpringBootApplication 的扫描一致：utils 的自动配置同在 com.kellen 包下，只能按自动配置的顺序与条件装配，不能被当成普通配置提前扫进来。
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {
                                "com\\.kellen\\.AiApplication",
                                "com\\.kellen\\.config\\.datasource\\..*",
                                "com\\.kellen\\.config\\.redis\\..*"
                        }
                )
        }
)
@ConfigurationPropertiesScan(basePackageClasses = {
        AiProperties.class,
        AiAgentClientProperties.class,
        NacosAiAgentRegistryProperties.class
})
@MapperScan(value = "com.kellen", annotationClass = Mapper.class)
public class TestAiApplication {
}

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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * AI 服务测试启动配置。
 *
 * <p>生产启动类完整接入 utils 动态数据源和外部基础设施；测试只验证 AI 业务上下文，
 * 因此排除 Seata 数据源代理和 ES 请求日志组件，使用 H2 内存库完成业务测试。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        MpmAutoConfiguration.class,
        DynamicDataSourceAutoConfiguration.class,
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
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = {
                        "com\\.kellen\\.AiApplication",
                        "com\\.kellen\\.aliyun\\..*",
                        "com\\.kellen\\.config\\.actuator\\..*",
                        "com\\.kellen\\.config\\.datasource\\..*",
                        "com\\.kellen\\.config\\.elasticsearch\\..*",
                        "com\\.kellen\\.config\\.redis\\..*",
                        "com\\.kellen\\.log\\..*",
                        "com\\.kellen\\.aop\\.RequestRequiredAspect"
                }
        )
)
@ConfigurationPropertiesScan(basePackageClasses = {
        AiProperties.class,
        AiAgentClientProperties.class,
        NacosAiAgentRegistryProperties.class
})
@MapperScan(value = "com.kellen", annotationClass = Mapper.class)
public class TestAiApplication {
}

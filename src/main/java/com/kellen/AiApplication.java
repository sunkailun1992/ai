package com.kellen;

import com.kellen.config.AiAgentClientProperties;
import com.kellen.config.AiProperties;
import com.kellen.config.NacosAiAgentRegistryProperties;
import com.kellen.config.aliyun.CommonAliyunProperties;
import com.kellen.config.wechat.CommonWechatProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * 患者分析 AI 服务启动类。
 *
 * <p>ai 作为 fleet 成员与 user/message 同构：根包 {@code com.kellen} 全量组件扫描，
 * 复用 utils 的动态数据源、MyBatis 配置、统一响应/异常、公共工具等共享能力。
 * 数据源由 utils 动态数据源接管，因此排除 Spring 默认数据源自动配置；
 * 微信登录与 owner 接口保留自有 opaque Bearer 方案；scoped 聊天/记忆入口显式调用
 * User RPC 验证 access JWT，不依赖全局 Security 链开关或用户/租户请求头。</p>
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class}) // 数据源交给 utils 动态数据源，排除 Spring 默认数据源自动配置。
@EnableDubbo // 开启 Dubbo 服务发布和引用扫描，RPC 共享配置由 Nacos dubbo.yaml 提供。
@ConfigurationPropertiesScan(basePackageClasses = {
        AiProperties.class,
        AiAgentClientProperties.class,
        NacosAiAgentRegistryProperties.class,
        CommonAliyunProperties.class,
        CommonWechatProperties.class
}) // 注册 AI 业务配置与 utils 公共配置类。
@MapperScan(value = "com.kellen", annotationClass = Mapper.class) // 只注册具体 MyBatis-Plus Mapper，跳过基础泛型接口。
public class AiApplication {

    /**
     * JVM 进程入口，启动 Spring 容器并加载患者分析 AI 服务。
     *
     * @param args 命令行参数，例如 --spring.profiles.active=dev。
     */
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args); // 引导 Spring Boot 应用。
    }
}

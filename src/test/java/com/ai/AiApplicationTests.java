package com.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用上下文加载测试。
 *
 * <p>验证全部 Bean（含 Agent Registry 客户端、记忆服务、配置绑定、MyBatis-Plus 实体）能在
 * H2 内存库环境下正常装配，是数据库实体和配置变更的最低安全网。</p>
 */
@SpringBootTest(properties = { // 测试环境覆盖外部依赖配置。
        "spring.profiles.active=dev", // 测试固定使用 dev 段配置，避免默认 prod 段的生产数据库和日志路径。
        "spring.config.name=ai-isolated-test", // 独立测试配置名，不加载含远程 import 的部署入口。
        "spring.cloud.nacos.config.import-check.enabled=false",
        "ai.bootstrap.seed-demo-content=true", // 仅测试显式加载仓库中的历史演示种子。
        "spring.cloud.nacos.config.enabled=false", // 单元测试不连接真实 Nacos 配置中心。
        "spring.cloud.nacos.discovery.enabled=false", // 单元测试不向真实 Nacos 注册服务。
        "seata.enabled=false", // 单元测试不初始化 Seata 客户端或连接事务注册中心。
        "spring.datasource.dynamic.seata=false", // H2 测试数据源不走 Seata 代理。
        "ai.ddl.enabled=false", // H2 使用测试 schema.sql，不执行 MySQL DDL runner。
        "spring.datasource.url=jdbc:h2:mem:ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // 使用 H2 内存库模拟 MySQL。
        "spring.datasource.driver-class-name=org.h2.Driver", // 指定 H2 JDBC 驱动。
        "spring.datasource.username=sa", // H2 默认用户名。
        "spring.datasource.password=", // H2 默认空密码。
        "mysql.url=jdbc:h2:mem:ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // utils 动态数据源主库占位符也指向 H2。
        "mysql.username=sa", // H2 主库用户名。
        "mysql.password=", // H2 主库空密码。
        "mysql.driverClassName=org.h2.Driver", // H2 主库驱动。
        "mysql-gray.url=jdbc:h2:mem:ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // 灰度库占位符复用同一个 H2。
        "mysql-gray.username=sa", // H2 灰度库用户名。
        "mysql-gray.password=", // H2 灰度库空密码。
        "mysql-gray.driverClassName=org.h2.Driver", // H2 灰度库驱动。
        "logging.file.path=build/logs", // 测试日志写入构建目录，不污染项目根目录和服务器路径。
        "logging.file.name=build/logs/ai-test.log" // 覆盖 dev 段的 ./logs/ai.log，避免构建后工作区出现未跟踪日志。
})
class AiApplicationTests {

    /**
     * 空测试体：上下文启动成功即通过，启动失败即失败。
     */
    @Test
    void contextLoads() {
    }
}

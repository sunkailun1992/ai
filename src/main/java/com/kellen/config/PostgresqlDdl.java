package com.kellen.config;

import com.baomidou.mybatisplus.extension.ddl.IDdl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;

/**
 * MyBatis-Plus 自动维护 AI 服务 PostgreSQL DDL。
 *
 * <p>AI 服务表结构从 JPA 自动建表切换为 SQL 脚本维护，便于后续发布时
 * 明确审阅表结构、索引、唯一约束和字段注释。</p>
 */
@Component
@ConditionalOnProperty(prefix = "ai.ddl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresqlDdl implements IDdl {

    private final DataSource dataSource;

    /**
     * 构造 DDL 执行入口。
     *
     * @param dataSource 当前服务数据源。
     */
    public PostgresqlDdl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 指定 MyBatis-Plus DDL 脚本执行使用的数据源。
     *
     * @param consumer DDL 执行器。
     */
    @Override
    public void runScript(Consumer<DataSource> consumer) {
        consumer.accept(dataSource);
    }

    /**
     * 获取 AI 服务合并初始化脚本。
     *
     * @return SQL 脚本列表。
     */
    @Override
    public List<String> getSqlFiles() {
        return List.of(
                "db/postgresql/common-infra-schema.sql",
                "db/postgresql/ai-schema.sql",
                "db/postgresql/ai-storage-console-20261111.sql"
        );
    }
}

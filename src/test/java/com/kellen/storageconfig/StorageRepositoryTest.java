package com.kellen.storageconfig;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 实际 MyBatis/H2：固定全局 scope、数据库 CAS 与不可删除平台引用。 */
class StorageRepositoryTest {
    @Test void configurationCasAndReferencesAreDatabaseEnforced() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:storage_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("storage-schema.sql")).execute(ds);
        new JdbcTemplate(ds).execute("CREATE TABLE ai_file_object(id BIGINT,storage_platform VARCHAR(128),is_delete BOOLEAN)");
        var cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(StorageConfigurationRepository.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);
        var interceptor=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler(){
                    public net.sf.jsqlparser.expression.Expression getTenantId(){throw new AssertionError("global repository must not consume ambient tenant");}
                }));
        factory.setPlugins(interceptor);
        var repo=new SqlSessionTemplate(factory.getObject()).getMapper(StorageConfigurationRepository.class);
        var row=new StorageConfigurationEntity();row.id="fixture-config";row.platform="fixture-platform";row.name="合成私有存储";
        row.provider="MINIO";row.endpoint="http://127.0.0.1:19000";row.bucket="fixture-bucket";row.uploadEnabled=true;
        row.keyId="fixture-key";row.nonce="fixture-nonce";row.ciphertext="fixture-ciphertext";row.credentialRevision=1;row.configRevision=1;row.version=1;
        row.credentialUpdatedAt=java.time.Instant.now();
        assertThat(repo.insert(row)).isEqualTo(1);
        assertThat(repo.byId(row.id).platform).isEqualTo("fixture-platform");
        row.name="新名称";
        assertThat(repo.update(row,1)).isEqualTo(1);
        assertThat(repo.update(row,1)).isZero();
        assertThat(repo.byId(row.id).version).isEqualTo(2);
        new JdbcTemplate(ds).update("INSERT INTO ai_file_object VALUES(1,?,true)",row.platform);
        assertThat(repo.references(row.platform,null)).isEqualTo(1); // 软删除引用仍不能造成旧文件失联。
        assertThat(repo.byPlatform(row.platform)).isNotNull();
        assertThat(repo.all()).hasSize(1);
    }
}

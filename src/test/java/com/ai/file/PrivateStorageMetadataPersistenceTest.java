package com.ai.file;

import com.kellen.file.*;
import com.kellen.storage.PrivateObjectStorageService;
import com.kellen.storageconfig.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** H2 / MyBatis 真读写保留平台，切换默认平台不改变已登记文件的读取位置。 */
@SpringBootTest(properties = { // 测试环境覆盖外部依赖配置。
        "test.storage.metadata.fixture=true",
        "spring.profiles.active=dev", // 测试固定使用 dev 段配置，避免默认 prod 段的生产数据库和日志路径。
        "ai.oss.endpoint=oss.example.invalid",
        "ai.oss.bucket=legacy-private",
        "ai.oss.access-key-id=synthetic-id",
        "ai.oss.access-key-secret=synthetic-secret",
        "spring.config.name=ai-isolated-test", // 独立测试配置名，不加载含远程 import 的部署入口。
        "spring.cloud.nacos.config.import-check.enabled=false",
        "ai.bootstrap.seed-demo-content=false", // 仅测试显式加载仓库中的历史演示种子。
        "spring.cloud.nacos.config.enabled=false", // 单元测试不连接真实 Nacos 配置中心。
        "spring.cloud.nacos.discovery.enabled=false", // 单元测试不向真实 Nacos 注册服务。
        "seata.enabled=false", // 单元测试不初始化 Seata 客户端或连接事务注册中心。
        "spring.datasource.dynamic.seata=false", // H2 测试数据源不走 Seata 代理。
        "ai.ddl.enabled=false", // H2 使用测试 schema.sql，不执行 MySQL DDL runner。
        "spring.datasource.url=jdbc:h2:mem:ai-file-persistence-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // 使用 H2 内存库模拟 MySQL。
        "spring.datasource.driver-class-name=org.h2.Driver", // 指定 H2 JDBC 驱动。
        "spring.datasource.username=sa", // H2 默认用户名。
        "spring.datasource.password=", // H2 默认空密码。
        "mysql.url=jdbc:h2:mem:ai-file-persistence-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // utils 动态数据源主库占位符也指向 H2。
        "mysql.username=sa", // H2 主库用户名。
        "mysql.password=", // H2 主库空密码。
        "mysql.driverClassName=org.h2.Driver", // H2 主库驱动。
        "mysql-gray.url=jdbc:h2:mem:ai-file-persistence-test;MODE=MySQL;DB_CLOSE_DELAY=-1", // 灰度库占位符复用同一个 H2。
        "mysql-gray.username=sa", // H2 灰度库用户名。
        "mysql-gray.password=", // H2 灰度库空密码。
        "mysql-gray.driverClassName=org.h2.Driver", // H2 灰度库驱动。
        "logging.file.path=build/logs", // 测试日志写入构建目录，不污染项目根目录和服务器路径。
        "logging.file.name=build/logs/ai-file-persistence-test.log" // 覆盖 dev 段的 ./logs/ai.log，避免构建后工作区出现未跟踪日志。
})
@Transactional
@Import(PrivateStorageMetadataPersistenceTest.Crypto.class)
class PrivateStorageMetadataPersistenceTest {
    @Autowired AiFileObjectRepository repository;
    @Autowired OssFileService service;
    @MockitoBean PrivateObjectStorageService storage;
    @MockitoBean StorageRuntimeRegistry.Factory factory;
    @Autowired StorageCredentialCipher cipher;
    @Autowired StorageConfigurationRepository configs;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void nativeBoundary(){
        when(factory.open(any(),any())).thenReturn(new StorageRuntimeRegistry.Handle(){
            public PrivateObjectStorageService adapter(){return storage;}
            public void write(String key,byte[] bytes){throw new AssertionError("not a write test");}
            public boolean exists(String key){throw new AssertionError("not an existence test");}
            public byte[] read(String key){throw new AssertionError("not a raw read test");}
            public boolean anonymousDenied(String key){throw new AssertionError("not a probe test");}
            public void delete(String key){throw new AssertionError("not a delete test");}
            public void close(){}
        });
    }
    private void configured(String platform){
        var row=new StorageConfigurationEntity();row.id=UUID.randomUUID().toString();row.platform=platform;row.provider="MINIO";
        row.name="合成历史平台";row.endpoint="http://127.0.0.1:19000";row.bucket="fixture-bucket";row.uploadEnabled=false;
        row.credentialRevision=1;row.configRevision=1;row.version=1;row.credentialUpdatedAt=java.time.Instant.now();
        var encrypted=cipher.encrypt(StorageRuntimeRegistry.binding(row),new StorageCredentials("synthetic-access","synthetic-secret"));
        row.keyId=encrypted.keyId();row.nonce=encrypted.nonce();row.ciphertext=encrypted.ciphertext();configs.insert(row);
        jdbc.update("UPDATE ai_storage_setting SET default_platform='replacement-private',legacy_null_platform=?,view_ttl_seconds=1800 WHERE id='PLATFORM'",platform);
    }
    @TestConfiguration(proxyBeanMethods=false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="test.storage.metadata.fixture",havingValue="true")
    static class Crypto {
        @Bean @Primary StorageCredentialCipher fixtureCipher(){return new StorageCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]),"fixture-metadata");}
    }

    @Test void platformSurvivesAMapperRoundTripAndRemainsBoundForSigning() {
        String key = "ai-miniapp/wechat-user-7/image/" + UUID.randomUUID() + ".png";
        AiFileObjectEntity file = AiFileObjectEntity.create(7L, key, "fixture.png", "image/png", "image", 12L, "hash");
        configured("original-private");
        file.setStoragePlatform("original-private");
        repository.save(file);
        AiFileObjectEntity readBack = repository.findByIdAndStatus(file.getId(), AiFileObjectEntity.STATUS_ACTIVE).orElseThrow();
        assertThat(readBack).isNotSameAs(file);
        assertThat(readBack.getStoragePlatform()).isEqualTo("original-private");
        when(storage.platform()).thenReturn("replacement-private");
        URI expected = URI.create("https://files.example.invalid/signed");
        when(storage.signedViewUri("original-private", key, 1800)).thenReturn(expected);
        assertThat(service.signedViewUri(readBack)).isEqualTo(expected);
        verify(storage).signedViewUri("original-private", key, 1800);
    }

    @Test void legacyNullPlatformUsesFixedDatabaseMappingInsteadOfChangedDefaultOrLegacyNacos() {
        configured("legacy-mapped");
        String key = "ai-miniapp/wechat-user-7/image/" + UUID.randomUUID() + ".png";
        AiFileObjectEntity file = AiFileObjectEntity.create(7L, key, "fixture.png", "image/png", "image", 12L, "hash");
        repository.save(file);
        AiFileObjectEntity readBack = repository.findByIdAndStatus(file.getId(), AiFileObjectEntity.STATUS_ACTIVE).orElseThrow();
        assertThat(readBack.getStoragePlatform()).isNull();
        URI expected=URI.create("https://files.example.invalid/private-mapped");
        when(storage.signedViewUri("legacy-mapped",key,1800)).thenReturn(expected);
        assertThat(service.signedViewUri(readBack)).isEqualTo(expected);
        verify(storage).signedViewUri("legacy-mapped",key,1800);
    }
}

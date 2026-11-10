package com.kellen.storageconfig;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StorageOperationCasTest {
    @Test void staleRunningReadCannotOverwriteACompletedWorkerCleanup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:operation_cas_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("storage-schema.sql")).execute(ds);
        var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);c.addMapper(StorageOperationRepository.class);
        var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setConfiguration(c);var repo=new SqlSessionTemplate(f.getObject()).getMapper(StorageOperationRepository.class);
        var r=new StorageOperationEntity();r.id="fixture-op";r.actorId="fixture-admin";r.requestId=UUID.randomUUID().toString();r.action="PROBE";r.configurationId="fixture-config";
        r.fingerprint="fixture";r.operationJson="running-snapshot";r.status="RUNNING";r.cleanupStatus="PENDING";r.startedAt=java.time.Instant.now();repo.insert(r);
        var stale=repo.get(r.actorId,r.requestId);String expected=stale.operationJson;
        r.status="UNKNOWN";r.cleanupStatus="SUCCEEDED";r.operationJson="worker-terminal-with-cleanup-confirmed";repo.update(r);
        stale.status="UNKNOWN";stale.cleanupStatus="UNKNOWN";stale.operationJson="stale-recovery";
        assertThat(repo.recoverUnknown(stale,expected)).isZero();
        assertThat(repo.get(r.actorId,r.requestId).cleanupStatus).isEqualTo("SUCCEEDED");
        assertThat(repo.get(r.actorId,r.requestId).operationJson).isEqualTo("worker-terminal-with-cleanup-confirmed");
    }
}

package com.kellen.storageconfig;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.kellen.storageconfig.StorageContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 Mapper + 数据库事务，不替换配置、幂等或 CAS 业务。 */
class StorageConfigurationServiceTest {
    StorageConfigurationService service;
    StorageRuntimeRegistry registry;
    JdbcTemplate jdbc;
    StorageProbeRunnerTest.Handle handle;
    StorageConfigurationRepository configs;
    StorageSettingsRepository settingsRepo;
    StorageOperationRepository operationsRepo;
    StorageCredentialCipher cipher;
    DataSourceTransactionManager manager;
    StorageConfigurationService another(StorageOperationRepository operations){return new StorageConfigurationService(configs,settingsRepo,operations,cipher,new StorageValidation(Set.of("http://127.0.0.1:19000")),registry,manager,new ObjectMapper());}
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:storage_service_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("storage-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE ai_file_object(id BIGINT,storage_platform VARCHAR(128),is_delete BOOLEAN)");
        var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);
        c.addMapper(StorageConfigurationRepository.class);c.addMapper(StorageSettingsRepository.class);c.addMapper(StorageOperationRepository.class);
        var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setConfiguration(c);var session=new SqlSessionTemplate(f.getObject());
        cipher=new StorageCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]),"fixture");
        handle=new StorageProbeRunnerTest.Handle();
        registry=new StorageRuntimeRegistry(cipher,(r,credentials)->handle);
        configs=session.getMapper(StorageConfigurationRepository.class);settingsRepo=session.getMapper(StorageSettingsRepository.class);operationsRepo=session.getMapper(StorageOperationRepository.class);manager=new DataSourceTransactionManager(ds);
        service=another(operationsRepo);
    }
    @AfterEach void close(){service.close();registry.close();}
    Create create(String request,String name){return new Create(request,name,"MINIO","http://127.0.0.1:19000","fixture-bucket","us-east-1",true,"REPLACE","synthetic-access","synthetic-secret",null);}
    @Test void sameRequestReturnsSameResultAndChangedSecretsCannotReuseTheRequestId() {
        String request=UUID.randomUUID().toString();var input=create(request,"合成存储");
        var first=service.create("admin",input);var repeated=service.create("admin",input);
        assertThat(first).isEqualTo(repeated);assertThat(service.list(1,20,null,null,null).total()).isEqualTo(1);
        var changed=new Create(request,"合成存储","MINIO","http://127.0.0.1:19000","fixture-bucket","us-east-1",true,"REPLACE","synthetic-access","different-synthetic-secret",null);
        assertThatThrownBy(()->service.create("admin",changed)).isInstanceOf(StorageFailure.class).extracting(e->((StorageFailure)e).code()).isEqualTo("STORAGE_REQUEST_ID_REUSED");
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM ai_storage_configuration",String.class)).doesNotContain("synthetic");
        assertThat(jdbc.queryForObject("SELECT operation_json FROM ai_storage_operation",String.class)).doesNotContain("synthetic-secret","accessKeyId","nonce","ciphertext");
        assertThatThrownBy(()->service.operation("other-admin",request)).isInstanceOf(StorageFailure.class);
    }
    @Test void defaultAndSoftDeletedReferencesPreventDeletionAndSettingsUseCas() {
        var config=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"合成存储")).result();
        var referenced=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"软删除仍引用")).result();
        jdbc.update("INSERT INTO ai_file_object VALUES(1,?,true)",referenced.platform());
        assertThatThrownBy(()->service.delete("admin",referenced.id(),UUID.randomUUID().toString(),1L)).isInstanceOf(StorageFailure.class);
        service.updateSettings("admin",new UpdateSettings(UUID.randomUUID().toString(),0L,config.platform(),60L,1800L,1024L,config.platform(),"legacy-prefix"));
        assertThatThrownBy(()->service.delete("admin",config.id(),UUID.randomUUID().toString(),1L)).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(()->service.updateSettings("admin",new UpdateSettings(UUID.randomUUID().toString(),0L,config.platform(),60L,1800L,1024L,null,null))).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(()->service.updateSettings("admin",new UpdateSettings(UUID.randomUUID().toString(),1L,config.platform(),60L,1800L,1024L,null,"changed"))).isInstanceOf(StorageFailure.class);
        assertThat(service.settings().baseDir()).isEqualTo("legacy-prefix");
    }
    @Test void unknownProbeIsNeverReplayedAndVerifiedCleanupAllowsExplicitConfigurationChange() throws Exception {
        var config=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"探针存储")).result();
        handle.unknownWrite=true;String request=UUID.randomUUID().toString();
        service.probe("admin",config.id(),new Probe(request,1L,"READ_WRITE"));
        Operation op=null;
        for(int i=0;i<200;i++){op=service.operation("admin",request);if(List.of("SUCCEEDED","FAILED","UNKNOWN").contains(op.status()))break;Thread.sleep(10);}
        assertThat(op.status()).isEqualTo("UNKNOWN");assertThat(op.cleanupStatus()).as("safe operation %s; writes %s; deletes %s",op,handle.writes,handle.deletes).isEqualTo("SUCCEEDED");
        service.probe("admin",config.id(),new Probe(request,1L,"READ_WRITE"));assertThat(handle.writes).isEqualTo(1);
        assertThat(service.update("admin",config.id(),new Update(UUID.randomUUID().toString(),1L,"已清理",false,"KEEP",null,null)).status()).isEqualTo("SUCCEEDED");
    }
    @Test void failedCleanupKeepsCredentialsAndConfigurationUntilOwnedObjectIsResolved() throws Exception {
        var c=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"清理失败存储")).result();
        handle.failedDelete=true;String request=UUID.randomUUID().toString();service.probe("admin",c.id(),new Probe(request,1L,"READ_WRITE"));
        Operation op=null;for(int i=0;i<200;i++){op=service.operation("admin",request);if(op.finishedAt()!=null)break;Thread.sleep(10);}
        assertThat(op.status()).isEqualTo("FAILED");assertThat(op.cleanupStatus()).isEqualTo("FAILED");assertThat(handle.exists).isTrue();
        assertThatThrownBy(()->service.update("admin",c.id(),new Update(UUID.randomUUID().toString(),1L,"不可改",false,"KEEP",null,null))).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(()->service.delete("admin",c.id(),UUID.randomUUID().toString(),1L)).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(()->service.probe("admin",c.id(),new Probe(UUID.randomUUID().toString(),1L,"READ_WRITE"))).isInstanceOf(StorageFailure.class);
    }
    @Test void twoServiceInstancesReturnTheSameProbeIntentWithoutASecondWrite() throws Exception {
        var c=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"并发存储")).result();String request=UUID.randomUUID().toString();
        var barrier=new java.util.concurrent.CyclicBarrier(2);var seen=ThreadLocal.withInitial(()->false);
        var delegate=org.mockito.Mockito.mock(StorageOperationRepository.class,org.mockito.AdditionalAnswers.delegatesTo(operationsRepo));
        org.mockito.Mockito.doAnswer(call->{
            StorageOperationEntity value=operationsRepo.get(call.getArgument(0),call.getArgument(1));
            if(request.equals(call.getArgument(1)) && !seen.get()){seen.set(true);barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);}
            return value;
        }).when(delegate).get(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        var release=new java.util.concurrent.CountDownLatch(1);
        handle=new StorageProbeRunnerTest.Handle(){public void write(String key,byte[] bytes){super.write(key,bytes);try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new StorageFailure("STORAGE_OUTCOME_UNKNOWN",503);}}};
        var a=another(delegate);var b=another(delegate);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->a.probe("admin",c.id(),new Probe(request,1L,"READ_WRITE")));
            var second=pool.submit(()->b.probe("admin",c.id(),new Probe(request,1L,"READ_WRITE")));
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).requestId()).isEqualTo(request);
            assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS).requestId()).isEqualTo(request);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_storage_operation WHERE request_id=?",Integer.class,request)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();a.close();b.close();}
        for(int i=0;i<200 && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT cleanup_status FROM ai_storage_operation WHERE request_id=?",String.class,request));i++)Thread.sleep(10);
        assertThat(handle.writes).isEqualTo(1);
    }
    @Test void renameDoesNotRotateRuntimeAndDisablingUploadsRetainsConfigurationForOldReads() {
        var c=(Configuration)service.create("admin",create(UUID.randomUUID().toString(),"旧名")).result();
        String before=jdbc.queryForObject("SELECT ciphertext FROM ai_storage_configuration WHERE id=?",String.class,c.id());
        var renamed=(Configuration)service.update("admin",c.id(),new Update(UUID.randomUUID().toString(),1L,"只改名称",true,"KEEP",null,null)).result();
        assertThat(renamed.version()).isEqualTo(2);assertThat(renamed.configRevision()).isEqualTo(1);
        var u=(Configuration)service.update("admin",c.id(),new Update(UUID.randomUUID().toString(),2L,"新名",false,"KEEP",null,null)).result();
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM ai_storage_configuration WHERE id=?",String.class,c.id())).isEqualTo(before);
        assertThat(u.version()).isEqualTo(3);assertThat(u.configRevision()).isEqualTo(2);assertThat(u.uploadEnabled()).isFalse();
        assertThat(service.configuration(c.id()).platform()).isEqualTo(c.platform());
        assertThatThrownBy(()->service.update("admin",c.id(),new Update(UUID.randomUUID().toString(),1L,"陈旧",false,"KEEP",null,null))).isInstanceOf(StorageFailure.class);
    }
}

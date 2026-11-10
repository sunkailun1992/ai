package com.kellen.storageconfig;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.controlplane.AiControlPlaneAdministrator;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.security.config.SecurityAuthConfig;
import com.kellen.utils.auth.JwtKeyConfig;
import com.kellen.utils.auth.JwtUtils;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=StorageConfigurationHttpTest.Beans.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.name=storage-http-isolated","spring.cloud.nacos.config.enabled=false","spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false","security.auth.enabled=true","security.auth.header-enabled=false","test.storage.http.fixture=true"})
class StorageConfigurationHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    @DynamicPropertySource static void key(DynamicPropertyRegistry p){p.add("security.jwt.secret",()->Base64.getEncoder().encodeToString(new byte[32]));}
    @Test void realHttpRejectsAnonymousOrdinaryAndForgedHeaderAdministrators() throws Exception {
        assertThat(call("GET","/storage-capabilities",null,null).statusCode()).isIn(401,403);
        assertThat(call("GET","/storage-capabilities",token("NONE",List.of()),null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/storage-settings",token("PLATFORM_SUPER_ADMIN",List.of()),null).statusCode()).isEqualTo(403);
        assertThat(call("GET","/storage-capabilities",admin(),null).statusCode()).isEqualTo(200);
    }
    @Test void createUsesWhiteListResponseAndHttpUnknownFieldsCannotModifyLocation() throws Exception {
        String request=UUID.randomUUID().toString();
        String body="{\"requestId\":\""+request+"\",\"name\":\"HTTP合成存储\",\"provider\":\"MINIO\",\"endpoint\":\"http://127.0.0.1:19000\",\"bucket\":\"fixture-bucket\",\"region\":\"us-east-1\",\"uploadEnabled\":true,\"credentialAction\":\"REPLACE\",\"accessKeyId\":\"synthetic-http-access\",\"secretKey\":\"synthetic-http-secret\"}";
        var created=call("POST","/storage-configurations",admin(),body);assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("synthetic-http-access","synthetic-http-secret","ciphertext","nonce","accessKeyId");
        var node=json.readTree(created.body());String id=node.path("data").path("result").path("id").asText();
        var repeated=call("POST","/storage-configurations",admin(),body);assertThat(repeated.statusCode()).isEqualTo(201);
        assertThat(json.readTree(repeated.body()).path("data").path("configurationId").asText()).isEqualTo(id);
        var conflict=call("POST","/storage-configurations",admin(),body.replace("synthetic-http-secret","changed-synthetic-secret"));
        assertThat(conflict.statusCode()).isEqualTo(409);assertThat(conflict.body()).contains("STORAGE_REQUEST_ID_REUSED").doesNotContain("changed-synthetic-secret");
        var location=call("PUT","/storage-configurations/"+id,admin(),"{\"requestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":1,\"name\":\"更新\",\"uploadEnabled\":true,\"credentialAction\":\"KEEP\",\"endpoint\":\"http://forbidden.invalid\"}");
        assertThat(location.statusCode()).isEqualTo(400);assertThat(location.body()).doesNotContain("forbidden.invalid");
        assertThat(call("GET","/storage-configurations/"+id,admin(),null).body()).contains("http://127.0.0.1:19000");
    }
    @Test void missingAndUnknownOperationsAreDifferentAndRestartNeverReplaysWrites() throws Exception {
        var missing=call("GET","/storage-operations/"+UUID.randomUUID(),admin(),null);
        assertThat(missing.statusCode()).isEqualTo(404);assertThat(missing.body()).contains("STORAGE_OPERATION_NOT_FOUND").doesNotContain("\"data\"");
        String request=UUID.randomUUID().toString();String id=UUID.randomUUID().toString();
        var op=new StorageContracts.Operation(request,"PROBE","fixture-id","RUNNING",1L,java.time.Instant.now(),null,null,List.of(),"PENDING",null);
        jdbc.update("INSERT INTO ai_storage_operation(id,scope,actor_id,request_id,action,configuration_id,fingerprint,operation_json,status,started_at) VALUES(?,'PLATFORM','fixture-admin',?,'PROBE','fixture-id','fixture',?,'RUNNING',CURRENT_TIMESTAMP)",id,request,json.writeValueAsString(op));
        var unknown=call("GET","/storage-operations/"+request,admin(),null);
        assertThat(unknown.statusCode()).isEqualTo(200);assertThat(unknown.body()).contains("\"status\":\"UNKNOWN\"","STORAGE_OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_storage_operation WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
    }
    private String admin(){return token("PLATFORM_SUPER_ADMIN",List.of("user:auth:manage"));}
    private String token(String admin,Object permissions){return JwtUtils.createJwt(UUID.randomUUID().toString(),"fixture-admin",Map.of("userId","fixture-admin","tenantId","fixture-tenant","adminType",admin,"permissions",permissions));}
    private HttpResponse<String> call(String method,String path,String token,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai"+path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").header("X-Admin-Type","PLATFORM_SUPER_ADMIN");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Configuration(proxyBeanMethods=false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="test.storage.http.fixture",havingValue="true")
    @Import({StorageConfigurationController.class,AiControlPlaneAdministrator.class,SecurityAuthConfig.class})
    @ImportAutoConfiguration({org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
            org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
            org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
            org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
            org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
            org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class})
    static class Beans {
        @Bean DriverManagerDataSource dataSource(){var ds=new DriverManagerDataSource("jdbc:h2:mem:storage_http_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");new ResourceDatabasePopulator(new ClassPathResource("storage-schema.sql")).execute(ds);new JdbcTemplate(ds).execute("CREATE TABLE ai_file_object(id BIGINT,storage_platform VARCHAR(128),is_delete BOOLEAN)");return ds;}
        @Bean JdbcTemplate jdbc(DriverManagerDataSource ds){return new JdbcTemplate(ds);}
        @Bean StorageCredentialCipher cipher(){return new StorageCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]),"fixture");}
        @Bean StorageRuntimeRegistry registry(StorageCredentialCipher cipher){return new StorageRuntimeRegistry(cipher,(r,c)->new StorageRuntimeRegistryTest.FakeHandle());}
        @Bean StorageConfigurationService service(DriverManagerDataSource ds,StorageCredentialCipher cipher,StorageRuntimeRegistry runtime,ObjectMapper json) throws Exception {
            var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);c.addMapper(StorageConfigurationRepository.class);c.addMapper(StorageSettingsRepository.class);c.addMapper(StorageOperationRepository.class);
            var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setConfiguration(c);var session=new SqlSessionTemplate(f.getObject());
            return new StorageConfigurationService(session.getMapper(StorageConfigurationRepository.class),session.getMapper(StorageSettingsRepository.class),session.getMapper(StorageOperationRepository.class),cipher,new StorageValidation(Set.of("http://127.0.0.1:19000")),runtime,new DataSourceTransactionManager(ds),json);
        }
        @Bean JwtKeyConfig jwtKeyConfig(Environment env){return new JwtKeyConfig(env);}
        @Bean AiRequestIdentityResolver identities(){return new AiRequestIdentityResolver(token->{var claims=JwtUtils.parseJwt(token);var identity=new AuthSessionIdentityRpcDTO();identity.setUserId(claims.get("userId",String.class));identity.setTenantId(claims.get("tenantId",String.class));return identity;});}
    }
}

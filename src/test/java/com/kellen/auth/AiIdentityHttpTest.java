package com.kellen.auth;

import com.kellen.conversation.*;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import reactor.core.publisher.Flux;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=AiIdentityHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"test.identity.fixture=true","spring.config.name=isolated-ai-identity","spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.import-check.enabled=false"})
class AiIdentityHttpTest {
    @LocalServerPort int port;
    HttpResponse<String> send(String authorization,String... headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai/chat"))
                .header("Content-Type","application/json");
        if(authorization!=null)request.header("Authorization",authorization);
        if(headers.length>0)request.headers(headers);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
                "{\"conversationId\":\"client-id\",\"message\":\"synthetic\",\"tenantId\":\"forged\",\"userId\":\"forged\"}"))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousForgedHeadersInvalidAndWechatOpaqueTokensCannotOpenScopedChat() throws Exception {
        assertThat(send(null,"X-User-Id","forged","X-Tenant-Id","forged").statusCode()).isEqualTo(401);
        assertThat(send("Bearer invalid").statusCode()).isEqualTo(401);
        assertThat(send("Bearer opaque-wechat-session").statusCode()).isEqualTo(401);
    }
    @Test void onlyUserVerifiedIdentityReachesDialogueAndDoesNotBecomeWechatOwner() throws Exception {
        var response=send("Bearer synthetic-user-token");
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("verified");
        assertThat(response.body()).doesNotContain("synthetic-user-token","tenant-a","user-a");
    }
    @Configuration @ConditionalOnProperty(name="test.identity.fixture",havingValue="true") @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class})
    static class Config {
        @Bean AiRequestIdentityResolver identities(){return new AiRequestIdentityResolver(token->{
            if(!token.equals("synthetic-user-token"))throw new SecurityException("rejected");
            var identity=new AuthSessionIdentityRpcDTO();identity.setUserId("user-a");identity.setTenantId("tenant-a");return identity;
        });}
        @Bean AiDialogueService dialogue(){
            var service=mock(AiDialogueService.class);
            when(service.stream(any(),isNull(),any())).thenAnswer(call->{
                AiRequestIdentity identity=call.getArgument(2);assertThat(identity.userId()).isEqualTo("user-a");
                assertThat(identity.tenantId()).isEqualTo("tenant-a");return Flux.just("verified");
            });return service;
        }
        @Bean AiDialogueController controller(AiDialogueService service,AiRequestIdentityResolver identities){
            return new AiDialogueController(service,mock(WechatAuthService.class),mock(ChatConversationService.class),identities);
        }
    }
}

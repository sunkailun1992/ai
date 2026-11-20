package com.ai.support;

import com.kellen.security.oauth2.FleetResourceServerAutoConfiguration;
import com.kellen.security.oauth2.SecurityContextRpcService;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

/**
 * 测试用资源服务器：Spring Security 与 utils 的资源服务器自动配置照常装配（过滤链、业务上下文、授权、统一错误），
 * 只把令牌解码与 user 服务的业务上下文换成本地替身。令牌签名、受众与签发方的校验由 utils 自己的测试覆盖。
 *
 * <p>测试类的 properties 里要带上 {@link #ISSUER}，资源服务器自动配置按这个属性装配。本类放在
 * {@code com.ai} 下，不在 {@code TestAiApplication} 的组件扫描范围里。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class, FleetResourceServerAutoConfiguration.class})
public class ResourceServerFixture {

    /**
     * 打开资源服务器自动配置的签发方属性；签发方是保留域名，测试不访问。
     */
    public static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.invalid/realms/github-dev";

    private static final String TOKEN_PREFIX = "synthetic-access-token-for-";

    /**
     * 某个用户的访问令牌请求头。
     *
     * @param userId 业务用户 ID（令牌的 user_id）
     * @return Authorization 请求头的值
     */
    public static String bearer(String userId) {
        return "Bearer " + accessToken(userId);
    }

    /**
     * 某个用户的访问令牌原文。
     *
     * @param userId 业务用户 ID
     * @return 令牌原文
     */
    public static String accessToken(String userId) {
        return TOKEN_PREFIX + userId;
    }

    @Bean
    FixtureUsers fixtureUsers() {
        return new FixtureUsers();
    }

    /**
     * 只认 {@code synthetic-access-token-for-<userId>}，解码成网关客户端签发、受众 github-api、带 user_id 的令牌。
     */
    @Bean
    JwtDecoder fixtureJwtDecoder() {
        return token -> {
            if (token == null || !token.startsWith(TOKEN_PREFIX) || token.length() == TOKEN_PREFIX.length()) {
                throw new BadJwtException("synthetic decoder rejects the token");
            }
            Instant now = Instant.now();
            return Jwt.withTokenValue(token).header("alg", "RS256")
                    .claim("user_id", token.substring(TOKEN_PREFIX.length())).claim("azp", "github-gateway")
                    .audience(List.of("github-api")).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        };
    }

    @Bean
    SecurityContextRpcService fixtureSecurityContext(FixtureUsers users) {
        return users::resolve;
    }
}

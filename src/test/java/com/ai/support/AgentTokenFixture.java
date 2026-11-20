package com.ai.support;

import com.kellen.security.oauth2.client.FleetOAuth2Clients;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;

/**
 * 不测令牌交换本身的用例用：{@code github-ai-agent} 注册直接给出固定令牌，调用 ai-agent 时照样带 Bearer 头、经 utils 的守卫。
 * 令牌交换的真实流程（令牌端点、受众、失败不调下游）见 {@code AiAgentClientTokenExchangeTest}。
 */
public final class AgentTokenFixture {

    private AgentTokenFixture() {
    }

    /**
     * 固定令牌的出站工具。
     *
     * @param token 调用 ai-agent 时带的令牌
     * @return 出站工具
     */
    public static FleetOAuth2Clients fixedToken(String token) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("github-ai-agent")
                .clientId("github-ai").clientSecret("fixture-secret")
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .tokenUri("https://keycloak.invalid/realms/github-dev/protocol/openid-connect/token").build();
        OAuth2AuthorizedClientManager manager = request -> new OAuth2AuthorizedClient(registration, request.getPrincipal().getName(),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, Instant.now(), Instant.now().plusSeconds(300)));
        return new FleetOAuth2Clients(manager, new InMemoryClientRegistrationRepository(registration));
    }
}

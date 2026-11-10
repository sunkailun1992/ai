package com.kellen.auth;

import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.rpc.auth.AuthSessionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 注册中心可用模式下的认证 RPC adapter；无注册中心测试不创建真实远端引用。 */
@Component
@ConditionalOnProperty(name="spring.cloud.nacos.discovery.enabled",havingValue="true",matchIfMissing=true)
public class AiSessionRpcClient {
    @DubboReference(check=false,lazy=true,timeout=3000,retries=0)
    private AuthSessionRpcService sessions;
    public AuthSessionIdentityRpcDTO resolveAccessIdentity(String token) {
        return sessions.resolveAccessIdentity(token);
    }
}

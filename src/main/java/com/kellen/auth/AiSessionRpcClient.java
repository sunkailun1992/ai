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
    // 本机灰度通道内也校验访问令牌：显式参与灰度，携带入站授权只选同标签 user；稳定请求仍走稳定 user。
    @DubboReference(check=false,lazy=true,timeout=3000,retries=0,parameters={"traffic.canary.enabled","true"})
    private AuthSessionRpcService sessions;
    public AuthSessionIdentityRpcDTO resolveAccessIdentity(String token) {
        return sessions.resolveAccessIdentity(token);
    }
}

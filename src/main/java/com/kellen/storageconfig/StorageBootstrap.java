package com.kellen.storageconfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 引导信任来自 OS 环境；业务配置及 Spring/Nacos 无法修改根密钥或允许访问的 origin。 */
@Configuration(proxyBeanMethods=false)
public class StorageBootstrap {
    @Bean StorageCredentialCipher storageCredentialCipher() {
        return new StorageCredentialCipher(System.getenv("AI_STORAGE_KEK_B64"),System.getenv("AI_STORAGE_KEK_ID"));
    }
    @Bean StorageValidation storageValidation() {
        String raw=System.getenv("AI_STORAGE_ALLOWED_ORIGINS");
        Set<String> origins=raw==null || raw.isBlank()?Set.of():Arrays.stream(raw.split(","))
                .map(String::trim).filter(s->!s.isEmpty()).map(StorageValidation::origin).collect(Collectors.toUnmodifiableSet());
        return new StorageValidation(origins);
    }
}

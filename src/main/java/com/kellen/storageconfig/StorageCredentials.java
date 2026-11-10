package com.kellen.storageconfig;

/** 仅在构建原生客户端时使用；不允许默认 toString 输出。 */
public record StorageCredentials(String accessKeyId, String secretKey) {
    @Override public String toString() { return "StorageCredentials[REDACTED]"; }
}

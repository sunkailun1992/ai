package com.kellen.storageconfig;

/** 内部持久化投影；只通过白名单 VO 返回，不作为 HTTP 响应。 */
@com.baomidou.mybatisplus.annotation.TableName("ai_storage_configuration")
public final class StorageConfigurationEntity {
    @com.baomidou.mybatisplus.annotation.TableId(type=com.baomidou.mybatisplus.annotation.IdType.INPUT)
    public String id;
    public String platform;
    public String name;
    public String provider;
    public String endpoint;
    public String bucket;
    public String region;
    public boolean uploadEnabled;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String keyId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String nonce;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String ciphertext;
    public long credentialRevision;
    public long configRevision;
    public long version;
    public java.time.Instant credentialUpdatedAt;
    @Override public String toString() { return "StorageConfigurationEntity[INTERNAL]"; }
}

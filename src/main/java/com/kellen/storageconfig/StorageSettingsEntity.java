package com.kellen.storageconfig;

/** 内部持久化投影；只通过白名单 VO 返回，不作为 HTTP 响应。 */
@com.baomidou.mybatisplus.annotation.TableName("ai_storage_setting")
public final class StorageSettingsEntity {
    @com.baomidou.mybatisplus.annotation.TableId(type=com.baomidou.mybatisplus.annotation.IdType.INPUT)
    public String id;
    public String defaultPlatform;
    public String legacyNullPlatform;
    public String baseDir;
    public long uploadTtlSeconds;
    public long viewTtlSeconds;
    public long maxUploadBytes;
    public long version;
    @Override public String toString() { return "StorageSettingsEntity[INTERNAL]"; }
}

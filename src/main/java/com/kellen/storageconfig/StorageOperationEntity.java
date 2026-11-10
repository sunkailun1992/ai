package com.kellen.storageconfig;

/** 内部持久化投影；只通过白名单 VO 返回，不作为 HTTP 响应。 */
@com.baomidou.mybatisplus.annotation.TableName("ai_storage_operation")
public final class StorageOperationEntity {
    @com.baomidou.mybatisplus.annotation.TableId(type=com.baomidou.mybatisplus.annotation.IdType.INPUT)
    public String id;
    public String actorId;
    public String requestId;
    public String action;
    public String configurationId;
    public String fingerprint;
    public String operationJson;
    public String status;
    public String cleanupStatus;
    public java.time.Instant startedAt;
    @Override public String toString() { return "StorageOperationEntity[INTERNAL]"; }
}

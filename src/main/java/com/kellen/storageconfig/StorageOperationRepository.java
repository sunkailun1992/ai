package com.kellen.storageconfig;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 只保存安全结果和 keyed request 指纹，绝不保存请求凭据。 */
@Mapper
@InterceptorIgnore(tenantLine="true")
public interface StorageOperationRepository {
    @Select("SELECT * FROM ai_storage_operation WHERE scope='PLATFORM' AND actor_id=#{actor} AND request_id=#{requestId}")
    StorageOperationEntity get(@Param("actor")String actor,@Param("requestId")String requestId);
    @Select("SELECT * FROM ai_storage_operation WHERE scope='PLATFORM' AND configuration_id=#{id} AND action='PROBE' ORDER BY started_at DESC,id DESC")
    List<StorageOperationEntity> probes(String id);
    @Select("SELECT COUNT(*) FROM ai_storage_operation WHERE scope='PLATFORM' AND configuration_id=#{id} AND action='PROBE' AND (status IN ('PENDING','RUNNING') OR cleanup_status IN ('PENDING','FAILED','UNKNOWN'))")
    long activeProbes(String id);
    @Insert("INSERT INTO ai_storage_operation(id,scope,actor_id,request_id,action,configuration_id,fingerprint,operation_json,status,cleanup_status,started_at) VALUES(#{id},'PLATFORM',#{actorId},#{requestId},#{action},#{configurationId},#{fingerprint},#{operationJson},#{status},#{cleanupStatus},#{startedAt})")
    int insert(StorageOperationEntity row);
    @Update("UPDATE ai_storage_operation SET operation_json=#{operationJson},status=#{status},cleanup_status=#{cleanupStatus},modify_date_time=CURRENT_TIMESTAMP WHERE scope='PLATFORM' AND id=#{id}")
    int update(StorageOperationEntity row);
    @Update("UPDATE ai_storage_operation SET operation_json=#{r.operationJson},status=#{r.status},cleanup_status=#{r.cleanupStatus},modify_date_time=CURRENT_TIMESTAMP WHERE scope='PLATFORM' AND id=#{r.id} AND status IN ('PENDING','RUNNING') AND operation_json=#{expectedJson}")
    int recoverUnknown(@Param("r")StorageOperationEntity row,@Param("expectedJson")String expectedJson);
}

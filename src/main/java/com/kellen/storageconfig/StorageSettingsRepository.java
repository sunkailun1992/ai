package com.kellen.storageconfig;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

/** 单一平台设置行；锁定它可串行化默认切换、删除和引用登记。 */
@Mapper
@InterceptorIgnore(tenantLine="true")
public interface StorageSettingsRepository {
    @Select("SELECT * FROM ai_storage_setting WHERE id='PLATFORM' AND scope='PLATFORM'")
    StorageSettingsEntity current();
    @Select("SELECT * FROM ai_storage_setting WHERE id='PLATFORM' AND scope='PLATFORM' FOR UPDATE")
    StorageSettingsEntity locked();
    @Update("UPDATE ai_storage_setting SET default_platform=#{r.defaultPlatform},legacy_null_platform=#{r.legacyNullPlatform},base_dir=#{r.baseDir},upload_ttl_seconds=#{r.uploadTtlSeconds},view_ttl_seconds=#{r.viewTtlSeconds},max_upload_bytes=#{r.maxUploadBytes},version=version+1,modify_date_time=CURRENT_TIMESTAMP WHERE id='PLATFORM' AND scope='PLATFORM' AND version=#{expected}")
    int update(@Param("r")StorageSettingsEntity row,@Param("expected")long expected);
}

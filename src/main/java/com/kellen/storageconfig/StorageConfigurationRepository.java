package com.kellen.storageconfig;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅管理 PLATFORM 全局配置；不修改普通文件查询的租户拦截规则。 */
@Mapper
@InterceptorIgnore(tenantLine="true")
public interface StorageConfigurationRepository {
    @Select("SELECT * FROM ai_storage_configuration WHERE scope='PLATFORM' AND id=#{id} AND is_delete=false")
    StorageConfigurationEntity byId(String id);
    @Select("SELECT * FROM ai_storage_configuration WHERE scope='PLATFORM' AND platform=#{platform} AND is_delete=false")
    StorageConfigurationEntity byPlatform(String platform);
    @Select("SELECT * FROM ai_storage_configuration WHERE scope='PLATFORM' AND is_delete=false ORDER BY create_date_time,id")
    List<StorageConfigurationEntity> all();
    @Insert("INSERT INTO ai_storage_configuration(id,scope,platform,name,provider,endpoint,bucket,region,upload_enabled,key_id,nonce,ciphertext,credential_revision,config_revision,credential_updated_at,version) VALUES(#{id},'PLATFORM',#{platform},#{name},#{provider},#{endpoint},#{bucket},#{region},#{uploadEnabled},#{keyId},#{nonce},#{ciphertext},#{credentialRevision},#{configRevision},#{credentialUpdatedAt},1)")
    int insert(StorageConfigurationEntity row);
    @Update("UPDATE ai_storage_configuration SET name=#{r.name},upload_enabled=#{r.uploadEnabled},key_id=#{r.keyId},nonce=#{r.nonce},ciphertext=#{r.ciphertext},credential_revision=#{r.credentialRevision},config_revision=#{r.configRevision},credential_updated_at=#{r.credentialUpdatedAt},version=version+1,modify_date_time=CURRENT_TIMESTAMP WHERE scope='PLATFORM' AND is_delete=false AND id=#{r.id} AND version=#{expected}")
    int update(@Param("r") StorageConfigurationEntity row,@Param("expected") long expected);
    @Update("UPDATE ai_storage_configuration SET is_delete=true,version=version+1,modify_date_time=CURRENT_TIMESTAMP WHERE scope='PLATFORM' AND is_delete=false AND id=#{id} AND version=#{expected}")
    int delete(@Param("id")String id,@Param("expected")long expected);
    @Select("SELECT COUNT(*) FROM ai_file_object WHERE storage_platform=#{platform} OR (storage_platform IS NULL AND #{platform}=#{legacy})")
    long references(@Param("platform")String platform,@Param("legacy")String legacy);
}

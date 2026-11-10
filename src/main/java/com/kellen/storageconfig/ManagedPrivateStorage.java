package com.kellen.storageconfig;

import com.kellen.storage.PrivateObjectStorageService;
import com.kellen.aliyun.oss.token.OssUploadPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;

/** 生产文件路径只消费 DB registry；缺配置时失败关闭，永不回退 Nacos 云密钥。 */
@Service
public class ManagedPrivateStorage {
    private final StorageConfigurationRepository configs;
    private final StorageSettingsRepository settings;
    private final StorageRuntimeRegistry runtime;
    public ManagedPrivateStorage(StorageConfigurationRepository configs,StorageSettingsRepository settings,StorageRuntimeRegistry runtime){this.configs=configs;this.settings=settings;this.runtime=runtime;}
    @Transactional
    public Upload upload(){
        var s=settings.locked();if(s==null || s.defaultPlatform==null)throw unavailable();
        var row=configs.byPlatform(s.defaultPlatform);if(row==null)throw unavailable();
        if(!row.uploadEnabled)throw new StorageFailure("STORAGE_UPLOAD_DISABLED",409);
        return new Upload(row.platform,s,runtime.acquire(row));
    }
    public URI signedViewUri(String savedPlatform,String objectKey){
        var s=settings.current();if(s==null)throw unavailable();
        String platform=savedPlatform==null || savedPlatform.isBlank()?s.legacyNullPlatform:savedPlatform;
        if(platform==null)throw unavailable();var row=configs.byPlatform(platform);if(row==null)throw unavailable();
        try(var lease=runtime.acquire(row)){return lease.handle().adapter().signedViewUri(platform,objectKey,s.viewTtlSeconds);}
        catch(StorageFailure e){throw e;}catch(Exception ignored){throw new StorageFailure("STORAGE_READ_FAILED",503);}
    }
    private static StorageFailure unavailable(){return new StorageFailure("STORAGE_UNCONFIGURED",503);}
    public static final class Upload implements AutoCloseable {
        private final String platform;
        private final StorageSettingsEntity settings;
        private final StorageRuntimeRegistry.Lease lease;
        Upload(String platform,StorageSettingsEntity settings,StorageRuntimeRegistry.Lease lease){this.platform=platform;this.settings=settings;this.lease=lease;}
        public String platform(){return platform;}
        public long maxUploadBytes(){return settings.maxUploadBytes;}
        public OssUploadPolicy policy(String owner,String type,String mime,String name){
            try{return adapter().createUploadPolicy(owner,type,mime,name,settings.baseDir,settings.uploadTtlSeconds,settings.viewTtlSeconds,settings.maxUploadBytes);}
            catch(Exception ignored){throw new StorageFailure("STORAGE_WRITE_FAILED",503);}
        }
        public void validateOwner(String owner,String key){
            try{adapter().validateObjectOwner(settings.baseDir,owner,key);}catch(Exception ignored){throw new StorageFailure("STORAGE_INVALID_REQUEST",400);}
        }
        public void verifyExists(String key){
            try{adapter().verifyObjectExists(platform,key);}catch(Exception ignored){throw new StorageFailure("STORAGE_READ_FAILED",503);}
        }
        private PrivateObjectStorageService adapter(){return lease.handle().adapter();}
        @Override public void close(){lease.close();}
    }
}

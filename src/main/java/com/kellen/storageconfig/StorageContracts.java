package com.kellen.storageconfig;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 管理 API 的白名单合同；所有结果均没有凭据、密文、对象 key 或签名 URL。 */
public final class StorageContracts {
    private StorageContracts() { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Configuration(String id,String platform,String name,String provider,String endpoint,String bucket,String region,
            boolean uploadEnabled,boolean isDefault,long version,long configRevision,Long appliedRevision,
            String runtimeStatus,boolean credentialsConfigured,Instant credentialUpdatedAt,long referenceCount,LastProbe lastProbe) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastProbe(String requestId,long checkedRevision,Instant finishedAt,String status,String cleanupStatus) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Settings(long version,String defaultPlatform,String legacyNullPlatform,String baseDir,
            long uploadTtlSeconds,long viewTtlSeconds,long maxUploadBytes,boolean privateOnly) { }
    public record Page(List<Configuration> items,int page,int size,long total) { }
    public record Range(long min,long max,long defaultValue) { }
    public record Limits(Range uploadTtlSeconds,Range viewTtlSeconds,Range maxUploadBytes) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Provider(String code,String label,String availability,boolean endpointRequired,boolean regionRequired,String reasonCode) { }
    public record Capabilities(List<Provider> providers,Limits limits) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Step(String name,String status,String failureCode) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(String requestId,String action,String configurationId,String status,Long checkedRevision,
            Instant startedAt,Instant finishedAt,String failureCode,List<Step> steps,String cleanupStatus,Object result) { }
    public record Create(String requestId,String name,String provider,String endpoint,String bucket,String region,
            Boolean uploadEnabled,String credentialAction,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String accessKeyId,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String secretKey,String legacyPlatform) {
        @Override public String toString(){return "Create[REDACTED]";}
        @JsonAnySetter public void unknown(String name,Object value){invalid();}
    }
    public record Update(String requestId,Long expectedVersion,String name,Boolean uploadEnabled,String credentialAction,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String accessKeyId,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String secretKey) {
        @Override public String toString(){return "Update[REDACTED]";}
        @JsonAnySetter public void unknown(String name,Object value){invalid();}
    }
    public record UpdateSettings(String requestId,Long expectedSettingsVersion,String defaultPlatform,
            Long uploadTtlSeconds,Long viewTtlSeconds,Long maxUploadBytes,String legacyNullPlatform,String baseDir) {
        @JsonAnySetter public void unknown(String name,Object value){invalid();}
    }
    public record Probe(String requestId,Long expectedVersion,String mode) {
        @JsonAnySetter public void unknown(String name,Object value){invalid();}
    }
    private static void invalid(){throw new StorageFailure("STORAGE_INVALID_REQUEST",400);}
}

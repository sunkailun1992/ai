package com.kellen.storageconfig;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** 控制台输入白名单。网络地址只能是批准的 origin，禁止签名参数与用户信息。 */
public final class StorageValidation {
    private final Set<String> allowedOrigins;
    public StorageValidation(Set<String> allowedOrigins) { this.allowedOrigins=Set.copyOf(allowedOrigins); }
    public record Location(String provider,String endpoint,String bucket,String region) { }
    public Location location(String provider,String endpoint,String bucket,String region) {
        if (provider==null || !Set.of("MINIO","S3_COMPATIBLE","ALIYUN_OSS","TENCENT_COS").contains(provider))
            throw new StorageFailure("STORAGE_PROVIDER_UNAVAILABLE",400);
        if (bucket==null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")) invalid();
        if (region!=null && !region.isBlank() && !region.matches("[a-z0-9-]{2,64}")) invalid();
        region=region==null || region.isBlank()?null:region;
        if (provider.equals("TENCENT_COS")) {
            if (region==null) invalid();
            String derived="https://"+bucket+".cos."+region+".myqcloud.com";
            if (endpoint!=null && !endpoint.isBlank() && !origin(endpoint).equals(derived)) denied();
            endpoint=derived;
        } else {
            endpoint=origin(endpoint);
            if (provider.equals("ALIYUN_OSS")) {
                if (!endpoint.matches("https://oss-[a-z0-9-]+\\.aliyuncs\\.com")) denied();
            } else if (!allowedOrigins.contains(endpoint)) denied();
        }
        return new Location(provider,endpoint,bucket,region);
    }
    public static String origin(String value) {
        try {
            var u=URI.create(value);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost()==null
                    || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
                    || !(u.getRawPath().isEmpty() || "/".equals(u.getRawPath()))
                    || u.getPort()==0 || u.getPort()>65535) denied();
            return u.getScheme()+"://"+u.getRawAuthority();
        } catch (StorageFailure e) { throw e; }
        catch (Exception ignored) { throw new StorageFailure("STORAGE_ENDPOINT_NOT_ALLOWED",400); }
    }
    public void credentials(String action,String id,String secret,boolean create) {
        if ("KEEP".equals(action) && !create && id==null && secret==null) return;
        if (!"REPLACE".equals(action) || !secretText(id) || !secretText(secret))
            throw new StorageFailure("STORAGE_CREDENTIAL_REQUIRED",400);
    }
    private boolean secretText(String value) {
        return value!=null && !value.isBlank() && value.length()<=4096
                && !value.contains("***") && value.codePoints().noneMatch(Character::isISOControl);
    }
    public void limits(long upload,long view,long bytes) {
        if (upload<60 || upload>3600 || view<60 || view>3600 || bytes<1 || bytes>52428800) invalid();
    }
    public static String name(String value) {
        if(value==null || value.isBlank() || value.length()>80 || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value.trim();
    }
    public static String requestId(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) invalid(); return value; }
        catch (Exception ignored) { throw new StorageFailure("STORAGE_INVALID_REQUEST",400); }
    }
    public static String platform(String value) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) invalid(); return value;
    }
    public static String baseDir(String value) {
        if(value==null || value.isBlank() || value.length()>200 || value.startsWith("/") || value.endsWith("/")
                || value.contains("..") || value.contains("//") || value.contains("\\")
                || value.codePoints().anyMatch(Character::isISOControl)) invalid();
        return value;
    }
    private static void denied() { throw new StorageFailure("STORAGE_ENDPOINT_NOT_ALLOWED",400); }
    private static void invalid() { throw new StorageFailure("STORAGE_INVALID_REQUEST",400); }
}

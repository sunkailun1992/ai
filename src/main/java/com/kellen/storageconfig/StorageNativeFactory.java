package com.kellen.storageconfig;

import com.kellen.storage.PrivateObjectStorageService;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.http.HttpProtocol;
import io.minio.MinioClient;
import org.dromara.x.file.storage.core.*;
import org.dromara.x.file.storage.core.platform.FileStorageClientFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** XFS 官方原生客户端 registry；只构建句柄，不自动访问云端或测试桶权限。 */
@Component
public class StorageNativeFactory implements StorageRuntimeRegistry.Factory {
    @Override public StorageRuntimeRegistry.Handle open(StorageConfigurationEntity row,StorageCredentials credentials) {
        var properties=new FileStorageProperties();properties.setDefaultPlatform(row.platform);
        FileStorageClientFactory<?> clientFactory;
        switch(row.provider) {
            case "MINIO", "S3_COMPATIBLE" -> {
                var c=new FileStorageProperties.MinioConfig();c.setPlatform(row.platform);c.setEndPoint(row.endpoint);
                c.setBucketName(row.bucket);c.setAccessKey(credentials.accessKeyId());c.setSecretKey(credentials.secretKey());c.setBasePath("");
                properties.setMinio(List.of(c));
                var http=new okhttp3.OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
                        .writeTimeout(5,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
                var builder=MinioClient.builder().endpoint(row.endpoint).credentials(credentials.accessKeyId(),credentials.secretKey()).httpClient(http);
                if(row.region!=null)builder.region(row.region);
                var client=builder.build();
                clientFactory=new FileStorageClientFactory<MinioClient>() {
                    public String getPlatform(){return row.platform;}
                    public MinioClient getClient(){return client;}
                    public void close(){try{client.close();}catch(Exception ignored){}http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
                };
            }
            case "ALIYUN_OSS" -> {
                var c=new FileStorageProperties.AliyunOssConfig();c.setPlatform(row.platform);c.setEndPoint(row.endpoint);
                c.setBucketName(row.bucket);c.setAccessKey(credentials.accessKeyId());c.setSecretKey(credentials.secretKey());c.setBasePath("");
                properties.setAliyunOss(List.of(c));
                var options=new ClientBuilderConfiguration();options.setConnectionTimeout(3000);options.setSocketTimeout(5000);options.setMaxErrorRetry(0);
                OSS client=new OSSClientBuilder().build(row.endpoint,credentials.accessKeyId(),credentials.secretKey(),options);
                clientFactory=new FileStorageClientFactory<OSS>() {
                    public String getPlatform(){return row.platform;}
                    public OSS getClient(){return client;}
                    public void close(){client.shutdown();}
                };
            }
            case "TENCENT_COS" -> {
                var c=new FileStorageProperties.TencentCosConfig();c.setPlatform(row.platform);c.setRegion(row.region);c.setBucketName(row.bucket);
                c.setSecretId(credentials.accessKeyId());c.setSecretKey(credentials.secretKey());c.setBasePath("");properties.setTencentCos(List.of(c));
                var options=new ClientConfig(new Region(row.region));options.setHttpProtocol(HttpProtocol.https);options.setConnectionTimeout(3000);
                options.setSocketTimeout(5000);options.setMaxErrorRetry(0);options.setChangeEndpointRetry(false);
                var endpoint=URI.create(row.endpoint);
                options.setHttpProtocol("http".equals(endpoint.getScheme())?HttpProtocol.http:HttpProtocol.https);
                options.setEndpointBuilder(new com.qcloud.cos.endpoint.EndpointBuilder(){
                    public String buildGeneralApiEndpoint(String bucket){return endpoint.getRawAuthority();}
                    public String buildGetServiceApiEndpoint(){throw new StorageFailure("STORAGE_PROVIDER_UNAVAILABLE",400);}
                });
                var client=new COSClient(new BasicCOSCredentials(credentials.accessKeyId(),credentials.secretKey()),options);
                clientFactory=new FileStorageClientFactory<COSClient>() {
                    public String getPlatform(){return row.platform;}
                    public COSClient getClient(){return client;}
                    public void close(){client.shutdown();}
                };
            }
            default -> throw new StorageFailure("STORAGE_PROVIDER_UNAVAILABLE",400);
        }
        try {
            var builder=FileStorageServiceBuilder.create(properties).useDefault();builder.getClientFactoryList().add(List.of(clientFactory));
            return new NativeHandle(row,builder.build());
        } catch(Exception ignored) {try{clientFactory.close();}catch(Exception suppressed){}throw new StorageFailure("STORAGE_APPLY_FAILED",503);}
    }
    private static final class NativeHandle implements StorageRuntimeRegistry.Handle {
        private final StorageConfigurationEntity row;
        private final FileStorageService files;
        private final PrivateObjectStorageService adapter;
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        NativeHandle(StorageConfigurationEntity row,FileStorageService files){this.row=row;this.files=files;this.adapter=new PrivateObjectStorageService(files);}
        public PrivateObjectStorageService adapter(){return adapter;}
        public void write(String key,byte[] bytes){
            try{adapter.putObject(key,"application/octet-stream",bytes.length,new ByteArrayInputStream(bytes));}
            catch(Exception ignored){throw new StorageFailure("STORAGE_OUTCOME_UNKNOWN",503);}
        }
        public boolean exists(String key){try{return files.exists(info(key));}catch(Exception ignored){throw unknown();}}
        public byte[] read(String key){return fetch(adapter.signedViewUri(key,60),false);}
        public boolean anonymousDenied(String key){
            String host=switch(row.provider){
                case "ALIYUN_OSS" -> {
                    var u=URI.create(row.endpoint);
                    // 原生 OSS 对 IP endpoint 使用 path-style；云端生产地址仍是固定官方域名。
                    if(u.getHost().matches("[0-9.]+") || u.getHost().equals("localhost"))yield row.endpoint+"/"+row.bucket;
                    yield u.getScheme()+"://"+row.bucket+"."+u.getRawAuthority();
                }
                case "TENCENT_COS" -> row.endpoint;
                default -> row.endpoint+"/"+row.bucket;
            };
            return fetch(URI.create(host+"/"+key),true)==null;
        }
        private byte[] fetch(URI uri,boolean anonymous){
            try{
                var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var body=response.body()){
                    if(anonymous){
                        if(response.statusCode()==401 || response.statusCode()==403)return null;
                        if(response.statusCode()==200 || response.statusCode()==206)throw new StorageFailure("STORAGE_PRIVATE_ACCESS_ALLOWED",409);
                        throw unknown();
                    }
                    if(response.statusCode()!=200)throw new StorageFailure("STORAGE_READ_FAILED",503);
                    byte[] bytes=body.readNBytes(1025);if(bytes.length>1024)throw new StorageFailure("STORAGE_READ_FAILED",503);return bytes;
                }
            }catch(StorageFailure e){throw e;}catch(Exception ignored){throw unknown();}
        }
        public void delete(String key){try{if(!files.delete(info(key)))throw unknown();}catch(Exception ignored){throw unknown();}}
        private FileInfo info(String key){int slash=key.lastIndexOf('/');return new FileInfo().setPlatform(row.platform).setBasePath("")
                .setPath(slash<0?"":key.substring(0,slash+1)).setFilename(key.substring(slash+1));}
        public void close(){try{files.getFileStorage(row.platform).close();}catch(Exception ignored){/* 不输出 SDK 异常。 */}}
        private static StorageFailure unknown(){return new StorageFailure("STORAGE_OUTCOME_UNKNOWN",503);}
    }
}

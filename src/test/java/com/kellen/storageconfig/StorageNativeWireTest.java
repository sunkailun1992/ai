package com.kellen.storageconfig;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/** 四种官方 SDK/XFS 句柄仅连接自己的回环 HTTP，实际验证读写、匿名拒绝与删除。 */
class StorageNativeWireTest {
    @ParameterizedTest @ValueSource(strings={"MINIO","S3_COMPATIBLE","ALIYUN_OSS","TENCENT_COS"})
    void sdkProbeExecutesOneWriteAndPreciseCleanup(String provider) throws Exception {
        Map<String,byte[]> objects=new ConcurrentHashMap<>();List<String> methods=new CopyOnWriteArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            String method=e.getRequestMethod(),path=e.getRequestURI().getPath();methods.add(method);
            boolean signed=e.getRequestHeaders().getFirst("Authorization")!=null || e.getRequestURI().getRawQuery()!=null;
            byte[] body=new byte[0];int status=200;
            if(method.equals("PUT")){objects.put(path,e.getRequestBody().readAllBytes());}
            else if(method.equals("DELETE")){objects.remove(path);status=204;}
            else if(!signed && method.equals("GET")){status=403;}
            else if(!objects.containsKey(path)){status=404;}
            else {body=objects.get(path);}
            try {e.getResponseHeaders().set("ETag","\""+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(objects.getOrDefault(path,new byte[0])))+"\"");}
            catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            e.getResponseHeaders().set("Last-Modified","Tue, 10 Nov 2026 00:00:00 GMT");
            e.getResponseHeaders().set("Content-Type","application/octet-stream");
            if(method.equals("HEAD")){e.getResponseHeaders().set("Content-Length",Integer.toString(body.length));e.sendResponseHeaders(status,-1);}
            else if(status>=400){byte[] error=("<Error><Code>"+(status==403?"AccessDenied":"NoSuchKey")+"</Code><Message>Synthetic</Message><RequestId>fixture</RequestId><HostId>fixture</HostId></Error>").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/xml");e.sendResponseHeaders(status,error.length);e.getResponseBody().write(error);}
            else if(body.length==0){e.sendResponseHeaders(status,-1);}
            else {e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);}
            e.close();
        });server.start();
        var row=new StorageConfigurationEntity();row.platform="wire-fixture";row.provider=provider;row.endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        row.bucket=provider.equals("TENCENT_COS")?"fixture-1250000000":"fixture-bucket";row.region="us-east-1";
        String key="_fleet-storage-probe/fixture-config/fixture-request.bin";
        try(var handle=new StorageNativeFactory().open(row,new StorageCredentials("synthetic-access","synthetic-secret"))){
            // 任何 SDK 地址失配必须在首次网络 I/O 前失败，不能意外访问真实云。
            var signed=handle.adapter().signedViewUri(key,60);
            assertThat(signed.getHost()).isEqualTo("127.0.0.1");assertThat(signed.getPort()).isEqualTo(server.getAddress().getPort());
            List<StorageContracts.Operation> events=new ArrayList<>();
            new StorageProbeRunner().run(handle,key,"fixture-request",1,Instant.now(),events::add);
            assertThat(events.get(events.size()-1).status()).as(provider+" probe "+events.get(events.size()-1).steps()).isEqualTo("SUCCEEDED");
            assertThat(events.get(events.size()-1).cleanupStatus()).isEqualTo("SUCCEEDED");
            assertThat(methods.stream().filter(m->m.equals("PUT")).count()).isEqualTo(1);
            assertThat(methods.stream().filter(m->m.equals("DELETE")).count()).isEqualTo(1);
            assertThat(objects).isEmpty();
        } finally{server.stop(0);}
    }
}

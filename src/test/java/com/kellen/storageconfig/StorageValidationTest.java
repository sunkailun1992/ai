package com.kellen.storageconfig;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class StorageValidationTest {
    private final StorageValidation rules = new StorageValidation(Set.of("http://127.0.0.1:19000"));
    @Test void endpointsAreOriginsAndOnlyApprovedPrivateOriginsAreAccepted() {
        assertThat(rules.location("MINIO","http://127.0.0.1:19000/","private-bucket",null).endpoint())
                .isEqualTo("http://127.0.0.1:19000");
        for (String endpoint : new String[]{"http://127.0.0.1:19001","http://user:secret@127.0.0.1:19000",
                "http://127.0.0.1:19000/?signed=secret","http://127.0.0.1:19000/#secret","http://127.0.0.1:19000/path"})
            assertThatThrownBy(() -> rules.location("MINIO",endpoint,"private-bucket",null)).isInstanceOf(StorageFailure.class);
    }
    @Test void cloudLocationIsFixedAndUnsupportedProvidersFailClosed() {
        assertThat(rules.location("TENCENT_COS",null,"fixture-1250000000","ap-singapore").endpoint())
                .isEqualTo("https://fixture-1250000000.cos.ap-singapore.myqcloud.com");
        assertThat(rules.location("ALIYUN_OSS","https://oss-cn-hangzhou.aliyuncs.com","fixture-bucket",null).provider())
                .isEqualTo("ALIYUN_OSS");
        assertThatThrownBy(() -> rules.location("ALIYUN_OSS","http://oss-cn-hangzhou.aliyuncs.com","fixture-bucket",null)).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(() -> rules.location("OTHER",null,"fixture-bucket",null)).isInstanceOf(StorageFailure.class);
    }
    @Test void ttlAndCredentialModesRejectAmbiguousMaskedOrPartialChanges() {
        rules.limits(60,3600,52428800);
        for(long ttl : new long[]{0,59,3601}) assertThatThrownBy(() -> rules.limits(ttl,1800,10)).isInstanceOf(StorageFailure.class);
        rules.credentials("KEEP",null,null,false);
        rules.credentials("REPLACE","synthetic-access","synthetic-secret",true);
        assertThatThrownBy(() -> rules.credentials("KEEP","",null,false)).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(() -> rules.credentials("REPLACE","synthetic",null,false)).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(() -> rules.credentials("REPLACE","******","******",false)).isInstanceOf(StorageFailure.class);
    }
}

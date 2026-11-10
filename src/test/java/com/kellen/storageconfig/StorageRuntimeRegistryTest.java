package com.kellen.storageconfig;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StorageRuntimeRegistryTest {
    @Test void rotationRetainsLeasedHandleAndLateRevisionCannotReplaceNewerOne() {
        var cipher=new StorageCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]),"fixture");
        List<FakeHandle> opened=new ArrayList<>();
        var registry=new StorageRuntimeRegistry(cipher,(r,c)->{var h=new FakeHandle();opened.add(h);return h;});
        var first=row(cipher,1);var second=row(cipher,2);
        var lease=registry.acquire(first); assertThat(registry.appliedRevision(first.platform)).isEqualTo(1);
        try(var newer=registry.acquire(second)){assertThat(registry.appliedRevision(first.platform)).isEqualTo(2);}
        assertThat(opened.get(0).closed).isFalse();
        assertThatThrownBy(()->registry.acquire(first)).isInstanceOf(StorageFailure.class);
        lease.close();assertThat(opened.get(0).closed).isTrue();
        registry.close();assertThat(opened.get(1).closed).isTrue();
    }
    @Test void failedReplacementRetainsOldHandleAndNeverClaimsNewRevisionApplied() {
        var cipher=new StorageCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]),"fixture");
        var old=new FakeHandle();var registry=new StorageRuntimeRegistry(cipher,(r,c)->{if(r.configRevision==2)throw new IllegalStateException("private");return old;});
        try(var lease=registry.acquire(row(cipher,1))) { }
        assertThatThrownBy(()->registry.acquire(row(cipher,2))).isInstanceOf(StorageFailure.class).hasNoCause();
        assertThat(registry.appliedRevision("fixture-platform")).isEqualTo(1);assertThat(old.closed).isFalse();
        registry.close();
    }
    private StorageConfigurationEntity row(StorageCredentialCipher c,long revision){
        var r=new StorageConfigurationEntity();r.id="fixture-id";r.platform="fixture-platform";r.provider="MINIO";
        r.configRevision=revision;r.credentialRevision=revision;
        var encrypted=c.encrypt("PLATFORM/"+r.id+"/"+r.provider+"/"+revision,new StorageCredentials("synthetic","synthetic"));
        r.keyId=encrypted.keyId();r.nonce=encrypted.nonce();r.ciphertext=encrypted.ciphertext();return r;
    }
    static class FakeHandle implements StorageRuntimeRegistry.Handle {
        boolean closed;
        public com.kellen.storage.PrivateObjectStorageService adapter(){return null;}
        public void write(String key,byte[] bytes) { }
        public boolean exists(String key){return true;}
        public byte[] read(String key){return new byte[0];}
        public boolean anonymousDenied(String key){return true;}
        public void delete(String key) { }
        public void close(){closed=true;}
    }
}

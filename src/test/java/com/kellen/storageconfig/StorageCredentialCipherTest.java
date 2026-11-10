package com.kellen.storageconfig;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageCredentialCipherTest {
    private StorageCredentialCipher cipher() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        return new StorageCredentialCipher(Base64.getEncoder().encodeToString(key), "fixture-v1");
    }

    @Test void authenticatedEncryptionUsesRandomNonceAndPreservesOnlyTheCorrectBinding() {
        var cipher = cipher();
        var credentials = new StorageCredentials("synthetic-access", "synthetic-storage-secret");
        var first = cipher.encrypt("PLATFORM/config-1/MINIO/1", credentials);
        var second = cipher.encrypt("PLATFORM/config-1/MINIO/1", credentials);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).doesNotContain("synthetic-access", "synthetic-storage-secret");
        var decoded = cipher.decrypt("PLATFORM/config-1/MINIO/1", first);
        assertThat(decoded.accessKeyId()).isEqualTo("synthetic-access");
        assertThat(decoded.secretKey()).isEqualTo("synthetic-storage-secret");
        assertThat(decoded.toString()).doesNotContain("synthetic");
        assertThatThrownBy(() -> cipher.decrypt("PLATFORM/config-2/MINIO/1", first))
                .isInstanceOf(StorageFailure.class).hasMessageNotContaining("synthetic");
    }

    @Test void wrongKeyTamperingAndUnknownKeyIdFailClosedWithoutPrivateCause() {
        var cipher = cipher();
        var encrypted = cipher.encrypt("PLATFORM/config-1/COS/1", new StorageCredentials("synthetic-access", "synthetic-secret"));
        assertThatThrownBy(() -> cipher().decrypt("PLATFORM/config-1/COS/1", encrypted))
                .isInstanceOf(StorageFailure.class).hasNoCause();
        var changed = new StorageCredentialCipher.EncryptedCredentials(encrypted.keyId(), encrypted.nonce(), "AAAA");
        assertThatThrownBy(() -> cipher.decrypt("PLATFORM/config-1/COS/1", changed))
                .isInstanceOf(StorageFailure.class).hasNoCause();
        var wrongId = new StorageCredentialCipher.EncryptedCredentials("other-key", encrypted.nonce(), encrypted.ciphertext());
        assertThatThrownBy(() -> cipher.decrypt("PLATFORM/config-1/COS/1", wrongId))
                .isInstanceOf(StorageFailure.class).hasNoCause();
    }

    @Test void missingOrInvalidMasterKeepsTheFeatureUnconfiguredWithoutWeakFallback() {
        for (String value : new String[]{"", "not-base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            var cipher = new StorageCredentialCipher(value, "v1");
            assertThat(cipher.configured()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("fixture", new StorageCredentials("synthetic", "synthetic")))
                    .isInstanceOf(StorageFailure.class).hasNoCause();
        }
    }

    @Test void idempotencyFingerprintIsKeyedAndDomainSeparatedFromEncryption() {
        var cipher = cipher(); byte[] input = "synthetic-request-with-secret".getBytes(StandardCharsets.UTF_8);
        assertThat(cipher.fingerprint(input)).isEqualTo(cipher.fingerprint(input)).hasSize(64);
        assertThat(cipher.fingerprint(input)).isNotEqualTo(cipher().fingerprint(input));
        assertThat(cipher.toString()).doesNotContain("key=");
    }
}

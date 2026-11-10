package com.kellen.storageconfig;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** 独立 OS 环境主密钥；AES-GCM 的 AAD 将密文绑定到配置及凭据版本。 */
public final class StorageCredentialCipher {
    private final byte[] key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();
    public StorageCredentialCipher(String base64Key, String keyId) {
        byte[] decoded = null;
        try { decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key); }
        catch (IllegalArgumentException ignored) { /* 无效配置保持 fail closed，不阻止其它业务启动。 */ }
        this.key = decoded != null && decoded.length == 32 ? decoded : null;
        this.keyId = keyId == null || keyId.isBlank() ? "v1" : keyId;
    }
    public boolean configured() { return key != null; }
    public EncryptedCredentials encrypt(String binding, StorageCredentials credentials) {
        requireKey();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF(credentials.accessKeyId()); out.writeUTF(credentials.secretKey());
            }
            byte[] plain = bytes.toByteArray();
            try {
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
                cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
                return new EncryptedCredentials(keyId, Base64.getEncoder().encodeToString(nonce),
                        Base64.getEncoder().encodeToString(cipher.doFinal(plain)));
            } finally { Arrays.fill(plain,(byte)0); }
        } catch (Exception ignored) { throw unavailable(); }
    }
    public StorageCredentials decrypt(String binding, EncryptedCredentials value) {
        requireKey();
        try {
            if (!keyId.equals(value.keyId())) throw unavailable();
            byte[] nonce=Base64.getDecoder().decode(value.nonce());
            if (nonce.length!=12) throw unavailable();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] plain=cipher.doFinal(Base64.getDecoder().decode(value.ciphertext()));
            try (var in = new DataInputStream(new ByteArrayInputStream(plain))) {
                var result = new StorageCredentials(in.readUTF(),in.readUTF());
                if (in.available()!=0) throw unavailable();
                return result;
            } finally { Arrays.fill(plain,(byte)0); }
        } catch (Exception ignored) { throw unavailable(); }
    }
    public String fingerprint(byte[] canonicalRequest) {
        requireKey();
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] derived=mac.doFinal("ai-storage/request-fingerprint/v1".getBytes(StandardCharsets.UTF_8));
            try { mac.init(new SecretKeySpec(derived,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(canonicalRequest)); }
            finally { Arrays.fill(derived,(byte)0); }
        } catch (Exception ignored) { throw unavailable(); }
    }
    private void requireKey() { if (!configured()) throw unavailable(); }
    private static StorageFailure unavailable() { return new StorageFailure("STORAGE_KEY_UNAVAILABLE",503); }
    public record EncryptedCredentials(String keyId, String nonce, String ciphertext) {
        @Override public String toString() { return "EncryptedCredentials[REDACTED]"; }
    }
}

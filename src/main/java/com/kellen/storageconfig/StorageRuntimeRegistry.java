package com.kellen.storageconfig;

import com.kellen.storage.PrivateObjectStorageService;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 每个不可变 revision 独立客户端；租约结束后才关闭退役句柄。装载不发探针。 */
@Service
public class StorageRuntimeRegistry implements AutoCloseable {
    public interface Handle extends AutoCloseable {
        PrivateObjectStorageService adapter();
        void write(String key,byte[] bytes);
        boolean exists(String key);
        byte[] read(String key);
        boolean anonymousDenied(String key);
        void delete(String key);
        @Override void close();
    }
    @FunctionalInterface public interface Factory { Handle open(StorageConfigurationEntity config,StorageCredentials credentials); }
    private final StorageCredentialCipher cipher;
    private final Factory factory;
    private final Map<String,Entry> current=new HashMap<>();
    private final Map<String,Long> failed=new HashMap<>();
    private boolean closed;
    public StorageRuntimeRegistry(StorageCredentialCipher cipher,Factory factory){this.cipher=cipher;this.factory=factory;}
    public synchronized Lease acquire(StorageConfigurationEntity row) {
        if(closed)throw new StorageFailure("STORAGE_UNCONFIGURED",503);
        Entry entry=current.get(row.platform);
        if(entry!=null && entry.revision>row.configRevision)throw new StorageFailure("STORAGE_VERSION_CONFLICT",409);
        if(entry==null || entry.revision<row.configRevision){
            Handle handle;
            try { handle=factory.open(row,cipher.decrypt(binding(row),new StorageCredentialCipher.EncryptedCredentials(row.keyId,row.nonce,row.ciphertext))); }
            catch(Exception ignored){failed.put(row.platform,row.configRevision);throw new StorageFailure("STORAGE_APPLY_FAILED",503);}
            var next=new Entry(row.configRevision,handle);
            current.put(row.platform,next);failed.remove(row.platform);
            if(entry!=null){entry.retired=true;disposeIfIdle(entry);}
            entry=next;
        }
        entry.leases++;return new Lease(entry);
    }
    public synchronized Long appliedRevision(String platform){var e=current.get(platform);return e==null?null:e.revision;}
    public synchronized boolean failed(String platform,long revision){return Objects.equals(failed.get(platform),revision);}
    public synchronized void retire(String platform){failed.remove(platform);var e=current.remove(platform);if(e!=null){e.retired=true;disposeIfIdle(e);}}
    public static String binding(StorageConfigurationEntity row){return "PLATFORM/"+row.id+"/"+row.provider+"/"+row.credentialRevision;}
    @PreDestroy @Override public synchronized void close(){closed=true;for(var e:current.values()){e.retired=true;disposeIfIdle(e);}current.clear();}
    private void disposeIfIdle(Entry e){if(e.retired && e.leases==0 && !e.closed){e.closed=true;try{e.handle.close();}catch(Exception ignored){/* SDK 原文不得进入日志。 */}}}
    private static final class Entry { final long revision;final Handle handle;int leases;boolean retired,closed;
        Entry(long revision,Handle handle){this.revision=revision;this.handle=handle;}}
    public final class Lease implements AutoCloseable {
        private Entry entry;
        private Lease(Entry entry){this.entry=entry;}
        public Handle handle(){synchronized(StorageRuntimeRegistry.this){if(entry==null)throw new StorageFailure("STORAGE_UNCONFIGURED",503);return entry.handle;}}
        @Override public void close(){synchronized(StorageRuntimeRegistry.this){if(entry!=null){var e=entry;entry=null;e.leases--;disposeIfIdle(e);}}}
    }
}

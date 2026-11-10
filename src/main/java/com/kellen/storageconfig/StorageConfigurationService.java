package com.kellen.storageconfig;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.kellen.storageconfig.StorageContracts.*;

/** 全局存储配置的事务边界。外部写只来自显式探针，意图落库后才执行一次。 */
@Service
public class StorageConfigurationService implements AutoCloseable {
    private final StorageConfigurationRepository configs;
    private final StorageSettingsRepository settings;
    private final StorageOperationRepository operations;
    private final StorageCredentialCipher cipher;
    private final StorageValidation validation;
    private final StorageRuntimeRegistry runtime;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Set<String> owned=ConcurrentHashMap.newKeySet();
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"storage-owned-probe");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public StorageConfigurationService(StorageConfigurationRepository configs,StorageSettingsRepository settings,
            StorageOperationRepository operations,StorageCredentialCipher cipher,StorageValidation validation,
            StorageRuntimeRegistry runtime,PlatformTransactionManager manager,ObjectMapper json) {
        this.configs=configs;this.settings=settings;this.operations=operations;this.cipher=cipher;this.validation=validation;
        this.runtime=runtime;this.tx=new TransactionTemplate(manager);this.json=json;
    }
    public Capabilities capabilities(){return new Capabilities(List.of(
            new Provider("MINIO","MinIO","AVAILABLE",true,false,null),
            new Provider("ALIYUN_OSS","阿里云 OSS","AVAILABLE",true,false,null),
            new Provider("TENCENT_COS","腾讯云 COS","AVAILABLE",false,true,null),
            new Provider("S3_COMPATIBLE","S3 兼容存储","AVAILABLE",true,false,null)),
            new Limits(new Range(60,3600,60),new Range(60,3600,1800),new Range(1,52428800,52428800)));}
    public Page list(int page,int size,String search,String provider,Boolean enabled){
        if(page<1 || size<1 || size>100 || search!=null && search.length()>100)throw invalid();
        var filtered=configs.all().stream().filter(r->search==null || search.isBlank() || r.name.contains(search) || r.platform.contains(search))
                .filter(r->provider==null || provider.isBlank() || provider.equals(r.provider))
                .filter(r->enabled==null || enabled==r.uploadEnabled).toList();
        long start=(long)(page-1)*size;
        var selected=start>=filtered.size()?List.<StorageConfigurationEntity>of():filtered.subList((int)start,(int)Math.min(start+size,filtered.size()));
        return new Page(selected.stream().map(r->{apply(r);return project(r);}).toList(),page,size,filtered.size());
    }
    public Configuration configuration(String id){var r=required(id);apply(r);return project(r);}
    public Settings settings(){return project(requiredSettings());}
    public Operation create(String actor,Create request){
        if(request==null)throw invalid();
        return mutate(actor,request.requestId(),"CREATE",null,canonical(request.name(),request.provider(),request.endpoint(),request.bucket(),request.region(),request.uploadEnabled(),request.credentialAction(),request.accessKeyId(),request.secretKey(),request.legacyPlatform()),()->{
            String name=StorageValidation.name(request.name());
            validation.credentials(request.credentialAction(),request.accessKeyId(),request.secretKey(),true);
            if(request.uploadEnabled()==null)throw invalid();
            var location=validation.location(request.provider(),request.endpoint(),request.bucket(),request.region());
            var r=new StorageConfigurationEntity();r.id=UUID.randomUUID().toString();
            r.platform=request.legacyPlatform()==null?"storage-"+r.id:StorageValidation.platform(request.legacyPlatform());
            if(configs.byPlatform(r.platform)!=null)throw new StorageFailure("STORAGE_LOCATION_IMMUTABLE",409);
            r.name=name;r.provider=location.provider();r.endpoint=location.endpoint();r.bucket=location.bucket();r.region=location.region();
            r.uploadEnabled=request.uploadEnabled();r.version=1;r.configRevision=1;r.credentialRevision=1;
            encrypt(r,request.accessKeyId(),request.secretKey());configs.insert(r);return project(r);
        });
    }
    public Operation update(String actor,String id,Update request){
        if(request==null)throw invalid();
        return mutate(actor,request.requestId(),"UPDATE",id,canonical(request.expectedVersion(),request.name(),request.uploadEnabled(),request.credentialAction(),request.accessKeyId(),request.secretKey()),()->{
            var r=required(id);expected(r,request.expectedVersion());
            if(operations.activeProbes(id)>0)throw new StorageFailure("STORAGE_OPERATION_IN_PROGRESS",409);
            validation.credentials(request.credentialAction(),request.accessKeyId(),request.secretKey(),false);
            if(request.uploadEnabled()==null)throw invalid();
            boolean changed=r.uploadEnabled!=request.uploadEnabled() || "REPLACE".equals(request.credentialAction());
            r.name=StorageValidation.name(request.name());r.uploadEnabled=request.uploadEnabled();
            if("REPLACE".equals(request.credentialAction())){r.credentialRevision++;encrypt(r,request.accessKeyId(),request.secretKey());}
            if(changed)r.configRevision++;
            if(configs.update(r,r.version)!=1)throw new StorageFailure("STORAGE_VERSION_CONFLICT",409);
            r.version++;return project(r);
        });
    }
    public Operation delete(String actor,String id,String requestId,Long version){
        String removedPlatform=configs.byId(id)==null?null:configs.byId(id).platform;
        var op=mutate(actor,requestId,"DELETE",id,canonical(version),()->{
            var r=required(id);expected(r,version);var s=requiredSettings();
            if(r.platform.equals(s.defaultPlatform) || r.platform.equals(s.legacyNullPlatform))throw new StorageFailure("STORAGE_DEFAULT_CONFIG",409);
            if(configs.references(r.platform,s.legacyNullPlatform)>0)throw new StorageFailure("STORAGE_CONFIG_REFERENCED",409);
            if(operations.activeProbes(id)>0)throw new StorageFailure("STORAGE_OPERATION_IN_PROGRESS",409);
            if(configs.delete(id,r.version)!=1)throw new StorageFailure("STORAGE_VERSION_CONFLICT",409);
            return null;
        });
        if(op.status().equals("SUCCEEDED")){var all=configs.all();if(all.stream().noneMatch(r->r.id.equals(id)))runtime.retire(removedPlatform);}
        return op;
    }
    public Operation updateSettings(String actor,UpdateSettings request){
        if(request==null)throw invalid();
        return mutate(actor,request.requestId(),"UPDATE_SETTINGS",null,canonical(request.expectedSettingsVersion(),request.defaultPlatform(),request.uploadTtlSeconds(),request.viewTtlSeconds(),request.maxUploadBytes(),request.legacyNullPlatform(),request.baseDir()),()->{
            var s=requiredSettings();
            if(request.expectedSettingsVersion()==null || request.expectedSettingsVersion()!=s.version)throw new StorageFailure("STORAGE_SETTINGS_VERSION_CONFLICT",409);
            if(request.uploadTtlSeconds()==null || request.viewTtlSeconds()==null || request.maxUploadBytes()==null)throw invalid();
            validation.limits(request.uploadTtlSeconds(),request.viewTtlSeconds(),request.maxUploadBytes());
            var target=configs.byPlatform(StorageValidation.platform(request.defaultPlatform()));
            if(target==null)throw new StorageFailure("STORAGE_CONFIG_NOT_FOUND",404);
            if(!target.uploadEnabled)throw new StorageFailure("STORAGE_UPLOAD_DISABLED",409);
            if(s.version==0){
                if(request.legacyNullPlatform()!=null){if(configs.byPlatform(StorageValidation.platform(request.legacyNullPlatform()))==null)throw new StorageFailure("STORAGE_CONFIG_NOT_FOUND",404);s.legacyNullPlatform=request.legacyNullPlatform();}
                if(request.baseDir()!=null)s.baseDir=StorageValidation.baseDir(request.baseDir());
            }else if(request.legacyNullPlatform()!=null && !Objects.equals(request.legacyNullPlatform(),s.legacyNullPlatform)
                    || request.baseDir()!=null && !Objects.equals(request.baseDir(),s.baseDir))throw new StorageFailure("STORAGE_LOCATION_IMMUTABLE",409);
            s.defaultPlatform=target.platform;s.uploadTtlSeconds=request.uploadTtlSeconds();s.viewTtlSeconds=request.viewTtlSeconds();s.maxUploadBytes=request.maxUploadBytes();
            if(settings.update(s,s.version)!=1)throw new StorageFailure("STORAGE_SETTINGS_VERSION_CONFLICT",409);
            s.version++;return project(s);
        });
    }
    private Operation mutate(String actor,String requestId,String action,String id,byte[] request,Supplier<Object> change){
        StorageValidation.requestId(requestId);String fingerprint=fingerprint(action,id,request);
        try{return tx.execute(status->{
            settings.locked();var old=operations.get(actor,requestId);if(old!=null)return reused(old,fingerprint);
            var row=newOperation(actor,requestId,action,id,fingerprint,"RUNNING",null);
            operations.insert(row);Object result=change.get();
            if(result instanceof Configuration c)row.configurationId=c.id();
            var op=new Operation(requestId,action,row.configurationId,"SUCCEEDED",null,row.startedAt,Instant.now(),null,List.of(),"NOT_NEEDED",result);
            save(row,op);return op;
        });}catch(DuplicateKeyException ignored){
            var old=operations.get(actor,requestId);if(old!=null)return reused(old,fingerprint);
            throw new StorageFailure("STORAGE_CONFLICT",409);
        }
    }
    public Operation operation(String actor,String requestId){
        StorageValidation.requestId(requestId);var row=operations.get(actor,requestId);
        if(row==null)throw new StorageFailure("STORAGE_OPERATION_NOT_FOUND",404);
        var op=decode(row);
        if((op.status().equals("PENDING") || op.status().equals("RUNNING")) && !owned.contains(row.id)){
            op=new Operation(op.requestId(),op.action(),op.configurationId(),"UNKNOWN",op.checkedRevision(),op.startedAt(),null,
                    "STORAGE_OUTCOME_UNKNOWN",op.steps(),"UNKNOWN",null);
            String before=row.operationJson;
            row.operationJson=encode(op);row.status="UNKNOWN";row.cleanupStatus="UNKNOWN";
            if(operations.recoverUnknown(row,before)==0)return decode(operations.get(actor,requestId));
        }
        return op;
    }
    public synchronized Operation probe(String actor,String id,Probe request){
        if(request==null || !"READ_WRITE".equals(request.mode()))throw invalid();
        StorageValidation.requestId(request.requestId());
        String fp=fingerprint("PROBE",id,canonical(request.expectedVersion(),request.mode()));
        var existing=operations.get(actor,request.requestId());if(existing!=null)return existingProbe(existing,fp);
        var intentId=new java.util.concurrent.atomic.AtomicReference<String>();
        ProbeSubmission submission;
        try{submission=tx.execute(status->{
            settings.locked();
            var old=operations.get(actor,request.requestId());
            if(old!=null){existingProbe(old,fp);return new ProbeSubmission(old,false);}
            var r=required(id);expected(r,request.expectedVersion());
            if(operations.activeProbes(id)>0)throw new StorageFailure("STORAGE_OPERATION_IN_PROGRESS",409);
            var intent=newOperation(actor,request.requestId(),"PROBE",id,fp,"PENDING",r.configRevision);
            intentId.set(intent.id);owned.add(intent.id);
            try{operations.insert(intent);return new ProbeSubmission(intent,true);}catch(RuntimeException e){owned.remove(intent.id);throw e;}
        });}catch(RuntimeException e){if(intentId.get()!=null)owned.remove(intentId.get());throw e;}
        var row=submission.row();
        if(!submission.created())return decode(row);
        var snapshot=required(id);
        try{worker.execute(()->executeProbe(row,snapshot));}
        catch(RejectedExecutionException ignored){owned.remove(row.id);save(row,new Operation(row.requestId,"PROBE",id,"FAILED",snapshot.configRevision,row.startedAt,Instant.now(),"STORAGE_BUSY",List.of(),"NOT_NEEDED",null));}
        return decode(row);
    }
    private record ProbeSubmission(StorageOperationEntity row,boolean created) { }
    private Operation existingProbe(StorageOperationEntity row,String fingerprint){
        if(!row.fingerprint.equals(fingerprint))throw new StorageFailure("STORAGE_REQUEST_ID_REUSED",409);
        return decode(row);
    }
    private void executeProbe(StorageOperationEntity row,StorageConfigurationEntity config){
        try(var lease=runtime.acquire(config)){
            String key="_fleet-storage-probe/"+config.id+"/"+row.requestId+".bin";
            new StorageProbeRunner().run(lease.handle(),key,row.requestId,config.configRevision,row.startedAt,op->save(row,new Operation(op.requestId(),op.action(),config.id,op.status(),op.checkedRevision(),op.startedAt(),op.finishedAt(),op.failureCode(),op.steps(),op.cleanupStatus(),null)));
        }catch(Exception ignored){
            var op=decode(row);if(op.status().equals("PENDING"))save(row,new Operation(row.requestId,"PROBE",config.id,"FAILED",config.configRevision,row.startedAt,Instant.now(),"STORAGE_APPLY_FAILED",List.of(),"NOT_NEEDED",null));
        }finally{owned.remove(row.id);}
    }
    private StorageOperationEntity newOperation(String actor,String requestId,String action,String id,String fingerprint,String state,Long revision){
        var row=new StorageOperationEntity();row.id=UUID.randomUUID().toString();row.actorId=actor;row.requestId=requestId;row.action=action;
        row.configurationId=id;row.fingerprint=fingerprint;row.startedAt=Instant.now();row.status=state;row.cleanupStatus="NOT_NEEDED";
        row.operationJson=encode(new Operation(requestId,action,id,state,revision,row.startedAt,null,null,List.of(),"NOT_NEEDED",null));return row;
    }
    private Operation reused(StorageOperationEntity old,String fingerprint){
        if(!old.fingerprint.equals(fingerprint))throw new StorageFailure("STORAGE_REQUEST_ID_REUSED",409);
        return operation(old.actorId,old.requestId);
    }
    private Configuration project(StorageConfigurationEntity r){
        var s=requiredSettings();Long applied=runtime.appliedRevision(r.platform);String runtimeStatus=applied!=null && applied==r.configRevision?"APPLIED":runtime.failed(r.platform,r.configRevision)?"APPLY_FAILED":"UNCONFIGURED";
        LastProbe last=null;
        var probes=operations.probes(r.id);if(!probes.isEmpty()){var op=decode(probes.get(0));if(op.finishedAt()!=null)last=new LastProbe(op.requestId(),op.checkedRevision(),op.finishedAt(),op.status(),op.cleanupStatus());}
        return new Configuration(r.id,r.platform,r.name,r.provider,r.endpoint,r.bucket,r.region,r.uploadEnabled,Objects.equals(s.defaultPlatform,r.platform),r.version,r.configRevision,applied,runtimeStatus,r.ciphertext!=null,r.credentialUpdatedAt,configs.references(r.platform,s.legacyNullPlatform),last);
    }
    private Settings project(StorageSettingsEntity s){return new Settings(s.version,s.defaultPlatform,s.legacyNullPlatform,s.baseDir,s.uploadTtlSeconds,s.viewTtlSeconds,s.maxUploadBytes,true);}
    private void apply(StorageConfigurationEntity r){try(var ignored=runtime.acquire(r)){}catch(StorageFailure ignored){/* 真实 runtimeStatus 由当前实例 revision 投影。 */}}
    private StorageConfigurationEntity required(String id){var r=configs.byId(id);if(r==null)throw new StorageFailure("STORAGE_CONFIG_NOT_FOUND",404);return r;}
    private StorageSettingsEntity requiredSettings(){var s=settings.current();if(s==null)throw new StorageFailure("STORAGE_UNCONFIGURED",503);return s;}
    private void expected(StorageConfigurationEntity r,Long value){if(value==null || value!=r.version)throw new StorageFailure("STORAGE_VERSION_CONFLICT",409);}
    private void encrypt(StorageConfigurationEntity r,String id,String secret){var e=cipher.encrypt(StorageRuntimeRegistry.binding(r),new StorageCredentials(id,secret));r.keyId=e.keyId();r.nonce=e.nonce();r.ciphertext=e.ciphertext();r.credentialUpdatedAt=Instant.now();}
    private void save(StorageOperationEntity row,Operation op){row.operationJson=encode(op);row.status=op.status();row.cleanupStatus=op.cleanupStatus();operations.update(row);}
    private String encode(Operation op){try{return json.writeValueAsString(op);}catch(Exception ignored){throw new StorageFailure("STORAGE_STATE_UNAVAILABLE",503);}}
    private Operation decode(StorageOperationEntity row){try{
        var op=json.readValue(row.operationJson,Operation.class);Object result=op.result();
        if(result!=null)result=op.action().equals("UPDATE_SETTINGS")?json.convertValue(result,Settings.class):json.convertValue(result,Configuration.class);
        return new Operation(op.requestId(),op.action(),op.configurationId(),op.status(),op.checkedRevision(),op.startedAt(),op.finishedAt(),op.failureCode(),op.steps(),op.cleanupStatus(),result);
    }catch(Exception ignored){throw new StorageFailure("STORAGE_STATE_UNAVAILABLE",503);}}
    private String fingerprint(String action,String id,byte[] request){byte[] header=canonical(action,id);byte[] all=Arrays.copyOf(header,header.length+request.length);System.arraycopy(request,0,all,header.length,request.length);return cipher.fingerprint(all);}
    private static byte[] canonical(Object... values){try{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        for(Object value:values){if(value==null){out.writeInt(-1);}else{byte[] b=value.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}}out.flush();return bytes.toByteArray();
    }catch(Exception ignored){throw invalid();}}
    private static StorageFailure invalid(){return new StorageFailure("STORAGE_INVALID_REQUEST",400);}
    @PreDestroy @Override public void close(){worker.shutdown();}
}

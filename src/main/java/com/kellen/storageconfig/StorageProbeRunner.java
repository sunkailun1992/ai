package com.kellen.storageconfig;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.kellen.storageconfig.StorageContracts.*;

/** 一次真实探针的状态机。未知写不重试；finally 只清理本次拥有的唯一对象。 */
public final class StorageProbeRunner {
    public void run(StorageRuntimeRegistry.Handle handle,String key,String requestId,long revision,Instant started,Consumer<Operation> journal){
        if(key==null || !key.matches("_fleet-storage-probe/[A-Za-z0-9-]+/[A-Za-z0-9-]+\\.bin"))
            throw new StorageFailure("STORAGE_INVALID_REQUEST",400);
        byte[] payload=("fleet-owned-storage-probe/v1/"+requestId).getBytes(StandardCharsets.UTF_8);
        var names=List.of("WRITE","EXISTS","READ","VERIFY_PRIVATE","CLEANUP");
        var steps=new ArrayList<Step>();for(String name:names)steps.add(new Step(name,"NOT_STARTED",null));
        String failure=null;String terminal="SUCCEEDED",cleanup="NOT_NEEDED";boolean attempted=false;
        int current=0;
        try{
            for(current=0;current<4;current++){
                steps.set(current,new Step(names.get(current),"RUNNING",null));
                journal.accept(event(requestId,revision,started,null,"RUNNING",null,steps,current==0?"PENDING":cleanup));
                switch(current){
                    case 0 -> {attempted=true;cleanup="PENDING";handle.write(key,payload);}
                    case 1 -> {if(!handle.exists(key))throw new StorageFailure("STORAGE_WRITE_FAILED",503);}
                    case 2 -> {if(!Arrays.equals(payload,handle.read(key)))throw new StorageFailure("STORAGE_READ_FAILED",503);}
                    case 3 -> {if(!handle.anonymousDenied(key))throw new StorageFailure("STORAGE_PRIVATE_ACCESS_ALLOWED",409);}
                    default -> throw new IllegalStateException();
                }
                steps.set(current,new Step(names.get(current),"SUCCEEDED",null));
                journal.accept(event(requestId,revision,started,null,"RUNNING",null,steps,cleanup));
            }
        }catch(Exception error){
            failure=error instanceof StorageFailure sf?sf.code():"STORAGE_OUTCOME_UNKNOWN";
            terminal=failure.equals("STORAGE_OUTCOME_UNKNOWN")?"UNKNOWN":"FAILED";
            if(current<4)steps.set(current,new Step(names.get(current),terminal,failure));
            for(int i=current+1;i<4;i++)steps.set(i,new Step(names.get(i),"SKIPPED",null));
        }finally{
            if(attempted){
                steps.set(4,new Step("CLEANUP","RUNNING",null));
                quiet(journal,event(requestId,revision,started,null,"RUNNING",failure,steps,"PENDING"));
                try{
                    StorageFailure deleteFailure=null;
                    try{handle.delete(key);}catch(Exception e){deleteFailure=e instanceof StorageFailure sf?sf:new StorageFailure("STORAGE_OUTCOME_UNKNOWN",503);}
                    if(handle.exists(key))throw deleteFailure==null?new StorageFailure("STORAGE_CLEANUP_FAILED",503):deleteFailure;
                    cleanup="SUCCEEDED";steps.set(4,new Step("CLEANUP","SUCCEEDED",null));
                }catch(Exception error){
                    String code=error instanceof StorageFailure sf?sf.code():"STORAGE_OUTCOME_UNKNOWN";
                    cleanup=code.equals("STORAGE_OUTCOME_UNKNOWN")?"UNKNOWN":"FAILED";
                    steps.set(4,new Step("CLEANUP",cleanup,code));
                    if(terminal.equals("SUCCEEDED") || cleanup.equals("UNKNOWN")){terminal=cleanup;failure=code.equals("STORAGE_OUTCOME_UNKNOWN")?code:"STORAGE_CLEANUP_FAILED";}
                }
            }else{steps.set(4,new Step("CLEANUP","SKIPPED",null));}
            quiet(journal,event(requestId,revision,started,Instant.now(),terminal,failure,steps,cleanup));
        }
    }
    private Operation event(String requestId,long revision,Instant started,Instant finished,String status,String failure,List<Step> steps,String cleanup){
        return new Operation(requestId,"PROBE",null,status,revision,started,finished,failure,List.copyOf(steps),cleanup,null);
    }
    private void quiet(Consumer<Operation> journal,Operation op){try{journal.accept(op);}catch(Exception ignored){/* 日志库故障不能阻止 owned cleanup；持久状态保持未知，禁止重放。 */}}
}

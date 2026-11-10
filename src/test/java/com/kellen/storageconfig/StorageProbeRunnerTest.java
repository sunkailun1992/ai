package com.kellen.storageconfig;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.kellen.storageconfig.StorageContracts.*;

class StorageProbeRunnerTest {
    @Test void realStepsArePersistedAndTheOwnedObjectIsDeletedAndReadBackAbsent() {
        var h=new Handle();List<Operation> events=new ArrayList<>();
        new StorageProbeRunner().run(h,"_fleet-storage-probe/config/request.bin","fixture-request",2,Instant.now(),events::add);
        var end=events.get(events.size()-1);
        assertThat(end.status()).isEqualTo("SUCCEEDED");assertThat(end.cleanupStatus()).isEqualTo("SUCCEEDED");
        assertThat(h.writes).isEqualTo(1);assertThat(h.deletes).isEqualTo(1);assertThat(h.exists).isFalse();
        assertThat(end.steps()).extracting(Step::name).containsExactly("WRITE","EXISTS","READ","VERIFY_PRIVATE","CLEANUP");
        assertThat(end.steps()).allMatch(s->s.status().equals("SUCCEEDED"));
    }
    @Test void unknownWriteIsNeverRetriedButOwnedCleanupIsStillAttemptedOnce() {
        var h=new Handle();h.unknownWrite=true;List<Operation> events=new ArrayList<>();
        new StorageProbeRunner().run(h,"_fleet-storage-probe/config/request.bin","fixture-request",2,Instant.now(),events::add);
        assertThat(h.writes).isEqualTo(1);assertThat(h.deletes).isEqualTo(1);
        assertThat(events.get(events.size()-1).status()).isEqualTo("UNKNOWN");
        assertThat(events.get(events.size()-1).cleanupStatus()).isEqualTo("SUCCEEDED");
    }
    @Test void anonymousTransportFailureCannotBeRecordedAsPrivateAndCleanupFailureIsIndependent() {
        var h=new Handle();h.unknownAnonymous=true;h.unknownDelete=true;List<Operation> events=new ArrayList<>();
        new StorageProbeRunner().run(h,"_fleet-storage-probe/config/request.bin","fixture-request",2,Instant.now(),events::add);
        var end=events.get(events.size()-1);assertThat(end.status()).isEqualTo("UNKNOWN");
        assertThat(end.cleanupStatus()).isEqualTo("UNKNOWN");assertThat(h.deletes).isEqualTo(1);
        assertThat(end.steps().get(3).status()).isEqualTo("UNKNOWN");
    }
    @Test void arbitraryObjectKeysAreRejectedBeforeAnyIo(){
        var h=new Handle();assertThatThrownBy(()->new StorageProbeRunner().run(h,"customer/file.bin","fixture",1,Instant.now(),op->{})).isInstanceOf(StorageFailure.class);
        assertThat(h.writes).isZero();assertThat(h.deletes).isZero();
    }
    static class Handle extends StorageRuntimeRegistryTest.FakeHandle {
        int writes,deletes;boolean exists,unknownWrite,unknownAnonymous,unknownDelete,failedDelete;byte[] bytes;
        public void write(String key,byte[] value){writes++;bytes=value;exists=true;if(unknownWrite)throw unknown();}
        public boolean exists(String key){return exists;}
        public byte[] read(String key){return bytes;}
        public boolean anonymousDenied(String key){if(unknownAnonymous)throw unknown();return true;}
        public void delete(String key){deletes++;if(failedDelete)throw new StorageFailure("STORAGE_CLEANUP_FAILED",503);if(unknownDelete)throw unknown();exists=false;}
        private StorageFailure unknown(){return new StorageFailure("STORAGE_OUTCOME_UNKNOWN",503);}
    }
}

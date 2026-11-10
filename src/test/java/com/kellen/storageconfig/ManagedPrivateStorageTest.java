package com.kellen.storageconfig;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedPrivateStorageTest {
    @Test void emptyDatabaseCannotFallBackAndDisabledUploadStillAllowsOriginalPlatformReads(){
        var configs=mock(StorageConfigurationRepository.class);var settings=mock(StorageSettingsRepository.class);
        var runtime=mock(StorageRuntimeRegistry.class);var s=new StorageSettingsEntity();s.defaultPlatform=null;s.legacyNullPlatform="original";s.viewTtlSeconds=1800;
        when(settings.current()).thenReturn(s);when(settings.locked()).thenReturn(s);
        var managed=new ManagedPrivateStorage(configs,settings,runtime);
        assertThatThrownBy(managed::upload).isInstanceOf(StorageFailure.class);
        verifyNoInteractions(runtime);
        var row=new StorageConfigurationEntity();row.platform="original";row.uploadEnabled=false;
        when(configs.byPlatform("original")).thenReturn(row);
        var lease=mock(StorageRuntimeRegistry.Lease.class);var handle=mock(StorageRuntimeRegistry.Handle.class);
        var adapter=mock(com.kellen.storage.PrivateObjectStorageService.class);
        when(runtime.acquire(row)).thenReturn(lease);when(lease.handle()).thenReturn(handle);when(handle.adapter()).thenReturn(adapter);
        when(adapter.signedViewUri("original","old/key.png",1800)).thenReturn(URI.create("https://files.example.invalid/owned"));
        assertThat(managed.signedViewUri(null,"old/key.png")).isEqualTo(URI.create("https://files.example.invalid/owned"));
        verify(adapter).signedViewUri("original","old/key.png",1800);verify(lease).close();
        s.defaultPlatform="original";
        assertThatThrownBy(managed::upload).isInstanceOf(StorageFailure.class);
    }
}

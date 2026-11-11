package com.ai.file;

import com.kellen.aliyun.oss.token.OssTokenUtils;
import com.kellen.file.AiFileObjectEntity;
import com.kellen.file.AiFileObjectRepository;
import com.kellen.file.OssFileService;
import com.kellen.storageconfig.ManagedPrivateStorage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 退役上传后仍保留既有文件的 token 校验及固定 DB 平台读取。 */
class OssFileServiceTests {
    @Test void validTokenLoadsAnExistingFileAndSignsOnlyItsSavedPlatform() {
        var repository = mock(AiFileObjectRepository.class);
        var managed = mock(ManagedPrivateStorage.class);
        var service = new OssFileService(repository, managed);
        var file = AiFileObjectEntity.create(7L, "legacy-owner/image.png", "image.png", "image/png", "image",
                12L, OssTokenUtils.displayTokenHash("synthetic-token"));
        file.setStoragePlatform("original-platform");
        when(repository.findByIdAndStatus(7L, AiFileObjectEntity.STATUS_ACTIVE)).thenReturn(Optional.of(file));
        assertThat(service.viewableFile(7L, "synthetic-token")).isSameAs(file);
        verifyNoInteractions(managed);
        var expected = URI.create("https://files.example.invalid/private/signed");
        when(managed.signedViewUri("original-platform", "legacy-owner/image.png")).thenReturn(expected);
        assertThat(service.signedViewUri(file)).isEqualTo(expected);
        verify(managed).signedViewUri("original-platform", "legacy-owner/image.png");
    }

    @Test void invalidTokenNeverReachesTheStorageRegistry() {
        var repository = mock(AiFileObjectRepository.class);
        var managed = mock(ManagedPrivateStorage.class);
        var file = AiFileObjectEntity.create(7L, "legacy-owner/image.png", "image.png", "image/png", "image",
                12L, OssTokenUtils.displayTokenHash("synthetic-token"));
        when(repository.findByIdAndStatus(7L, AiFileObjectEntity.STATUS_ACTIVE)).thenReturn(Optional.of(file));
        assertThatThrownBy(() -> new OssFileService(repository, managed).viewableFile(7L, "wrong-token"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("文件展示 token 无效");
        verifyNoInteractions(managed);
    }

    @Test void historicalNullPlatformDelegatesToTheFixedDatabaseMapping() {
        var managed = mock(ManagedPrivateStorage.class);
        var file = AiFileObjectEntity.create(7L, "legacy-owner/image.png", "image.png", "image/png", "image", 12L, "hash");
        var expected = URI.create("https://files.example.invalid/private/mapped");
        when(managed.signedViewUri(null, "legacy-owner/image.png")).thenReturn(expected);
        assertThat(new OssFileService(mock(AiFileObjectRepository.class), managed).signedViewUri(file)).isEqualTo(expected);
        verify(managed).signedViewUri(null, "legacy-owner/image.png");
    }
}

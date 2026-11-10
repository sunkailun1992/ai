package com.ai.file;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.config.AiProperties;

import com.kellen.config.aliyun.CommonAliyunProperties;
import com.kellen.file.AiFileObjectRepository;
import com.kellen.file.OssFileService;
import com.kellen.file.FileUploadCompleteRequest;
import com.kellen.file.AiFileObjectEntity;
import com.kellen.storage.PrivateObjectStorageService;
import com.kellen.aliyun.oss.token.OssUploadPolicy;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import java.net.URI;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OSS 文件服务测试。
 *
 * <p>覆盖配置安全边界：未配置 bucket 或密钥时，后端不能签发直传策略，
 * 也不能写入文件元数据。</p>
 */
class OssFileServiceTests {

    /**
     * 验证 OSS 配置缺失时无法生成上传策略。
     */
    @Test
    void uploadPolicyRequiresOssCredentials() {
        AiFileObjectRepository repository = mock(AiFileObjectRepository.class);
        OssFileService service = new OssFileService(testProperties(), emptyCommonAliyunProperties(), repository);

        AiWechatUserEntity user = AiWechatUserEntity.create("fixture-openid", "");
        ReflectionTestUtils.setField(user, "id", 7L);
        assertThrows(IllegalStateException.class,
                () -> service.createUploadPolicy(user, "image", "image/png", "fixture.png"));
        verifyNoInteractions(repository);
    }

    @Test
    void unifiedStorageUploadsWithoutLegacyCredentialsAndPersistsThePlatform() {
        AiFileObjectRepository repository = mock(AiFileObjectRepository.class);
        PrivateObjectStorageService storage = mock(PrivateObjectStorageService.class);
        when(storage.selected()).thenReturn(true);
        when(storage.platform()).thenReturn("minio-private");
        String key = "ai-miniapp/wechat-user-7/image/fixture.png";
        Map<String, String> fields = Map.of("key", key, "x-amz-signature", "fixture-signature");
        when(storage.createUploadPolicy(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong())).thenReturn(new OssUploadPolicy("id", "policy", "sig",
                        "https://files.example.invalid/private", "123", key, "image", "image/png", 52428800, null, fields));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OssFileService service = new OssFileService(testProperties(), emptyCommonAliyunProperties(), repository, storage);
        AiWechatUserEntity user = AiWechatUserEntity.create("fixture-openid", "");
        ReflectionTestUtils.setField(user, "id", 7L);

        var policy = service.createUploadPolicy(user, "image", "image/png", "fixture.png");
        assertThat(policy.formFields()).isEqualTo(fields);
        assertThat(policy.storagePlatform()).isEqualTo("minio-private");
        var completed = service.completeUpload(user, new FileUploadCompleteRequest(key, "fixture.png",
                "image/png", "image", 12L, policy.storagePlatform()));
        assertThat(completed.entity().getStoragePlatform()).isEqualTo("minio-private");
        verify(storage).validateObjectOwner("ai-miniapp", "wechat-user-7", key);
        verify(storage).verifyObjectExists("minio-private", key);
        assertThat(completed.entity().getDisplayTokenHash()).isNotEqualTo(completed.displayToken());
    }

    @Test
    void selectedButMisconfiguredStorageCannotFallBackToLegacyOss() {
        PrivateObjectStorageService storage = mock(PrivateObjectStorageService.class);
        when(storage.selected()).thenReturn(true);
        when(storage.configured()).thenReturn(false);
        when(storage.createUploadPolicy(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong())).thenThrow(new IllegalStateException("selected-platform-unavailable"));
        AiWechatUserEntity user = AiWechatUserEntity.create("fixture-openid", "");
        ReflectionTestUtils.setField(user, "id", 7L);
        OssFileService service = new OssFileService(testProperties(), emptyCommonAliyunProperties(),
                mock(AiFileObjectRepository.class), storage);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createUploadPolicy(user, "image",
                "image/png", "fixture.png")).hasMessage("selected-platform-unavailable");
        verify(storage).createUploadPolicy(eq("wechat-user-7"), eq("image"), eq("image/png"), eq("fixture.png"),
                anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void savedPlatformIsUsedForViewingAfterTheDefaultPlatformChanges() {
        PrivateObjectStorageService storage = mock(PrivateObjectStorageService.class);
        when(storage.platform()).thenReturn("replacement-platform");
        AiFileObjectEntity file = AiFileObjectEntity.create(7L, "owner/image.png", "image.png", "image/png",
                "image", 12L, "hash");
        file.setStoragePlatform("original-platform");
        URI signed = URI.create("https://files.example.invalid/private/signed");
        when(storage.signedViewUri("original-platform", "owner/image.png", 1800)).thenReturn(signed);
        OssFileService service = new OssFileService(testProperties(), emptyCommonAliyunProperties(),
                mock(AiFileObjectRepository.class), storage);
        assertThat(service.signedViewUri(file)).isEqualTo(signed);
        verify(storage).signedViewUri("original-platform", "owner/image.png", 1800);
    }

    @Test
    void clientCannotRegisterAnObjectOnAnUnselectedStoragePlatform() {
        PrivateObjectStorageService storage = mock(PrivateObjectStorageService.class);
        when(storage.selected()).thenReturn(true);
        when(storage.platform()).thenReturn("selected");
        AiFileObjectRepository repository = mock(AiFileObjectRepository.class);
        OssFileService service = new OssFileService(testProperties(), emptyCommonAliyunProperties(), repository, storage);
        AiWechatUserEntity user = AiWechatUserEntity.create("fixture-openid", "");
        ReflectionTestUtils.setField(user, "id", 7L);
        assertThrows(IllegalArgumentException.class, () -> service.completeUpload(user,
                new FileUploadCompleteRequest("ai-miniapp/wechat-user-7/image/fixture.png", "fixture.png",
                        "image/png", "image", 12L, "other")));
        verifyNoInteractions(repository);
        verify(storage, never()).verifyObjectExists(anyString(), anyString());
    }

    private static AiProperties testProperties() {
        return new AiProperties(
                new AiProperties.DialogueProperties("patient-analysis", "兜底患者分析提示词", 4, 20971520, List.of("http", "https"),
                        List.of("image/*", "text/plain"), new AiProperties.MemoryProperties(20, 7)),
                new AiProperties.WechatProperties("wx-test", "", 30),
                new AiProperties.OssProperties("oss-cn-hangzhou.aliyuncs.com", "", "", "", "ai-miniapp", 60, 1800, 52428800));
    }

    private static CommonAliyunProperties emptyCommonAliyunProperties() {
        return new CommonAliyunProperties("", "", new CommonAliyunProperties.OssProperties("", "", "ai-miniapp", 60, 1800, 52428800));
    }
}

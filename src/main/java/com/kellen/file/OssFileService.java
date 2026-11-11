package com.kellen.file;

import com.kellen.aliyun.oss.token.OssTokenUtils;
import com.kellen.storageconfig.ManagedPrivateStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

/** 既有私有文件的只读 token 校验与签名；平台解析始终使用加密 DB registry。 */
@Service
public class OssFileService {
    private final AiFileObjectRepository fileObjectRepository;
    private final ManagedPrivateStorage managed;

    public OssFileService(AiFileObjectRepository fileObjectRepository, ManagedPrivateStorage managed) {
        this.fileObjectRepository = fileObjectRepository;
        this.managed = managed;
    }

    /** 展示 token 只与已登记文件的哈希匹配，不接受身份 ID 替代。 */
    @Transactional(readOnly = true)
    public AiFileObjectEntity viewableFile(Long fileId, String displayToken) {
        AiFileObjectEntity entity = fileObjectRepository.findByIdAndStatus(fileId, AiFileObjectEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或已失效"));
        if (!OssTokenUtils.matchesDisplayToken(displayToken, entity.getDisplayTokenHash())) {
            throw new IllegalArgumentException("文件展示 token 无效");
        }
        return entity;
    }

    /** 固定使用文件保存的平台；历史 null 平台由 DB 的 legacyNullPlatform 映射解析。 */
    public URI signedViewUri(AiFileObjectEntity entity) {
        return managed.signedViewUri(entity.getStoragePlatform(), entity.getObjectKey());
    }
}

package com.kellen.file;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 既有私有文件对象实体，对应 ai_file_object 表。
 *
 * <p>业务侧只保存 OSS objectKey 和展示 token 哈希，不保存公共 OSS URL。
 * 图片、PDF、JSON 等资料展示时必须走后端 token 校验和短时签名跳转。</p>
 */
@TableName("ai_file_object")
public class AiFileObjectEntity extends AiGovernanceEntity {

    public static final String STATUS_ACTIVE = "ACTIVE"; // 文件可展示状态。

    @TableId(type = IdType.AUTO)
    private Long id; // 文件 ID。

    private Long ownerUserId; // 历史登记的 owner ID；不与 User subject 混用。

    private String storagePlatform; // 对象存储平台；空值为迁移前的 OSS。

    private String objectKey; // OSS objectKey。

    private String originalName; // 原始文件名。

    private String mimeType; // MIME 类型。

    private String fileType; // 文件业务类型。

    private Long fileSizeBytes; // 文件大小。

    private String displayTokenHash; // 展示 token 哈希。

    private String status = STATUS_ACTIVE; // 文件状态。

    private LocalDateTime createdAt; // 创建时间。

    private LocalDateTime updatedAt; // 更新时间。

    /**
     * 创建文件对象元数据。
     *
     * @param ownerUserId      历史登记的 owner ID；不与 User subject 混用。
     * @param objectKey        OSS objectKey。
     * @param originalName     原始文件名。
     * @param mimeType         MIME 类型。
     * @param fileType         文件业务类型。
     * @param fileSizeBytes    文件大小。
     * @param displayTokenHash 展示 token 哈希。
     * @return 文件对象实体。
     */
    public static AiFileObjectEntity create(Long ownerUserId,
                                            String objectKey,
                                            String originalName,
                                            String mimeType,
                                            String fileType,
                                            Long fileSizeBytes,
                                            String displayTokenHash) {
        AiFileObjectEntity entity = new AiFileObjectEntity();
        entity.ownerUserId = ownerUserId;
        entity.objectKey = objectKey;
        entity.originalName = originalName;
        entity.mimeType = mimeType;
        entity.fileType = fileType;
        entity.fileSizeBytes = fileSizeBytes;
        entity.displayTokenHash = displayTokenHash;
        entity.status = STATUS_ACTIVE;
        return entity;
    }

    /**
     * 刷新展示 token 哈希。
     *
     * @param displayTokenHash 新展示 token 哈希。
     */
    public void refreshDisplayTokenHash(String displayTokenHash) {
        this.displayTokenHash = displayTokenHash;
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getStoragePlatform() {
        return storagePlatform;
    }

    public void setStoragePlatform(String storagePlatform) {
        this.storagePlatform = storagePlatform;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getFileType() {
        return fileType;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getDisplayTokenHash() {
        return displayTokenHash;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

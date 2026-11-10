package com.kellen.file;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.storageconfig.ManagedPrivateStorage;
import com.kellen.storageconfig.StorageFailure;
import com.kellen.config.AiProperties;

import com.kellen.aliyun.oss.token.OssTokenProperties;
import com.kellen.aliyun.oss.token.OssTokenUtils;
import com.kellen.aliyun.oss.token.OssUploadPolicy;
import com.kellen.config.aliyun.CommonAliyunProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.kellen.storage.PrivateObjectStorageService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * 私有 OSS 文件服务。
 *
 * <p>上传采用小程序直传 OSS：后端签发短时 PostObject 表单策略，小程序上传成功后
 * 再回调后端完成登记。展示和传给 AI 的地址统一走后端 token URL，后端校验后生成
 * 短时签名 OSS URL，避免业务侧保存或传播公共 OSS 链接。</p>
 */
@Service // 注册为 Spring Service，供文件 Controller 调用。
public class OssFileService {

    private final ManagedPrivateStorage managed; // 生产只使用加密 DB registry。
    private final AiProperties properties; // OSS endpoint、bucket 和密钥配置。
    private final CommonAliyunProperties commonAliyunProperties; // 公共配置中心顶层 aliyun.* 兜底配置。
    private final PrivateObjectStorageService storage; // X File Storage 私有存储适配器。
    private final AiFileObjectRepository fileObjectRepository; // 文件对象元数据 Repository。

    /**
     * 构造器注入文件服务依赖。
     *
     * @param properties           AI 配置对象。
     * @param fileObjectRepository 文件对象 Repository。
     */
    public OssFileService(AiProperties properties,
                          CommonAliyunProperties commonAliyunProperties,
                          AiFileObjectRepository fileObjectRepository) {
        this(properties, commonAliyunProperties, fileObjectRepository, null);
    }

    /** Spring 使用统一存储；保留旧构造入口供既有测试和独立调用方迁移。 */
    public OssFileService(AiProperties properties, CommonAliyunProperties commonAliyunProperties,
                          AiFileObjectRepository fileObjectRepository, PrivateObjectStorageService storage) {
        this(properties,commonAliyunProperties,fileObjectRepository,storage,null);
    }

    @Autowired
    public OssFileService(AiProperties properties, CommonAliyunProperties commonAliyunProperties,
                          AiFileObjectRepository fileObjectRepository, PrivateObjectStorageService storage,
                          ManagedPrivateStorage managed) {
        this.managed = managed;
        this.storage = storage;
        this.properties = properties;
        this.commonAliyunProperties = commonAliyunProperties;
        this.fileObjectRepository = fileObjectRepository;
    }

    /**
     * 创建小程序直传 OSS 的表单策略。
     *
     * @param user     当前登录微信用户。
     * @param fileType 文件业务类型。
     * @param mimeType 文件 MIME 类型，可由文件名兜底推断。
     * @param fileName 原始文件名。
     * @return 上传策略响应。
     */
    public FileUploadPolicyResponse createUploadPolicy(AiWechatUserEntity user, String fileType, String mimeType, String fileName) {
        Long userId = requiredUserId(user);
        if (managed != null) {
            try (var upload = managed.upload()) {
                return FileUploadPolicyResponse.ok(upload.policy(ownerScope(userId),fileType,mimeType,fileName),upload.platform());
            }
        }
        if (unifiedStorage()) {
            AiProperties.OssProperties limits = effectiveOssProperties();
            OssUploadPolicy policy = storage.createUploadPolicy(ownerScope(userId), fileType, mimeType, fileName,
                    limits.baseDir(), limits.uploadExpireSeconds(), limits.viewExpireSeconds(), limits.maxUploadBytes());
            return FileUploadPolicyResponse.ok(policy, storage.platform());
        }
        OssUploadPolicy policy = OssTokenUtils.createUploadPolicy(ownerScope(userId), fileType, mimeType, fileName,
                requiredOssTokenProperties());
        return FileUploadPolicyResponse.ok(policy, null);
    }

    /**
     * 完成 OSS 直传后的文件登记。
     *
     * @param user    当前登录微信用户。
     * @param request 完成登记请求。
     * @return 文件实体和本次展示 token。
     */
    @Transactional // OSS 存在性检查通过后，元数据和展示 token 哈希必须原子写入。
    public CompletedFile completeUpload(AiWechatUserEntity user, FileUploadCompleteRequest request) {
        if (managed != null) return completeManagedUpload(user,request);
        AiProperties.OssProperties limits = effectiveOssProperties();
        boolean unified = unifiedStorage();
        OssTokenProperties oss = unified ? null : requiredOssTokenProperties();
        Long userId = requiredUserId(user); // 当前登录用户。
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String objectKey = requiredText(request.objectKey(), "objectKey"); // 上传对象 key。
        String platform = unified ? storage.platform() : null;
        if (StringUtils.hasText(request.storagePlatform()) && !request.storagePlatform().equals(platform)) {
            throw new IllegalArgumentException("上传平台已变更，请重新获取上传策略");
        }
        if (unified) {
            storage.validateObjectOwner(limits.baseDir(), ownerScope(userId), objectKey);
        } else {
            OssTokenUtils.validateObjectKeyOwner(oss, ownerScope(userId), objectKey);
        }
        if (fileObjectRepository.existsByObjectKey(objectKey)) { // objectKey 幂等保护。
            throw new IllegalArgumentException("文件已登记，请勿重复提交");
        }

        String originalName = OssTokenUtils.safeOriginalName(request.originalName(), objectKey); // 展示文件名。
        String mimeType = OssTokenUtils.normalizeMimeType(request.mimeType(), originalName); // 标准化 MIME。
        String fileType = OssTokenUtils.normalizeFileType(request.fileType(), mimeType); // 标准化文件类型。
        OssTokenUtils.validateFileTypeMatchesMime(fileType, mimeType); // 文件类型和 MIME 必须一致。
        long fileSize = request.fileSize() == null ? 0 : request.fileSize(); // 小程序可能拿不到大小，允许 0。
        if (fileSize < 0 || fileSize > Math.max(1, limits.maxUploadBytes())) { // 文件大小不能超过策略上限。
            throw new IllegalArgumentException("文件大小超过限制");
        }
        if (unified) {
            storage.verifyObjectExists(platform, objectKey);
        } else {
            OssTokenUtils.verifyObjectExists(oss, objectKey);
        }

        String displayToken = OssTokenUtils.generateDisplayToken();
        AiFileObjectEntity entity = AiFileObjectEntity.create(userId, objectKey, originalName, mimeType, fileType,
                fileSize, OssTokenUtils.displayTokenHash(displayToken));
        entity.setStoragePlatform(platform);
        AiFileObjectEntity saved = fileObjectRepository.save(entity);
        return new CompletedFile(saved, displayToken);
    }

    /**
     * 刷新文件展示 token。
     *
     * @param user   当前登录微信用户。
     * @param fileId 文件 ID。
     * @return 文件实体和新的展示 token。
     */
    @Transactional // 刷新 token 会更新数据库哈希。
    public CompletedFile refreshDisplayToken(AiWechatUserEntity user, Long fileId) {
        Long userId = requiredUserId(user);
        return refreshDisplayToken(userId, fileId);
    }

    /**
     * 使用用户 ID 刷新文件展示 token。
     *
     * @param ownerUserId 文件所属微信用户 ID。
     * @param fileId      文件 ID。
     * @return 文件实体和新的展示 token。
     */
    @Transactional // 刷新 token 会更新数据库哈希。
    public CompletedFile refreshDisplayToken(Long ownerUserId, Long fileId) {
        Long userId = requiredUserId(ownerUserId);
        AiFileObjectEntity entity = ownedActiveFile(userId, fileId);
        String displayToken = OssTokenUtils.generateDisplayToken();
        entity.refreshDisplayTokenHash(OssTokenUtils.displayTokenHash(displayToken));
        AiFileObjectEntity saved = fileObjectRepository.save(entity);
        return new CompletedFile(saved, displayToken);
    }

    /**
     * 查询用户自己的文件元数据。
     *
     * @param user   当前登录微信用户。
     * @param fileId 文件 ID。
     * @return 文件对象实体。
     */
    @Transactional(readOnly = true)
    public AiFileObjectEntity metadata(AiWechatUserEntity user, Long fileId) {
        return ownedActiveFile(requiredUserId(user), fileId);
    }

    /**
     * 使用展示 token 查询可访问文件。
     *
     * @param fileId       文件 ID。
     * @param displayToken 展示 token 明文。
     * @return 文件对象实体。
     */
    @Transactional(readOnly = true)
    public AiFileObjectEntity viewableFile(Long fileId, String displayToken) {
        AiFileObjectEntity entity = fileObjectRepository.findByIdAndStatus(fileId, AiFileObjectEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或已失效"));
        if (!OssTokenUtils.matchesDisplayToken(displayToken, entity.getDisplayTokenHash())) {
            throw new IllegalArgumentException("文件展示 token 无效");
        }
        return entity;
    }

    /**
     * 为已通过 token 校验的文件生成短时 OSS 签名 URL。
     *
     * @param entity 文件对象实体。
     * @return 短时签名 URL。
     */
    public URI signedViewUri(AiFileObjectEntity entity) {
        if (managed != null) return managed.signedViewUri(entity.getStoragePlatform(),entity.getObjectKey());
        if (StringUtils.hasText(entity.getStoragePlatform())) {
            if (storage == null) {
                throw new IllegalStateException("文件存储平台未配置");
            }
            return storage.signedViewUri(entity.getStoragePlatform(), entity.getObjectKey(),
                    effectiveOssProperties().viewExpireSeconds());
        }
        // 迁移前的空平台记录始终沿用原 OSS，不能随着 default-platform 变化而读错对象。
        return OssTokenUtils.signedViewUri(entity.getObjectKey(), requiredOssTokenProperties());
    }

    private CompletedFile completeManagedUpload(AiWechatUserEntity user, FileUploadCompleteRequest request) {
        Long userId=requiredUserId(user);
        if(request==null)throw new IllegalArgumentException("请求体不能为空");
        String key=requiredText(request.objectKey(),"objectKey");
        try(var upload=managed.upload()) {
            if(!upload.platform().equals(request.storagePlatform()))throw new StorageFailure("STORAGE_VERSION_CONFLICT",409);
            upload.validateOwner(ownerScope(userId),key);
            if(fileObjectRepository.existsByObjectKey(key))throw new IllegalArgumentException("文件已登记，请勿重复提交");
            String originalName=OssTokenUtils.safeOriginalName(request.originalName(),key);
            String mime=OssTokenUtils.normalizeMimeType(request.mimeType(),originalName);
            String type=OssTokenUtils.normalizeFileType(request.fileType(),mime);
            OssTokenUtils.validateFileTypeMatchesMime(type,mime);
            long size=request.fileSize()==null?0:request.fileSize();
            if(size<0 || size>upload.maxUploadBytes())throw new IllegalArgumentException("文件大小超过限制");
            upload.verifyExists(key);
            String token=OssTokenUtils.generateDisplayToken();
            var entity=AiFileObjectEntity.create(userId,key,originalName,mime,type,size,OssTokenUtils.displayTokenHash(token));
            entity.setStoragePlatform(upload.platform());
            return new CompletedFile(fileObjectRepository.save(entity),token);
        }
    }

    private boolean unifiedStorage() {
        return storage != null && storage.selected();
    }

    private AiFileObjectEntity ownedActiveFile(Long userId, Long fileId) {
        AiFileObjectEntity entity = fileObjectRepository.findByIdAndStatus(fileId, AiFileObjectEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或已失效"));
        if (!userId.equals(entity.getOwnerUserId())) {
            throw new IllegalArgumentException("无权访问该文件");
        }
        return entity;
    }

    private String ownerScope(Long userId) {
        return "wechat-user-" + requiredUserId(userId);
    }

    private AiProperties.OssProperties requiredOssProperties() {
        AiProperties.OssProperties oss = effectiveOssProperties();
        if (oss == null || !StringUtils.hasText(oss.endpoint()) || !StringUtils.hasText(oss.bucket())
                || !StringUtils.hasText(oss.accessKeyId()) || !StringUtils.hasText(oss.accessKeySecret())) {
            throw new IllegalStateException("OSS endpoint、bucket、accessKeyId 或 accessKeySecret 未配置");
        }
        return oss;
    }

    private OssTokenProperties requiredOssTokenProperties() {
        AiProperties.OssProperties oss = requiredOssProperties();
        return new OssTokenProperties(oss.endpoint(), oss.bucket(), oss.accessKeyId(), oss.accessKeySecret(),
                oss.baseDir(), oss.uploadExpireSeconds(), oss.viewExpireSeconds(), oss.maxUploadBytes());
    }

    private AiProperties.OssProperties effectiveOssProperties() {
        AiProperties.OssProperties aiOss = properties.oss();
        CommonAliyunProperties.OssProperties commonOss = commonAliyunProperties.oss();
        return new AiProperties.OssProperties(
                firstText(aiOss.endpoint(), commonOss.endpoint()),
                firstText(aiOss.bucket(), commonOss.bucket()),
                firstText(aiOss.accessKeyId(), commonAliyunProperties.accessKeyId()),
                firstText(aiOss.accessKeySecret(), commonAliyunProperties.accessKeySecret()),
                firstText(aiOss.baseDir(), commonOss.baseDir()),
                positive(aiOss.uploadExpireSeconds(), commonOss.uploadExpireSeconds()),
                positive(aiOss.viewExpireSeconds(), commonOss.viewExpireSeconds()),
                positive(aiOss.maxUploadBytes(), commonOss.maxUploadBytes()));
    }

    private Long requiredUserId(AiWechatUserEntity user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return user.getId();
    }

    private Long requiredUserId(Long ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return ownerUserId;
    }

    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : fallback == null ? "" : fallback.trim();
    }

    private long positive(long preferred, long fallback) {
        return preferred > 0 ? preferred : fallback;
    }

    /**
     * 完成登记后的文件和展示 token。
     *
     * @param entity       文件对象实体。
     * @param displayToken 展示 token 明文。
     */
    public record CompletedFile(AiFileObjectEntity entity, String displayToken) {
    }
}

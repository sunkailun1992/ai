package com.kellen.file;

/**
 * OSS 直传完成登记请求。
 *
 * @param objectKey    上传策略返回的 OSS objectKey。
 * @param originalName 用户侧原始文件名。
 * @param mimeType     文件 MIME 类型。
 * @param fileType     文件业务类型：image/video/audio/file。
 * @param fileSize     文件大小，单位字节。
 */
public record FileUploadCompleteRequest(
        String objectKey,
        String originalName,
        String mimeType,
        String fileType,
        Long fileSize,
        String storagePlatform
) {
    /** 兼容既有 OSS 调用方；未传平台时以当前服务端上传平台为准。 */
    public FileUploadCompleteRequest(String objectKey, String originalName, String mimeType,
                                     String fileType, Long fileSize) {
        this(objectKey, originalName, mimeType, fileType, fileSize, null);
    }
}

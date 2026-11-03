package com.kellen.file;


import java.time.LocalDateTime;

/**
 * 文件对象响应。
 *
 * @param success     请求是否成功。
 * @param fileId      文件 ID。
 * @param fileType    文件业务类型。
 * @param mimeType    文件 MIME 类型。
 * @param originalName 原始文件名。
 * @param fileSize    文件大小，单位字节。
 * @param displayToken 展示 token 明文，仅完成登记或刷新 token 时返回。
 * @param displayUrl   后端 token 展示地址，可传给 AI attachments.url 或小程序组件展示。
 * @param error       错误说明。
 * @param timestamp   响应时间。
 */
public record FileObjectResponse(
        boolean success,
        Long fileId,
        String fileType,
        String mimeType,
        String originalName,
        Long fileSize,
        String displayToken,
        String displayUrl,
        String error,
        LocalDateTime timestamp
) {

    /**
     * 构造成功响应。
     */
    public static FileObjectResponse ok(AiFileObjectEntity entity, String displayToken, String displayUrl) {
        return new FileObjectResponse(true, entity.getId(), entity.getFileType(), entity.getMimeType(),
                entity.getOriginalName(), entity.getFileSizeBytes(), displayToken, displayUrl, null, LocalDateTime.now());
    }

    /**
     * 构造失败响应。
     */
    public static FileObjectResponse error(String error) {
        return new FileObjectResponse(false, null, null, null, null, null, null, null, error, LocalDateTime.now());
    }
}

package com.kellen.file;

/**
 * 多模态媒体输入。
 *
 * @param url          远程媒体地址；当前只允许 http/https 协议，由 MediaInputValidator 校验。
 * @param mimeType     媒体 MIME 类型，例如 image/png、image/jpeg，必填。
 * @param fileId       业务文件 ID，可选；小程序上传文件后用于历史展示时刷新 token。
 * @param fileType     文件业务类型，可选；image/video/audio/file。
 * @param originalName 原始文件名，可选；用于历史消息展示。
 */
public record MediaInput(
        String url, // 远程媒体地址。
        String mimeType, // 明确的 MIME 类型，交给 Spring AI 组装多模态消息。
        Long fileId, // 可选业务文件 ID。
        String fileType, // 可选文件业务类型。
        String originalName // 可选原始文件名。
) {

    /**
     * 兼容只传 URL 和 MIME 的调用方。
     *
     * @param url      远程媒体地址。
     * @param mimeType 媒体 MIME 类型。
     */
    public MediaInput(String url, String mimeType) {
        this(url, mimeType, null, null, null);
    }
}

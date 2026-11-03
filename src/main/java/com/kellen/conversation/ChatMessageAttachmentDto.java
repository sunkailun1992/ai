package com.kellen.conversation;

import com.kellen.file.MediaInput;

/**
 * 聊天展示附件视图。
 *
 * @param fileId       业务文件 ID，可空；有值时历史接口可刷新展示 token。
 * @param fileType     文件业务类型，image/video/audio/file。
 * @param mimeType     文件 MIME 类型。
 * @param originalName 原始文件名。
 * @param displayUrl   后端 token 展示地址。
 * @param previewType  前端预览类型，image 或 file。
 */
public record ChatMessageAttachmentDto(
        Long fileId,
        String fileType,
        String mimeType,
        String originalName,
        String displayUrl,
        String previewType
) {

    /**
     * 从多模态输入构造展示附件。
     *
     * @param input 多模态输入。
     * @return 展示附件。
     */
    public static ChatMessageAttachmentDto from(MediaInput input) {
        String mimeType = input.mimeType() == null ? "" : input.mimeType();
        String fileType = input.fileType() == null || input.fileType().isBlank() ? inferFileType(mimeType) : input.fileType();
        return new ChatMessageAttachmentDto(input.fileId(), fileType, mimeType, input.originalName(), input.url(), previewType(fileType, mimeType));
    }

    /**
     * 返回替换展示地址后的附件。
     *
     * @param displayUrl 新展示地址。
     * @return 新附件视图。
     */
    public ChatMessageAttachmentDto withDisplayUrl(String displayUrl) {
        return new ChatMessageAttachmentDto(fileId, fileType, mimeType, originalName, displayUrl, previewType);
    }

    private static String inferFileType(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType.startsWith("video/")) {
            return "video";
        }
        if (mimeType.startsWith("audio/")) {
            return "audio";
        }
        return "file";
    }

    private static String previewType(String fileType, String mimeType) {
        if ("image".equalsIgnoreCase(fileType) || mimeType.startsWith("image/")) {
            return "image";
        }
        return "file";
    }
}

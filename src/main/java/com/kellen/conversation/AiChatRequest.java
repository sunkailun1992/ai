package com.kellen.conversation;

import com.kellen.file.MediaInput;

import java.util.List;

/**
 * 流式对话请求。
 *
 * @param conversationId 可选会话 ID，用于多轮记忆归组和审计记录归组；为空时由服务端归入 default 会话。
 * @param role           可选 AI 角色；为空时使用配置中的 patient-analysis。
 * @param patientId      可选患者 ID，用于拉取患者长期记忆。
 * @param sourceTenantId 可选来源业务租户 ID，用于隔离不同业务来源下的患者记忆。
 * @param message        用户输入的本轮消息，不能为空。
 * @param instruction    本轮补充要求；只能影响输出偏好，不能覆盖角色安全边界。
 * @param systemPrompt   旧字段，兼容历史调用方；新调用方应使用 instruction。
 * @param attachments    可选远程附件 URL 列表；文件需先上传到文件服务或 OSS 后再传入可访问 URL。
 * @param media          旧字段，兼容历史调用方；新调用方应使用 attachments。
 */
public record AiChatRequest(
        String conversationId, // 可选会话 ID。
        String role, // 可选 AI 角色，后续新增角色时通过配置扩展。
        String patientId, // 可选患者 ID，用于患者长期记忆检索。
        String sourceTenantId, // 可选来源业务租户 ID，默认使用 100。
        String message, // 用户本轮问题或指令。
        String instruction, // 本轮补充要求，不允许覆盖角色提示词中的医疗安全边界。
        String systemPrompt, // 旧字段：历史 systemPrompt 入参按 instruction 兼容处理。
        List<MediaInput> attachments, // 可选远程附件列表，支持图像、音频、视频和文档等多模态输入。
        List<MediaInput> media // 旧字段：历史 media 入参按 attachments 兼容处理。
) {

    /**
     * 兼容只传文本的调用方，默认不附带远程媒体。
     *
     * @param conversationId 可选会话 ID。
     * @param message        用户本轮问题或指令。
     * @param instruction    本轮补充要求，不能覆盖角色安全边界。
     */
    public AiChatRequest(String conversationId, String message, String instruction) {
        this(conversationId, null, null, null, message, instruction, null, List.of(), List.of());
    }

    /**
     * 获取本轮补充要求，新字段 instruction 优先，旧字段 systemPrompt 仅做兼容。
     *
     * @return 本轮补充要求，可空。
     */
    public String effectiveInstruction() {
        if (instruction != null && !instruction.isBlank()) {
            return instruction;
        }
        return systemPrompt;
    }

    /**
     * 获取远程附件，新字段 attachments 优先，旧字段 media 仅做兼容。
     *
     * @return 远程附件列表。
     */
    public List<MediaInput> effectiveAttachments() {
        if (attachments != null && !attachments.isEmpty()) {
            return attachments;
        }
        if (media != null && !media.isEmpty()) {
            return media;
        }
        return List.of();
    }

    /**
     * 获取来源业务租户 ID。
     *
     * @return 来源业务租户 ID，缺省使用 100。
     */
    public String effectiveSourceTenantId() {
        if (sourceTenantId != null && !sourceTenantId.isBlank()) {
            return sourceTenantId.trim();
        }
        return "100";
    }
}

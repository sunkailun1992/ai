package com.kellen.conversation;

import java.time.LocalDateTime;

/**
 * AI 接口统一响应，便于前端和调用方稳定解析。
 *
 * @param success   是否调用成功。
 * @param model     本轮实际调用的模型名；非模型调用（如清空记忆）时为空。
 * @param content   模型返回内容或操作结果说明。
 * @param error     错误说明，成功时为空；只含摘要，不含异常堆栈。
 * @param timestamp 服务端生成响应的时间。
 */
public record AiChatResponse(
        boolean success, // 成功标记。
        String model, // 本轮实际调用的模型名。
        String content, // AI 输出内容或操作结果。
        String error, // 安全的错误摘要。
        LocalDateTime timestamp // 响应生成时间。
) {

    /**
     * 构建成功响应。
     *
     * @param model   本轮实际调用的模型名，可空。
     * @param content 模型输出或操作结果。
     * @return 成功响应实例。
     */
    public static AiChatResponse ok(String model, String content) {
        return new AiChatResponse(true, model, content, null, LocalDateTime.now()); // 成功时不返回错误字段。
    }

    /**
     * 构建失败响应。
     *
     * @param message 安全的错误描述。
     * @return 失败响应实例。
     */
    public static AiChatResponse error(String message) {
        return new AiChatResponse(false, null, null, message, LocalDateTime.now()); // 失败时只返回安全错误描述。
    }
}

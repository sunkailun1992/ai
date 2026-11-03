package com.kellen.conversation;

/**
 * 新建 AI 对话会话请求。
 *
 * @param title          可选会话标题；为空时使用“新对话”。
 * @param conversationId 可选会话 ID；用于小程序首次升级时接管本地缓存的旧会话。
 */
public record ConversationCreateRequest(
        String title, // 可选会话标题。
        String conversationId // 可选会话 ID。
) {
}

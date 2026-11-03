package com.kellen.conversation;

/**
 * 重命名 AI 对话会话请求。
 *
 * @param title 新会话标题。
 */
public record ConversationRenameRequest(
        String title // 新会话标题，后端会去除首尾空白并限制长度。
) {
}

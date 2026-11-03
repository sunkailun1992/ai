package com.kellen.agent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 默认模型选择响应。
 *
 * @param provider           当前默认 Provider。
 * @param model              Agent 执行层模型名。
 * @param supportedProviders 支持的 Provider 列表。
 * @param updatedAt          配置更新时间。
 */
public record AiModelSelectionResponse(
        String provider,
        String model,
        List<String> supportedProviders,
        LocalDateTime updatedAt
) {
}

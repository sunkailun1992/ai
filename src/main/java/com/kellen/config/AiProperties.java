package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Nacos 配置中心和本地启动兜底中 {@code ai.*} 配置段的类型安全绑定。
 *
 * <p>主 {@code ai} 服务不再直接持有 Spring AI 模型供应商配置，也不再连接向量库执行
 * RAG。它只负责业务状态、审计、文件令牌和会话记忆；真实模型执行由
 * Nacos AI Registry 发现的 {@code ai-agent} 完成。</p>
 *
 * @param dialogue      对话业务配置段（默认角色、多模态限制、记忆窗口）。
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        @DefaultValue DialogueProperties dialogue
) {

    /**
     * 对话业务配置：默认角色、多模态安全限制和记忆窗口。
     *
     * @param defaultRole           调用方未传 role 时使用的默认 AI 角色。
     * @param defaultSystemPrompt   数据库角色缺失时使用的极简兜底系统提示词。
     * @param maxMediaCount         单次多模态请求最多允许的媒体数量。
     * @param maxMediaBytes         单个远程媒体读取的最大字节数。
     * @param allowedMediaSchemes   允许读取的媒体 URL 协议白名单。
     * @param allowedMediaMimeTypes 允许读取的媒体 MIME 白名单，支持 image/* 这类通配。
     * @param memory                对话记忆窗口配置。
     */
    public record DialogueProperties(
            @DefaultValue("patient-analysis") String defaultRole,
            @DefaultValue("你是一个患者分析助手，负责帮助用户理解报告和病情信息，不能替代医生诊断、治疗、用药或急救判断。")
            String defaultSystemPrompt,
            @DefaultValue("4") int maxMediaCount,
            @DefaultValue("20971520") long maxMediaBytes,
            @DefaultValue({"http", "https"}) List<String> allowedMediaSchemes,
            @DefaultValue({"image/*", "audio/*", "video/*", "application/pdf", "text/plain", "text/markdown", "text/csv", "application/json"})
            List<String> allowedMediaMimeTypes,
            @DefaultValue MemoryProperties memory
    ) {
    }

    /**
     * 对话记忆窗口配置，两个限制同时生效，防止长会话撑爆 token 和小机器内存。
     *
     * @param maxMessages 单次传给 Agent 的最大历史消息条数。
     * @param windowDays  只读取最近 N 天的会话历史。
     */
    public record MemoryProperties(
            @DefaultValue("20") int maxMessages,
            @DefaultValue("7") int windowDays
    ) {
    }

}

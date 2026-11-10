package com.kellen.modelcontrolplane;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** 白名单目录摘要；AVAILABLE 只表示目录读取成功，不代表模型健康或业务已接入。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelControlPlaneResponse(boolean available, Status status, String platformVersion,
        String consoleBaseUrl, Instant checkedAt, List<Deployment> modelCatalog,
        List<TaskBinding> taskBindings) {
    public enum Status { AVAILABLE, NOT_CONFIGURED, UNREACHABLE }
    public enum Capability { CHAT, EMBEDDING, RERANK, OCR, AUDIO_TRANSCRIPTION, AUDIO_SPEECH, IMAGE, VIDEO, OTHER }
    public enum Verification { NOT_CHECKED, VERIFIED, FAILED, UNKNOWN }
    public enum BindingStatus { CONFIGURED, NOT_CONFIGURED, NOT_INTEGRATED }
    public enum BindingReason { RAG_INDEX_BOUND, BUSINESS_MANAGED, NOT_INTEGRATED }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Deployment(String id, String modelName, String provider, String model,
            List<Capability> capabilities, Long contextWindow, Verification verificationStatus,
            Instant verifiedAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskBinding(String taskCode, String taskName, Capability capability, String modelName,
            BindingStatus status, boolean readOnly, BindingReason reasonCode) { }
}

package com.kellen.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 患者长期记忆实体，对应 ai_patient_memory 表。
 *
 * <p>长期记忆保存跨会话可复用的患者级结构化事实。MySQL 是权威存储；向量库仅作为
 * 检索索引，使用 embedding_status/vector_* 字段跟踪同步状态。</p>
 */
@TableName("ai_patient_memory")
public class PatientMemoryEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String EMBEDDING_PENDING = "PENDING";

    @TableId(type = IdType.AUTO)
    private Long id; // 记忆主键。

    private String patientId; // 患者 ID。

    private String sourceTenantId; // 来源业务租户 ID。

    private String memoryType; // 记忆类型，例如 questionnaire_summary/questionnaire_answer。

    private String memoryKey; // 同一患者下稳定记忆键。

    private String title; // 记忆标题。

    private String content; // 可直接注入模型的短文本事实。

    private String factsJson; // 结构化事实 JSON。

    private String sourceType; // 来源类型，例如 QUESTIONNAIRE_SESSION。

    private String sourceId; // 来源业务 ID，例如问卷 sessionId。

    private Double confidence; // 置信度。

    private String status; // ACTIVE/ARCHIVED。

    private String embeddingStatus; // PENDING/SYNCED/DISABLED。

    private String vectorCollection; // Qdrant collection 名称。

    private String vectorId; // 向量点位 ID。

    private LocalDateTime occurredAt; // 事实发生时间。

    private LocalDateTime createdAt; // 创建时间。

    private LocalDateTime updatedAt; // 更新时间。

    /**
     * 创建患者长期记忆。
     */
    public static PatientMemoryEntity create(String patientId,
                                             String sourceTenantId,
                                             String memoryType,
                                             String memoryKey,
                                             String title,
                                             String content,
                                             String factsJson,
                                             String sourceType,
                                             String sourceId,
                                             double confidence,
                                             LocalDateTime occurredAt) {
        PatientMemoryEntity entity = new PatientMemoryEntity();
        entity.patientId = patientId;
        entity.sourceTenantId = normalizeSourceTenantId(sourceTenantId);
        entity.memoryType = memoryType;
        entity.memoryKey = memoryKey;
        entity.title = title;
        entity.content = content;
        entity.factsJson = factsJson;
        entity.sourceType = sourceType;
        entity.sourceId = sourceId;
        entity.confidence = confidence;
        entity.status = STATUS_ACTIVE;
        entity.embeddingStatus = EMBEDDING_PENDING;
        entity.occurredAt = occurredAt;
        return entity;
    }

    /**
     * 更新已有长期记忆。
     */
    public void refresh(String title,
                        String content,
                        String factsJson,
                        String sourceType,
                        String sourceId,
                        double confidence,
                        LocalDateTime occurredAt) {
        this.title = title;
        this.content = content;
        this.factsJson = factsJson;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.confidence = confidence;
        this.status = STATUS_ACTIVE;
        this.embeddingStatus = EMBEDDING_PENDING;
        this.occurredAt = occurredAt;
        this.updatedAt = LocalDateTime.now();
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (occurredAt == null) {
            occurredAt = now;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_ACTIVE;
        }
        if (embeddingStatus == null || embeddingStatus.isBlank()) {
            embeddingStatus = EMBEDDING_PENDING;
        }
        sourceTenantId = normalizeSourceTenantId(sourceTenantId);
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
        sourceTenantId = normalizeSourceTenantId(sourceTenantId);
    }

    private static String normalizeSourceTenantId(String sourceTenantId) {
        return sourceTenantId == null ? "" : sourceTenantId.trim();
    }

    public Long getId() {
        return id;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getSourceTenantId() {
        return sourceTenantId;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getFactsJson() {
        return factsJson;
    }

    public String getStatus() {
        return status;
    }
}

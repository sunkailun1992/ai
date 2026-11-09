package com.kellen.integration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 外部系统推送 outbox 实体，对应 ai_integration_outbox 表。
 *
 * <p>用户侧对话请求不直接同步调用外部系统。问卷完成后先写 outbox，
 * 后台任务后续再负责推送 SaaS、CGA、五云山或医院系统，失败可重试。</p>
 */
@TableName("ai_integration_outbox")
public class IntegrationOutboxEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // outbox 事件 ID。

    private String eventType; // 例如 QUESTIONNAIRE_COMPLETED。

    private String targetSystem; // 后续异步推送的系统标识。

    private String aggregateType; // 当前固定为 QUESTIONNAIRE_SESSION。

    private String aggregateId; // 问卷会话 ID 字符串。

    private String idempotencyKey; // 防止同一问卷完成事件重复写入或重复推送。

    private String payloadJson; // 后台任务读取后推送给目标系统。

    private String status; // PENDING / SENT / FAILED 等状态。

    private int retryCount; // 后台推送失败后的重试计数。

    private String lastError; // 异步推送失败时记录安全错误摘要。

    private LocalDateTime createdAt; // 事件写入时间。

    private LocalDateTime updatedAt; // 状态或重试信息更新时间。

    /**
     * 创建问卷完成事件。
     *
     * <p>该方法只负责生成待发送 outbox 记录，不直接调用外部系统；
     * 幂等键由事件类型、问卷会话和目标系统组成，避免同一会话重复推送。</p>
     *
     * @param sessionId    已完成的问卷会话 ID
     * @param targetSystem 后续异步推送目标系统
     * @param payloadJson  推送任务读取的事件载荷
     * @return 待写入数据库的 outbox 实体
     */
    public static IntegrationOutboxEntity questionnaireCompleted(Long sessionId, String targetSystem, String payloadJson) {
        IntegrationOutboxEntity entity = new IntegrationOutboxEntity();
        entity.eventType = "QUESTIONNAIRE_COMPLETED";
        entity.targetSystem = targetSystem == null || targetSystem.isBlank() ? "test-system" : targetSystem;
        entity.aggregateType = "QUESTIONNAIRE_SESSION";
        entity.aggregateId = String.valueOf(sessionId);
        entity.idempotencyKey = entity.eventType + ":" + entity.aggregateId + ":" + entity.targetSystem;
        entity.payloadJson = payloadJson;
        entity.status = "PENDING";
        entity.retryCount = 0;
        return entity;
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTargetSystem() {
        return targetSystem;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getStatus() {
        return status;
    }
}

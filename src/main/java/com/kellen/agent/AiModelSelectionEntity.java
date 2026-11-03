package com.kellen.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 默认模型选择配置。
 */
@TableName("ai_model_selection")
public class AiModelSelectionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String provider;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static AiModelSelectionEntity create(String configKey, String provider) {
        AiModelSelectionEntity entity = new AiModelSelectionEntity();
        entity.configKey = configKey;
        entity.provider = provider;
        return entity;
    }

    void beforeCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void beforeUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

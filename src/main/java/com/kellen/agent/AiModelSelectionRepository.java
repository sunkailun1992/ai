package com.kellen.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kellen.repository.AiBaseRepository;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * AI 默认模型选择 Repository。
 */
@Mapper
public interface AiModelSelectionRepository extends AiBaseRepository<AiModelSelectionEntity> {

    /**
     * 按配置键查询模型选择。
     *
     * @param configKey 配置键。
     * @return 匹配配置。
     */
    default Optional<AiModelSelectionEntity> findByConfigKey(String configKey) {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiModelSelectionEntity>()
                .eq("config_key", configKey)
                .last("LIMIT 1")));
    }
}

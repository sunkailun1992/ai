package com.kellen.integration;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.Optional;

/**
 * 外部系统推送 outbox Repository。
 */
@Mapper
public interface IntegrationOutboxRepository extends AiBaseRepository<IntegrationOutboxEntity> {

    /**
     * 通过幂等键查询 outbox 事件，避免重复写入同一问卷完成事件。
     *
     * @param idempotencyKey 幂等键。
     * @return 已存在的 outbox 事件。
     */
    default Optional<IntegrationOutboxEntity> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(selectOne(new QueryWrapper<IntegrationOutboxEntity>()
                .eq("idempotency_key", idempotencyKey)
                .last("LIMIT 1")));
    }
}

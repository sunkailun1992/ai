package com.kellen.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 患者长期记忆 Repository。
 */
@Mapper
public interface PatientMemoryRepository extends AiBaseRepository<PatientMemoryEntity> {

    /**
     * 按患者、来源租户和稳定 key 查询当前记忆。
     */
    default Optional<PatientMemoryEntity> findActiveByPatientAndKey(String patientId,
                                                                    String sourceTenantId,
                                                                    String memoryKey) {
        return Optional.ofNullable(selectOne(new QueryWrapper<PatientMemoryEntity>()
                .eq("patient_id", patientId)
                .eq("source_tenant_id", normalize(sourceTenantId))
                .eq("memory_key", memoryKey)
                .eq("status", PatientMemoryEntity.STATUS_ACTIVE)));
    }

    /**
     * 查询患者最新长期记忆，用于注入模型上下文。
     */
    default List<PatientMemoryEntity> findActiveByPatient(String patientId, String sourceTenantId, int limit) {
        QueryWrapper<PatientMemoryEntity> wrapper = new QueryWrapper<PatientMemoryEntity>()
                .eq("patient_id", patientId)
                .eq("status", PatientMemoryEntity.STATUS_ACTIVE)
                .orderByDesc("updated_at")
                .last("LIMIT " + Math.max(1, limit));
        if (StringUtils.hasText(sourceTenantId)) {
            wrapper.eq("source_tenant_id", sourceTenantId.trim());
        }
        return selectList(wrapper);
    }

    private static String normalize(String sourceTenantId) {
        return sourceTenantId == null ? "" : sourceTenantId.trim();
    }
}

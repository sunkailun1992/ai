package com.kellen.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/** 读取已有患者长期记忆；写入与治理由独立记忆服务负责。 */
@Service
public class PatientMemoryService {

    private static final int DEFAULT_LONG_MEMORY_LIMIT = 12;
    private static final int DEFAULT_LONG_MEMORY_CONTEXT_CHARS = 2400;

    private final PatientMemoryRepository repository;

    public PatientMemoryService(PatientMemoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String patientContext(String patientId, String sourceTenantId) {
        if (!StringUtils.hasText(patientId)) {
            return "";
        }
        String context = repository.findActiveByPatient(patientId.trim(), sourceTenantId, DEFAULT_LONG_MEMORY_LIMIT)
                .stream()
                .map(memory -> "- " + memory.getTitle() + "：" + memory.getContent())
                .collect(Collectors.joining("\n"));
        if (context.length() <= DEFAULT_LONG_MEMORY_CONTEXT_CHARS) {
            return context;
        }
        return context.substring(0, DEFAULT_LONG_MEMORY_CONTEXT_CHARS);
    }

}

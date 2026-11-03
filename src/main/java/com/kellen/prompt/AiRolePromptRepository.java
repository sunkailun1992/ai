package com.kellen.prompt;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Optional;

/**
 * AI 角色 Prompt Repository，负责 ai_role_prompt 表的数据访问。
 */
@Mapper
public interface AiRolePromptRepository extends AiBaseRepository<AiRolePromptEntity> {

    /**
     * 查询启用状态的指定角色 Prompt。
     *
     * @param roleKey 角色 key。
     * @return 匹配角色。
     */
    default Optional<AiRolePromptEntity> findFirstByRoleKeyAndEnabledTrueOrderByUpdatedAtDesc(String roleKey) {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiRolePromptEntity>()
                .eq("role_key", roleKey)
                .eq("enabled", true)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询启用状态的默认角色 Prompt。
     *
     * @return 默认角色。
     */
    default Optional<AiRolePromptEntity> findFirstByDefaultRoleTrueAndEnabledTrueOrderByUpdatedAtDesc() {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiRolePromptEntity>()
                .eq("default_role", true)
                .eq("enabled", true)
                .orderByDesc("updated_at")
                .last("LIMIT 1")));
    }

    /**
     * 查询所有启用角色，供错误提示和管理接口复用。
     *
     * @return 启用角色列表。
     */
    default List<AiRolePromptEntity> findByEnabledTrueOrderByRoleKeyAsc() {
        return selectList(new QueryWrapper<AiRolePromptEntity>()
                .eq("enabled", true)
                .orderByAsc("role_key"));
    }

    /**
     * 检查角色 key 是否已经存在，启动种子只在缺失时写入默认角色。
     *
     * @param roleKey 角色 key。
     * @return 存在时返回 true。
     */
    default boolean existsByRoleKey(String roleKey) {
        return selectCount(new QueryWrapper<AiRolePromptEntity>().eq("role_key", roleKey)) > 0;
    }
}

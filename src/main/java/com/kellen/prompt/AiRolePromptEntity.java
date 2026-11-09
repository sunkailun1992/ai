package com.kellen.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.kellen.repository.AiGovernanceEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 角色提示词实体，对应 ai_role_prompt 表。
 *
 * <p>角色 Prompt 属于运行时业务配置，使用数据库持久化，避免把可运营的角色设定
 * 长期固化在 application.yml 中。</p>
 */
@TableName("ai_role_prompt")
public class AiRolePromptEntity extends AiGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 角色提示词主键。

    private String roleKey; // 接口入参 role 使用的稳定 key，例如 patient-analysis。

    private String roleName; // 展示名称，例如患者分析助手。

    private String systemPrompt; // 该角色完整系统提示词。

    @TableField("prompt_version")
    private String version; // Prompt 版本号，便于运营回溯。

    private String description; // 角色用途说明。

    private boolean defaultRole; // 是否默认角色。

    private boolean enabled = true; // 是否启用。

    private LocalDateTime createdAt; // 创建时间。

    private LocalDateTime updatedAt; // 更新时间。

    /**
     * 创建启用状态的角色 Prompt。
     *
     * @param roleKey      角色 key。
     * @param roleName     角色名称。
     * @param systemPrompt 完整系统提示词。
     * @param defaultRole  是否默认角色。
     * @param version      Prompt 版本号。
     * @param description  角色说明。
     * @return 角色 Prompt 实体。
     */
    public static AiRolePromptEntity create(String roleKey,
                                            String roleName,
                                            String systemPrompt,
                                            boolean defaultRole,
                                            String version,
                                            String description) {
        AiRolePromptEntity entity = new AiRolePromptEntity();
        entity.roleKey = roleKey;
        entity.roleName = roleName;
        entity.systemPrompt = systemPrompt;
        entity.defaultRole = defaultRole;
        entity.version = version;
        entity.description = description;
        entity.enabled = true;
        return entity;
    }

    /**
     * MyBatis-Plus 写入前维护创建和更新时间。
     */
    void beforeCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * MyBatis-Plus 更新前维护更新时间。
     */
    void beforeUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** @return 角色 key。 */
    public String getRoleKey() {
        return roleKey;
    }

    /** @return 角色名称。 */
    public String getRoleName() {
        return roleName;
    }

    /** @return 完整系统提示词。 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** @return 是否默认角色。 */
    public boolean isDefaultRole() {
        return defaultRole;
    }

    /** @return 是否启用。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return Prompt 版本号。 */
    public String getVersion() {
        return version;
    }
}

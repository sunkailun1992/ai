package com.kellen.repository;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * AI 独立自增实体的公共字段：创建人（列 create_by）、更新时间（列 update_time）、更新人（列 update_by）与创建部门（列 create_dept）。
 *
 * <p>由共享 MyMetaObjectHandler 在 Mapper 插入、更新时填充：更新时间替代 MySQL ON UPDATE；创建人、创建部门插入时取当前
 * 登录用户，更新人插入和更新时取当前登录用户，没有登录用户时为空或不改。不继承字符串主键，不改变实体既有 updatedAt 等
 * 业务时间，也不增加响应 JSON 字段。</p>
 */
public abstract class AiGovernanceEntity {

    @JsonIgnore
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private String createBy;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @JsonIgnore
    @TableField(value = "update_by", fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    @JsonIgnore
    @TableField(value = "create_dept", fill = FieldFill.INSERT)
    private String createDept;
}

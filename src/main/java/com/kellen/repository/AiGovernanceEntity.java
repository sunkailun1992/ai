package com.kellen.repository;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * AI 独立自增实体的公共修改时间。
 *
 * <p>由共享 MyMetaObjectHandler 在 Mapper 插入、更新时填充，替代 MySQL ON UPDATE；
 * 不继承字符串主键，不改变实体既有 updatedAt 等业务时间，也不增加响应 JSON 字段。</p>
 */
public abstract class AiGovernanceEntity {

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime modifyDateTime;
}

package com.kellen.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.kellen.entity.TenantEntityBase;
import com.kellen.example.entity.enums.ExampleStateEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 示例业务对象。
 * <p>
 * AI 新增实体时，有租户的表继承 TenantEntityBase（无租户的继承 EntityBase）。
 * 基类只承接 createTime、updateTime、creator、updater、deleted 与 tenantId，由 MyMetaObjectHandler 自动填充；
 * id、乐观锁 version，以及 code、description、sorting、type、state 等用到才由当前实体声明。
 *
 * @author sunkailun
 * @className ExampleEntity
 * @time 2026/05/26
 */
@Getter
@Setter
@TableName("example_record")
@Schema(description = "示例业务对象")
public class ExampleEntity extends TenantEntityBase {

    /**
     * 主键，字符串雪花ID。
     */
    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 业务编码，查询白名单允许按它查询和排序。
     */
    @Schema(description = "编码")
    private String code;

    /**
     * 排序值。
     */
    @Schema(description = "排序")
    private Integer sorting;

    /**
     * MyBatis-Plus 乐观锁版本号，更新时回传查询到的值。
     */
    @Version
    @Schema(description = "版本号")
    private Integer version;

    /**
     * 示例名称。
     */
    @Schema(description = "示例名称")
    private String name;

    /**
     * 示例状态。
     * <p>
     * state 是业务状态字段，必须由当前业务模块定义自己的 IEnum，
     * 不要把业务状态枚举放入实体基类。
     */
    @Schema(description = "示例状态")
    private ExampleStateEnum state;
}

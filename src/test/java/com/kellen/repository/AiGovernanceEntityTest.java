package com.kellen.repository;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kellen.config.mybatis.MyMetaObjectHandler;
import com.kellen.memory.ChatMemoryEntity;
import com.kellen.security.SecurityUser;
import com.kellen.security.UserContextHolder;
import com.kellen.security.config.TenantProperties;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 实体的公共字段：插入时由 utils 的填充器写入当前登录用户的部门，不出现在响应 JSON 里。 */
class AiGovernanceEntityTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void createDeptIsAnInsertFilledColumnHiddenFromJson() throws Exception {
        var field = AiGovernanceEntity.class.getDeclaredField("createDept");
        assertThat(field.getAnnotation(TableField.class).value()).isEqualTo("create_dept");
        assertThat(field.getAnnotation(TableField.class).fill()).isEqualTo(FieldFill.INSERT);
        assertThat(field.isAnnotationPresent(JsonIgnore.class)).isTrue();
    }

    @Test
    void insertFillsCreateDeptFromTheLoggedInUserAndLeavesItEmptyWithoutOne() throws Exception {
        var handler = new MyMetaObjectHandler(new TenantProperties());
        var field = AiGovernanceEntity.class.getDeclaredField("createDept");
        field.setAccessible(true);

        var anonymous = new ChatMemoryEntity();
        handler.insertFill(SystemMetaObject.forObject(anonymous));
        assertThat(field.get(anonymous)).isNull();

        UserContextHolder.set(new SecurityUser("u-1", "fixture", "t-1", "d-1", "ALL", List.of(), List.of()));
        var entity = new ChatMemoryEntity();
        handler.insertFill(SystemMetaObject.forObject(entity));
        assertThat(field.get(entity)).isEqualTo("d-1");
    }
}

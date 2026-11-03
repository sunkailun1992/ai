package com.kellen.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * AI 服务 MyBatis-Plus Mapper 基础接口。
 *
 * <p>本次持久层从 JPA 迁移到 MyBatis-Plus，但业务服务已经稳定依赖
 * {@code save/findById/findAll} 这些 Repository 语义。这里保留兼容方法，
 * 同时在写入前触发实体里的时间维护方法，避免业务层感知 ORM 替换。</p>
 *
 * @param <T> 实体类型。
 */
public interface AiBaseRepository<T> extends BaseMapper<T> {

    /**
     * 插入或按主键更新实体。
     *
     * @param entity 待保存实体。
     * @return 保存后的同一实体，插入后包含数据库生成主键。
     */
    default T save(T entity) {
        if (idValue(entity) == null) {
            invokeLifecycle(entity, "prePersist", "beforeCreate", "touchUpdatedAt");
            insert(entity);
            return entity;
        }
        invokeLifecycle(entity, "preUpdate", "beforeUpdate", "touchUpdatedAt");
        updateById(entity);
        return entity;
    }

    /**
     * MyBatis-Plus 没有 JPA flush 语义；这里保留测试和旧服务代码的兼容入口。
     *
     * @param entity 待保存实体。
     * @return 保存后的实体。
     */
    default T saveAndFlush(T entity) {
        return save(entity);
    }

    /**
     * 按主键查询实体。
     *
     * @param id 主键。
     * @return 匹配实体。
     */
    default Optional<T> findById(Serializable id) {
        return Optional.ofNullable(selectById(id));
    }

    /**
     * 查询当前表全部记录，主要供测试断言和小规模配置表使用。
     *
     * @return 全部记录。
     */
    default List<T> findAll() {
        return selectList(new QueryWrapper<>());
    }

    private Object idValue(T entity) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            return field.get(entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("entity id field is required for MyBatis-Plus save: " + entity.getClass().getName(), exception);
        }
    }

    private void invokeLifecycle(T entity, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = entity.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(entity);
            } catch (NoSuchMethodException ignored) {
                // Not every entity needs both insert and update lifecycle hooks.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("failed to invoke entity lifecycle method: " + methodName, exception);
            }
        }
    }
}

package com.kellen.file;

import com.kellen.repository.AiBaseRepository;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.Optional;

/**
 * 既有私有文件对象 Repository。
 *
 * <p>负责读取和保存私有 OSS 对象元数据；展示 token 明文不入库，
 * 只能通过业务服务生成、哈希和校验。</p>
 */
@Mapper
public interface AiFileObjectRepository extends AiBaseRepository<AiFileObjectEntity> {

    /**
     * 判断 OSS objectKey 是否已登记，避免同一对象重复绑定多个文件记录。
     *
     * @param objectKey OSS 对象 key。
     * @return true 表示已存在。
     */
    default boolean existsByObjectKey(String objectKey) {
        return selectCount(new QueryWrapper<AiFileObjectEntity>().eq("object_key", objectKey)) > 0;
    }

    /**
     * 按文件 ID 和状态查询文件。
     *
     * @param id     文件 ID。
     * @param status 文件状态。
     * @return 匹配文件。
     */
    default Optional<AiFileObjectEntity> findByIdAndStatus(Long id, String status) {
        return Optional.ofNullable(selectOne(new QueryWrapper<AiFileObjectEntity>()
                .eq("id", id)
                .eq("status", status)
                .last("LIMIT 1")));
    }
}

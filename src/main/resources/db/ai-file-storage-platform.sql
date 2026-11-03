-- 新增存储平台路由；历史空值保留原 OSS 读路径，不迁移任何文件。
-- 空库预建与 DDL runner 可能先后执行，必须在当前 schema 中检查列是否已存在。
SET @ai_file_storage_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_file_object'
      AND column_name = 'storage_platform'
);
SET @ai_file_storage_ddl = IF(
    @ai_file_storage_column_exists > 0,
    'SELECT 1',
    'ALTER TABLE ai_file_object ADD COLUMN storage_platform VARCHAR(128) NULL COMMENT ''对象存储平台，空值为迁移前OSS'' AFTER object_key'
);
PREPARE ai_file_storage_statement FROM @ai_file_storage_ddl;
EXECUTE ai_file_storage_statement;
DEALLOCATE PREPARE ai_file_storage_statement;

-- 每张表都必须有全部基础字段：V5 只给 7 张 AiGovernanceEntity 表加了 create_dept，这里给存储三表补上（创建部门ID，可空）。
-- 存储三表是平台级配置，由注解 SQL 写入，不经 MyBatis-Plus 自动填充，新列暂为空。只加列，不删列、不改已有数据。

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_configuration ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_storage_configuration.create_dept IS '创建部门ID';

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_operation ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_storage_operation.create_dept IS '创建部门ID';

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_setting ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_storage_setting.create_dept IS '创建部门ID';

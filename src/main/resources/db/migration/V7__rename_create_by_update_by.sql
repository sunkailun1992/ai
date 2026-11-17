-- 审计列改为主流项目的多数命名（RuoYi-Vue、RuoYi-Vue-Plus、JeecgBoot）：creator→create_by、updater→update_by。
-- 只改列名，列注释、默认值与数据随列保留；已提交的 V3 不修改。

-- AI对话记忆表，按角色逐条保存可回放给模型的文本上下文。
ALTER TABLE ai_chat_memory RENAME COLUMN creator TO create_by;
ALTER TABLE ai_chat_memory RENAME COLUMN updater TO update_by;

-- AI会话短记忆摘要表，保存被裁剪历史的滚动摘要。
ALTER TABLE ai_chat_memory_summary RENAME COLUMN creator TO create_by;
ALTER TABLE ai_chat_memory_summary RENAME COLUMN updater TO update_by;

-- AI对话审计记录表，保存请求、响应、模型、媒体摘要和错误摘要。
ALTER TABLE ai_chat_message RENAME COLUMN creator TO create_by;
ALTER TABLE ai_chat_message RENAME COLUMN updater TO update_by;

-- AI小程序文件对象表，用于保存私有OSS对象元数据和展示token哈希。
ALTER TABLE ai_file_object RENAME COLUMN creator TO create_by;
ALTER TABLE ai_file_object RENAME COLUMN updater TO update_by;

-- AI默认模型选择配置表。
ALTER TABLE ai_model_selection RENAME COLUMN creator TO create_by;
ALTER TABLE ai_model_selection RENAME COLUMN updater TO update_by;

-- AI患者长期记忆表，保存跨会话可复用的患者级结构化事实。
ALTER TABLE ai_patient_memory RENAME COLUMN creator TO create_by;
ALTER TABLE ai_patient_memory RENAME COLUMN updater TO update_by;

-- AI角色提示词表，用于运行时配置患者分析等可切换角色的系统提示词。
ALTER TABLE ai_role_prompt RENAME COLUMN creator TO create_by;
ALTER TABLE ai_role_prompt RENAME COLUMN updater TO update_by;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_configuration RENAME COLUMN creator TO create_by;
ALTER TABLE ai_storage_configuration RENAME COLUMN updater TO update_by;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_operation RENAME COLUMN creator TO create_by;
ALTER TABLE ai_storage_operation RENAME COLUMN updater TO update_by;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_setting RENAME COLUMN creator TO create_by;
ALTER TABLE ai_storage_setting RENAME COLUMN updater TO update_by;

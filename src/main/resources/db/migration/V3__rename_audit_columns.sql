-- 审计列按实体基类改名：create_date_time→create_time、modify_date_time→update_time、create_name→creator、
-- modify_name→updater、is_delete→is_deleted。列注释、默认值与索引随列保留，数据不变。
-- 只处理保留的 10 张表；8 张没人读写的表在 V5 整表删除，不先改名。

-- AI对话记忆表，按角色逐条保存可回放给模型的文本上下文。
ALTER TABLE ai_chat_memory RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_chat_memory RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_chat_memory RENAME COLUMN create_name TO creator;
ALTER TABLE ai_chat_memory RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_chat_memory RENAME COLUMN is_delete TO is_deleted;

-- AI会话短记忆摘要表，保存被裁剪历史的滚动摘要。
ALTER TABLE ai_chat_memory_summary RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_chat_memory_summary RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_chat_memory_summary RENAME COLUMN create_name TO creator;
ALTER TABLE ai_chat_memory_summary RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_chat_memory_summary RENAME COLUMN is_delete TO is_deleted;

-- AI对话审计记录表，保存请求、响应、模型、媒体摘要和错误摘要。
ALTER TABLE ai_chat_message RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_chat_message RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_chat_message RENAME COLUMN create_name TO creator;
ALTER TABLE ai_chat_message RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_chat_message RENAME COLUMN is_delete TO is_deleted;

-- AI小程序文件对象表，用于保存私有OSS对象元数据和展示token哈希。
ALTER TABLE ai_file_object RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_file_object RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_file_object RENAME COLUMN create_name TO creator;
ALTER TABLE ai_file_object RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_file_object RENAME COLUMN is_delete TO is_deleted;

-- AI默认模型选择配置表。
ALTER TABLE ai_model_selection RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_model_selection RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_model_selection RENAME COLUMN create_name TO creator;
ALTER TABLE ai_model_selection RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_model_selection RENAME COLUMN is_delete TO is_deleted;

-- AI患者长期记忆表，保存跨会话可复用的患者级结构化事实。
ALTER TABLE ai_patient_memory RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_patient_memory RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_patient_memory RENAME COLUMN create_name TO creator;
ALTER TABLE ai_patient_memory RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_patient_memory RENAME COLUMN is_delete TO is_deleted;

-- AI角色提示词表，用于运行时配置患者分析等可切换角色的系统提示词。
ALTER TABLE ai_role_prompt RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_role_prompt RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_role_prompt RENAME COLUMN create_name TO creator;
ALTER TABLE ai_role_prompt RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_role_prompt RENAME COLUMN is_delete TO is_deleted;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_configuration RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_storage_configuration RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_storage_configuration RENAME COLUMN create_name TO creator;
ALTER TABLE ai_storage_configuration RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_storage_configuration RENAME COLUMN is_delete TO is_deleted;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_operation RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_storage_operation RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_storage_operation RENAME COLUMN create_name TO creator;
ALTER TABLE ai_storage_operation RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_storage_operation RENAME COLUMN is_delete TO is_deleted;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_setting RENAME COLUMN create_date_time TO create_time;
ALTER TABLE ai_storage_setting RENAME COLUMN modify_date_time TO update_time;
ALTER TABLE ai_storage_setting RENAME COLUMN create_name TO creator;
ALTER TABLE ai_storage_setting RENAME COLUMN modify_name TO updater;
ALTER TABLE ai_storage_setting RENAME COLUMN is_delete TO is_deleted;

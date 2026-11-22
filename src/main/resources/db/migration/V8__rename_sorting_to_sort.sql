-- 排序列 sorting 改名 sort（第6阶段第三部分，对应 utils 2.0.9 的 EntityBase.sort）。
-- 只改列名，列注释、默认值与数据随列保留；本库没有名字或定义里带 sorting 的索引。
-- 每张表只由本服务读写、前后端同批发布，按《Refactoring Databases》的 Rename Column 直接改名，不留过渡列。

-- AI对话记忆表，按角色逐条保存可回放给模型的文本上下文。
ALTER TABLE ai_chat_memory RENAME COLUMN sorting TO sort;

-- AI会话短记忆摘要表，保存被裁剪历史的滚动摘要。
ALTER TABLE ai_chat_memory_summary RENAME COLUMN sorting TO sort;

-- AI对话审计记录表，保存请求、响应、模型、媒体摘要和错误摘要。
ALTER TABLE ai_chat_message RENAME COLUMN sorting TO sort;

-- AI小程序文件对象表，用于保存私有OSS对象元数据和展示token哈希。
ALTER TABLE ai_file_object RENAME COLUMN sorting TO sort;

-- AI默认模型选择配置表。
ALTER TABLE ai_model_selection RENAME COLUMN sorting TO sort;

-- AI患者长期记忆表，保存跨会话可复用的患者级结构化事实。
ALTER TABLE ai_patient_memory RENAME COLUMN sorting TO sort;

-- AI角色提示词表，用于运行时配置患者分析等可切换角色的系统提示词。
ALTER TABLE ai_role_prompt RENAME COLUMN sorting TO sort;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_configuration RENAME COLUMN sorting TO sort;
ALTER TABLE ai_storage_operation RENAME COLUMN sorting TO sort;
ALTER TABLE ai_storage_setting RENAME COLUMN sorting TO sort;

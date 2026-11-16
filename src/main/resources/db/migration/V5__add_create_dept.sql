-- 新增创建部门：继承 AiGovernanceEntity 的 7 张表加 create_dept，插入时由 utils 自动填当前登录用户的部门，没有登录用户（服务调用、后台任务）时为空。
-- 只加列，不删列、不改已有数据；已有行的创建部门未知，保持为空。存储三表是平台级配置，不加。

-- AI对话记忆表，按角色逐条保存可回放给模型的文本上下文。
ALTER TABLE ai_chat_memory ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_chat_memory.create_dept IS '创建部门ID';

-- AI会话短记忆摘要表，保存被裁剪历史的滚动摘要。
ALTER TABLE ai_chat_memory_summary ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_chat_memory_summary.create_dept IS '创建部门ID';

-- AI对话审计记录表，保存请求、响应、模型、媒体摘要和错误摘要。
ALTER TABLE ai_chat_message ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_chat_message.create_dept IS '创建部门ID';

-- AI小程序文件对象表，用于保存私有OSS对象元数据和展示token哈希。
ALTER TABLE ai_file_object ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_file_object.create_dept IS '创建部门ID';

-- AI默认模型选择配置表。
ALTER TABLE ai_model_selection ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_model_selection.create_dept IS '创建部门ID';

-- AI患者长期记忆表，保存跨会话可复用的患者级结构化事实。
ALTER TABLE ai_patient_memory ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_patient_memory.create_dept IS '创建部门ID';

-- AI角色提示词表，用于运行时配置患者分析等可切换角色的系统提示词。
ALTER TABLE ai_role_prompt ADD COLUMN create_dept varchar(64) COLLATE fleet_general_ci;
COMMENT ON COLUMN ai_role_prompt.create_dept IS '创建部门ID';

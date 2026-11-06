# AI 项目编码规范

## 通用修改入口

先按根 `AGENTS.md`、`BRANCHING_SPEC.md` 核对仓库、WIP、最新 main 与任务授权；版本按 `VERSIONING_SPEC.md` 做进位和占用检查。main/test/preprod 的集成证据、远端核对、短分支清理与 worktree 保护分别记录；纯文档不重复跑 Java 测试或额外提升制品版本。

本文档面向 AI / Codex / 新协作者。修改本项目代码前，先阅读根目录 `AGENTS.md`、`README.md`，再阅读本目录其他规范。

## 1. 项目定位

`ai` 是 AI 患者分析助手的主业务服务。它负责入口编排、微信小程序用户、会话、文件 token、问卷状态机、普通聊天记忆和调用审计。

真实模型执行属于 `ai-agent` 或后续独立 Agent 服务。主 `ai` 服务只通过 Nacos AI Registry 读取 AgentCard，再调用 Agent 暴露的 HTTP 接口；不得重新引入 Spring AI 模型客户端、本地 Qdrant RAG 执行链、本地 Provider 切换或本地模型兜底。`qdrant.yaml` 仅作为备用向量数据库连接配置保留，是否实际连接由明确业务入口和开关控制。

## 2. 构建与运行

```bash
./gradlew test --no-daemon
./gradlew bootRun
```

运行配置通过 `src/main/resources/application.yml` 选择 profile，再由 `application-dev.yml`、`application-test.yml`、`application-prod.yml` 接入对应环境的 Nacos 配置中心。真实模型 Key、数据库密码、OSS 密钥和 Nacos 凭据必须放在配置中心或环境变量，不得提交到仓库、测试资源或 GitHub Actions 明文。

## 3. 必要配置

本地 profile 文件只导入当前主服务实际需要的 Data ID：

- `logging.yml`
- `reuse-configuration.yaml`
- `traffic-governance.yaml`
- `swagger.yaml`
- `ai.yaml`
- `ai-spring.yaml`
- `qdrant.yaml`
- `mybatis-plus.yaml`
- `redis.yaml`
- `rabbitmq.yaml`
- `elasticsearch.yaml`
- `seata.yaml`
- `dubbo.yaml`
- `xxl-job.yaml`
- `a2a.yaml`

`ai.yaml` 必须配置：

- `ai.agent-client.timeout`
- `ai.agent-registry.enabled`
- `ai.agent-registry.server-addr`，必须引用 `reuse-configuration.yaml` 的 `${custom.infra-nacos-addr}`，不得写裸 IP。
- `ai.agent-registry.namespace`，必须引用 `${custom.namespace}` 或环境变量。
- `ai.agent-registry.context-path`
- `ai.agent-registry.patient-analysis-agent-name`
- `ai.agent-registry.questionnaire-agent-name`
- `ai.dialogue.*`
- `ai.questionnaire.ai-interpreter-enabled`
- `ai.wechat.*`
- `ai.oss.*`

不再配置：

- `spring.ai.*`
- `ai.provider`
- `ai.deepseek.*`
- `ai.google.*`
- `ai.qwen.*`
- `system_parameter.ai_provider`

`qdrant.yaml` 允许保存 Qdrant 连接参数；这不允许主服务默认注入 Spring AI VectorStore 或绕过 Agent 体系执行本地 RAG。

## 4. 分层规则

- `controller`：HTTP 入参出参，不直接拼 Prompt，不接触 Agent SDK 或模型 SDK。
- `config`：类型安全配置绑定、DDL 初始化和 Nacos AI Registry 配置。
- `service`：业务编排和单一职责组件。
- `dto`：接口请求/响应结构。
- `entity`：数据库持久化实体。
- `repository`：MyBatis-Plus 数据访问接口。
- `exception`：统一异常响应。

关键服务职责：

- `AiDialogueService`：问卷优先路由、角色 Prompt、记忆裁剪、Agent 调用、审计和历史展示。
- `NacosAiAgentRegistryClient`：使用 Nacos 3.x 官方 AI SDK 读取 AgentCard。
- `AiAgentClient`：按 AgentCard 接口地址调用 `ai-agent`。
- `RagRetrievalClient`：通过 Dubbo RPC 调用独立 `rag` 服务获取检索上下文；无资料或调用失败时返回空上下文，不阻断 Agent 调用。
- `AiRolePromptService`：从 `ai_role_prompt` 解析角色 Prompt。
- `DbChatMemoryService`：保存主服务可控的普通对话文本记忆。
- `MediaInputValidator`：校验附件 URL 数量、协议、Content-Type 和 MIME。
- `ChatAuditService`：审计落库。
- `OssFileService`：OSS token 上传和展示。

## 5. Agent 调用规则

- 主服务不得直接注入 `ChatClient`、`OpenAiChatModel`、Google SDK、DeepSeek SDK 或 Spring AI VectorStore。
- 主服务不得保留本地 `baseUrl` 或服务发现兜底绕过 Nacos AI Registry。
- Nacos AI Registry 没有对应 AgentCard 或接口地址时，应显式失败。
- 患者分析 Agent：`patient-analysis-agent`。
- 问卷 Agent：`questionnaire-agent`。
- Agent 接口协议当前使用 `HTTP+JSON`；患者分析接口开启流式传输。

## 6. 对话记忆规则

- `ai_chat_memory` 只保存普通聊天 user / assistant 文本上下文。
- 主服务在调用 Agent 前裁剪记忆窗口，并以文本上下文传给 Agent。
- 问卷解释、问卷自然追问、候选 JSON、题目上下文不得写入普通聊天记忆。
- `ai_chat_conversation` 是小程序会话元数据表。
- `ai_chat_conversation_message` 是小程序历史展示表，可保存附件 JSON。
- `ai_chat_message` 是审计表，不是模型记忆表。

## 7. 问卷规则

- 问卷模板、会话、答案、附件摘要和 outbox 属于业务库数据。
- 问卷模板以 `ai_questionnaire_template` 为源，不迁到 Nacos AI Registry。
- 问卷启动策略固定为 `RESUME_UNFINISHED`。
- 问卷 Agent 只负责自然提问和答案解释，不直接写库、不推进状态、不推送外部系统。
- `ai.questionnaire.ai-interpreter-enabled=false` 只用于测试或明确降级场景。

## 8. 数据库规则

- 表结构和种子数据由 `MysqlDdl.java` 加载 `src/main/resources/db/ai-init.sql` 初始化。
- 全新或清空后的业务库首次启动前，建议先在目标业务库手动执行 `db/ai-init.sql`；Seata AT 会在 `DataSource` 初始化时先检查 `undo_log`，不能依赖 MyBatis-Plus DDL 首次启动自动创建该表。
- 新增表、字段、索引和种子数据统一同步到 `db/ai-init.sql`。
- 测试环境使用 `src/test/resources/schema.sql`。
- 数据库访问统一使用 MyBatis-Plus Repository。
- 不拼接用户输入形成 SQL。
- 新增字段必须同步 MySQL DDL 注释和 H2 测试 schema。
- 新增业务表默认补齐公共治理字段：`code`、`description`、`create_date_time`、`create_name`、`modify_date_time`、`modify_name`、`is_delete`、`type`、`state`、`label`、`sorting`、`version`、`tenant_id`。
- 数据库列 `version` 只表示乐观锁版本号；Prompt、问卷模板、接口协议等业务版本必须使用更明确的列名，例如 `prompt_version`、`template_version`、`protocol_version`。
- 现有 AI 业务表保留 `bigint` 自增主键和 `created_at` / `updated_at` 运行时字段，后续新增通用后台管理型业务表再优先继承 `EntityBase`。

## 9. 安全规则

- 多模态 URL 必须校验协议、数量、MIME 和远程 Content-Type。
- 文件、图片、PDF、JSON 等资料必须先上传到文件服务或 OSS，再作为 `attachments.url` 传入。
- AI 对话接口不得重新暴露 `multipart/form-data` 文件流入口。
- 异常响应不得暴露内部堆栈、密钥、数据库地址、完整媒体 URL 或内部服务路径。
- 患者分析角色必须保留医疗安全边界，不能替代医生诊断、治疗、用药或急救判断。

## 10. 验证

```bash
./gradlew test --no-daemon
bash -n scripts/check-nacos-ai-config.sh
NACOS_USERNAME=nacos NACOS_PASSWORD=nacos bash scripts/check-nacos-ai-config.sh
```

涉及 Nacos AI Registry、Agent 接口、DDL、问卷状态机、文件 token 或会话归属时，必须执行相关测试或说明无法执行原因。

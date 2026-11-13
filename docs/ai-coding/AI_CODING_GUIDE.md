# AI 项目编码规范

## 通用修改入口

先按根 `AGENTS.md`、`BRANCHING_SPEC.md` 核对仓库、WIP、最新 main 与任务授权；版本按 `VERSIONING_SPEC.md` 做进位和占用检查。main 的集成证据、远端核对、短分支清理与 worktree 保护分别记录；纯文档不重复跑 Java 测试或额外提升制品版本。

本文档面向 AI / Codex / 新协作者。修改本项目代码前，先阅读根目录 `AGENTS.md`、`README.md`，再阅读本目录其他规范。

## 1. 项目定位

`ai` 是 AI 患者分析助手的主业务服务。它负责User 认证入口编排、文件 token、普通聊天记忆和调用审计。

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
- `a2a.yaml`

`ai.yaml` 必须配置：

- `ai.agent-client.timeout`
- `ai.agent-registry.enabled`
- `ai.agent-registry.server-addr`，必须引用 `reuse-configuration.yaml` 的 `${custom.infra-nacos-addr}`，不得写裸 IP。
- `ai.agent-registry.namespace`，必须引用 `${custom.namespace}` 或环境变量。
- `ai.agent-registry.context-path`
- `ai.agent-registry.patient-analysis-agent-name`
- `ai.dialogue.*`
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

以下是职责分层，不是包名；代码按功能分包（见 `AI_DIRECTORY_STRUCTURE_GUIDE.md`），同一功能包内按这些职责拆类。

- `controller`：HTTP 入参出参，不直接拼 Prompt，不接触 Agent SDK 或模型 SDK。
- `config`：类型安全配置绑定、DDL 初始化和 Nacos AI Registry 配置。
- `service`：业务编排和单一职责组件。
- `dto`：接口请求/响应结构。
- `entity`：数据库持久化实体。
- `repository`：MyBatis-Plus 数据访问接口。
- `exception`：统一异常响应。

关键服务职责：

- `AiDialogueService`：角色 Prompt、记忆裁剪、Agent 调用、审计和历史展示。
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
- Agent 接口协议当前使用 `HTTP+JSON`；患者分析接口开启流式传输。

## 6. 对话记忆规则

- `ai_chat_memory` 只保存普通聊天 user / assistant 文本上下文。
- 主服务在调用 Agent 前裁剪记忆窗口，并以文本上下文传给 Agent。
- 微信 owner 会话元数据/历史展示能力已退役；不重新注册旧实体、Repository 或会话管理路由。
- `ai_chat_message` 是审计表，不是模型记忆表。

## 7. 数据库规则

- 当前表结构由 `PostgresqlDdl.java` 加载 `db/postgresql/common-infra-schema.sql` 与 `db/postgresql/ai-schema.sql`；历史 MySQL 脚本不在 PostgreSQL 执行。
- 全新或清空后的业务库首次启动前，必须先在目标业务库手动执行 utils 的 `db/postgresql/common-infra-schema.sql`，再执行服务的 `db/postgresql/ai-schema.sql`；Seata AT 会在 `DataSource` 初始化时先检查 `undo_log`，不能依赖 MyBatis-Plus DDL 首次启动自动创建该表。
- 当前 PostgreSQL 表结构变化使用增量脚本并同步 H2 测试结构；已发布的历史 MySQL 脚本不回改。
- 测试环境使用 `src/test/resources/schema.sql`。
- 数据库访问统一使用 MyBatis-Plus Repository。
- 不拼接用户输入形成 SQL。
- 新增字段必须同步 PostgreSQL DDL 注释和 H2 测试 schema。
- 新增业务表默认补齐公共治理字段：`code`、`description`、`create_date_time`、`create_name`、`modify_date_time`、`modify_name`、`is_delete`、`type`、`state`、`label`、`sorting`、`version`、`tenant_id`。
- 数据库列 `version` 只表示乐观锁版本号；Prompt、接口协议等业务版本必须使用更明确的列名，例如 `prompt_version`、`template_version`、`protocol_version`。
- 现有 AI 业务表保留 `bigint` 自增主键和 `created_at` / `updated_at` 运行时字段，后续新增通用后台管理型业务表再优先继承 `EntityBase`。

## 8. 安全规则

- 多模态 URL 必须校验协议、数量、MIME 和远程 Content-Type。
- 文件、图片、PDF、JSON 等资料必须先上传到文件服务或 OSS，再作为 `attachments.url` 传入。
- AI 对话接口不得重新暴露 `multipart/form-data` 文件流入口。
- 异常响应不得暴露内部堆栈、密钥、数据库地址、完整媒体 URL 或内部服务路径。
- 患者分析角色必须保留医疗安全边界，不能替代医生诊断、治疗、用药或急救判断。

## 对话编排观测

`ai.dialogue.stream` 从整轮编排开始，包含实际记忆上下文与 RAG 预取、Agent 流和终态持久化。`memory.context`、`rag.prefetch` 是固定编排步骤，不伪装成模型自主工具调用。入口父上下文在装配、订阅和异步完成/失败/取消的副作用中显式恢复，每次订阅只终结一次 span；模型降级文案不覆盖原失败结果。

角色 Prompt 的正文、`prompt_version` 与来源从同一解析结果取得；数据库来源和配置兜底分别标识，未知版本不从正文推算。只写有限 `fleet.ai.prompt.*` 元数据，不将 Prompt 正文、业务会话 ID 或患者身份放入 trace。历史 DTO 的 `traceId` 是业务关联，不作为 OTel trace ID。真实模型 usage 与公开 reasoning 存在性由执行层 ai-agent 采集，主服务不伪造供应商数据。

## 9. 验证

```bash
./gradlew test --no-daemon
bash -n scripts/check-nacos-ai-config.sh
NACOS_USERNAME=nacos NACOS_PASSWORD=nacos bash scripts/check-nacos-ai-config.sh
```

涉及 Nacos AI Registry、Agent 接口、DDL、文件 token 或会话归属时，必须执行相关测试或说明无法执行原因。

个人 XXL-JOB 已退役，不再新增其依赖、配置类或 `xxl-job.yaml` 导入。调度选用 NAS 独立 Conductor OSS，通过 REST 接入；业务 Java 调度接口尚未实现，保持 Java 17，不为此引入要求 Java 21 的最新 SDK。

## Scoped AI 身份边界

聊天及记忆读取/清理要求 User access JWT，由 User `AuthSessionRpcService` 复核完整生命周期与账号租户授权。不能把 `X-User-*`、正文 `sourceTenantId` 或历史默认 `100` 当作授权身份。入口捕获的不可变 subject/tenant 在每个执行线程短暂绑定并恢复；终端 token 只作为 Authorization header 转发给验签后的 Agent，不能进入正文或日志。

User 聊天按 authTenant + subject + 客户端原始 conversationId 建内部摘要键；客户端双轮继续原 ID，不能从 `fleet_` 前缀信任任意内部键。微信登录、owner 会话及旧上传管理路由已删除；不提供 User ID 到旧 owner 的隐式映射。普通附件继续使用可访问 URL 与通用 token view；不宣称已实现 User 直传管理 API。

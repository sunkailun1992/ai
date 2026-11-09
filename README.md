# ai

`ai` 是 AI 患者分析助手的主业务服务，基于 Java 17、Spring Boot、Spring Cloud Alibaba、Nacos 3.x、MyBatis-Plus 和 Gradle Wrapper 构建。

个人公共包固定为 `com:utils:1.6.1`，跨服务契约固定为 `com:rpc-api:1.0.7`。个人与公司共用私有 Maven 地址，utils 分别使用 `com` 与 `com.kellen` group 隔离；Java 包名保持 `com.kellen.*`。

本服务只负责入口编排、微信小程序用户、会话、文件 token、问卷状态机、聊天记忆和调用审计。真实模型执行已经拆到 `ai-agent`，主服务通过 Nacos AI Registry 读取 AgentCard 后调用 Agent 接口，不再保留 Spring AI 模型客户端、本地 Qdrant RAG 执行链、本地 Provider 切换或本地模型兜底。`qdrant.yaml` 仍作为备用向量数据库连接配置由 profile 导入，便于后续显式开启向量库操作。

代码分支使用 `main`（正式主干）、`test`（整合测试）、`preprod`（候选预发）与短功能分支。分支不自动改变运行 profile；完整功能进入远端 main 并核对后，按本任务授权清理本地与远程功能分支，保护未合、其他 worktree 占用及长期分支。细则见 [分支管理规范](docs/ai-coding/BRANCHING_SPEC.md)。

## 本地 dev 启动

本地默认使用 `dev` profile。IDE 的 working directory、`./gradlew bootRun` 和 `java -jar build/libs/<服务名>.jar` 的当前目录必须是本服务仓库根目录，Spring 才会自动读取 `config/application-dev.properties`。该私有文件由本机入口生成、权限为 `0600`，仅保存 Nacos 连接的五个引导键；不放模型或数据库密钥，不提交 Git，也不修改 `application.yml`。从其它目录启动时，应显式设置 `spring.config.additional-location`，不能假设自动搜索会定位到服务仓库。

同名 OS 环境变量优先于私有文件；dev 地址按 `GITHUB_NACOS_DEV_SERVER_ADDR`、`GITHUB_NACOS_SERVER_ADDR` 依次读取，两者都未提供时拒绝缺失地址，不再回落到 `127.0.0.1:8848`。控制台使用 `18080`，客户端/SDK 使用 `8848`，SDK gRPC 连接使用 `9848`，不能把控制台端口当客户端地址。个人 namespace ID 保持 `github-dev`；远端业务配置仍从 Nacos 读取。单独激活 `test` 或 `prod` 不会读取这个 dev 私有文件，需提供其各自的连接配置。

## 核心能力

- 统一 AI 入口：`POST /api/ai/chat` 负责小程序和外部调用的 SSE 对话入口。
- Agent 委托：通过 Nacos AI Registry 查找 `patient-analysis-agent`、`questionnaire-agent` 的接口地址。
- 患者分析助手：角色 Prompt 存在 `ai_role_prompt`，主服务解析后传给 `ai-agent` 执行。
- 问卷状态机：问卷模板、会话、答案、附件和 outbox 保存在业务库，不写入普通聊天记忆。
- 数据库记忆：`ai_chat_memory` 只保存普通对话 user / assistant 文本上下文。
- 调用审计：`ai_chat_message` 保存请求、响应、Agent、媒体摘要和错误摘要。
- 私有文件：通过 X File Storage 统一接入 OSS / MinIO，采用 token 展示模式，小程序不保存公共对象 URL。

## 技术栈

- Java 17
- Spring Boot 4
- Spring Web / WebFlux
- Spring Cloud Alibaba Nacos
- Nacos AI Registry SDK
- MyBatis-Plus
- PostgreSQL 18
- Gradle Wrapper
- H2 Test Runtime

项目使用 Gradle Wrapper，不使用 Maven。

## 调用链

```text
AiDialogueController
  -> AiDialogueService
    -> NacosAiAgentRegistryClient
    -> AiAgentClient
    -> AiRolePromptService
    -> DbChatMemoryService
    -> MediaInputValidator
    -> ChatAuditService
```

职责边界：

- `AiDialogueController`：HTTP 入参、路径映射和 SSE 返回。
- `AiDialogueService`：问卷优先路由、角色 Prompt、记忆裁剪、Agent 调用、审计和历史展示。
- `NacosAiAgentRegistryClient`：使用 Nacos 3.x 官方 AI SDK 读取 AgentCard。
- `AiAgentClient`：只按 AgentCard 接口地址调用 `ai-agent`，不保存本地 baseUrl 兜底。
- `DbChatMemoryService`：保存主服务可控的普通对话记忆，不实现 Spring AI ChatMemory。
- `MediaInputValidator`：校验附件 URL 数量、协议、Content-Type 和 MIME。

## Nacos 配置

本地 `application.yml` 只负责连接 Nacos，并导入必要配置：

- `logging.yml`
- `reuse-configuration.yaml`
- `ai.yaml`
- `ai-spring.yaml`（位于全局 Nacos 配置 group `DEFAULT_GROUP`，服务框架/环境配置）
- `mybatis-plus.yaml`

`reuse-configuration.yaml` 统一维护基础设施地址，`ai.yaml` 不直接写裸 IP：

```yaml
custom:
  infra-host: ${INFRA_HOST:172.16.1.39}
  infra-nacos-addr: ${custom.infra-host}:8848
```

`ai.yaml` 至少需要配置 Agent Registry，并引用公共地址变量：

```yaml
ai:
  agent-client:
    timeout: 8s
  agent-registry:
    enabled: true
    server-addr: ${custom.infra-nacos-addr}
    namespace: ${custom.namespace}
    context-path: /nacos
    username: ${NACOS_USERNAME:}
    password: ${NACOS_PASSWORD:}
    patient-analysis-agent-name: patient-analysis-agent
    questionnaire-agent-name: questionnaire-agent
```

不再配置这些主服务本地能力：

- `spring.ai.*`
- `ai.provider`
- `ai.deepseek.*`
- `ai.google.*`
- `ai.qwen.*`
- `ai.agent-client.base-url`

`qdrant.yaml` 保留 Qdrant 向量数据库连接参数；保留连接配置不等于默认启用主服务本地 RAG 或 Spring AI VectorStore 调用链，新增向量库操作必须有明确业务入口和开关。

如果 Nacos AI Registry 没有发布对应 AgentCard，主服务应显式失败，不允许静默绕回本地模型执行。

检查 Nacos 配置和 AgentCard：

```bash
NACOS_USERNAME=nacos NACOS_PASSWORD=nacos bash scripts/check-nacos-ai-config.sh
```

脚本会先探测 Nacos 版本，再按版本读取配置并尝试核对 AgentCard；旧版本只检查配置中心，并提示不支持 AI Registry。运行时代码使用 Nacos 官方 `AiService` SDK 读取 AgentCard。

## Nacos AI Registry

当前需要两个 Agent：

- `patient-analysis-agent`
  - URL：`http://<AI_AGENT_HOST>:7310/api/agents/patient-analysis/stream`
  - 协议：`HTTP+JSON`
  - 流式传输：开启
  - 技能建议：`patient-analysis`、`report-analysis`
- `questionnaire-agent`
  - URL 1：`http://<AI_AGENT_HOST>:7310/api/agents/questionnaire/reply`
  - URL 2：`http://<AI_AGENT_HOST>:7310/api/agents/questionnaire/interpret`
  - 协议：`HTTP+JSON`
  - 技能建议：`conversational-questionnaire`、`answer-interpretation`

`<AI_AGENT_HOST>` 必须从主 `ai` 服务运行环境可达。Docker 同网络可以使用
`ai-agent` 主机名；本地 Mac 联调时通常使用 `127.0.0.1` 或本机局域网 IP。
不要把仅浏览器或 Nacos 控制台能访问的地址当成服务间调用地址。

不要把主服务入口 `/api/ai/chat` 或 `/api/ai/questionnaire/chat` 配成 Agent 执行接口。

## 数据库表

- `ai_chat_message`：聊天审计记录。
- `ai_chat_memory`：模型上下文记忆。
- `ai_role_prompt`：患者分析等角色 Prompt。
- `ai_questionnaire_template`：问卷模板源数据。
- `ai_questionnaire_session`：问卷会话状态。
- `ai_questionnaire_answer`：问卷题目答案。
- `ai_integration_outbox`：问卷完成后的异步推送事件。
- `ai_file_object`：私有文件元数据、持久化存储平台和展示 token。
- `ai_wechat_user` / `ai_wechat_session`：小程序用户和登录态。

表结构由 `src/main/java/com/kellen/config/PostgresqlDdl.java` 依序加载 `db/postgresql/common-infra-schema.sql` 和 `db/postgresql/ai-schema.sql` 维护。公共基础表脚本来自 `utils`，必须在首次启动前手动执行，确保 `ddl_history` 和 Seata AT `undo_log` 先于应用数据源初始化存在。历史 `db/ai-init.sql` 与增量脚本保留，不在 PostgreSQL 上执行。个人空库不自动导入业务角色或问卷种子；Java 演示种子须显式设置 `ai.bootstrap.seed-demo-content=true`。快速业务回归使用 H2；PostgreSQL 差异另由真实 PG 测试验证。


PostgreSQL 使用 `postgresql.*` / `postgresql-gray.*` 连接键与 `org.postgresql.Driver`，不回退到旧 MySQL 键。字符字段使用 ICU `fleet_general_ci` 保留常用大小写、重音不敏感的等值、唯一键和 `LIKE` 查询；这不等于完整复刻 MySQL Unicode 权重或尾空格规则，迁移前必须检查唯一键碰撞和尾空格。PG 18 的 nondeterministic collation 支持 `LIKE`，不要将现有查询改成不支持该排序规则的 `ILIKE` 或正则匹配。

真实 PG 结构回归通过受控进程环境提供 `FLEET_PG_TEST_URL`、`FLEET_PG_TEST_USERNAME`、`FLEET_PG_TEST_PASSWORD`；只允许 `fleet_pg_test_` 前缀的独立测试库，测试只创建并清理随机 schema。未提供时明确跳过，H2 回归不能代替 PostgreSQL 验收。凭据不得写进命令文本、仓库或测试报告。
AI 表使用 `bigint GENERATED BY DEFAULT AS IDENTITY` 保留迁入主键，并由应用填充公共 `modify_date_time`；保留现有和运行时 `created_at` / `updated_at` 字段，同时补齐公共治理字段；数据库列 `version` 固定表示乐观锁，业务版本使用 `prompt_version`、`template_version` 等明确列名。

## 开发规则

- 主服务不得重新引入 Spring AI 模型执行依赖。
- 主服务不得重新引入 `system_parameter.ai_provider` 这类本地 Provider 切换。
- 问卷解释 Prompt 不得污染普通 AI 记忆。
- Prompt 和问卷模板属于业务数据，保存在业务库；Nacos AI Registry 只管 Agent 能力目录和接口发现。
- 新增 RAG、MQ、Seata、Qdrant 等能力时，先确认是否属于主业务服务；纯 Agent 能力优先放到 `ai-agent` 或独立 Agent 服务。

## 验证

```bash
./gradlew test --no-daemon
bash -n scripts/check-nacos-ai-config.sh
NACOS_USERNAME=nacos NACOS_PASSWORD=nacos bash scripts/check-nacos-ai-config.sh
```

通用安全、隔离与本批验证边界见 [安全与架构说明](docs/security-architecture-sync.md)。

## 本批可观测性接入

公共能力精确消费 `com:utils:1.6.1`（公共制品和消费者须先完成 Maven Local 验证，再发布匹配 main 制品）。九服务共享 `actuator.yaml` 暴露 health/info/prometheus；除 health 外仍要求 `actuatorReq` 运维令牌，空值或错误值失败关闭。`logging.yml` 使用 ECS 固定字段白名单，未知 MDC、原始消息、正文和异常不进入采集结果；OTel agent 标准 `trace_id/span_id` 与业务会话 ID 分开。应用指标带固定 application/environment 标签，禁止动态业务 ID。

Admin/Zipkin 模板与三 profile 的 imports 已退役。服务 JVM 的可选 OTel agent 由统一启动入口负责，不通过全局 JAVA_TOOL_OPTIONS 注入 Gradle 或测试。部署时 Java 指标只由 Micrometer Prometheus 提供，OTEL_METRICS_EXPORTER 与 OTEL_LOGS_EXPORTER 均为 none；监控组件、配置发布和实际采集仍须独立验证。回滚须恢复匹配的制品与配置，先恢复旧 dataId 再回退旧消费者，不能用已删除的配置项假装完成回滚。

个人业务 PostgreSQL 迁移基线服务版本为 `1.6.0`，首版公共库为 `com:utils:1.6.0`（传递安全采集库 `com:fleet-observability:0.1.1`）。版本和本机构建通过不代表目标环境已切库，正式迁移与运行态验收另行记录。

个人 Java 服务已移除 XXL-JOB 依赖、执行器配置与演示任务，不再读取 `xxl-job.yaml`。Conductor OSS 作为 NAS 独立服务通过 REST 接入；当前未接入业务 Java 调度接口，不添加 Java 21 SDK 或虚构任务开关。其他环境仍使用的 NAS 容器不由本次个人源码退役处理。

## PostgreSQL 启动修复补丁

当前服务补丁版本为 `1.6.1`，公共依赖精确固定 `com:utils:1.6.1`，接入 PostgreSQL/Druid 超时单位修复及事务化、失败即拒绝启动的 DDL 执行。原定连接 3 秒、读取 30 秒预算保持，RPC 契约 `com:rpc-api:1.0.7` 与轻量采集库 `com:fleet-observability:0.1.1` 保持。公共制品、Jar 构建、服务器与 IDEA 原生启动须分别验证，修改依赖不代表目标环境已恢复。

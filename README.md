# ai

`ai` 是 AI 患者分析助手的主业务服务，基于 Java 21、Spring Boot、Spring Cloud Alibaba、Nacos 3.x、MyBatis-Plus 和 Gradle Wrapper 构建。

个人公共包固定为 `com:utils:1.6.7`，跨服务契约固定为 `com:rpc-api:1.0.9`。个人与公司共用私有 Maven 地址，utils 分别使用 `com` 与 `com.kellen` group 隔离；Java 包名保持 `com.kellen.*`。

本服务只负责User 认证入口编排、文件 token、聊天记忆和调用审计。真实模型执行已经拆到 `ai-agent`，主服务通过 Nacos AI Registry 读取 AgentCard 后调用 Agent 接口，不再保留 Spring AI 模型客户端、本地 Qdrant RAG 执行链、本地 Provider 切换或本地模型兜底。`qdrant.yaml` 仍作为备用向量数据库连接配置由 profile 导入，便于后续显式开启向量库操作。

代码分支只保留 `main`（正式主干）与短功能分支。分支不自动改变运行 profile；完整功能进入远端 main 并核对后，按本任务授权清理本地与远程功能分支，保护未合、其他 worktree 占用及长期分支。细则见 [分支管理规范](docs/ai-coding/BRANCHING_SPEC.md)。

## 本地 dev 启动

本地默认使用 `dev` profile。IDE 的 working directory、`./gradlew bootRun` 和 `java -jar build/libs/<服务名>.jar` 的当前目录必须是本服务仓库根目录，Spring 才会自动读取 `config/application-dev.properties`。该私有文件由本机入口生成、权限为 `0600`，仅保存 Nacos 连接的五个引导键；不放模型或数据库密钥，不提交 Git，也不修改 `application.yml`。从其它目录启动时，应显式设置 `spring.config.additional-location`，不能假设自动搜索会定位到服务仓库。

同名 OS 环境变量优先于私有文件；dev 地址按 `GITHUB_NACOS_DEV_SERVER_ADDR`、`GITHUB_NACOS_SERVER_ADDR` 依次读取，两者都未提供时拒绝缺失地址，不再回落到 `127.0.0.1:8848`。控制台使用 `18080`，客户端/SDK 使用 `8848`，SDK gRPC 连接使用 `9848`，不能把控制台端口当客户端地址。个人 namespace ID 保持 `github-dev`；远端业务配置仍从 Nacos 读取。单独激活 `test` 或 `prod` 不会读取这个 dev 私有文件，需提供其各自的连接配置。

## 核心能力

- 统一 AI 入口：`POST /api/ai/chat` 要求已验证 User 身份的 SSE 对话入口。
- Agent 委托：通过 Nacos AI Registry 查找 `patient-analysis-agent` 的接口地址。
- 患者分析助手：角色 Prompt 存在 `ai_role_prompt`，主服务解析后传给 `ai-agent` 执行。
- 数据库记忆：`ai_chat_memory` 只保存普通对话 user / assistant 文本上下文。
- 调用审计：`ai_chat_message` 保存请求、响应、Agent、媒体摘要和错误摘要。
- 私有文件：通过 DB 控制面与 X File Storage 统一接入 OSS / COS / MinIO / S3-compatible，保留既有文件的 token 展示模式；存储管理不等于新 User 直传 API。

## 技术栈

- Java 21
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
- `AiDialogueService`：角色 Prompt、记忆裁剪、Agent 调用和审计。
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

当前只发现患者分析 Agent：

- `patient-analysis-agent`
  - URL：`http://<AI_AGENT_HOST>:7310/api/agents/patient-analysis/stream`
  - 协议：`HTTP+JSON`
  - 流式传输：开启
  - 技能建议：`patient-analysis`、`report-analysis`
`<AI_AGENT_HOST>` 必须从主 `ai` 服务运行环境可达。Docker 同网络可以使用
`ai-agent` 主机名；本地 Mac 联调时通常使用 `127.0.0.1` 或本机局域网 IP。
不要把仅浏览器或 Nacos 控制台能访问的地址当成服务间调用地址。

不要把主服务入口 `/api/ai/chat` 配成 Agent 执行接口。

## 数据库表

- `ai_chat_message`：聊天审计记录。
- `ai_chat_memory`：模型上下文记忆。
- `ai_role_prompt`：患者分析等角色 Prompt。
- `ai_file_object`：私有文件元数据、持久化存储平台和展示 token。

表结构由 `src/main/java/com/kellen/config/PostgresqlDdl.java` 依序加载 `db/postgresql/common-infra-schema.sql` 和 `db/postgresql/ai-schema.sql` 维护。公共基础表脚本来自 `utils`，必须在首次启动前手动执行，确保 `ddl_history` 和 Seata AT `undo_log` 先于应用数据源初始化存在。历史 `db/ai-init.sql` 与增量脚本保留，不在 PostgreSQL 上执行。个人空库不自动导入业务角色种子；Java 演示种子须显式设置 `ai.bootstrap.seed-demo-content=true`。快速业务回归使用 H2；PostgreSQL 差异另由真实 PG 测试验证。


PostgreSQL 使用 `postgresql.*` / `postgresql-gray.*` 连接键与 `org.postgresql.Driver`，不回退到旧 MySQL 键。字符字段使用 ICU `fleet_general_ci` 保留常用大小写、重音不敏感的等值、唯一键和 `LIKE` 查询；这不等于完整复刻 MySQL Unicode 权重或尾空格规则，迁移前必须检查唯一键碰撞和尾空格。PG 18 的 nondeterministic collation 支持 `LIKE`，不要将现有查询改成不支持该排序规则的 `ILIKE` 或正则匹配。

真实 PG 结构回归通过受控进程环境提供 `FLEET_PG_TEST_URL`、`FLEET_PG_TEST_USERNAME`、`FLEET_PG_TEST_PASSWORD`；只允许 `fleet_pg_test_` 前缀的独立测试库，测试只创建并清理随机 schema。未提供时明确跳过，H2 回归不能代替 PostgreSQL 验收。凭据不得写进命令文本、仓库或测试报告。
AI 表使用 `bigint GENERATED BY DEFAULT AS IDENTITY` 保留迁入主键，并由应用填充公共 `modify_date_time`；保留现有和运行时 `created_at` / `updated_at` 字段，同时补齐公共治理字段；数据库列 `version` 固定表示乐观锁，业务版本使用 `prompt_version`、`template_version` 等明确列名。

## 开发规则

- 主服务不得重新引入 Spring AI 模型执行依赖。
- 主服务不得重新引入 `system_parameter.ai_provider` 这类本地 Provider 切换。
- 新增 RAG、MQ、Seata、Qdrant 等能力时，先确认是否属于主业务服务；纯 Agent 能力优先放到 `ai-agent` 或独立 Agent 服务。

## 验证

```bash
./gradlew test --no-daemon
bash -n scripts/check-nacos-ai-config.sh
NACOS_USERNAME=nacos NACOS_PASSWORD=nacos bash scripts/check-nacos-ai-config.sh
```

通用安全、隔离与本批验证边界见 [安全与架构说明](docs/security-architecture-sync.md)。

## 本批可观测性接入

公共能力精确消费 `com:utils:1.6.2`（公共制品和消费者须先完成 Maven Local 验证，再发布匹配 main 制品）。九服务共享 `actuator.yaml` 暴露 health/info/prometheus；除 health 外仍要求 `actuatorReq` 运维令牌，空值或错误值失败关闭。`logging.yml` 使用 ECS 固定字段白名单，未知 MDC、原始消息、正文和异常不进入采集结果；OTel agent 标准 `trace_id/span_id` 与业务会话 ID 分开。应用指标带固定 application/environment 标签，禁止动态业务 ID。

Admin/Zipkin 模板与三 profile 的 imports 已退役。服务 JVM 的可选 OTel agent 由统一启动入口负责，不通过全局 JAVA_TOOL_OPTIONS 注入 Gradle 或测试。部署时 Java 指标只由 Micrometer Prometheus 提供，OTEL_METRICS_EXPORTER 与 OTEL_LOGS_EXPORTER 均为 none；监控组件、配置发布和实际采集仍须独立验证。回滚须恢复匹配的制品与配置，先恢复旧 dataId 再回退旧消费者，不能用已删除的配置项假装完成回滚。

个人业务 PostgreSQL 迁移基线服务版本为 `1.6.0`，首版公共库为 `com:utils:1.6.0`（传递安全采集库 `com:fleet-observability:0.1.1`）。版本和本机构建通过不代表目标环境已切库，正式迁移与运行态验收另行记录。

个人 Java 服务已移除 XXL-JOB 依赖、执行器配置与演示任务，不再读取 `xxl-job.yaml`。Conductor OSS 作为 NAS 独立服务通过 REST 接入；当前未接入业务 Java 调度接口，不添加 Java 21 SDK 或虚构任务开关。其他环境仍使用的 NAS 容器不由本次个人源码退役处理。

## PostgreSQL 启动修复补丁

PostgreSQL 启动修复补丁版本为 `1.6.4`，公共依赖精确固定 `com:utils:1.6.2`，接入 PostgreSQL/Druid 超时单位修复及事务化、失败即拒绝启动的 DDL 执行。原定连接 3 秒、读取 30 秒预算保持，RPC 契约 `com:rpc-api:1.0.8` 与轻量采集库 `com:fleet-observability:0.1.2` 保持。公共制品、Jar 构建、服务器与 IDEA 原生启动须分别验证，修改依赖不代表目标环境已恢复。

本次公共观测补丁通过 `com:utils:1.6.2` 精确传递 `com:fleet-observability:0.1.2`，修复 Servlet Security 在 ERROR 分派中再次拒绝导致终态缺失的问题；认证和错误响应保持。源码与测试通过后，目标进程和追踪/日志联结仍须单独读回验收。

`1.7.4` 为第1阶段底座升级：Java 21、Gradle 9.9.1、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Nacos 客户端 3.3.0，公共依赖 `com:utils:1.6.6`（传递 `com:fleet-observability:0.1.4`）与 `com:rpc-api:1.0.9`，OTel BOM 1.67.0 对齐 Java agent 2.33.1。业务接口与配置不变；运行环境须为 Java 21。

`1.7.6` 将公共依赖升到 `com:utils:1.6.7`：修复 Dubbo 上下文透传过滤器拿不到 Spring 绑定的灰度与租户配置（提供方误拒全部灰度授权、灰度实例不带授权也放行、自定义租户头名不生效），本服务代码不变。

### AI 可信身份与会话隔离

`/api/ai/chat` 和 `/api/ai/memory/{conversationId}` 现在要求个人 User 中心签发的有效 access JWT。AI 通过 `com:rpc-api:1.0.9` 的 `AuthSessionRpcService` 验证签名、issuer、有效期、access 类型、jti、撤销状态、tokenVersion、启用账号及有效租户授权。匿名、伪造用户/租户头及旧 opaque token 不能开启 scoped RAG/Memory。`sourceTenantId` 仍是业务来源分区，不是授权身份。

内部会话键为经过验证的租户、subject 和客户端原始 `conversationId` 的固定摘要。SSE 继续返回文本数据和既有 typed error，客户端每轮传同一个原始 ID，不能把内部键当作新的公开会话 ID；直接传 `fleet_...` 也会按当前身份重新隔离。

本轮身份在入口捕获为不可变值，RAG 预取与异步审计回调只在当前执行线程绑定已验证租户并恢复旧状态；纯 HTTP 订阅阶段临时清空 ambient 租户，防止新 worker 继承伪造请求头。调用 Agent 时 token 只通过 Authorization header 转发，不能进入签名正文、DTO、日志或异常；Agent 独立复核后才使用其租户调用 Memory。源码测试、HTTP 测试与真实整链读回分别验收。

从 1.6.8 起，成功、失败和取消审计均由 `ChatAuditService` 接收本轮已验证身份，将其 `tenantId` 显式写入 `ai_chat_message.tenant_id`。身份缺失、租户为空或超出既有列的 64 字符边界时，在 SQL 前拒绝；不从来源分区、线程上下文或数据库默认租户补值。即使 `tenant.enabled=false`，审计仍保存实际调用租户。异步回调的租户绑定与上下文恢复继续保留，平台模型、Prompt、存储等全局配置的作用域不变。此次仅修复后续写入，不增加 DDL，也不推断或回填历史默认租户 `1` 的记录。真实 MyBatis/H2 PostgreSQL 模式回归保留数据库默认 `1`，覆盖 A/B 成功、失败、取消及 worker 复用；实际部署后的审计读回另行验收。

### Agent 流式响应协议

患者分析请求显式发送 `Accept: text/event-stream`。Agent 响应必须为 HTTP 2xx 并声明 `text/event-stream`（允许 charset 等参数），不能将 JSON、普通文本、缺失或非法 Content-Type 当作模型输出。协议失败仅使用固定 `AI_AGENT_STREAM_PROTOCOL_INVALID`，不把上游正文或响应头带入异常。HTTP 4xx/5xx 保留原有 `WebClientResponseException` 状态处理；其他非 2xx 均拒绝，重定向即使带合法 SSE 也不接受，不自动跟随或重试。

合法 SSE 在 EOF 前必须至少提供一个解码后长度大于零的 data 片段；空响应、空 data，以及只有心跳注释或 id/event 元数据的 EOF 以 `AI_AGENT_STREAM_EMPTY` 失败，不能触发成功指标或成功审计。仅跳过空字符串，不 trim，空格等有效文本片段保留原顺序。取消仍直接向上游传播并关闭连接，不以空流错误覆盖取消。回环 HTTP 合同覆盖合法 SSE、异常协议、HTTP 错误和实际连接关闭；本机测试通过不代替真实七服务整链验收。

接口失败事件与配套升级见 [typed SSE 合同](docs/typed-sse-contract.md)。功能退役范围和数据清理边界见 [退役说明](docs/feature-retirement.md)。


### 全局默认模型写入权限（1.6.6）

`PUT /api/ai/model-selection` 修改的是全局 `default-agent-provider` 数据库配置，只允许同时拥有 `PLATFORM_SUPER_ADMIN` 身份和 `user:auth:manage` 权限的活跃 User access JWT。每次写入先调用认证中心验证令牌生命周期，再校验同一签名 JWT 的用户、租户与 RPC 身份一致；客户端请求头、普通登录、租户管理员、角色名称或字符串形式的权限不能替代该校验。撤销令牌、RPC 不可用或身份不一致均拒绝写入；合法身份缺少平台管理条件返回 403。GET 查询与启动时加载默认值保持既有行为。

`AiModelSelectionAuthorizationHttpTest` 使用真实 HTTP、安全过滤器、全局异常处理器和 MyBatis-Plus/H2 Repository，验证拒绝请求不会改变全局值、更新时间或行数，且实际平台管理员可以保存当前供应商。User RPC 和 Agent 模型信息只在进程边界使用合成替身，不调用真实模型。该回归不能替代部署后的活跃令牌、实际权限与数据库读回验收；不需要更改 Nacos、RPC DTO 或共享安全开关。

## 1.6.7 配置控制面

模型配置以 **LiteLLM 原生管理台** 为部署、凭据、虚拟密钥和额度的唯一管理源。`GET /api/ai/model-control-plane` 只聚合本环境 `/v1/model/info` 白名单目录和有效管理台入口，不执行推理或返回模型密钥；`NOT_CONFIGURED` / `UNREACHABLE` 与真实 `AVAILABLE` 分开。当前任务映射返回空数组，平台运行版本没有可靠接口时为 null，不推定 OCR、音频等业务已完成接入。旧 `/api/ai/model-selection` 继续保留既有业务默认模型合同。

只读模型入口从私有服务环境注入 `AI_MODELCONTROLPLANE_APIBASEURL`、`AI_MODELCONTROLPLANE_APIKEY`、`AI_MODELCONTROLPLANE_CONSOLEBASEURL`；变量名称与 Spring 实际 SystemEnvironment 绑定一致，不增加额外下划线。不把 LiteLLM master key 发给浏览器。

可选的 `ai.model-control-plane.verification-file`（环境变量 `AI_MODELCONTROLPLANE_VERIFICATIONFILE`）指向服务账号持有的本机 JSON。使用规范化绝对路径和普通文件，权限为 `0600` 或 `0400`，路径中无符号链接，文件无额外硬链接。目录及各级父目录由服务账号或受信任的运维管理，其他用户无写权限。文件仅含以下格式的历史实测摘要：

`{"entries":[{"deploymentId":"example-id","modelName":"example-alias","model":"provider/example-model","capability":"CHAT","verificationStatus":"VERIFIED","verifiedAt":"2025-06-01T09:00:00Z"}]}`

服务每次读取目录时核对部署 ID、别名、物理模型、能力四项，只有完全匹配才投影历史状态和 ISO 时间。允许 `VERIFIED`、`FAILED`、`UNKNOWN`，最多 256 项、128 KiB UTF-8。文件缺失、无法读取、内容非法或有重复匹配项时，目录继续可用，未匹配的条目显示未验证。文件由运维根据真实验收更新，建议原子替换。凭据或参数变化可能保留相同部署 ID，应同步撤下或更新受影响记录。记录只代表 `verifiedAt` 时刻的实测。目录凭据权限及上游请求范围保持原样。

存储控制面使用本服务新增的 `ai_storage_configuration`、`ai_storage_setting`、`ai_storage_operation`，唯一来源是 DB。三个 profile 不再导入 `storage.yaml`。基础设施连接、DB/MQ、注册发现和观测配置仍属于 Nacos。运行须独立注入 `AI_STORAGE_KEK_B64`（32 字节随机主密钥的标准 Base64）、`AI_STORAGE_KEK_ID`（非秘密版本标识，默认 v1）及 `AI_STORAGE_ALLOWED_ORIGINS`（逗号分隔 MinIO/S3 完整 origin 白名单）。KEK 不复用 JWT/LiteLLM，也不从 Nacos/Spring 业务属性读取；缺 key/空 DB 不阻止其它 AI 功能启动，实际文件操作明确不可用。

管理接口统一要求活跃平台超级管理员及 `user:auth:manage`，列表/配置/设置也不能由普通用户读取：

- `GET /api/ai/storage-capabilities`：原生 provider 支持及 TTL/大小边界；支持不代表已配置或健康。
- `GET/POST /api/ai/storage-configurations`，`GET/PUT/DELETE /api/ai/storage-configurations/{id}`：白名单配置管理；列表 page/size/search/provider/uploadEnabled，默认 size 20、最大 100。
- `GET/PUT /api/ai/storage-settings`：默认上传平台与策略 CAS，`expectedSettingsVersion` 对应返回 version。
- `POST /api/ai/storage-configurations/{id}/probes`：`{requestId,expectedVersion,mode:"READ_WRITE"}` 显式有界小对象探针，202 后通过 `GET /api/ai/storage-operations/{requestId}` 查回。

所有 mutator 要求 UUID requestId；config 更新/删除/probe 的 expectedVersion 对应治理 version，不是 configRevision。DELETE 的 requestId/expectedVersion 位于 query。成功使用 ApiResponse.data；失败保留标准安全错误分类，额外提供固定 failureCode，不返回 SDK 正文、密文、签名 URL 或对象 key。相同主体+requestId+请求返回已有操作，不重发外部写；异请求复用返回 `STORAGE_REQUEST_ID_REUSED`。操作 UNKNOWN 是已知结果未知的资源（200），NOT_FOUND 是无记录（404），两者都不允许自动 POST 重发。

provider/platform/endpoint/bucket/region 创建后固定；KEEP 必须省略两项凭据，REPLACE 必须完整提供两项且不接受掩码。COS region 必填并派生标准 endpoint；MinIO/S3 私有 origin 必须命中引导白名单。TTL 为 60–3600 秒，默认上传 60/读取 1800；上传上限 50 MiB。`uploadEnabled=false` 只阻止新写，原文件按保存的 platform 读取；历史 null platform 使用初始化时绑定、后续不可变的 legacyNullPlatform，不随默认切换。已签名凭证在 TTL 内可能仍有效。引用/default/固定历史映射配置不能删，软删不触及任何物理对象。

新增 PG 脚本 `db/postgresql/ai-storage-console-20261111.sql` 为增量，包含治理字段/中文注释；不回改旧脚本，不修改 ai_file_object 行。首次受控迁移由运维私有备份旧配置与计数，启动新版本的 typed unconfigured 状态后，以同一管理 API 创建原 legacyPlatform 并 CAS 导入真实 baseDir/TTL/大小/固定 null 映射；不由外部脚本自行加密或直库写业务 SQL。旧 Nacos dataId 仅保留供明确旧版回滚，不是新版本 fallback。

每个 DB revision 独立装载 XFS 原生客户端；首次使用/只读管理查询会按需装载，旧 lease 排空才关闭。APPLIED 只表示本实例已装载该 revision，不能当作存储健康。探针只写固定 owned 前缀的合成对象，记录真实 WRITE/EXISTS/READ/VERIFY_PRIVATE/CLEANUP，finally 一次删除并读回缺失。匿名网络错误/未知状态不能当作私有拒绝，只有明确 401/403 通过。持久 RUNNING 在进程恢复后变 UNKNOWN，不恢复写入；读取使用条件 CAS，不能用迟到 RUNNING 快照覆盖 worker 终态。未能确认清理的操作会阻止配置变更，需运维按精确 owned 对象处理，页面不能批量删除或盲目重试。

本批精确消费 `com:utils:1.6.3`（COS SDK 5.6.283），rpc-api 保持 1.0.8，其余服务不强制升级。离线验证使用真实官方 SDK/XFS 与回环 HTTP，并不证明真实云账号的 ACL/网络可用；每个已配置 provider 的真实读写与隐私拒绝仍须独立运行验收。当前没有历史文件数据时，只能报告原 platform/null 映射 fixture 回归，不能声称恢复已有用户文件。

## 1.6.9 小程序服务端退役

移除微信认证 `/api/auth` 与 `/api/v1/auth` 下的 `wx-login`、`me`、`logout`、`test-domain`，以及 `/api/ai/conversations` 的创建、列表、消息、删除和重命名；不保留空实现或 opaque token 兼容认证。微信用户/会话与 owner 专属会话/展示消息的实体、Repository、Service、请求响应类均不再注册。

移除 `/api/files` 与 `/api/v1/files` 下旧 `upload-policy`、`complete`、`/{fileId}` 元数据和 `/{fileId}/view-token` 刷新入口。保留 `/{fileId}/view?token=...` 的 GET/HEAD、既有文件记录和 token 校验；签名继续固定文件保存的平台，null 平台使用 DB 的 `legacyNullPlatform` 映射，不能换成当前默认平台。`OssFileService` 只使用 DB registry，不再保留旧微信上传入口或测试专用 Nacos OSS 回退构造器；`AiProperties` 不再绑定旧微信/OSS 业务凭据。

普通 User 对话附件仍使用 `attachments` 中已可访问的 HTTP(S) URL（包括合法的既有文件 token view URL），由原 `MediaInputValidator` 检查数量、协议、Content-Type 和 MIME；本批没有创建 User 直传、登记或文件 owner 管理 API，也不把 User subject 复用为旧 Long owner。User SSE、命名空间记忆与审计，以及存储控制面、X File Storage 和平台管理员门禁保持。

现存 PostgreSQL/MySQL 基线中仍包含退役表结构，历史脚本不会因删代码自动 DROP 或清数据；运行库专属数据/表处理必须单独备份、精确迁移并读回。本批不执行数据库删除，不重放真实模型请求。

## 1.7.0 缺失路由的状态与隐私

1.6.9 已移除旧控制器，但原回环路由测试未导入生产 `ApiExceptionHandler`；实际缺失路由会被其兜底异常处理转成 500。1.7.0 为 Spring MVC 的 `NoResourceFoundException` / `NoHandlerFoundException` 增加通用 404 映射，响应沿用 `AiChatResponse`，固定错误码 `AI_RESOURCE_NOT_FOUND`，不回显路径、查询串或异常原文。没有新增退役路由占位控制器，也不把其它失败视为路由验收成功。

未知异常仍返回 HTTP 500，兜底响应只含固定 `AI_REQUEST_FAILED`，不再拼接异常消息，也不记录原始异常文本。真实 Advice 与合成抛错 Controller 的 HTTP 回归覆盖包含合成凭据、内部 URL、SQL 标记的直接/嵌套异常，验证均不回显；已有公开入参错误的 400 状态和定制文案保持。

回归将生产 Advice 与真实 Controller 一起加载，分别开启/关闭静态资源映射，验证全部 21 个退役路由及任意缺失路由均为安全 404，同时保留文件 view GET/HEAD。离线构建与修复后实际部署的重新读回分别验收；首次实际 500 证据保留，不重写成原先已通过。

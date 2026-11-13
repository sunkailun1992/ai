# 微服务通用编码规范（ai 适用版）

## 定位

fleet 各 Java 微服务共用一套通用编码规则（各仓库的 `docs/ai-coding/PROJECT_CODING_SPEC.md`）。本文件只写这些规则在 `ai` 中怎么用：能直接适用的保留要点，`ai` 写法不同的写明实际做法和依据代码。

- `ai` 专属规则以根目录 `AGENTS.md`、`AI_CODING_GUIDE.md` 为准；与本文件冲突时以它们为准。
- 公共错误码、乐观锁、枚举与数据库规则见 `UTILS_PUBLIC_SPEC.md`。
- 当前服务的接口、权限与业务边界写在根目录 `README.md`，不写进本文件。

## 适用一览

| 通用规则 | 在 `ai` 中 |
| --- | --- |
| Utils 边界、路径与本机环境、配置中心、DDL 维护、README 维护、测试 | 适用 |
| 公共 examples | 适用同步规则；示例分层不作为 `ai` 的代码样例 |
| 公共字段、枚举 | 新增表或新增状态字段时适用；现有表保留原结构 |
| 标准分层（BO / Query / VO / ServiceImpl） | 不采用，按功能分包 |
| RESTful | 新增管理类资源适用；对话 SSE 按合同 |
| 实体与 Repository、Controller 返回、权限、多租户、逻辑删除 | 写法不同，见下文 |
| 数据权限 | 当前未接入 |

## Utils 边界

- 公共工具、通用组件、基础配置和跨微服务复用能力统一放同级 `../utils`；`ai` 通过 `com:utils` 依赖使用，版本见 `AGENTS.md`。
- 不因临时缺少工具就在 `ai` 新增 `utils`、`common`、`helper` 等公共包；先检索 `../utils` 已有实现，有就直接调用。
- `utils` 确实缺少能力时，先改 `../utils`，用 `./gradlew publishToMavenLocal` 本地验证；正式发布按 `VERSIONING_SPEC.md`，再回到 `ai` 升级依赖并验证。
- 判断标准：会被两个以上微服务复用的进 `utils`，只服务 `ai` 的留在对应功能包。
- `.github/workflows/ci.yml` 按源码提交固定了 `rpc-api` 与 `utils`，与依赖版本一一对应。升级这两个依赖时同步修改 CI 里的 `ref`，否则 CI 编译的不是同一份契约。

## 公共 examples

- `docs/ai-coding/examples` 是 `../utils/docs/ai-coding/examples` 的本地副本，唯一源头是 utils；`ai` 不单独长期修改，需要同步时从 utils 覆盖。
- 这些示例演示通用后台管理分层（BO、Query、VO、ServiceImpl、`EntityBase`、`@PreAuthorize`），`ai` 现有代码不按这套组织。修改 `ai` 时以同一功能包里的现有类为样例，不因示例引入 BO / Query / VO 分层。
- 需要 `ai` 专属示例时放 `docs/ai-coding/project-examples`，不放进 `examples`。

## 技术基线

- Java 21；依赖版本以 `build.gradle` 为准，不在规范里另抄版本号。
- 保持 Spring Boot 4 依赖线与 Spring AI 2 正式版（见 `AGENTS.md`）；Servlet 与 Validation 使用 `jakarta.*`。

## 配置中心

- Nacos 接入、profile 与 namespace 按 `ENVIRONMENT_CONFIG_SPEC.md`、`NACOS_CONFIG_SPEC.md`。
- 基础设施地址集中维护在 Nacos 公共配置，业务配置只引用公共变量，不在多个配置文件里重复写同一个地址。
- 模型部署与上游密钥以 LiteLLM 原生管理台为唯一源；存储 provider、凭据与默认值以 AI DB 和管理 API 为唯一源（见 `AGENTS.md`）。

## 路径与本机环境

- README、AI 规范、YAML、properties、SQL、脚本、测试、示例和 Java 代码中不得写个人电脑绝对路径、下载目录、IDE 路径、JDK 安装路径或本机仓库完整路径。
- 描述同级仓库用 `../utils`、`../rpc-api`、`../gateway`、`../ai-agent` 等相对路径。
- 可变目录用环境变量、Nacos 配置、`${user.home}`、`${java.io.tmpdir}` 或 `<PLACEHOLDER>` 占位符。
- 提交前用 `rg` 搜索本机用户名、用户目录和仓库根目录关键字，确认没有残留。

## 分包与请求对象

- 通用规范的 `controller / dto / entity(bo, vo, enums) / mapper / service(query, results, impl)` 分层不用于 `ai`。`ai` 按功能分包（见 `AI_DIRECTORY_STRUCTURE_GUIDE.md`），职责分层见 `AI_CODING_GUIDE.md` 第 4 节。
- 请求和响应对象命名为 `XxxRequest`、`XxxResponse` 或 `XxxDto`，用 `record` 定义（如 `AiChatRequest`、`AiChatResponse`），不新建 BO / Query / VO 体系。
- 查询条件和写入参数分开；GET 查询不加 `@RequestBody`。
- 不把 Entity 直接返回给前端，敏感字段不进入响应对象。
- 修改类请求携带期望版本，需要幂等的携带 `requestId`（如存储控制面的 `Update.expectedVersion`、`UpdateSettings.expectedSettingsVersion`），由 Repository 做比较并更新。

## RESTful

- 新增管理类资源接口按通用 RESTful：路径用复数名词表达资源，HTTP 方法表达动作，资源 ID 放路径；不用 `/save`、`/update`、`/remove`、`/page` 这类动词路径。
- 对话 SSE（`POST /api/ai/chat`）等流式动作按 `docs/typed-sse-contract.md`，不强行改造成资源 CRUD。
- 新增对外 Controller 或服务前缀时，按 `AGENTS.md` 同步评估 `../gateway` 的路由与文档聚合。

## DDL 维护

- DDL 入口是 `com.kellen.config.PostgresqlDdl`，`getSqlFiles()` 声明脚本列表与执行顺序；脚本在 `src/main/resources/db/postgresql/`。修改前先看该列表。
- 全新或清空后的业务库首次启动前，按 `AGENTS.md` 先手动执行 utils 的 `db/postgresql/common-infra-schema.sql`，再执行 `db/postgresql/ai-schema.sql`。
- 修改已存在的脚本前，先查目标库 `ddl_history` 是否执行过。执行过或无法确认的，一律新增增量脚本并追加到 `getSqlFiles()`，不改历史脚本（先例：`ai-storage-console-20261111.sql`）。
- 脚本必须可重复执行：`IF NOT EXISTS`、`INSERT ... ON CONFLICT DO NOTHING` 或带明确主键/租户条件的确定性 `UPDATE`；`ADD COLUMN IF NOT EXISTS` 只解决重复执行，仍须核对实际列结构。
- 历史 MySQL 脚本（`src/main/resources/db/` 根下的 `*.sql`）不回改、不在 PostgreSQL 执行。
- 新增字段同步 PostgreSQL 字段中文注释（`COMMENT ON COLUMN`）和 `src/test/resources/schema.sql`。

## 公共字段与枚举

- 新增业务表补齐公共治理字段，字段清单见 `AI_CODING_GUIDE.md` 第 7 节；`version` 只做乐观锁，业务版本用 `prompt_version` 等明确列名。
- 现有 AI 业务表保留 bigint 自增主键和原有时间字段；后续新增通用后台管理型业务表优先继承 utils 的 `EntityBase`（`AI_CODING_GUIDE.md` 第 7 节）。
- 只有需要数据权限过滤的业务主表才加 `owner_user_id`、`dept_id`。
- 新增实体需要读写 `type`、`state` 等状态字段时，建对应枚举并实现 `IEnum<Integer>`（见 `UTILS_PUBLIC_SPEC.md`），放在所属功能包内。接口响应里的类型化取值可以用 `record` 内嵌枚举（如 `ModelControlPlaneResponse.Capability`）。

## 实体与 Repository

- 实体用 MyBatis-Plus `@TableName` 映射。现有 AI 业务实体继承 `AiGovernanceEntity`（只映射自动填充的 `modifyDateTime`）；存储控制面实体是独立的 `final class`。
- 数据访问接口命名为 `XxxRepository` 并加 `@Mapper`。普通实体继承 `AiBaseRepository<T>`，它在 `BaseMapper` 上保留 `save`、`findById` 等兼容语义；需要比较并更新、显式租户或平台范围时，在 Repository 上写注解 SQL（见存储控制面的三个 Repository）。
- 不拼接用户输入形成 SQL，参数一律绑定。

## Service 与事务

- Service 负责业务编排、事务、身份与租户上下文和 Repository 调用；Controller 不写业务规则、不写 SQL。
- 写操作需要事务时用 `@Transactional`；方法会抛受检异常时显式写 `rollbackFor = Exception.class`，因为 Spring 默认只对运行时异常回滚。
- 先查后改必须带上期望 `version`，写法见 `UTILS_PUBLIC_SPEC.md`「乐观锁规则」。
- 对象转换优先复用 utils 已有工具，不在 `ai` 另写通用转换器。

## Controller 返回

- 普通 JSON 接口外层用 `com.kellen.utils.response.ApiResponse`，经 `ResponseEntity` 返回；对话 SSE 按 typed SSE 合同返回事件；存储控制面的失败用 `SafeFailure`。
- 统一异常由 `ApiExceptionHandler` 处理，对外信息脱敏，不带内部堆栈、地址或凭据。
- 现有 Controller 没有使用 `@Tag`、`@Operation`、`@Schema`。是否补齐 OpenAPI 注解单独决定，不在功能改动里顺带大面积添加。

## 权限

- `ai` 不用 `@PreAuthorize`。普通用户接口通过 `AiRequestIdentityResolver` 解析并校验本轮 `AiRequestIdentity`。
- 平台控制面接口要求活跃的 `PLATFORM_SUPER_ADMIN` 且具备 `user:auth:manage`，由 `AiControlPlaneAdministrator` 校验（模型选择在 `AiModelSelectionService` 内同样校验）。
- 新增受保护接口复用这两条路径，不另造权限框架。

## 多租户

- 租户来自本轮已验证的 `AiRequestIdentity`，它在请求范围内设置 `TenantContextHolder`。
- 对话审计 `ai_chat_message` 的成功、失败和取消都显式写入 `tenant_id`，不依赖全局租户插件、来源分区、ambient 上下文或数据库默认值（`AGENTS.md`）。
- 平台级表（存储控制面）在 Repository 上标注 `@InterceptorIgnore(tenantLine = "true")`，SQL 显式限定 `scope = 'PLATFORM'`。

## 数据权限

- utils 提供 `DataPermissionInterceptor`，`ai` 当前没有配置 `security.data-permission.table-rules`；会话、记忆与审计的归属按 `AiRequestIdentity` 的用户与租户校验。
- 需要接入时按 utils 能力和 fleet 通用规则统一配置，不在 Service 里为单个页面手写数据范围过滤。

## 逻辑删除

- 见 `UTILS_PUBLIC_SPEC.md`「数据库规则」：手写 SQL 的查询显式带 `is_delete = false`，删除写成软删除并递增 `version`。

## 测试

- 以 `TESTING_SPEC.md` 为准，包括「测试稳定性」。
- 只用 JUnit 5，不新增 JUnit4、Spock 或 Groovy 测试。
- 测试类放 `src/test/java`，包名与被测类一致；新测试命名 `XxxTest`，集成测试可用 `XxxIntegrationTest`，已有的 `XxxTests` 保持原名。
- 断言验证关键输出和副作用；涉及权限、租户、乐观锁、异常和返回值转换时，至少覆盖一个正向场景和一个失败或边界场景。
- 需要真实 Nacos、真实 provider 或测试库的手工集成测试，用 `@Tag` 标明并写清运行前提，在 `CI=true` 时跳过。

## 注释

- 以 `AI_COMMENT_STYLE_GUIDE.md` 为准。

## README 维护

- 新增或修改功能后同步检查根目录 `README.md`：业务说明、接口、权限、DDL 脚本、默认数据、启动与验证命令。
- README 只写 `ai` 的真实业务上下文，通用编码规则留在 `docs/ai-coding`。

## 调度

- 个人 XXL-JOB 已退役，不新增其依赖、配置类或 `xxl-job.yaml` 导入；调度选用 NAS 上独立的 Conductor OSS，经 REST 接入。

## AI 编码检查清单

- 是否放进正确的功能包，没有新建按层划分的平行目录或 `common`、`helper` 包？
- 是否先检查 `../utils` 已有能力？
- 新增表是否补齐公共治理字段、PostgreSQL 注释与 H2 `schema.sql`，并按 `ddl_history` 规则新增增量脚本？
- 先查后改是否携带期望 `version`？
- 新表是用户/租户数据（显式写 `tenant_id`）还是平台数据（`@InterceptorIgnore` 加平台范围），是否写清？
- 受保护接口是否经过 `AiRequestIdentityResolver` 或 `AiControlPlaneAdministrator`？
- 普通 JSON 接口是否用 `ApiResponse`，异常是否脱敏？
- 是否同步 README 与 JUnit 5 测试，并满足 `TESTING_SPEC.md`「测试稳定性」？
- 升级 `rpc-api`、`utils` 时是否同步 CI 里固定的 `ref`？
- 是否执行 `TESTING_SPEC.md` 的必跑命令，推送后核对精确提交的 CI？

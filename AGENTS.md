# AGENTS.md

本文件是 `ai` 项目的 AI 编码入口。AI 修改本项目代码前，必须先阅读本文件，再按任务风险阅读对应规范和真实代码。

## Nacos 配置注释要求

- 新增或修改 Nacos 配置、模板、引导配置及渲染/发布工具前，必须阅读 [Nacos 注释与归类规范](docs/ai-coding/NACOS_COMMENT_SPEC.md)。
- 每个 dataId 写明职责、环境归属、消费者；每个可调参数写明用途、必要单位、格式、空值及开关影响。YAML 使用 `#` 注释，渲染结果必须保留说明。
- 注释整理保持键、类型、值、占位符和列表/import 顺序不变；发布前读回权威配置并备份，使用旧版本比对/CAS，发布后逐项读回。真实凭据不得进入 Git 或注释。
- 只处理明确授权的仓库及环境；Yudao 项目排除，不随本规范同步修改。

## 通用工程与 Git 交付约束

- 个人 GitHub 仅保留 `main` 长期分支，不再创建 `test/preprod`；短功能分支交付后清理。Git 分支不自动决定 profile/namespace。个人 dev 入口、`github-dev` 与公共库 `com` 坐标保持，不能复制公司环境或业务分支规则覆盖。
- 修改前先核仓库、HEAD、dirty、worktree 和其他会话，再 fetch；普通新目标从最新 origin/main 建短分支，同一任务续作和用户明确基线按现有授权处理。完整功能与必需跨仓配套一起验证交付。
- 本任务已授权合 main 并清理时，完整功能进入远端 main 且包含关系读回通过后清理本地和远程功能分支；未合、被其他 worktree 使用或仍有唯一工作时保留。保护 `main`，不强推，不用强删绕过检查。细则见 `docs/ai-coding/BRANCHING_SPEC.md`。
- 高风险变更必须有独立风险验证，不能只依赖实现者的唯一自证测试；Review 可按需补充，不增加已授权提交/合并/推送的人工批准门禁。纯文档运行差异、路径和凭据检查，不机械重跑 Java 测试。
- 修改版本前读 `docs/ai-coding/VERSIONING_SPEC.md`：MINOR/PATCH 各为 0–9，`1.2.9 -> 1.3.0`、`1.9.9 -> 2.0.0`；先核远端、tag、在途分支和已发布制品占用，同批只升一次，纯规范不升业务版本，不覆盖旧 tag/同版制品。
- Git 集成、CI、远端制品、部署和实际验收分别取证；当前任务已给出的授权持续有效，未覆盖的外部发布动作仍需明确授权。
- 维护者本人，以及维护者本机用维护者账号运行的 Claude / Codex 会话，可以直接合并到 `main`：合并前本机验证命令全过，合并后看 CI，红了马上修。本仓库 push 目前不会自动触发 CI，推送 `main` 后手动执行 `gh workflow run CI --repo sunkailun1992/ai --ref main`，确认新 run 的 `headSha` 等于推送的提交再读结论。
- 云端会话（`CLAUDE_CODE_REMOTE=true`，或会话里没有本机配置）只推会话分支，由维护者或本机会话合并。其他人的代码走 PR：CI 全绿、维护者审核批准、评审对话解决后再合并。代理不修改 ruleset 与仓库权限，除非用户当场明确要求。

## 项目定位

- 项目名称：`ai`
- 项目类型：患者分析与多模态报告解读后端
- 技术栈：Java 17、Spring Boot 4、Spring AI、Spring Cloud Alibaba、Nacos、Dubbo、MyBatis-Plus、PostgreSQL、Gradle Wrapper、`com:rpc-api`、`com:utils`
- 外部模型：Google Gemini、DeepSeek/OpenAI-compatible、通义千问 DashScope/OpenAI-compatible（由 `ai-agent` 执行层配置）
- Utils 制品隔离：本 GitHub 个人项目固定使用 `com:utils:1.6.3`；公司项目使用 `com.kellen:utils`。二者共用私有 Maven 地址，以 group 区分制品；Java 包名 `com.kellen.*` 不随 Maven group 改动。
- 核心风险：模型 Provider 选错、医疗 Prompt 污染、外部媒体 URL SSRF、重新引入 AI 对话文件流入口、密钥泄露、患者资料越权、会话历史越权、日志输出用户隐私

## 修改前阅读顺序

任何代码修改前必须先阅读：

1. `docs/ai-coding/README.md`：确认 AI 编码规范目录和阅读顺序。
2. `docs/ai-coding/AI_CODING_GUIDE.md`：确认项目职责、构建方式、模型切换、数据库实体、接口和目录边界。
3. `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md`：确认 Java、Spring Boot、Spring AI、Gradle、测试和文档目录规则。
4. `docs/ai-coding/AI_COMMENT_STYLE_GUIDE.md`：确认当前文件类型的注释规范、自解释优先、禁止注释掉死代码和排版要求。
5. `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md`：确认 Provider、Service、Validator、Repository 等设计模式和禁止过度抽象规则。
6. `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md`：确认风险分级、Definition of Done、必须验证项、CI 门禁和回滚要求。
7. `docs/ai-coding/AI_AUTOMATION_WORKFLOW.md`：需求较大、需要需求说明或验收标准时，按其流程和模板执行。
8. `docs/ai-coding/BRANCHING_SPEC.md`：确认分支命名、短分支生命周期、release/hotfix、tag 和清理规则。
9. `docs/ai-coding/ENVIRONMENT_CONFIG_SPEC.md`：确认环境、Nacos namespace、Java profile 和前端与服务端边界。
10. `docs/ai-coding/VERSIONING_SPEC.md`：确认 `group = 'com'`、`version = '1.0.0'`、补丁递增和消费者同步规则。
11. `docs/ai-coding/RPC_API_CODING_SPEC.md`：涉及 Dubbo RPC provider、consumer、接口或 DTO 时必须阅读。
12. `docs/ai-coding/TESTING_SPEC.md`：确认业务模块 SpringBootTest、真实 HTTP 集成测试、测试库、模型 stub、AssertJ 边界和测试稳定性。
13. `docs/ai-coding/PROJECT_CODING_SPEC.md`：确认通用微服务规则在 `ai` 的适用范围；与本文件冲突时以本文件为准。
14. `docs/ai-coding/SECURITY_CODING_SPEC.md`：确认密钥、用户输入、多模态 URL、日志、数据库和模型调用安全规则。
15. `docs/ai-coding/UTILS_PUBLIC_SPEC.md`：涉及错误码、数据库、乐观锁或 `utils` 公共能力时阅读。
16. `docs/ai-coding/NACOS_CONFIG_SPEC.md`：修改 Nacos 配置中心、共享 dataId 或 `application.yml` import 前必读。
17. 目标 Controller、Service、Repository、Entity、`application.yml` 和 Nacos 模板：确认真实调用链，不按文件名猜行为。

## 项目边界

- 模型选择必须通过 `ai-agent` 的 `AiChatClientProvider`（模型执行已在 `ai-agent`，`ai` 只经 `AiModelSelectionService` 维护默认 Provider），业务代码不得直接绑定某个模型供应商。
- 运行时模型切换通过 `ai_model_selection` 控制，允许值当前为 `google` / `deepseek` / `qwen`。
- 默认且当前唯一开放角色是 `patient-analysis` 患者分析助手；普通闲聊不作为产品能力保留。
- 角色切换必须通过 `AiRolePromptService` 读取数据库表 `ai_role_prompt`，配置中心只保留默认角色 key 和极简兜底提示词。
- 调用方 `instruction` 只能作为本轮补充要求，不能覆盖医疗安全边界；旧 `systemPrompt` 仅作为兼容字段处理。
- 微信小程序、微信 opaque 登录和 owner 专属会话/展示消息/上传管理均已退役，不恢复兼容端点或把 User ID 转为旧微信 owner。普通 User SSE、记忆、审计、RAG 与附件校验继续维护。
- 对话记忆统一通过 `DbChatMemoryService` 维护，模型上下文写入 `ai_chat_memory`，由 `MessageChatMemoryAdvisor` 自动回放。
- 对话审计记录统一写入 `ai_chat_message`；成功、失败和取消均必须由本轮已验证 `AiRequestIdentity` 显式写入 `tenant_id`，不依赖全局租户插件、来源分区、ambient 上下文或数据库默认值。系统参数统一写入 `system_parameter`。
- 角色 Prompt 统一写入 `ai_role_prompt`，新增角色不得把完整长 Prompt 固化到 YAML。
- 既有私有文件展示仅通过 `/api/v1/files/{fileId}/view?token=...`（或 `/api/files` 别名），先校验展示 token，再按已保存平台签名；不提供普通 User 直传登记或文件 owner 管理 API。
- 文件展示和 AI 多模态附件 URL 必须使用后端令牌地址 `/api/v1/files/{fileId}/view?token=...`；后端校验 token 后再生成短时签名 OSS URL，HEAD 请求必须返回真实 Content-Type 供多模态校验器使用。
- 新增或修改 MyBatis-Plus 实体时，必须同步新增或维护 `src/main/resources/db/postgresql/` 的 PostgreSQL 表、字段中文注释和 `src/test/resources/schema.sql`；历史 MySQL 脚本不回改，已执行的 PG 脚本追加增量，并补齐相应测试。字段含义要能直接支撑数据库查看和外部系统对接。
- 新增业务表必须补齐公共治理字段；数据库列 `version` 只做乐观锁，Prompt、模板、协议等业务版本使用 `prompt_version`、`template_version`、`protocol_version` 等明确列名。
- AI 服务全新或清空后的业务库首次启动前，必须先在目标业务库手动执行 utils 的 `db/postgresql/common-infra-schema.sql`，再执行 `db/postgresql/ai-schema.sql`；公共脚本包含 `ddl_history` 和 Seata AT `undo_log`，不能依赖应用首次启动自动创建基础表。
- 多模态附件只能使用配置白名单协议，默认 `http` / `https`；文件、图片、PDF、JSON 材料必须先上传到业务文件服务或 OSS，再把可访问 URL 传入 `attachments`。
- 新增模型 Provider、记忆策略、多模态校验、审计或响应结构时，必须优先沿用 `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md` 中的 Provider、Application Service、Strategy、Adapter、Validator、Repository 等既有模式。
- 新增本服务对外 Controller、服务前缀，或新增后续独立 Agent 服务需要经网关访问时，必须同步评估 `../gateway` 的 Nacos `gateway-spring.yaml`；需要 Swagger UI 聚合的服务要先保证对应网关文档路径返回 200，再加入 `springdoc.swagger-ui.urls`。
- 跨服务 Dubbo RPC 接口和 DTO 统一维护在同级 `../rpc-api`；`ai` 只实现或调用契约，不在本服务或 `utils` 复制接口定义。
- 配置归属按业务控制面与基础设施区分：模型部署、上游密钥、虚拟密钥与额度以 LiteLLM 原生管理台为唯一源；AI 管理入口仅展示真实只读目录，不执行推理探针。存储 provider/凭据/默认/文件策略以 AI DB 与管理 API 为唯一源，凭据使用 AES-GCM 加密，独立 KEK 仅从私有 OS 环境读取；不再导入 `storage.yaml`，不回退旧 Nacos 存储凭据。DB/MQ/注册中心等基础运行配置继续由 Nacos 管理，模板只提交占位符。
- 存储控制面读写统一要求活跃的 `PLATFORM_SUPER_ADMIN` 且具有 `user:auth:manage`。连接位置创建后不可变，凭据仅显式 KEEP/REPLACE；默认/历史 null 平台映射必须稳定，禁用上传不关闭历史读取，引用/default 配置禁删。任何外部探针先保存 requestId 意图，未知结果只读查回，不自动重放。
- 全部 Java 微服务统一保留 Spring Boot 4 依赖线；AI 项目使用 Spring AI 2 正式版，不能再回退到旧版 Spring Boot 或 Spring AI 预发布版本。
- 本项目不得引入交易、K线、下单、账户、行情、Discord、Apple 订阅等量化业务能力。

## AI 工程门禁

- 新增或修改功能前，必须按 `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md` 判断风险等级。
- 模型 Provider、Prompt、对话记忆、多模态、数据库、生产配置和安全边界相关改动默认中高风险。
- AI 不能为了让编译或测试通过而绕过 Provider 白名单、媒体 URL 协议限制、附件 MIME 校验、记忆窗口限制、数据库记录或异常脱敏。
- 测试分层按 `docs/ai-coding/TESTING_SPEC.md` 执行；核心业务不能只靠 mock 或纯对象 `assertThat`，必须补 Spring Boot 级别测试。
- 测试必须稳定可重复，按 `TESTING_SPEC.md`「测试稳定性」：不依赖时序、连接复用或执行顺序；偶发失败按缺陷处理，不用重试、跳过或删除测试掩盖。
- 修改模型调用时，必须保证同一轮请求使用同一个 `CurrentClient` 完成调用、响应和落库（`CurrentClient` 在 `ai-agent`，跨服务改动同步核对）。
- 修改对话记忆或会话切换时，必须保留条数上限、时间窗口和用户归属校验，且不得混淆 `ai_chat_conversation` 会话元数据、`ai_chat_memory` 模型记忆表与 `ai_chat_message` 审计表。
- 修改 Prompt 或角色时，必须保留“不能替代医生诊断、治疗、用药或急救判断”的安全边界。

## 验证命令

按改动范围选择验证：

```bash
./gradlew -q compileJava
./gradlew test
./gradlew clean build
bash scripts/check-secrets.sh
```

涉及 `rpc-api` 契约、数据库实体、Repository、Provider 切换、Prompt 或多模态 URL 时，必须执行完整构建或说明不能执行的原因。CI 依次执行编译、测试与密钥扫描；推送 `main` 后按上方合并规则核对精确提交的 CI。

## 禁止事项

- 禁止重新引入 Maven 构建。
- 禁止把模型调用散落到 Controller。
- 禁止把 Provider 名称、模型名、URL 或密钥写死在业务代码里。
- 禁止提交真实密钥、数据库密码、Webhook 或生产配置值；调整 Nacos 地址、namespace 或 group 必须能说明和当前微服务环境的对应关系。
- 禁止把用户输入直接拼 SQL、文件路径、URL 目标或系统命令。
- 禁止恢复公共 OSS 读链接模式；AI 对话只允许使用后端 token 展示地址引用已登记文件。
- 禁止提交 `.DS_Store`、本机绝对路径、临时日志和无关构建产物。

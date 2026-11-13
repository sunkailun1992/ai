# AI 工程门禁规范

通用交付按 `AGENTS.md` / `BRANCHING_SPEC.md`：完整功能与必需跨仓配套进入 main、远端核对后按授权清理源分支；CI、制品与部署分开报告。Review 是可按需补充的检查，不作为已授权提交、合并或推送的人工批准门禁；安全关键改动仍须有独立风险验证。

本文档用于约束 AI 生成或修改代码进入 `ai` 主业务服务的工程门禁。

## 1. 核心原则

1. AI 生成内容默认不可信，必须经过风险匹配的 diff、编译、测试和安全检查；Review 可按需补充。
2. 主 `ai` 服务不得重新引入本地模型执行，模型执行只能委托给 Nacos AI Registry 发现的 Agent。
3. AgentCard 缺失或接口地址缺失时必须显式失败，不能静默回退本地模型。
4. 对话记忆必须通过 `DbChatMemoryService`，并保留条数上限和时间窗口限制。
5. 多模态附件 URL 默认不可信，必须做协议、数量、远程 Content-Type、MIME 和异常处理。
6. 患者分析角色必须保留医疗安全边界，不能替代医生诊断、治疗、用药或急救判断。
7. 角色 Prompt 必须通过 `ai_role_prompt` 表运营，不能把完整长 Prompt 固化到 YAML。
8. 数据库访问必须通过 Repository 或受控 MyBatis-Plus 查询，不拼接用户输入。
9. 日志、异常响应和测试数据不得包含 API Key、Token、Webhook、用户隐私和内部服务地址。
10. 测试必须稳定可重复：不依赖时序、连接复用或执行顺序；偶发失败按缺陷处理，不用重试、跳过或删除测试掩盖，细则见 `TESTING_SPEC.md`「测试稳定性」。

## 2. 风险分级

| 风险级别 | 变更类型 | 门禁要求 |
| --- | --- | --- |
| 低风险 | README、注释、接口示例、无行为变化的文档 | 检查 diff，确认无密钥和错误接口说明 |
| 中风险 | DTO、普通 Controller、普通 Service、小范围配置、普通数据库查询 | 执行 `TESTING_SPEC.md` 的必跑命令，核对异常响应、配置、DTO 和数据库字段 |
| 极高风险 | 密钥管理、生产配置、认证授权、重新引入文件流、工具调用、持久化聊天记录结构重建 | 先写方案和验收标准，再拆分实现、测试、回滚步骤 |

## 3. Definition of Done

1. 需求边界明确，说明做什么和不做什么。
2. 代码符合现有分层，不把 Agent 调用散落到多个 Controller。
3. 主服务没有重新引入 Spring AI 模型执行依赖。
4. Nacos AI Registry 配置和 AgentCard 读取路径清晰。
5. 多模态输入有协议、数量、远程 Content-Type 和 MIME 限制。
6. MyBatis-Plus 实体字段、索引和种子数据与 `src/main/resources/db/postgresql/` 的 PostgreSQL 脚本及 `src/test/resources/schema.sql` 保持一致；全新或清空后的业务库首次启动前，已在目标业务库先手动执行 utils 的 `db/postgresql/common-infra-schema.sql`，再执行 `db/postgresql/ai-schema.sql`，确保 `ddl_history` 和 Seata AT `undo_log` 先于应用启动存在。`src/main/resources/db/ai-init.sql` 是历史 MySQL 脚本，不回改、不再执行。
7. 异常响应不泄露内部堆栈、密钥或底层服务地址。
8. 文档同步更新接口示例、配置说明和验证命令。
9. 已执行 `TESTING_SPEC.md` 的必跑命令，新增或修改的测试符合「测试稳定性」；未执行的写明原因。
10. 每次推送 `main` 后，精确提交的远端 CI 有结论且通过。本仓库 push 目前不会自动触发 CI，需手动触发并确认 run 的 `headSha` 等于推送的提交；CI 失败立即修复，不以本机通过代替，交付说明写明 run 链接与结论。

## 4. 必查项

| 场景 | 必查项 |
| --- | --- |
| AI 对话 | Prompt 是否来自 `ai_role_prompt`、是否通过 Agent Registry 调用 Agent、是否保留患者分析医疗安全边界 |
| Agent Registry | Agent 名称、接口 URL、协议、流式开关、技能和租户是否与 Nacos AI 注册中心一致 |
| 对话记忆 | 会话 ID、记忆条数和时间窗口、清空接口影响范围、记忆表与审计表区分 |
| 多模态 | URL 协议、媒体数量、远程 Content-Type、MIME 类型、错误响应、SSRF 边界 |
| 流式响应 | SSE 类型、异常路径、客户端断开后的服务端行为、完成后是否落库 |
| 数据库 | Entity 字段、Repository 查询、DDL 初始化、H2 测试覆盖、是否引入无关表 |
| 配置 | 环境变量、默认值、Nacos Data ID、数据库地址、密钥占位符 |
| 安全 | 密钥、日志脱敏、用户输入、外部 URL、异常信息 |
| 测试稳定性 | 时间与等待是否注入或有总时限、桩服务端口与连接关闭、进程级共享状态、连续三轮结果、平台差异 |
| 构建 | `./gradlew -q compileJava`、`./gradlew test`、`bash scripts/check-secrets.sh`、`bash -n scripts/check-nacos-ai-config.sh`、`git status --short`、`git diff --stat` |
| CI | 推送后 run 的 `headSha`、Compile / Test / Secret warning scan 三步结论、失败用例与处理 |

## 5. 交付模板

```text
变更内容：
- <本次修改了什么>

验证结果：
- <测试/编译/接口验证命令和结果>
- <CI run 链接、headSha 与结论；只在一个平台验证过的结论写明平台>

Agent 检查：
- <Nacos AI Registry、AgentCard、接口 URL、协议和流式配置>

数据库检查：
- <实体、Repository、DDL、测试库覆盖>

安全检查：
- <密钥、多模态 URL、日志、异常响应检查结果>

风险和回滚：
- <剩余风险>
- <回滚方式>
```

# AI 目录管理规范

本规范约束 AI 在 `ai` 主业务服务中新增、移动、拆分和命名目录的方式。当前服务是 Spring Boot 4 + Spring Cloud Alibaba + Nacos AI Registry + MyBatis-Plus 项目，不是模型执行服务。

## 核心依据

- Gradle Java SourceSet：生产源码放 `src/main/java`，生产资源放 `src/main/resources`，测试源码放 `src/test/java`，测试资源放 `src/test/resources`。
- Spring Boot 包结构：主应用类位于根包下，业务组件位于根包子包内。
- Agent 边界：主服务只编排和调用 Agent，不持有模型 SDK。
- MyBatis-Plus 分层：Entity、Repository、Service 和 DTO 职责清晰。
- AI 规范放 `docs/ai-coding/`，根目录只保留 `AGENTS.md` 和 README 作为入口。

## 当前标准目录

```text
.
├── AGENTS.md
├── README.md
├── build.gradle
├── settings.gradle
├── gradle/
├── src/
├── docs/ai-coding/
├── scripts/
└── .github/
```

生产代码根包（主应用类 `AiApplication`）：

```text
src/main/java/com/kellen
```

代码按功能分包，同一功能的 Controller、Service、Repository、Entity 与请求/响应对象放在同一个包里。当前功能包职责：

| 包 | 职责 |
| --- | --- |
| `agent` | 按 Nacos AI Registry 调用 `ai-agent`（`AiAgentClient`、注册中心客户端、请求签名）与运行时模型选择。 |
| `audit` | 对话审计 `ai_chat_message` 落库。 |
| `auth` | 请求身份解析与租户上下文（`AiRequestIdentity`）。 |
| `config` | `ai.*` 类型安全配置绑定与 PostgreSQL DDL 初始化（`PostgresqlDdl`）。 |
| `controlplane` | 平台控制面管理员校验。 |
| `conversation` | 对话入口与编排（`AiDialogueController`、`AiDialogueService`）及其请求/响应对象。 |
| `exception` | 统一异常响应。 |
| `file` | 私有文件展示、OSS 签名与多模态附件校验。 |
| `memory` | 对话记忆、记忆摘要与患者记忆。 |
| `modelcontrolplane` | LiteLLM 只读模型目录与历史实测结果投影。 |
| `observability` | 模型调用观测。 |
| `prompt` | 角色 Prompt 解析与默认提示词初始化。 |
| `rag` | 通过 Dubbo 调用独立 `rag` 服务获取检索上下文。 |
| `repository` | MyBatis-Plus 基础接口 `AiBaseRepository` 与现有 AI 业务实体的公共字段基类 `AiGovernanceEntity`。 |
| `storageconfig` | 统一存储控制面：存储配置、加密凭据、全局默认与操作记录。 |

## 目录规则

- 新增模型执行能力优先放到 `ai-agent` 或新的独立 Agent 服务，不在主 `ai` 服务新增厂商 SDK 目录。
- 新增 Agent 消费逻辑放在 `AiAgentClient` 或 Nacos Registry 相关服务，不在 Controller 直接调用外部 Agent。
- 新增能力放进对应功能包；确属新功能时新建 `com.kellen.<功能>` 包，把该功能的 Controller、Service、Repository、Entity 与请求/响应对象放在一起，不另建按层划分的平行目录。
- 新增多模态、Prompt、安全校验、审计或记忆策略时优先复用现有功能包里的组件，不新增 `helper`、`manager`、`common` 等低信息目录。
- 新增测试放 `src/test/java`，包名与被测类一致（主代码均在 `com.kellen.*`）；`src/test/java/com/ai` 下是早期测试，可原地维护。测试配置和 Mock 数据放 `src/test/resources`。
- `src/main/resources/db/postgresql/` 放运行时 PostgreSQL 全量与增量脚本；`src/main/resources/db/` 根下的 `ai-init.sql` 等是历史 MySQL 脚本，不回改；`src/test/resources/schema.sql` 放 H2 测试库 DDL。
- Nacos 真实配置只维护在配置中心，不恢复 `docs/nacos` 本地模板目录。
- 构建产物、日志、IDE 文件、本机模块文件和系统文件不得提交。

## 变更流程

1. 先判断文件属于源码、测试、资源、文档、脚本、CI 还是工具配置。
2. 查找现有同类目录，优先复用，不新增平行体系。
3. 移动 Java 文件时同步 `package`、import、测试、README 和 AI 规范引用。
4. 目录移动和行为修改尽量分开。
5. 涉及 Java 目录或 package 变化时执行 `./gradlew test` 或说明无法执行原因。

## 检查清单

- 是否符合 Java / Spring Boot / Gradle 主流目录约定？
- 是否保持 `AiDialogueController -> AiDialogueService -> AiAgentClient -> ai-agent` 主链路清晰？
- 是否避免把模型执行依赖放回主服务？
- 是否避免把测试、Mock、构建产物或日志放进生产目录？
- 是否没有移动或替换已有模型 Key、数据库连接和生产配置？

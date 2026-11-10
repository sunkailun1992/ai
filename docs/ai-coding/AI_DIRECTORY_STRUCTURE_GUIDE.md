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

生产代码根包：

```text
src/main/java/com/ai
```

当前服务目录职责：

| 目录 | 职责 |
| --- | --- |
| `config` | `ai.*` 类型安全配置绑定、DDL 初始化和 Nacos AI Registry 配置。 |
| `controller` | AI 对话、文件和微信认证 HTTP 入口。 |
| `dto` | 对话、多模态、文件和记忆接口 DTO。 |
| `entity` | 业务表 MyBatis-Plus 实体。 |
| `exception` | 统一异常响应。 |
| `repository` | MyBatis-Plus Mapper Repository。 |
| `service` | 主业务编排、Agent 调用、角色 Prompt、记忆、审计、多模态校验和文件服务。 |

## 目录规则

- 新增模型执行能力优先放到 `ai-agent` 或新的独立 Agent 服务，不在主 `ai` 服务新增厂商 SDK 目录。
- 新增 Agent 消费逻辑放在 `AiAgentClient` 或 Nacos Registry 相关服务，不在 Controller 直接调用外部 Agent。
- 新增接口 DTO 放 `dto`，新增数据库表放 `entity` + `repository`，新增业务编排放 `service`。
- 新增多模态、Prompt、安全校验、审计或记忆策略时优先复用现有 service 子职责，不新增 `helper`、`manager`、`common` 等低信息目录。
- 新增测试放 `src/test/java/com/ai`，测试配置和 Mock 数据放 `src/test/resources`。
- `src/main/resources/db/` 放运行时 MySQL DDL；`src/test/resources/schema.sql` 放 H2 测试库 DDL。
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

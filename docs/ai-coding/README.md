# AI 编码规范入口

涉及 Nacos 配置、模板、引导或渲染/发布工具时，必须同时阅读 [Nacos 配置注释与归类规范](NACOS_COMMENT_SPEC.md)。

本目录集中维护 `ai` 主业务服务的 AI 编程规范。根目录 `AGENTS.md` 只保留项目入口和最高优先级边界；细分规范统一放在本目录。

## 通用规则入口

先核对 `BRANCHING_SPEC.md` 的 main/test/preprod、完整功能交付与清理保护，再按 `VERSIONING_SPEC.md` 做版本进位和已用制品核对；安全关键改动须有独立风险验证，Review 是补充。纯规范变更不提升业务版本、不机械重跑 Java 测试。

## 阅读顺序

1. 根目录 `AGENTS.md`
2. 根目录 `README.md`
3. `AI_CODING_GUIDE.md`
4. `AI_DIRECTORY_STRUCTURE_GUIDE.md`
5. `AI_COMMENT_STYLE_GUIDE.md`
6. `AI_DESIGN_PATTERN_GUIDE.md`
7. `AI_ENGINEERING_GUARDRAILS.md`
8. `BRANCHING_SPEC.md`
9. `ENVIRONMENT_CONFIG_SPEC.md`
10. `VERSIONING_SPEC.md`
11. `RPC_API_CODING_SPEC.md`
12. `TESTING_SPEC.md`
13. `SECURITY_CODING_SPEC.md`
14. 目标 Controller、Service、Repository、Entity、DTO、配置或测试

## 目录结构

```text
docs/ai-coding/
  README.md
  AI_CODING_GUIDE.md
  AI_DIRECTORY_STRUCTURE_GUIDE.md
  AI_COMMENT_STYLE_GUIDE.md
  AI_DESIGN_PATTERN_GUIDE.md
  AI_ENGINEERING_GUARDRAILS.md
  BRANCHING_SPEC.md
  ENVIRONMENT_CONFIG_SPEC.md
  VERSIONING_SPEC.md
  RPC_API_CODING_SPEC.md
  TESTING_SPEC.md
  SECURITY_CODING_SPEC.md
  archive/
  examples/
```

## 必读结论

- `ai` 是主业务编排服务，不直接执行模型。
- 模型执行通过 Nacos AI Registry 发现 `ai-agent` 或后续独立 Agent 服务。
- 主调用链必须保持 `AiDialogueController -> AiDialogueService -> AiAgentClient -> ai-agent`。
- 角色 Prompt 通过 `AiRolePromptService` 和 `ai_role_prompt` 表维护。
- AI 表设计按 `AI_CODING_GUIDE.md` 公共治理字段执行；`version` 只做乐观锁，业务版本使用 `prompt_version`、`template_version` 等明确列名。
- 已有模型 Key、数据库连接和生产配置只能提示风险，不得由 AI 自动替换、删除或移动。
- 分支命名、短分支生命周期、release/hotfix、tag 和分支清理按 `BRANCHING_SPEC.md` 处理。
- 环境、Nacos namespace、Java profile 和前端与服务端边界按 `ENVIRONMENT_CONFIG_SPEC.md` 处理。
- 项目版本、公共包依赖和后端 Java 新项目基础坐标按 `VERSIONING_SPEC.md` 处理；纯 AI 规范或 README 改动不提升制品版本。
- Dubbo RPC 契约统一维护在同级 `../rpc-api`；`ai` 通过契约调用或发布稳定能力，不在服务内复制接口/DTO。
- 测试按 `TESTING_SPEC.md` 分层；核心接口用真实 Spring Boot HTTP 集成测试，核心 Service 用真实 Spring Bean 测试，模型/OSS 等外部依赖默认 stub。

接口失败事件与配套升级见 [typed SSE 合同](docs/typed-sse-contract.md)。功能退役范围和数据清理边界见 [退役说明](docs/feature-retirement.md)。

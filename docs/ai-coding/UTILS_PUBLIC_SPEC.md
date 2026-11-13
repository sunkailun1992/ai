# Utils 公共规范迁移说明

本文件是从 `utils` 公共 markdown 提炼出的 AI 阅读版规范，只保留 `ai` 编码会用到的公共规则。归档原文保存在 `docs/ai-coding/archive/utils-markdown/`，需要完整错误码、公告原文或 Java 通用规范时再打开对应文档。本文件与根目录 `AGENTS.md` 冲突时，以 `AGENTS.md` 为准。

## 原始资料映射

| 原始文件 | 迁移后位置 | AI 阅读用途 |
| --- | --- | --- |
| `Announcement.md` | `archive/utils-markdown/Announcement.md` | 团队分支、环境、乐观锁、枚举、数据库和注释规则 |
| `ErrorCode.md` | `archive/utils-markdown/ErrorCode.md` | 统一错误码分类和错误信息 |
| `Specification.md` | `archive/utils-markdown/Specification.md` | 压缩版 Java 开发规范，已去掉图片和外部网站地址 |

## 环境规则

- profile、Nacos namespace 与私有引导配置按 `ENVIRONMENT_CONFIG_SPEC.md`；当前个人本地 dev 使用 `github-dev`，不沿用归档里的旧环境或组织工具约定。
- main/test/preprod 是 Git 职责，不自动切换运行环境；正式环境不得制造测试数据，配置、数据修复和部署按明确授权与回滚计划执行。

## 分支规则

- 当前分支流程以 `AGENTS.md` / `BRANCHING_SPEC.md` 为准：核仓库、WIP、worktree 后 fetch，普通新目标从最新 origin/main 建短分支，承载完整功能及必需配套。
- 授权集成 test/preprod 后保留尚未进入 main 的源分支；完整功能及配套进入远端 main、读回包含关系后，按已有授权清理本地/远程功能分支。
- 保护 main/test/preprod、未合或其他 worktree 在用的分支；不强推、不按过期天数自动删除，不把释放 worktree 当作分支交付。
- archive 里的团队原文只作历史资料，不作为当前操作指令；版本、精确公共包和外部发布授权按现行规范执行。

## 数据库规则

- 表结构变动时，同步 `src/main/resources/db/postgresql/` 的权威 DDL、实体与 `src/test/resources/schema.sql`，并记录 migration 和回滚影响；已执行的脚本按 `PROJECT_CODING_SPEC.md`「DDL 维护」追加增量脚本。
- 表结构变更 SQL 必须关联对应需求与交付记录。
- 数据库状态字段要有对应枚举类，整数状态枚举实现 `IEnum<Integer>`，用于接口返回和中文说明输出。现有 AI 业务实体没有映射 `type`、`state`；新增实体需要读写这类字段时按此执行，不把业务枚举放进公共字段基类。
- 租户：utils 的 `MyBatisPlusConfig` 注册了 `TenantLineInnerInterceptor`。`ai` 的对话审计由本轮已验证的 `AiRequestIdentity` 显式写入 `tenant_id`，不依赖全局租户插件、ambient 上下文或数据库默认值；平台级存储控制面表在 Repository 上用 `@InterceptorIgnore(tenantLine = "true")` 显式跳过租户拼接。新增表先判断属于用户/租户数据还是平台数据，再选其中一种写法。
- 逻辑删除：现有 AI 实体没有使用 `@TableLogic`。手写 SQL 的查询显式带 `is_delete = false`，删除写成软删除并同步递增 `version`（见 `StorageConfigurationRepository`）。

## 乐观锁规则

- 数据库列 `version` 只表示乐观锁，不是 HTTP 请求头版本号，也不是 Prompt、模板、协议等业务版本（业务版本用 `prompt_version` 等明确列名）。
- 任何先查询后修改的操作，都必须以读到的 `version` 作为期望值提交，不能绕过版本直接覆盖数据。
- 手写 SQL 写成比较并更新：`WHERE ... AND version = #{expected}`，同时 `SET version = version + 1`。影响行数为 0 时按并发修改处理，不自动重试覆盖（见存储控制面 Repository）。
- 实体若继承带 `@Version` 的基类（如 utils 的 `EntityBase`），由 utils 注册的 `OptimisticLockerInnerInterceptor` 校验；更新优先 `updateById(entity)`，实体必须携带查询得到的旧 `version`。
- AI 生成更新方法时，必须检查入参或查询对象是否携带数据库记录的 `version`。

## 错误码规则

错误码完整表见 `archive/utils-markdown/ErrorCode.md`。AI 编码时先按错误来源选择分类：

| 分类 | 含义 | 常见场景 |
| --- | --- | --- |
| `A` | 用户端错误 | 参数错误、登录失败、无权限、用户版本异常、重复请求 |
| `B` | 当前系统错误 | 程序执行失败、超时、限流、资源耗尽、业务逻辑异常 |
| `C` | 第三方服务错误 | RPC、消息、缓存、配置、网络、数据库、通知服务异常 |

常用错误码优先级：

- 成功使用 `0 | 成功`，代码中优先复用 `ReturnCode.成功`。
- 未授权或权限不足使用访问权限异常，例如 `A0301 | 访问未授权` 或 `A0312 | 无权限使用 API`。
- 参数缺失使用 `A0410 | 请求必填参数为空`。
- 参数格式错误使用 `A0421 | 参数格式不匹配`。
- 重复请求使用 `A0506 | 用户重复请求`。
- 乐观锁更新失败时使用项目已有并发修改或业务失败返回码；不要再按 HTTP 请求头 API 版本校验处理。
- 数据库表、列、死锁、主键冲突等基础设施问题归入 `C03xx`。
- 对外失败响应仍按 `SECURITY_CODING_SPEC.md` 脱敏，不把上游原始异常、地址或凭据带进错误信息。

## AI 编码前检查

- 是否已阅读 `PROJECT_CODING_SPEC.md` 和本文件。
- 是否需要打开 `archive/utils-markdown/ErrorCode.md` 查完整错误码。
- 是否需要打开 `archive/utils-markdown/Announcement.md` 核对历史原文；当前操作仍按 `AGENTS.md` / `BRANCHING_SPEC.md`，不恢复旧分支流程。
- 是否需要打开 `archive/utils-markdown/Specification.md` 核对 Java 通用规范。
- 是否避免把公共规范重新放回运行时 `src/main/resources`。

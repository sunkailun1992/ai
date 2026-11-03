# AI 注释规范

本规范约束 AI 在 `ai` 项目中新增或修改注释的方式。项目是 Spring Boot 主业务服务，通过 Nacos AI Registry 委托 `ai-agent` 执行模型，注释必须服务于长期维护，不能留下机械的“AI 做了什么”痕迹。

## 0. AI 执行流程

- 修改注释前先识别文件类型和上下文，例如 Java/Spring Boot、Nacos AI Registry、YAML、Gradle、Shell、SQL、Markdown 或 properties。
- 优先阅读 `AGENTS.md`、`AI_CODING_GUIDE.md`、`AI_DIRECTORY_STRUCTURE_GUIDE.md`、`AI_ENGINEERING_GUARDRAILS.md`、`SECURITY_CODING_SPEC.md`。
- 本规范未覆盖的文件类型，先查官方或主流规范，补充规范来源和项目落地规则后再改代码。
- 不为了统一风格批量重排配置文件、密钥所在行或生产连接配置。

## 1. 总原则

- 自解释优先：能用清晰命名、小方法、常量和类型表达的意图，先重构代码，不用注释补救。
- 注释只解释代码看不出的内容：Agent 来源、Prompt 约束、对话记忆窗口、多模态 URL 安全、审计落库、副作用和失败策略。
- 不给 package、import、普通注解、简单赋值、普通 getter/setter 或显而易见的链式调用逐行加注释。
- 禁止逐行翻译式注释，例如“调用 service”“返回结果”“设置字段”。
- 禁止用注释保留废弃实现、调试 main 或整块旧代码；历史版本交给 Git，注释掉的死代码应删除。
- 注释必须随代码同步更新，过时注释比没有注释更危险。
- 作者、创建时间、邮箱和修改历史交给 Git 记录；Javadoc 不写 `@DateTime`、`@email`、`@ClassName`、`@explain` 等非标准标签。

## 2. Java 注释

- Controller、Service、Agent Adapter、Memory、Validator、Audit、配置类和异常处理类应使用类级 Javadoc 说明职责、边界和安全约束。
- 公开方法、Bean 方法、外部模型调用入口、数据库写入入口和安全校验方法应使用方法级 Javadoc 说明契约、副作用、异常和降级策略。
- 实现注释只写在关键逻辑块上方，说明为什么这样做、保护什么边界、不能绕过什么约束。
- Agent 调用相关注释必须说明 AgentCard、接口地址和调用方责任，避免业务代码绕过 Nacos AI Registry。
- 多模态 URL、Prompt、聊天记忆、审计日志和异常脱敏逻辑的注释要说明安全边界，不重复语法。

## 3. YAML、Gradle、Shell 和 Markdown 注释

- YAML 配置和 Nacos 模板注释解释配置意图、默认值含义、覆盖方式和误改风险，不要求机械行尾注释。
- 发现模型 Key、数据库密码、Webhook 或生产连接信息时，只报告文件行号和风险，不自动替换、删除或移动。
- Gradle 注释解释插件、依赖、任务和版本选择的项目原因，不解释 DSL 语法。
- Shell 注释解释安全边界、错误处理、密钥脱敏、退出码和幂等策略，不解释普通命令。
- Markdown 文档把关键规则写成可见正文，不用隐藏注释承载规范。

## 4. 格式和美观度

- 维持当前文件缩进、空行、换行宽度和段落风格，不在同一文件混用多套注释风格。
- JavaDoc 段落短而完整，长句按语义换行，不写超长单行。
- 行尾注释只用于短枚举、短单位或既有对齐风格；造成列宽混乱或超长行时改为块上方注释。
- 不为了“看起来整齐”改动密钥、生产地址、数据库凭据、token 或 webhook 所在行。
- 提交前从 diff 视觉检查一次：注释应让代码更容易扫读，而不是更乱。

## 5. 检查清单

- 注释是否解释了代码看不出的“为什么”？
- 是否可以用更好的命名、小方法、常量或类型替代注释？
- 是否存在注释掉的旧代码、调试代码或整块废弃实现？
- 是否泄露密钥、生产地址、个人凭据或内部令牌？
- 缩进、换行、对齐和段落是否与当前文件风格一致？

## 6. 参考依据

- [Google Java Style Guide - Javadoc](https://google.github.io/styleguide/javaguide.html#s7-javadoc)
- [Oracle JDK Documentation Comment Specification](https://docs.oracle.com/en/java/javase/21/docs/specs/javadoc/doc-comment-spec.html)
- [YAML 1.2.2 Specification - Comments](https://yaml.org/spec/1.2.2/#comments)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Gradle Build Language Reference](https://docs.gradle.org/current/dsl/)
- [Google Shell Style Guide](https://google.github.io/styleguide/shellguide.html)
- Robert C. Martin《Clean Code》第 4 章 Comments：注释是次优手段，优先让代码自解释；注释掉的代码应删除。

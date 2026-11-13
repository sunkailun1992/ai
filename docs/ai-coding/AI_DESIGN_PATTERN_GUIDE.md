# AI 设计模式规范

本规范约束 AI 在 `ai` 主业务服务中选择、引入和调整设计模式的方式。目标是让 Agent 委托、记忆、审计和多模态安全边界更清晰，而不是为了展示模式增加抽象。

## 1. 总原则

- 先识别当前语言、框架和模块边界；本项目默认按 Java 21、Spring Boot 4、Nacos AI Registry、MyBatis-Plus 和 Gradle 生态落地。
- 优先沿用项目已有结构：Controller、Service、Adapter、Validator、Repository、Entity、DTO。
- 只有当模式能稳定隔离 Agent 发现、Prompt、记忆、审计、多模态校验或数据库访问时才引入。
- 不允许为简单逻辑硬套 Factory、Manager、Helper、Abstract 层或过深继承。
- 设计模式不能绕过 Nacos AI Registry、多模态协议限制、记忆窗口、审计落库、异常脱敏和密钥保护。

## 2. 推荐模式

### Application Service / Facade

- `AiDialogueService` 是对话用例入口，负责Prompt、记忆、Agent 调用和审计编排。
- Controller 保持薄入口，只处理 HTTP 入参出参。
- 不把 Agent 调用、审计、记忆写入分散到多个 Controller。

### Adapter

- `NacosAiAgentRegistryClient` 是 Nacos AI Registry 的适配器。
- `AiAgentClient` 是 Agent HTTP 接口的适配器。
- 上层业务只依赖统一服务契约，不关心 Nacos SDK 和 HTTP 细节。

### Validator

- `MediaInputValidator` 负责多模态 URL、数量、MIME、协议和远程 Content-Type 校验。
- 校验器应保持可单测。
- 安全校验失败默认拒绝，不降级为跳过检查。

### Repository / Data Mapper

- `*Repository` 是 MyBatis-Plus Mapper Repository，只表达数据访问。
- 新增查询必须考虑会话归属、分页、时间窗口和脱敏。
- 复杂业务放在 Service，不放进 Repository 默认方法。

### Builder / Factory Method

- 适用于复杂 DTO、审计记录和响应对象构造。
- 默认值必须确认不会破坏 MyBatis-Plus、配置绑定或审计语义。
- 不隐藏网络调用、写库或 Agent 调用副作用。

## 3. 禁止事项

- 不在主 `ai` 服务中新增模型 Provider 工厂。
- 不在 Controller 中直接绑定厂商 SDK 或 Agent HTTP 地址。
- 不为当前两个 Agent 过早引入复杂路由引擎；真实出现多 Agent 策略时再抽象。

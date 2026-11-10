# 安全编码规范

本文件维护 `ai` 主业务服务的安全规则。新增或修改接口、Agent 调用、Prompt、多模态、数据库、配置、日志或测试时必须遵守。

## 密钥和外部凭证

- Gemini、DeepSeek、数据库、OSS、Nacos、Webhook 等密钥不得写入 Java 代码、测试资源、脚本默认值、日志和接口响应。
- 测试不能为了通过 CI 写真实凭据。
- 发现历史疑似密钥时，只报告文件和行号、说明风险，不自动替换为新密钥。
- 异常信息不得返回完整 API Key、Secret、数据库连接串、JWT、会话标识和内部服务器路径。

## Agent 调用安全

- 主 `ai` 服务只通过 Nacos AI Registry 读取 AgentCard 并调用 Agent 接口。
- 不允许绕过 Registry 保存本地 Agent baseUrl 兜底。
- 不允许在主服务重新注入 Spring AI 模型客户端或厂商 SDK。
- AgentCard 缺失、接口地址缺失或协议不匹配时应显式失败。
- Agent 名称、接口地址和技能说明由 Nacos AI Registry 管理；模型 Key 和 Provider 由 `ai-agent` 配置管理。
- Agent 经网关暴露前必须配置专用服务身份签名与执行层严格验签；身份来自受控服务配置，不能取自终端用户、租户或请求正文，也不与外部 MCP 客户端共用。签名需绑定真实方法、路径、查询串与正文，每次流式订阅使用新 nonce；失败关闭与当前接法见 [通用安全与私有存储](../security-architecture-sync.md)。

## Prompt 和 AI 输出安全

- 完整角色 Prompt 存在 `ai_role_prompt`，不能把可运营长 Prompt 固化到 YAML。
- 患者分析助手必须明确：输出只用于健康信息理解和就医沟通准备，不能替代医生诊断、治疗、用药或急救判断。
- 用户传入的 `instruction` 只能作为本轮补充要求，不能覆盖医疗安全边界或角色边界。
- 模型输出只能作为文本结果保存和返回，不能直接拼 SQL、URL、文件路径、命令或危险操作。

## 多模态和外部 URL

- 多模态媒体 URL 默认不可信，必须校验协议白名单、数量、MIME 和远程 Content-Type。
- 当前默认只允许 `http` / `https`。
- 不允许支持 `file`、`classpath`、`ftp`、`jar` 等本地或非预期协议。
- 文件、图片、PDF、JSON 材料等资料必须先上传到业务文件服务或 OSS，再把可访问 URL 传入 `attachments`。
- AI 对话接口不得重新暴露 `multipart/form-data` 文件流入口。

## 数据库和查询安全

- 数据库访问统一使用 MyBatis-Plus Repository，不拼接用户输入形成 SQL。
- `ai_chat_message` 只保存审计。
- `ai_chat_memory` 只保存普通聊天上下文。
- `ai_role_prompt` 保存可运营角色 Prompt。
- 主服务不再使用 `system_parameter.ai_provider` 做模型切换。
- 真实数据库密码、模型 Key、OSS 密钥必须在 Nacos 配置中心或环境变量维护。

## 接口安全

- 对话类 Controller 必须返回 SSE 流式输出，不新增同步 JSON 模型输出接口。
- 公开接口不得返回内部异常堆栈、数据库地址、完整配置或服务器文件路径。
- 会话列表、历史消息、删除和重命名必须校验当前登录用户归属。
- 参数必须校验非空、长度、枚举值和协议白名单。

## 日志安全

- 日志不得输出完整 API Key、Secret、数据库密码、用户隐私或完整媒体 URL 列表。
- Agent 调用异常日志应记录错误类型、会话 ID、Agent 名称和摘要。
- 长文本 Prompt、用户输入和模型输出默认不打印到生产日志。

## 测试要求

- 数据库实体变更必须覆盖 Spring 上下文启动。
- 多模态必须覆盖数量超限、非法协议、非法 MIME、远程 Content-Type 不匹配和合法远程附件输入。
- 对话记忆必须覆盖时间正序回放、写入和按会话清空。
- 测试环境使用 H2 内存库，不依赖真实外部模型或生产 MySQL。

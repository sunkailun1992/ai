# AI 与 Agent 的流式错误合同

患者分析和文档／报告分析继续通过 `POST /api/ai/chat` 进入，由主 `ai` 编排后调用
Agent 的 `POST /api/agents/patient-analysis/stream`。正常输出维持纯文本 SSE `data`；
失败使用 `event:error`，`data` 是固定友好提示。事件类型承载机器可读的失败语义，
不靠文本是否包含“失败”等词判断，不使用 `id` 充当错误码，也不返回原始供应商异常。

Agent 执行失败的逻辑数据为 `\n[AI Agent 调用失败] 请稍后重试`，编码示例：

```text
event:error
data:
data:[AI Agent 调用失败] 请稍后重试

```

Agent Service 保留失败终态，模型与 Agent 执行观测继续记录 failure；只有 Controller
将失败转换为上述 HTTP 事件。AI Client 读取 SSE 元数据，遇 `error` 产生无上游正文、
无原始 cause 的 `AI_AGENT_EXECUTION_FAILED` 异常，再由 AI 的 HTTP 边界返回相同友好提示。
HTTP 200 只是已经建立流，不能单独代表模型调用成功。

AI 在失败路径记录 failure 审计和指标。即使此前已经输出部分文本，也不得执行 success
审计、assistant 记忆写入或正常 assistant 历史保存；已写的 user 输入保持既有生命周期。
取消保持 cancelled，不能转成失败友好事件或 success。

## 内部协议确认与升级

AI 请求携带 `X-AI-Stream-Protocol: typed-sse-v1`。Agent 响应使用同名、同值的单一响应头
确认协议；不支持的请求值在模型执行前返回 HTTP 406。AI 拒绝缺失、重复或不支持的响应
确认，以及非 SSE、未知事件类型和从未提供输出的空 EOF。无 `event` 或 `event:message`
均表示正常文本 chunk；`event:error` 无论是否带数据都表示失败。

HTTP 401/403/5xx 继续作为 HTTP 错误处理，不会为了协议确认被改判为普通成功文本。
服务 HMAC、每次订阅 nonce、终端 User JWT、租户、Memory 和取消释放机制保持原边界。
Controller 在 Servlet 入口装配业务流，捕获本轮可信身份和父 trace，不能延迟到另一线程
再从请求上下文推定身份。

| AI 版本 | Agent 版本 | 结果 |
|---|---|---|
| 支持本合同 | 支持本合同 | 明确传播失败，友好文本与错误终态同时保留 |
| 支持本合同 | 旧版 | 缺协议确认即失败关闭，不把旧 fallback 文本当成功 |
| 旧版 | 支持本合同 | 能继续显示纯文本 data；旧 AI 忽略错误类型，仍可能错误记录 success |

AI 和 Agent 必须配套升级并验收。先升级 AI 能防止旧协议的文本被计作成功，但旧 Agent
仍可能在响应被 AI 拒绝前调用模型；协议校验不是“供应商从未执行”的保证。回滚同样需要
考虑配对版本，不能只回滚 Agent 后期待新版 AI 自动信任旧文本。

## 回归与验收

`AiAgentStreamProtocolHttpTests` 使用回环 HTTP 覆盖协议确认、显式错误、部分输出后失败、
未知事件、非 SSE、空 EOF、HTTP 拒绝及取消连接。`AiDialogueErrorHttpTest` 通过真实 Spring
HTTP、Controller、编排和 Client，验证错误事件到达调用方且没有成功持久化副作用。
`AiDialogueTraceTest` 验证同轮失败、租户与父 trace；既有身份和 HMAC 回归继续运行。

默认测试使用合成身份、模型／Agent 替身，不调用真实供应商。源码和隔离测试通过不代表
目标进程已经部署，也不替代 Gateway、真实失败流、日志与 trace 的运行态配套验收。

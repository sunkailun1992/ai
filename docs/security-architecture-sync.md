# 通用安全与私有存储

Agent 请求沿用服务间签名，绑定 HTTP 方法、原始路径、原始查询串与实际 JSON 字节；流式请求每次订阅生成新 nonce。服务凭据仅由受限配置注入。聊天/记忆要求 User access JWT 并经 User RPC 复核生命周期与租户授权，签名不能代替调用用户身份验证。

AI 1.6.9 已移除微信登录、opaque 会话和 owner 专属会话/展示消息，以及旧文件上传策略、完成登记、元数据、token 刷新管理接口。不存在 User subject 到旧 Long owner 的隐式映射，也未新增 User 直传管理。普通附件使用已可访问的 HTTP(S) URL，经 `MediaInputValidator` 校验；HTTP(S) Agent 路径检查不能代替完整媒体 URL / 网络出口 SSRF 防护。

既有私有文件保留 `/api/v1/files/{id}/view?token=...` 与 `/api/files` 别名，GET/HEAD 均先校验文件状态与展示 token。GET 才向 DB registry 请求短时签名，HEAD 返回真实 MIME。文件非空平台固定使用保存值，null 平台由 DB 的 `legacyNullPlatform` 固定映射，不随新上传默认值变动；禁用上传也不关闭旧读取。不回退 Nacos OSS 凭据。

存储 provider、凭据与业务策略由 AI DB/API 管理，复用 `com:utils:1.6.3` / X File Storage；凭据 AES-GCM 加密，独立 KEK 只由私有 OS 环境提供。存储控制面读写要求活跃平台超级管理员与 `user:auth:manage`，不向普通用户返回配置或密钥。模型部署、上游密钥、应用 virtual key 与额度以 LiteLLM 原生管理台为唯一源；AI 管理入口只投影真实目录和入口。

历史数据库初始化保留为迁移证据，删代码不会自动删表、记录或对象。运行库退役数据/表的处理必须独立备份与精确读回，不能混入普通 AI 记忆、审计、RAG 或通用文件。默认启动不导入演示种子。

验证执行 `./gradlew clean build --no-daemon`、严格凭据扫描与差异检查。真实 Controller 回环 HTTP 覆盖退役路由 404、通用文件 view 与 User 认证边界；MyBatis/H2 覆盖文件平台持久化、null 固定映射与审计归属。这些离线测试不替代实际 User/Nacos/对象存储/模型验收。Git、CI、制品与部署分别取证。

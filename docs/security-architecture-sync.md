# 通用安全与私有存储

Agent 请求可通过 `ai.agent-client.auth.enabled/client-id/client-secret` 启用服务间签名。签名绑定 HTTP 方法、原始路径、原始查询串与实际 JSON 字节；流式请求每次订阅生成新 nonce。密钥只能由 Nacos 或环境变量注入。此能力需与执行层和 user 统一认证配置成套启用。代码默认关闭以兼容既有内网调用；GitHub 独立环境通过公共配置显式启用，服务凭据缺失时拒绝启动。

私有文件上传继续使用 `/api/v1/files/upload-policy` → 对象存储 POST 表单 → `/complete`；展示仍需后端 token 校验。配置 `storage.yaml` 中 `dromara.x-file-storage.*` 后，统一复用 `com:utils:1.5.1` 的 X File Storage 适配器，支持 OSS / MinIO。`formFields` 是上传时应原样发送的完整签名表单，旧 OSS 字段保留；`storagePlatform` 需随完成登记请求回传。小程序客户端同步使用通用表单字段。

`ai.oss.base-dir/upload-expire-seconds/view-expire-seconds/max-upload-bytes` 继续控制业务目录、签名时效与大小。平台的 `base-path` 必须为空，避免与已包含业务根目录的 objectKey 重复拼接。

数据库升级追加可重入的 `db/ai-file-storage-platform.sql`；它检查当前 schema 的列状态，允许预建结构后由 DDL runner 再次执行。已执行数据库的历史初始化不回写；本轮仅清理个人新库初始化脚本的演示 INSERT。新文件持久化存储平台，展示按该平台签名；历史空平台记录始终走原 OSS，切换默认平台不搬迁历史文件。需读取旧文件时仍需保留旧 OSS 配置。独立新库先运行原初始化脚本，再运行追加脚本。

权限、所有者目录、MIME、大小、文件登记和展示 token 留在 `OssFileService`，公共存储层不决定业务授权。HTTP(S) Agent 路径校验不能代替完整的媒体 URL / 网络出口 SSRF 防护。

验证：`./gradlew test --no-daemon`、`./gradlew bootJar --no-daemon`、`bash scripts/check-secrets.sh`。这些测试使用合成身份与本地 HTTP 桩，不表示真实 Nacos / user RPC / OSS / MinIO 已验收。

个人空库初始化已移除历史演示角色种子。Java/classpath 角色种子只有在显式设置 `ai.bootstrap.seed-demo-content=true` 时才导入，默认不启用；普通服务启动不会重新生成业务模板。此处按照本轮个人隔离要求清理初始脚本的历史演示 INSERT，不对既有数据库执行删除或覆盖。

交付验证执行完整 `./gradlew clean build --rerun-tasks --no-daemon`、凭据扫描与差异检查。公共库固定为仓库声明的精确版本；GitHub CI 从相应公共库的 main 构建 Maven Local。Git 提交、远端 CI、制品发布与实际部署分别以各自回执为准。

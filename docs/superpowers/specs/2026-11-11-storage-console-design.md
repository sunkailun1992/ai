# 私有对象存储控制台设计

> 历史 1.6.7 设计/执行记录。1.6.9 已移除微信认证、owner 会话和上传管理入口；当前合同见 README 的退役章节，DB registry 与既有文件 token 展示保留。

状态：主会话已冻结核心方案并授权实施。本文不表示已发布配置、创建表、迁移凭据或验证真实存储。

## 目标与配置归属

将个人 AI 文件服务使用的 OSS、MinIO、腾讯 COS 连接与业务文件策略交给管理页维护，保持历史文件读取、所有者校验和展示 token 合同。继续使用 X File Storage，不增加第二套存储框架。

| 配置或能力 | 唯一管理来源 |
|---|---|
| Provider、bucket/region/endpoint、应用存储凭据、新上传默认平台、上传开关、签名 TTL、大小限制 | AI 存储控制台 API 与 PostgreSQL；凭据密文保存 |
| bucket 创建、ACL/CORS、云账号/IAM、原生生命周期与物理对象管理 | 对应云平台/MinIO 原生控制台；业务页不自动改桶策略 |
| LLM/embedding/rerank/OCR 等模型部署、上游凭据、虚拟密钥与额度 | LiteLLM 原生管理台；admin 只提供入口、真实能力目录、业务默认映射和接入状态 |
| DB、MQ、注册中心、环境、端口、可观测性与连接池 | 现有 Nacos/基础运行配置 |
| 存储凭据加密主 key、key-id、允许的私有存储 origin | 受限运行环境；主 key 不放 DB、页面或 Nacos 可编辑业务配置 |

本批只支持私有对象，不提供把文件设为公共读取的开关。普通登录用户不能读取存储配置；所有管理读写均要求已验证的活跃 `PLATFORM_SUPER_ADMIN` 且具有 `user:auth:manage`。复用 User 会话验证链，不相信前端角色或请求中的 tenant。

## 现有事实与缺口

- 当前只有 `ai` 的三个 profile 导入 `storage.yaml`，公开模板提供 `minio-private` 与 `dromara.x-file-storage.*`。`utils` 的 `ObjectStorageConfig` 使用 `@EnableFileStorage`，版本固定 2.3.0。
- `PrivateObjectStorageService` 已封装 POST 表单签名、存在性检查、短时 GET 和服务端上传；当前仅接受 OSS/MinIO。腾讯 COS 在 XFS 中已有平台实现，但本项目缺少 SDK 和适配分支。
- `ai_file_object` 保存 `storage_platform/object_key`、所有者、展示 token 哈希与治理字段。非空平台按该平台读取；空平台始终回到历史 OSS，不随默认平台变化。没有存储配置表、密钥加密、管理 API、操作账本或运行时 revision 状态。
- 当前上传策略多次读取默认平台；完成登记又与当前默认比较。动态切换必须固定本轮所选平台，避免策略来源与返回标识不一致。
- 文件业务入口仍有微信 opaque 身份与历史记录。存储控制台采用 User 管理员身份；本批不恢复小程序、不迁移文件所有者身份、不删除历史元数据。
- 现有 MinIO smoke 会创建测试桶；本批真实验收改为使用预先确认的专用桶和唯一探针前缀，不能直接把该旧测试当作无写入检查。

源码依据：`src/main/java/com/kellen/file/OssFileService.java`、`AiFileObjectEntity.java`、`FileObjectController.java`，以及同级 utils 的 `storage/PrivateObjectStorageService.java` 和公开 `ops/github-dev/nacos/DEFAULT_GROUP/storage.yaml`。未读取真实 Nacos 或存储凭据。

## 方案比较与推荐

1. 页面直接发布 Nacos：工作量小，但与用户选择相反，并继续混合运行基础设施和业务密钥；不采用。
2. DB 配置原地修改共享 XFS properties/list：实现较短，但并发签名、旧文件和客户端关闭容易错配；不采用。
3. **AI 持有配置/加密/权限/操作账本，每个配置 revision 使用独立的 XFS 原生客户端句柄；utils 仅补 COS。** 业务控制面有清晰归属，保留旧公开方法与平台绑定，推荐采用。

XFS 官方 `FileStorageServiceBuilder.create(properties).useDefault().build()` 可创建独立实例，其内仍使用原生平台、客户端工厂和文件处理器。每个实例只放一个平台，不在请求中修改全局 default/properties/list。管理层以 `platform -> handle` 映射选择客户端；请求持有 lease，凭据轮换构造新 handle 后原子发布，旧 handle 等在途操作结束再关闭。关闭失败仅记固定码，不传播 SDK 原始异常或 endpoint。

连接身份（provider、platform、bucket、region、endpoint）创建后不可变，移动位置通过新建配置再切默认完成；凭据可轮换。历史有引用的配置不得删除。`uploadEnabled=false` 只禁止新上传策略/新服务端上传，不隐式关闭旧文件读取；首轮不提供关闭历史读取的开关。已签出的 URL/上传策略在其 TTL 内仍可能有效，应用开关不等于撤销云端已签凭证。

## 公开参考与实际差异

- [XFS 2.3.0 原生 Builder](https://github.com/dromara/x-file-storage/blob/f7baa6e933398185096a5bff5e427593247b19d7/x-file-storage-core/src/main/java/org/dromara/x/file/storage/core/FileStorageServiceBuilder.java) 与 [Tencent COS 平台](https://github.com/dromara/x-file-storage/blob/f7baa6e933398185096a5bff5e427593247b19d7/x-file-storage-core/src/main/java/org/dromara/x/file/storage/core/platform/TencentCosFileStorage.java)：复用原生构造、上传、存在性检查和预签名读取。
- [RuoYi-Vue-Pro FileConfigService](https://github.com/YunaiV/ruoyi-vue-pro/blob/3e13d8ea901a5368a0d4824e3b55775928bd5760/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/service/file/FileConfigServiceImpl.java)、[客户端工厂](https://github.com/YunaiV/ruoyi-vue-pro/blob/3e13d8ea901a5368a0d4824e3b55775928bd5760/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/framework/file/core/client/FileClientFactoryImpl.java)、[文件 configId 绑定](https://github.com/YunaiV/ruoyi-vue-pro/blob/3e13d8ea901a5368a0d4824e3b55775928bd5760/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/dal/dataobject/file/FileDO.java)：借鉴配置 CRUD、默认选择和文件绑定配置。其 JSON 配置、原地 refresh、GET 测试并返回 URL 的做法不直接复制；本项目另做密文、lease、显式 POST 探针和安全摘要。
- [腾讯 COS POST Object 官方协议](https://cloud.tencent.com/document/product/436/14690) 与 [官方 COSSigner](https://github.com/tencentyun/cos-java-sdk-v5/blob/e79235712564681d117d1790323a8a152d08ae21/src/main/java/com/qcloud/cos/auth/COSSigner.java)：COS 表单签名与普通请求签名不同，使用原生 `buildPostObjectSignature`，保持精确 key/MIME/大小/到期约束与现有 `formFields` 合同。

仅查公开远端仓库，没有访问本机 Yudao 或第三方私有配置，也不复制第三方整块代码。

## REST 合同

新入口沿用 `ApiResponse<T>` 成功包装和 HTTP 状态。失败使用已有公共错误分类与固定安全提示；不返回 SDK message、原始 cause、签名 URL、objectKey 或凭据。操作资源的 `failureCode` 为业务固定枚举。

| 方法与路径 | 行为 |
|---|---|
| GET `/api/ai/storage-capabilities` | 返回 provider、字段适用性和策略单位/上下界；不表示已配置或健康 |
| GET `/api/ai/storage-configurations` | 分页、名称/provider/上传状态筛选；只返回元数据 |
| GET `/api/ai/storage-configurations/{id}` | 读取单条元数据，无任何凭据明文或回显占位符 |
| POST `/api/ai/storage-configurations` | 创建；业务 id/platform 由后端生成，不自动成为默认 |
| PUT `/api/ai/storage-configurations/{id}` | 更新名称、开关、凭据；连接身份字段禁止修改 |
| DELETE `/api/ai/storage-configurations/{id}` | 仅软删除未被引用且非默认的配置；不删除对象或 bucket |
| GET `/api/ai/storage-settings` | 读取默认平台、历史空平台映射和上传/读取策略 |
| PUT `/api/ai/storage-settings` | CAS 切换默认或更新策略；全局影响由前端具体确认 |
| POST `/api/ai/storage-configurations/{id}/probes` | 用户显式启动有界 READ_WRITE 探针，202 返回操作资源 |
| GET `/api/ai/storage-operations/{requestId}` | 查回该管理员的操作；只读，不重新执行探针或 CRUD |

所有 mutator 必填 UUID `requestId`，更新/删除/probe 填 `expectedVersion`，它严格对应 config 返回的 `version`；settings 写入填 `expectedSettingsVersion`，对应 settings 的 `version`。`configRevision` 是运行配置版本，区别于治理列 `version`；仅运行配置变化时增加。相同 requestId/主体/规范化请求返回原操作，不再执行；同 key 不同请求 409。操作账本先于任何外部写入持久化，不能以 HTTP 超时或 404 自动 POST 重发。

```text
StorageConfigurationVO {
  id: string, platform: string, name: string,
  provider: MINIO | ALIYUN_OSS | TENCENT_COS | S3_COMPATIBLE,
  endpoint: string, bucket: string, region: string|null,
  uploadEnabled: boolean, isDefault: boolean,
  version: number, configRevision: number, appliedRevision: number|null,
  runtimeStatus: UNCONFIGURED | APPLIED | APPLY_FAILED,
  credentialsConfigured: boolean, credentialUpdatedAt: ISO8601|null,
  referenceCount: number,
  lastProbe: {requestId, checkedRevision, finishedAt, status, cleanupStatus}|null
}

CreateStorageConfiguration {
  requestId, name, provider, endpoint, bucket, region?,
  uploadEnabled,
  credentialAction: REPLACE, accessKeyId, secretKey
}

UpdateStorageConfiguration {
  requestId, expectedVersion, name, uploadEnabled,
  credentialAction: KEEP | REPLACE, accessKeyId?, secretKey?
}
```

KEEP 必须完全省略两个凭据字段；REPLACE 必须同时提供非空二元凭据，COS 的 accessKeyId 映射原生 SecretId。空串、前端掩码或只填一项不能表示保留。DTO 不生成包含凭据的 `toString`；实体和错误响应不序列化密文。COS endpoint 按标准 region/bucket 派生并校验；首批不开放任意自定义域名。

```text
StorageSettingsVO {
  version, defaultPlatform: string|null, legacyNullPlatform: string|null,
  baseDir, uploadTtlSeconds, viewTtlSeconds, maxUploadBytes,
  privateOnly: true
}
StorageOperationVO {
  requestId, action, configurationId: string|null,
  status: PENDING | RUNNING | SUCCEEDED | FAILED | UNKNOWN,
  checkedRevision: number|null, startedAt, finishedAt,
  failureCode: string|null,
  steps: [{name: WRITE | EXISTS | READ | VERIFY_PRIVATE | CLEANUP,
           status: NOT_STARTED | RUNNING | SUCCEEDED | FAILED | UNKNOWN | SKIPPED,
           failureCode: string|null}],
  cleanupStatus: NOT_NEEDED | PENDING | SUCCEEDED | FAILED | UNKNOWN,
  result: StorageConfigurationVO | StorageSettingsVO | null
}
```

查到 UNKNOWN 返回 HTTP 200 的操作资源，表示结果未知；查不到 requestId 返回 404 失败响应，`data` 按现有 `NON_NULL` 规则省略。二者均不授权自动重新发送。UNKNOWN 仅 GET 查回，页面不提供重新 POST；服务器遇到重复 key 也只返回已有操作，不重新发外部写入。元数据保存成功但客户端装载失败，返回真实 `appliedRevision/runtimeStatus`，不能把保存或 enabled 当作连接健康。

TTL 单位秒，业务范围固定 60–3600，默认上传 60、读取 1800；默认单文件 52428800 字节。capabilities 返回这些边界；策略修改只影响新签发，不改变旧对象键。端点只允许 HTTP(S)、无 user-info/query/fragment；云平台使用 HTTPS 标准地址，MinIO 的私有 origin 必须命中服务端运行白名单，不能任意探测 localhost/元数据服务。

## DB 与密钥

AI 拥有三个新表：

1. `ai_storage_configuration`：稳定 id/platform、provider、不可变位置、名称、上传开关、credential ciphertext/nonce/key-id、config_revision、治理 version 与完整公共治理字段。platform 唯一。
2. `ai_storage_setting`：平台范围唯一行、默认 platform、固定历史空平台映射、业务路径/TTL/大小策略、治理 version。
3. `ai_storage_operation`：管理员+requestId 唯一、操作类型/目标、规范化请求 HMAC、状态/固定 failureCode、探针阶段/清理状态、受限结果引用和治理字段；不存请求原文或凭据。

全局配置 DAO 只对这些精确表采用明确的平台 scope 查询，不关闭全应用租户拦截器。文件元数据及业务租户隔离不变。治理 `version` 仅做乐观锁，业务使用 `config_revision`；新版本仅 DB 配置源；空 DB 返回 typed UNAVAILABLE，不保留长期 Nacos fallback。新增 PG 迁移文件和中文字段注释，H2 测试 schema 同步，不回改已执行历史 SQL。

凭据二元组使用 JDK AES-256-GCM，随机 nonce，AAD 绑定平台范围/id/provider/credentialRevision。主 key/key-id 从受限环境注入，独立于 JWT、数据库密码和 LiteLLM master key。DB 只存密文；未知 key-id、错误 key、损坏密文均失败关闭，不回退明文或旧 Nacos。主 key 的部署与轮换由运维控制，页面不能改；请求幂等 HMAC 与上传票据若使用派生 key，必须用不同的固定域分离。

`referenceCount` 统计所有未物理清理的历史文件引用（包括可恢复的软删除行），不能只数当前租户的活动记录。历史 null platform 通过固定 `legacyNullPlatform` 映射到经实际快照确认的旧存储配置，不批量改文件行、不移动对象。此映射一经绑定历史数据便不得随默认平台修改。

## 运行生效与旧文件

- 首次使用/管理只读查询按需从 DB 装载配置，连接身份不可变；每条 revision 生成独立 XFS properties/service/私有适配器，发布后按需使用 SDK。未配置不假装健康，文件操作明确不可用。
- 凭据轮换先事务保存；提交后后续请求按 DB revision 构建并发布新 handle，in-flight lease 保留旧客户端直至结束。装载失败与持久保存分开报告，不进行外部写入。
- 文件读取始终通过记录 platform 或固定历史 null 映射；默认平台仅选择新上传。关闭上传不关闭读取；首轮不提供关闭历史读取，不删除引用或文件。
- 上传策略只采样一次所选平台，响应携带该稳定 platform；完成登记不能把原平台改成后来的默认平台。在途上传保留既有安全拒绝并明确要求重新取策略；首轮不增加上传票据/新的上传入口，不能无校验接受任意 platform。
- SDK 异常在新 facade 边界去 cause/正文/地址，只返回固定失败。应用自己只记录 provider/config id/revision、动作、结果码与 trace，不记录 endpoint、签名 URL、对象键或凭据。

## 有界探针

探针仅写服务端生成的专属前缀和合成小对象，先记 operation intent，再 WRITE → EXISTS → READ/内容 SHA → 无签名访问拒绝 → 精确 CLEANUP。禁止创建 bucket、改 ACL/CORS、读取用户文件或批量删除。只有完整步骤及清理成功才标 SUCCEEDED，lastProbe 绑定 checkedRevision；旧 revision 的 PASS 不代表新凭据可用。

外部写入结果未知立即记 UNKNOWN，不自动 PUT/POST 重发；仅允许对账本内精确探针 key 做后续只读核对，不能把 404 直接当作允许重放。清理结果分别报告，未知/失败保留安全的操作证据供受控处理。API 不返回 objectKey、签名 URL、SDK body 或 header。

## 兼容迁移与回滚

1. 主会话先私有备份现有存储配置与只读元数据计数，识别 MinIO stable platform 和 null 平台 OSS 的真实引用；发现无法定位的旧引用就停止切流。
2. 新版本增量创建表并以 typed unconfigured 启动；主会话通过真实平台管理 create/settings API 导入，由服务端同一 AES 实现保存经过确认的配置及固定 legacy 映射，不用 Python 加密或直接写业务 SQL。现有 platform 字符串、bucket/endpoint、objectKey 保持不变，实际 secret 不进入 chat/Git。
3. 新 AI 配置 API 只以 DB 为唯一来源；确认 DB 配置可解密及映射完整后，移除三个 profile 的 storage.yaml import 与旧服务配置中的存储凭据来源。其它环境/DB/MQ 等导入不变；Nacos 旧项仅供明确旧版回滚，不宣称仍导入时已卸载密钥。
4. 受控重启确定 immutable Jar，真实验证旧平台与 null 平台样本、新探针和默认切换。历史用户文件只读，探针只清理自己的对象；不做全库文件搬迁。
5. 回滚必须恢复匹配的旧制品/私有配置；若已产生新 provider 的文件引用，旧版不能读取该 provider 时禁止简单回退。DB 配置与匹配主 key 保留，不删除表/对象或覆盖历史连接身份。

## 跨仓与版本

- utils：计划 `1.6.2 -> 1.6.3`，仅在 `PrivateObjectStorageService` 增补 COS 的 configured/required/basePath/POST 签名分支及必要 SDK，S3-compatible 优先复用已依赖的 MinIO S3 wire；其它未具备 SDK 的 provider 返回 UNAVAILABLE。原公开方法和构造器保持；腾讯 SDK 固定官方已发布 `com.qcloud:cos_api:5.6.283`。完整测试、Maven Local、AI 消费者验证后，main 确定提交必须真实发布远端 Maven。
- AI：当前 `1.6.6`，同批计划 `1.6.7`；存储配置/加密/registry/API/operations/迁移与文件桥接都留在本服务。与同批模型目录变更共用一次版本提升，由主会话协调。
- rpc-api：本批没有第二个存储 provider/consumer，保持 `1.0.8`，不增加远程分发凭据的契约。
- 其它八个 Java 服务继续精确使用旧 utils，不强制全批升级。当前 utils 工作区有其它会话草案，SDK 分支/工作区归属先由主会话协调，不 checkout 覆盖。

## 验证与完成标准

- AES-GCM round-trip、随机 nonce、错误 key/AAD/密文拒绝；API/日志/审计序列化不得包含合成密钥或密文。
- 真 Spring HTTP + DB：无身份/普通用户/非平台管理员拒绝；global scope、版本冲突、同 requestId 重放、同 key 改 payload、UNKNOWN 查回、引用/default 禁删及 metadata-only KEEP 不改密文。
- 真 SDK + 本机 HTTP fixture：OSS/MinIO/COS POST policy 约束、存在性检查、签名 GET；错误/超时原因脱敏。COS 是真实 SDK wire，不能只测 instanceof 或拼装 DTO。
- registry 并发：轮换时老请求仍用旧 handle、新请求用新 revision，排空后才 close；迟到旧 revision 的探针/装载结果不能覆盖新状态。保存失败和装载失败分开断言。
- 文件兼容：真实 Repository round-trip 后仍按原 platform 读取，null 映射固定；默认切换、上传关闭分别验证，不能串改所有权/租户/展示 token。
- 真实存储由主会话单独执行：每种已配置 provider 用预先批准桶/唯一前缀做一次小对象读写、匿名读取拒绝、owner/MIME/大小拒绝与精确清理；没有 COS/OSS 真实测试凭据时只报告 wire 已测，不能宣称真实云已通过。
- 构建使用独立输出目录和 immutable 候选，不能覆盖运行 JVM 的 build/libs；Git、Maven、CI、DB/Nacos 迁移、运行健康与真实文件读写分别给回执。

## 本批实现裁定

- 当前实现不增加周期性后台对账或长期迁移 fallback；DB revision 在文件/管理请求中按需装载。此裁定避免无授权定时网络副作用，代价是保存后尚未有新请求时 appliedRevision 仍可为 null。
- CRUD/config/settings 的意图与结果在同一数据库事务保存；只有显式探针跨事务触及外部存储，先落持久 PENDING 再排入有界单 worker。重启/提交未知不自动恢复写入。
- GET 操作恢复只以原 operationJson + RUNNING/PENDING 条件 CAS 标记 UNKNOWN，失败时重新读回终态，防止旧读覆盖 worker 完成记录。
- 本次运行前数据库全表 ai_file_object=0（主会话只读确认）；旧引用/null 映射仅以离线 fixture 证明，真实验收不宣称已有历史文件恢复。

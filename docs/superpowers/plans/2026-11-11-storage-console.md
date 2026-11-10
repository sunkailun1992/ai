# 私有存储控制台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 用 AI 管理 API/加密 DB 管理私有 OSS、COS、MinIO/S3-compatible，旧文件仍按原平台读取。

**Architecture:** AI 持有全局管理员门禁、密文配置、设置与操作账本；每个配置 revision 构建独立 XFS 原生句柄并以 lease 管理生命周期。utils 只补 COS，公开方法保持；rpc-api 不变。

**Tech Stack:** Java 17、Spring Boot 4、MyBatis-Plus/PostgreSQL、JDK AES-GCM、X File Storage 2.3.0、COS SDK 5.6.283。

**Spec:** `docs/superpowers/specs/2026-11-11-storage-console-design.md`，以主会话后续冻结修订为准。

## Global Constraints

- AI 同批 1.6.7，utils 1.6.3，rpc-api 1.0.8；其它消费者不强制升级。
- TTL 60–3600 秒，默认 60/1800，默认/上限 52428800 字节；首轮不暴露关闭历史读取。
- 仅平台超级管理员且具有 user:auth:manage；主 key 只从独立受限环境注入。
- 配置位置创建后固定，凭据 KEEP/REPLACE；引用/default 禁删；未知结果仅 GET 查回。
- 不访问真实存储/Nacos/私有 key，不覆盖运行 Jar；实际导入、移除 source import、env 和云端验收由主会话负责。
- utils 使用当前获授权分支，未知未追踪草案不 stage/commit；AI build/docs 由本 worker 独占。

## Review Focus

- 掩码/空值误写覆盖凭据：KEEP 带字段和 REPLACE 缺一项必须拒绝。
- 默认切换与旧引用：原 platform 和 null 历史映射不可跟随新默认。
- 凭据轮换/迟到探针：老 handle 在途保留；旧 checkedRevision 不能污染新状态。
- transport unknown：操作账本已存在时不能重新发外部写入，404 也不自动重放。
- 异常与日志：SDK cause、签名地址、合成凭据均不得进入 API/安全日志。

## Task 1：AI-only 共享管理门禁

**Files:** `controlplane/AiControlPlaneAdministrator.java`；测试 `controlplane/AiControlPlaneAdministratorTest.java`。

**Interface:** `public AiControlPlaneAdministrator(AiRequestIdentityResolver identities)`；`public AiRequestIdentity require(String authorization)`。

- [x] RED：有效普通用户、非平台管理员、无 manage 权限、JWT/已验身份不一致均拒绝；唯一合法组合通过。
- [x] 实现与现有 model-selection 相同的活跃 User 会话、JWT user/tenant 一致性和双门禁。
- [x] 运行定向测试，告知主会话模型只读 backend 可依赖该稳定 signature；原 model-selection 先保持。

## Task 2：utils COS 最小适配与发布

**Files:** utils `PrivateObjectStorageService.java`、`build.gradle`、存储 tests/README。

**Interface:** 原方法不变；新增 COS 类型分支后 `configured/POST policy/exists/GET/putObject` 同合同可用。

- [x] RED：真实 COSSigner policy key/MIME/大小/TTL、SDK HTTP fixture 存在性与 GET、固定失败文本，旧 OSS/MinIO 回归。
- [x] 加必要 SDK 5.6.283，补 COS 分支，base-path 继续必须为空。
- [x] 全 utils 测试、secret/diff、Maven Local；AI 编译验证后正常 main 集成并远端发布 1.6.3/精确 CI，不 stage 网关草案。

## Task 3：密文与持久化合同

**Files:** `storageconfig/{StorageCredentialCipher,StorageConfigurationEntity,StorageSettingsEntity,StorageOperationEntity,*Repository}.java`；新增 PG migration 与 H2 schema；`PostgresqlDdl.java`。

**Interface:** Cipher 仅在内部返回二元凭据；三个 Repository 保持固定 PLATFORM scope；`version` 乐观锁、`configRevision` 运行版本。

- [x] RED：AES-GCM 随机 nonce、AAD/错误 key/密文拒绝，序列化和 toString 无 secret。
- [x] 实现 DB-owned 配置/设置/操作账本，完整治理字段与中文注释；不改历史 SQL、现有 file 表或真实 DB。
- [x] H2 真 Repository round-trip、CAS、同 requestId 同请求复用/异请求拒绝；无主 key/空 DB typed unavailable。

## Task 4：稳定 DTO、权限与配置管理 API

**Files:** `storageconfig/{Storage*Request,Storage*Response,StorageConfigurationService,StorageConfigurationController,StorageOperationController}.java`；契约文档。

**Interface:** Spec 中 REST 路径；成功 ApiResponse，失败额外固定 failureCode；mutator 返回 OperationVO，expectedVersion 对应 version。

- [x] RED：真 HTTP 401/403、位置更新拒绝、KEEP/REPLACE、引用/default 禁删、settings CAS、UNKNOWN 与 NOT_FOUND 区别。
- [x] CRUD、settings 和 requestId 账本原子提交；初次 settings/version0 可设固定 baseDir/legacyNullPlatform，后续只读；create-only legacyPlatform 供主会话导入。
- [x] 将最终 JSON/enum 对齐前端，验证未知字段和原始异常不外泄。

## Task 5：DB 动态 registry 与文件桥接

**Files:** `storageconfig/{StorageRuntimeRegistry,StorageRuntimeHandle,ManagedPrivateStorage}.java`；`file/OssFileService.java` 的最小桥接；必要构造测试。

**Interface:** registry 按稳定 platform/revision 获取 AutoCloseable lease；每 handle 使用独立 XFS Builder/service/PrivateObjectStorageService。

- [x] RED：轮换期间旧 lease 不关闭、新请求用新 revision，失败装载保留旧 handle、迟到 revision 不覆盖。
- [x] 支持三云与既有 MinIO S3-compatible；其余 provider capabilities=UNAVAILABLE；无新依赖替换底层。
- [x] 上传选择一次；老文件始终用原 platform/null 固定映射；uploadEnabled 仅阻止新策略/新 put，先保留在途默认变化的安全拒绝。
- [x] 真 DB 元数据读取/展示 token/原平台与 null 平台回归；不增加小程序或新的上传业务入口。

## Task 6：有界真实 SDK 探针

**Files:** `storageconfig/{StorageProbeService,StorageProbeRunner}.java`；loopback HTTP fixtures。

**Interface:** POST probe=202 操作；固定 owned key、WRITE/EXISTS/READ/VERIFY_PRIVATE/CLEANUP；lastProbe.checkedRevision。

- [x] RED：一次合成对象读写+finally 删除/缺失读回；网络 unknown 不再发写入、cleanup 单独状态、任意 key 拒绝。
- [x] 探针先落 intent、从 revision lease 操作、只记录真实步骤；不创建桶/改 ACL、不返回 key/URL/body。
- [x] 本机真实 SDK wire 验证四 provider，签名目标在首次 I/O 前断言回环地址；实际云验收待主会话独立执行。

## Task 7：整体交付

- [x] 全 AI 测试、utils 必需回归、前端消费合同、secret/diff、独立 review。
- [ ] 计划/README/版本已同步；逐仓 fresh fetch，保留并发改动，main/CI 的最终状态记录在构建目录脱敏回执，不以计划勾选替代实际结果。
- [x] 构建到独立目标、完整 SHA/Manifest、0400 immutable Jar；运行 libs 前后哈希不变。
- [x] 分别报告源码/Maven/CI/候选制品与未执行的真实 DB/Nacos/存储验收；主会话负责私有导入与实际切流。

## 验证回读

- AI isolated `build` 成功，162 项总计：160 通过、2 跳过，0 失败/错误；其中 storageconfig 29、模型只读目录 28、管理 guard 8。外部真实模型/存储不在此离线计数中。
- utils 1.6.3 已交付 main `2a692c7149a23f79c9344352b2317f8ea29a8a0b`；本地 root 138 通过/11 跳过，observability 46 通过，精确 CI 成功；远端 POM/JAR/sources/javadoc SHA 与本机构建一致，metadata 含该版本。
- 四 provider 原生 SDK/XFS 回环 fixture 完整执行一次 PUT/HEAD/签名 GET/匿名拒绝/DELETE/缺失读回；不等同真实云验收。
- 独立审查两项已修：cleanup FAILED 仍保留对象时禁配置变更，以及两个 Service/同 DB/同 requestId 并发必须只排一次探针；均有 RED→GREEN。
- 另捕获 stale RUNNING GET 覆盖 worker 清理终态，用数据库 operationJson 条件 CAS 保护，确定性回归通过。
- 旧文件 H2/真实 Mapper+AES+registry fixture 保留原 platform/null 固定映射；主会话真实库原文件数为 0，不能宣称旧文件实际恢复。

## 实施裁定

- 本批复用已有 checkout/短分支和主会话冻结协议，不创建额外 worktree；其他会话只新增独立模型 package，统一同一候选交付。
- 配置按首次文件/管理请求装载 DB revision，不新增周期性后台网络任务。代价是未发生后续请求时 appliedRevision 可以为空；页面明确保存与生效的区别。
- CRUD 意图与安全结果同事务；probe 先持久化意图再一次执行，不采用自动恢复写入。清理未确认必须受控处理，不能靠重试或删账本解除。
- 按主会话后续明确要求维护 ai-schema.sql 纯 CREATE/COMMENT 全量基线；只有增量脚本保存 v0 设置种子。全局禁 DML 的基础引导门禁保持。

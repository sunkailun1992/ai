# 测试分层规范

## 定位

`ai` 是主业务编排服务，负责入口、鉴权、会话、审计、文件 token 和持久化。核心业务测试必须覆盖真实 Spring Boot 业务链路，不能只靠纯对象断言。

## 主流分层

- 单元测试：DTO、枚举、状态转换、小工具、小函数，使用 JUnit 5 + AssertJ。
- Slice 测试：局部 Controller/Repository 可以使用 `@WebMvcTest`、`@MybatisTest`，只作为补充。
- Service 集成测试：核心业务用 `@SpringBootTest` 注入真实 Spring Bean，验证事务、AOP、Repository、会话状态。
- HTTP 集成测试：核心对外接口用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`，通过 `TestRestTemplate` 或 `WebTestClient` 发真实 HTTP 请求。
- 跨服务测试：ai-agent、Dubbo、Gateway、Nacos、OSS 等链路放到独立 integration profile 或测试环境。

## assertThat 规则

`assertThat` 是断言工具，可以继续使用。判断测试是否充分，看被断言的数据是否来自真实业务链路，而不是看是否用了 `assertThat`。

## 业务测试要求

- Controller 测试优先走真实 HTTP 请求，验证路由、参数校验、权限、统一响应、SSE/流式边界和异常处理。
- Service 测试必须验证真实 Bean、真实 Repository、真实事务和真实用户归属校验。
- 不允许只用 mock 或纯 Java 对象测试替代核心业务集成测试。

## 外部模型和附件

- 默认测试不调用真实模型供应商。
- 模型 Provider、OSS、远端附件 URL 默认使用 stub、fake、mock server 或测试 profile。
- 需要真实模型或真实 OSS 的测试必须单独标记为 integration，不进入普通 CI 默认路径。

## 测试数据

- 使用 `test` profile 和测试库。
- 测试数据可以落库，但必须可重复、可识别、可清理。
- 测试前置数据必须由 SQL、fixture、builder 或测试接口显式准备，不依赖本机临时数据刚好存在。

## 测试稳定性

- 测试结果只能由输入决定，不依赖真实时间、线程调度、连接复用、端口、执行顺序或本机遗留状态。需要时间的逻辑注入 `Clock`；等待异步结果用有总时限的条件等待，不用固定 `Thread.sleep` 赌时序。
- 本地 HTTP 桩服务每个测试独占随机端口，并在 `@AfterEach` 停止。同一测试对同一被测实例请求多次时，桩响应设 `Connection: close`，不依赖 JDK `HttpServer` 的 keep-alive 连接回收时序（见 `AiAgentSignedClientHttpTests`、`ModelControlPlaneServiceTest`）。更复杂的桩可评估 MockWebServer 或 WireMock，新增依赖按依赖变更处理。
- Reactor Netty 全局连接池与事件循环、静态单例、系统属性等进程级共享状态，不能成为测试之间的隐含依赖；用到时写明隔离方式，不依赖测试类或测试方法的执行顺序。
- 新增或修改涉及网络、并发、超时、SSE 的测试，合并前本机连续三轮运行该测试类全部通过：`./gradlew test --rerun --tests '<测试类全名>'`。
- 偶发失败按缺陷处理：先查明原因再修，不用自动重试、放宽断言、`@Disabled` 或删除测试换取通过。确需临时禁用时，在注解里写明原因与恢复条件，并在交付说明中列出。
- 本机 macOS 通过不代表 CI 的 Linux 通过；只在一个平台验证过的结论要写明平台。

## 必跑命令

与 CI（`.github/workflows/ci.yml`）同序执行：

```bash
./gradlew -q compileJava
./gradlew test
bash scripts/check-secrets.sh
```

需要排除旧构建产物干扰时改用 `./gradlew clean test`。推送 `main` 后按 `AI_ENGINEERING_GUARDRAILS.md` 的 Definition of Done 核对精确提交的 CI。

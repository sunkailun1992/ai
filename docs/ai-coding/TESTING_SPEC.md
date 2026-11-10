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

## 必跑命令

```bash
./gradlew clean test
```

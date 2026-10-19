# AI-LOG-B 交付记录

日期：2026-10-19，Asia/Shanghai。状态：开发及本会话定向验证完成，待 PM 组合验收。

- 分支：`feature/AI-LOG-B-telemetry`，基线：已发布 `master 577b318`。
- 原 `feature/AI-PROMPT-B-runtime` 分支和文件保留；创建新分支前工作树干净。
- 已读协调任务合同、专项研究报告与一期边界；Agent 二期仍暂停，不引入 SDK。
- 所有权：`AiCallTelemetry`、`AiProviderRuntime`、`OpenRouterTransport`、`AiConfiguredProviderRuntime` 及对应测试。
- 首合同：`286abffd56f7c0de3ca33401bada2a40c2145806`。不可变 telemetry 全部合同访问器；旧七参 `ExecutionOutcome` 保留，缺失遥测为 null，两个 toString 固定脱敏。
- 采集源码及测试：`721a949a59548ce84dfb4e9711b13d42ea0fb597`。在首合同之后顺序挑入，无其他未提交源码依赖。

## 实际修改

| 文件 | 修改 |
|---|---|
| `ai/contract/AiCallTelemetry.java` | 可空测量、精确金额、有界脱敏三文本及采集质量；非流输出时间不可伪造 |
| `ai/contract/AiProviderRuntime.java` | Outcome 追加 telemetry、保留旧构造与脱敏 toString |
| `ai/config/OpenRouterTransport.java` | 接收边界挂接、错误响应有界读取、发送起点/真实首字节、现有 provider ID 防凭据回显、OpenAI 流式尾 usage 请求选项 |
| `ai/config/AiResponseTelemetry.java` | 单次请求内采集、未知扩展/有序 SSE、递归及跨帧脱敏、精度/大小/异常边界 |
| `ai/config/AiCallTelemetryTransportTest.java` | 15 项实际传输与解析专项，HTTP 受控，非厂商验收 |

`AiConfiguredProviderRuntime` 已只读核查，保留其认证、实际 Key 校验、紧急停用以及原样返回 transport outcome；本轮无需修改。未修改 C 归属的存储、API、executor、journal、连接测试或目录交互源码，未并改 required 测试清单。

## 采集口径与防护

- 遥测从同一次 `sendAsync` 之前的单调时钟起算。响应头、InputStream 首个正字节读取、推理/正文等首输出、首正文与末输出分别记录。非流 `firstOutputMs/firstTextMs/lastOutputMs/firstOutputKind` 为 null；头时间不冒充首字节。
- 空 role、心跳和独立 usage 不算首输出；重复尾帧覆盖/合并字段，不累加 Token 子集或重复用量。cache miss 不冒充 cache write；未报告的计数和费用保持 null。
- 保留收到的未知 JSON 字段、SSE 事件顺序/时间/协议字段及扩展；头、usage 和 body 的 UTF-8 归档字节合计不超过 256 KiB。原始 receivedBytes 独立统计实际读取字节，JSON/SSE 元数据包装可使归档与收到大小不同。
- 先递归清理凭据字段、响应 Cookie/Set-Cookie、实际 Key 及编码形式，再处理归档截断。跨读取块、跨 SSE 帧、推理与正文间的 Key 回显均有验证；部分尾巴不归档 Key 前缀。
- `returnedModel/finishReason/ExecutionOutcome.providerRequestId` 同样核对实际 Key 及编码回显；固定 kind/reason/status 不接收上游任意描述。toString 不输出正文、密钥或隐藏提示词。采集对象不接收出站 messages。
- HTTP 错误、坏 JSON、模型不匹配、取消、超时及部分输出也有采集质量；保留原路由/URL/接收和输出边界、预算及输出后禁止重试规则。
- 日志归档异常为 `FAILED/ARCHIVE_FAILED`，不改成功模型结果、不二次调用模型。费用仅实报 BigDecimal；precision/scale/整数位有界，极端指数保留有界 raw usage，但规范金额为 null 且标识 `UNSUPPORTED_COST`。不猜货币、不将不同货币合并，不将负值改成估价。

OpenAI 流式 usage 尾帧需显式选项，非流不加；OpenRouter 当前自动返回末帧 usage，不追加已弃用选项。依据：[OpenAI Chat API 参考](https://platform.openai.com/docs/api-reference/chat/create)、[OpenRouter Usage Accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting)。本轮未为查 usage 额外发送模型或账单请求。

## 实际检查及证据

- 首合同：Java 17 standalone javac、64 MiB，4 个自包含合同源，exit 0。
- 实现：Java 17 main/test javac、单 128 MiB，均 exit 0。
- `AiCallTelemetryTransportTest`：最终 15/15 成功、0 跳过、0 失败，1322 ms。
- 直接相关回归：`OpenRouterTransportTest` 9 + `OpenRouterRetryBoundaryTest` 8 + `OpenRouterJsonObjectTest` 7，24/24 成功、0 跳过、0 失败，1708 ms。
- 专项最初 13 项通过；补充两项后，一次测试夹具未注册 Instant 模块导致自身序列化失败，修复标准模块后最终 15 项全通过。中间失败不算通过证据。
- 证据：工作区 `.qa-local/20261018-ai-practical/b/ai-log-main.log`、`ai-log-tests-compile.log`、`ai-log-junit.log`、`ai-log-regression.log`。保留旧日期 QA 目录仅复用现有缓存/启动器，不表示本轮发生于旧日期。
- 新 required 类名及最低 15 项已报 PM/C 统一登记。B 检查窗口已释放，无 B JVM、服务或数据库进程留存。

## 待验收与未验证

未运行完整 Maven、C 持久化/权限 API 组合验证、真实数据库或真实厂商调用；受控 HTTP 不冒充真实上游证据。不推上游、不合主干，未引入 Agent/Pi/Java SDK。PM 按既有 Gitflow 统一组合、验收与发布。

## 追加验收修复：无响应失败的安全分类

状态：本地实现与限定检查完成，待 PM 合并及正式 gate。此前 721a949 的 15+24 项结果仍是原提交证据，不代替本补丁检查。

- 只在既有 `telemetry.captureReason` 中追加固定原因；未加共享 DTO 或 HTTP 字段。
- 原因白名单为 `HTTP_TIMEOUT / CONNECT_TIMEOUT / TLS_ERROR / CONNECT_ERROR / DNS_ERROR / IO_ERROR / CANCELLED / INTERRUPTED / OTHER_ERROR`，按实际阶段前缀 `NO_RESPONSE_` 或 `NO_UPSTREAM_`。仅原本缺少响应的情况使用该分类；HTTP 响应、部分、截断及归档错误的原原因优先。
- 最多检查 6 个 cause，并以对象身份防循环；不保留 Throwable、异常 message、stack、动态类名、URL 或出站内容。取消及超时的业务判断、错误码、重试、预算和取消流程均未改。
- 实际修改：`AiResponseTelemetry.failure()` 固定映射，`OpenRouterTransport` catch 中挂接，以及新增 `AiCallTelemetryFailureReasonTest`。
- 限定 Java 17 单 128 MiB main/test javac 均 exit 0；新增 4/4 项成功，0 跳过、0 失败，1863 ms。覆盖 future 多层包装的 HttpTimeout/ConnectTimeout/TLS/Connect/DNS/IO/未知异常、消息含 Key/私有 URL 不泄露、原 `NETWORK_ERROR` 与 retryable 不变、取消、cause 循环/深度、HTTP/部分/截断原因优先。
- 证据：同 QA 目录 `ai-log-reason-main.log`、`ai-log-reason-tests-compile.log`、`ai-log-reason-junit.log`。
- 未重跑旧大套或完整 Maven；未发送外部请求、未重启服务。该固定 5 秒异常尚未因此被确认原因。窗口已释放，无 B JVM；新类 4 项请 PM/C 统一登记。

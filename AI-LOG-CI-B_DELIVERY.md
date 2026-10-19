# AI-LOG-CI-B 发布门槛修复

日期：2026-10-19，Asia/Shanghai。状态：本地定向验证完成，待 PM 审查及发布 CI。

分支 `fix/AI-LOG-ci-wait`，自 `origin/dev bf7a9fc` 创建；创建前工作树干净，旧分支保留。未使用 reset/clean，未推送、合并或调用模型。

## 原因与实际修复

发布 CI 的失败发生于 `AiSettingsKeyRotationTest.active()` 的原 `active=true` 断言。源审发现配置测试 worker 把 `test.status=COMPLETED` 与清除 `configuration.running_test_id` 分成两次自动提交。另一条完成回调可在两步之间读取成功证据，然后被仍存在的运行标记拒绝启用；异常处理把启用意图永久写成 `BLOCKED`。worker 后续回调不会再处理该意图。

- `AiConfigurationTests.run()`：将终态写入和运行标记清理放进同一个既有 `TransactionTemplate`，提交之后再通知完成观察者。
- `AiSettingsKeyRotationTest.finish()`：等待真实运行终态与启用意图终态，不再由测试轮询人工调用完成处理；不以反复等待 `active=true` 掩盖失败。
- 同类新增确定性交错回归：在真实 JDBC 已写终态、尚未清标记处用 latch 暂停 worker；另一连接调用完成处理。原代码误写 `BLOCKED`，修复代码在事务提交前仍看到 `RUNNING/PENDING`，放行后由实际观察者启用为 `APPLIED/active=true`。

保留所有既有预期、拒绝与状态断言；没有跳过测试、降低门槛、只增加 sleep 或修改 SSL/网络及模型业务策略。

## 实际检查

- Java 17 单 128 MiB main/test 定向 javac：exit 0。
- 修复版本：该类 18/18 成功，0 跳过、0 失败，3148 ms。
- 原始 `bf7a9fc` 生产类编译到独立临时目录，运行新增单回归：1/1 预期失败，明确 `expected PENDING but was BLOCKED`，1482 ms。原工作树文件未回退。
- 首次私有 JUnit runner 缺少 classpath 未启动测试，修复启动器路径后才取得上述实际测试结果，不把启动失败算通过。
- 证据目录：工作区 `.qa-local/20261019-ai-log/b-ciwait/` 的 `main.log`、`tests-compile.log`、`junit.log`、`before-main.log`、`before-junit.log`。

未运行全量 Maven、远端 CI、真实 MySQL 或外部请求。原 CI 的具体线程时序未在运行时追踪，但新增回归已确定性复现源代码竞态及永久阻断结果。重型组合及 MySQL 发布门槛由 PM/远端 CI 验收；本地检查窗口已释放，无 B JVM 留存。

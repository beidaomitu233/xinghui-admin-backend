# AI-SERVER-REDIS-EMPTY

2026-10-20；独立分支 `fix/AI-SERVER-REDIS-EMPTY`，基线 `origin/dev` = `6d4eb1bc5780454b4fad6ed4ba4ab5a3e51bded3`。本次是应用修复，不修改固定工具 `283697842447d5557331f08995336c1580ab6bf9`、publisher、服务器 Redis 认证或任何服务器文件。

## 原因与修复

PM 在首发验证发现：原生 Redis 未设密码，而后端 `REDIS_PASSWORD=` 使 RedissonAutoConfigurationV2 启动发送空密码 AUTH，收到 Redis 的未配置密码错误，启动失败。

本项目锁定 starter **3.40.2**。已核对该版本官方源码与本地实际依赖 JAR 的 `javap`：V2 继承 `RedissonAutoConfiguration`，将最终 RedisProperties/ConnectionDetails 密码直接赋给 SingleServerConfig，随后执行 customizers，最后创建客户端；连接层在密码非 null 时发送 AUTH，空字符串也会触发。因此应在客户端创建前，将最终单机配置中的空字符串转为 null。

修改现有 `common-redis/RedissonConfig` customizer：保留 Lua 脚本缓存，只在当前 standalone 配置的最终密码恰为 `""` 时设置 null。不 trim 密码，不用 hasText 判断，空格本身也可为有效非空密码；非空密码、ACL用户名、host/port/database保持原值。没有密码时仅省略客户端 AUTH，不改变服务端安全设置。

`application.yml` 的密码占位符改为 `${REDIS_PASSWORD:}`，使变量未设置时也走同一无密码路径。集群/sentinel拓扑不在本次修改或验证范围内。

官方版本依据：[starter 3.40.2 源码](https://github.com/redisson/redisson/blob/redisson-3.40.2/redisson-spring-boot-starter/src/main/java/org/redisson/spring/starter/RedissonAutoConfiguration.java)。连接层行为同时从本地 `redisson-3.40.2.jar` 的 `BaseConnectionHandler` 字节码核实，未假设其他版本行为。

## 回归与门禁

新增必需测试 `RedissonPasswordStartupTest`。用真正 starter V2 与 Redisson 3.40.2 客户端，在随机回环端口连接最小 RESP 协议 fixture：它记录实际命令；无密码时若收到 AUTH 会返回错误；有密码时必须正确认证才接受读取。没有安装/连接真实 Redis，不访问服务器或使用环境凭据，不写业务数据。载入项目实际 application.yml 并去除测试进程继承的系统环境/属性来源，验证占位符缺省行为。

7项用例：

- 密码未设置与显式空串：客户端启动、DBSIZE读取成功，不发送AUTH；保持DB14选择和脚本缓存。
- 普通非空密码、带前后空格的密码、单个空格密码：AUTH字节内容原样保留并读取成功。
- ACL用户名+密码：保留双参数AUTH认证。
- 错误非空密码：启动失败，不能通过清除密码来绕过认证。

本地定向7项已通过，无失败/跳过。临时恢复origin/dev旧Customizer后，同一缺省/空串握手回归2项均失败，根因均为实际发送AUTH后收到fixture的未配置密码错误；修复源码在finally中逐字节恢复。新增类已列入 `scripts/quality/required-tests.txt`，将参加原必需打包/报告门禁；原 MySQL5.7/8.0 服务门禁继续由现有CI执行，不削减任何已有测试。

本地既有门禁已通过：AI inventory自测及分类（requiredTotal=110）、全模块`clean package`运行必需列表、583项测试无失败/跳过、可执行Boot JAR校验和报告门禁负例自测均通过。使用已有本地Maven缓存、Java17和受限内存，未启动真实Redis/数据库。MySQL服务矩阵等待PR中的原CI执行，不能把本地结果写成已通过服务验收。

PR/CI精确提交与运行收据在任务最终回执报告，避免为CI收据单独新增提交。原服务器首发的BLOCKED/IN_PROGRESS、重试与镜像更新由PM统一处理，本分支不删除标记、不自动回滚或部署，Actions开关保持false，等待PM验收合入。

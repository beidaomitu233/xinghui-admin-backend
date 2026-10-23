# USE-05 交付（B，2026-10-23）

## 提交与范围

- 两端分支：`feature/USE-05-notification-cleanup`；隔离树 `worktrees/USE-05/{backend,frontend}`。
- 后端基线 `6d7435966ff1043677135d5773eb601c34eb91b4`；实现提交 `2ed849e5f3f5c00d1682eb3b5a28628d284d8dc3`。
- 前端基线 `2e3ba8e5473e2e7fa14f786487a761196961f42e`；提交 `12e7b9c43b06d4ddc25dde6434238cf52cd57b46`。
- 本地交付，未推送、合并、发布或测试生产。PM负责后续验收与Gitflow。

后端删除 `ContentNewsController`、仅含NEWS的 `ContentService`、`NotificationTemplateAdminController`、`NotificationTemplateService/Rules` 及退役功能专属测试。没有可复用的新闻/模板实体或Mapper遗留。`NotificationEventPublisher/Service`改为内部业务内容快照；`NotificationContentRules`校验内容/目标，Delivery保留认领/租约/重试/接收人/租户/去重逻辑。通知管理和真实业务事件发布仍没有匿名HTTP入口。

`SqlDatasetRegistry`删除资讯注册及权限映射，`DatabaseBindingReader`删除资讯权限映射；两处通用表读取显式拒绝sys_news，旧授权也不能重新读取。保留的PlatformVisibility历史权限分类仅为防御限制，不提供资讯业务API。同步质量required-tests清单及旧测试清理/验证脚本；旧Flyway文件未修改。

前端删除news与notification-template页面/API、孤立content-validation及专属测试；静态demo移除对应菜单/权限/数据/读写实现并验证旧端点拒绝。顶部 `personal-notification-entry.vue` 承接通知中心抽屉，新 `layouts/notification-center.vue`复用完整列表、分页、筛选、详情、单/批已读、安全目标。业务页打开/关闭中心留原路由与查询。成功目标跳转关闭中心。

旧 `/notifications?notificationId=...` 为隐藏兼容入口，打开同一顶部中心；移除routes-helper强制个人菜单。无其他业务页的登录账号默认可回退兼容入口。`retired-tabs.ts`在权限初始化处仅移除新闻/模板/个人收件箱三类旧session标签，重建标签缓存；保留其他固定标签、定制标题、顺序及个人偏好，没有清空localStorage。保留PM通知store的$reset和未读请求隔离。

内容列表/详情加局部请求代次，晚到旧结果/错误/loading不覆盖新选择；卸载/关闭失效旧请求。顶部预览按捕获token隔离换账号响应。真实页面发现Long总数序列化为字符串，已在 `personal-notification.ts`转换安全计数给分页；ID保留number|string，长ID不转浮点，深链接受正Long字符串。

公开文档同步API目录、通知事件快照教程、模块扩展/动作示例（改用字典或注入业务动作）、能力/入口说明及退役迁移说明。news文档只保留退役说明，移除功能导航及路径映射；历史版本章节保留历史事实。未改A低代码源码或引入Pi/Agent。

## V330与升级语义

新文件 `V20261023_330__retire_news_and_notification_templates.sql`：

1. 联表检查旧模板状态；PENDING、PROCESSING、FAILED/DELIVERY_ERROR中，关联禁用或缺失模板的记录置 `FAILED/TEMPLATE_RETIRED`，清claim_token/lease_until/next_attempt_at。该错误不能进入临时错误补偿。
2. 合法启用模板已生成的快照保留原状态，继续投递。DELIVERED及历史inbox内容、已读时间不改。
3. 删除fk_notification_template与event_request.template_id，再删模板表；保留event_request和inbox及它们之间的历史外键。
4. 删除资讯/模板角色菜单关联及专属菜单权限，删sys_news；SysNotice及公共用户/文件表不删。

**组合发布顺序**：A的V320与本V330必须在同一批从线上41条升级，不先单发V330再加低版本V320。测试最终断言已改为Flyway无pending/validate及V330成功，支持组合43条；历史41条目标和已跑41→42证据未改。按PM要求不为该断言调整重建/清空已验证库。

## 已实际验证

### 后端与数据库

- 单JVM384 MiB/CPU2，forkCount0，无-T并行，定向5类共39项，0失败/0跳过：NotificationContentRules(3)、EventService(3)、DeliveryService(5)、InboxService(4)、DatabaseBindings(24)。
- 初次单模块离线因snapshot依赖缺失失败；PM批准串行-am后编译。forkCount0初次触发DatabaseBindingsTest相对文件路径，改为classpath读原迁移资源后39项通过；不跳过原断言。
- PM分配的独立空库use05_20261023：全部42条迁移、Flyway validate，随后真实MySQL快照发布/幂等/实际收件人/禁用与缺失用户/本人他人跨租户/单批已读及新服务读取通过，1项0跳过。
- 另一独立空库use05_upgrade_20261023：先41条历史迁移，再建立6条旧事件和已读历史inbox（正常、禁用、缺失模板、处理中、可补偿失败、已投递）；追加V330到42并validate。4条不合法旧工作退役且不能deliver/requeue，6条旧请求/历史正文和read_time保留，合法旧快照继续投递；新快照/幂等/隔离/已读也通过，1项0跳过。两库未清空，升级证据保留。

精确命令（Maven3.9.11/JDK17）：

```text
MAVEN_OPTS=-Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC
mvn -o -B -ntp -pl system-admin -am -Dtest=NotificationContentRulesTest,NotificationEventServiceTest,NotificationDeliveryServiceTest,NotificationInboxServiceTest,DatabaseBindingsTest -DforkCount=0 -Dsurefire.failIfNoSpecifiedTests=false test
```

库验证用相同资源参数与 `-Dtest=NotificationEventIsolatedDbTest`，环境由PM Set-QaEnvironment.ps1选择USE-05、USE-05-UPGRADE；USE05_ISOLATED_DB=true、USE05_MIGRATION_MODE=fresh/upgrade。failIfNoSpecifiedTests只允许无匹配依赖模块，目标报告实际跑了用例。

### 前端与真实HTTP/页面

- 定向测试累计74项：初始8文件66项，补无菜单fallback/分页2项、请求竞态/业务上下文3项、旧缓存标签1项、API字符串计数2项。涉及修改后的受影响子集已分别复跑通过，不声称一次全量74重跑。
- 最新原样式配置Stylelint通过；改动文件Prettier通过；ESLint 0错误（12项既有测试stub/多组件warning）。完整公开文档54页检查通过。未在本机跑全量FE typecheck/生产build，交PM正式组合门禁。
- 实际候选后端poller消费16条隔离QA快照（B15、PM1）；另为无菜单账号准备1条null目标通知。不是生产消息，也不是只mock响应。
- B实际Chrome登录后从工作台顶部打开预览/查看全部，真实第二页与详情可读，单条与批量PUT成功，关闭详情保留工作台路由，打开获准/system/user目标成功并关闭中心。
- 旧/notifications?notificationId=4打开本人详情，关闭清理兼容query后刷新仍打开中心。390×844、100%比例实际截图，document宽390无页面横向溢出，0pageerror。人工查看桌面/窄屏暗色截图；未将其扩为全页面/双主题视觉验收。
- 真实HTTP用独立PM账号：本人仅1条；他人详情/含他人ID批量均拒绝（现有业务错误code201），批量拒绝未部分更新本人行；本人已读后重新GET有read_time。退休news/templates端点无业务handler，走现有通用错误响应（HTTP200/code201，不宣称404）；菜单树无两模块组件。
- 页面初测发现total字符串导致ElPagination警告和分页缺失，修API后控件及真实第二页通过，补API测试验证字符串总数/精确ID及坏计数拒绝。旧警告仍保留在早期console日志，不计作修后成功证据。
- 测试客户端初次错误使用/api直连前缀/Bearer导致401，按当前Vite代理与LoginFilter合同修正，未改产品会话；最终HTTP验证通过。一次原生input.check超时改为实际可见label点击后批量成功，无force点击。中文SQL夹具初次客户端字符集1366失败，改UTF8/ASCII明确QA文案成功，不改产品数据编码。

## 证据与PM接手

后端 `.qa-local/use05/`：unit-reactor.log、fresh-db.log、upgrade-db.log、package.log、package-source.txt、instance.json、runtime.out.log/runtime.err.log、http-result.json。前端同目录：unit.log、race-tests.log、tab-tests.log、count-tests.log、eslint-final.log、docs-check.log、ui-flow.log（中途选择失败保留）、ui-continue.log、deep-flow.log。

截图（未进Git）：前端 `output/playwright/use05-center-desktop-dark.png`、`use05-center-narrow-dark.png`，以及早期checkpoint。不保存auth-state/trace/JWT。

- BE PID40092 / 17865，Xmx256m/CPU2；FE Vite PID35716 /15165。Chrome use05已关闭，供PM唯一窗口接手；不停止别的进程。
- 包 `system-admin/target/system-admin.jar`，SHA256 `3D6485F8E12F09AD18A84C25A48D2B3D1DB38C76FC1AEEC0BAC62ACA6F582679`，来自本任务dirty源码，打包前清单已记录。打包仅skipTests，与已实际跑测试分离；包后仅测试计数断言/文档脚本变化，主代码/迁移内容未变，不冒称从clean提交重新构建。
- PM账号文件：后端 `.qa-local/use05/login-pm-private.json`；无菜单独立907：login-nomenu-private.json。均仅本机受限文件，不在聊天输出凭据，B用login-b-private.json避免互踢。
- 无菜单907正常启用，无角色/业务菜单授权，1条真实快照已交应用投递；PM实际登录验收待完成，B没有仅凭单测宣称该账号UI通过。

## 尚待PM门禁

PM独立实际页面/无菜单账号验收、A V320+B V330组合43条新/旧库及最终远端全lint/type/build/CI；正式合入与发布。源码与数据已可复核，不把本地定向测试或分离打包当作全门禁。不触发真实外部供应商消息，不更改生产数据。

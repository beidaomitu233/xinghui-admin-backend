# UI-NATIVE-03 · C 本人任务板

## 领取与范围

- 领取：2026-10-24（Asia/Shanghai）。状态：进行中，未验收。
- 分支：两端 `feature/UI-NATIVE-03`；沿用 `worktrees/USE-03/{backend,frontend}`。
- BE 起点 `94a01795113b1d0f43684271d809528f1da9f486`；FE 起点 `565bb61dfc2707cd9e5bde40880f293ad4ba8cad`。实际 fetch 已核对，与生产 ad59704 / 7efdf27 内容 diff 为空。
- 只处理 AI 全部页面、业务配置、微信管理兼容入口及 AI 列表排序。认证、适配层、低代码、全局样式、packages、依赖 lock、发布均不属于 C。
- 当前任务全文、中央任务板/通信 10-24 当前节及本人旧业务合同已核对；不恢复暂停 Agent 二期，不重跑历史门禁。

## 组件来源与逐页映射（实施计划，不代表已完成）

原生来源：`@vben/common-ui` 的 Page / useVbenModal / useVbenDrawer / Loading；`#/adapter/form` 的 useVbenForm；`#/adapter/vxe-table` 的 useVbenVxeGrid。其次使用已有 Element Plus。参考根 `.qa-local/20261024-vben-reuse/reference/vxe-{basic,remote}.vue`，不引入 antdv-next 或新包装。

以下路径以 `apps/web-ele/src/views/system/` 为根。每行在实际实现和验收后单独更新，不以源码替换计为通过。

| 页面/业务组件 | 现状与目标来源 | 保留业务专用交互的原因 | 状态 |
|---|---|---|---|
| ai-config/index.vue | 原生 Page title/description/extra，移除 AdminPageHeader | 模型/场景路由两工作区和离开保护保留 | 进行中：已改待查 |
| ai-config/ModelHubLayout.vue | 原生 Page，Element Tabs/Input，原生内容布局 | 左侧模型/供应商选择是主从配置，不强制改分页列表 | 进行中：已改待查 |
| ai-config/ModelHubWorkspace.vue | Vben Modal/Loading、Element Buttons | 多供应商/主备/测试状态及未知提交恢复保留 | 进行中：已改待查 |
| ai-config/ModelHubEditor.vue | Vben Modal/Form | 模型元数据编辑及离开保护 | 进行中：已改待查 |
| ai-config/ModelHubModel.vue | Element Form/Descriptions、Vxe 有界路由表 | 路由优先级是持久业务顺序，与表头排序分离 | 进行中：已改待查 |
| ai-config/ModelHubOverview.vue | Vxe 有界汇总表、Element 统计/空态 | 汇总与逐模型状态导航保留 | 进行中：已改待查 |
| ai-config/ModelHubSupplier.vue | Vxe 目录表/分页、Element Descriptions/Alert | 模型扫描受控状态与明确未验证状态保留 | 进行中：已改待查 |
| ai-config/ChannelWorkspace.vue | Vben Drawer/Form/Loading | 多 Key、多模型绑定、内存新 Key 与保存/测试语义保留 | 进行中：已改待查 |
| ai-config/SceneRoutingWorkspace.vue | Vben Form、Element Collapse/Select/ButtonGroup | 场景 AUTO/主备路由为业务操作，不将临时排序写回优先级 | 进行中：已改待查 |
| ai-config/SceneRouteEditor.vue | Vben Form/Modal、Element Select | 多渠道逐 Key 模型关联保留 | 进行中：已改待查 |
| ai-config/KeySettingsPanel.vue | Vxe 远程列表和本地明细表、Vben Form/Drawer | 历史配置/版本/测试兼容权限保留 | 进行中：已改待查 |
| ai-config/SimpleSettingsPanel.vue | 复用迁移后的 ChannelWorkspace 原生 Grid/Drawer | 兼容入口不另造容器 | 已审阅保留，待回归 |
| ai-model/index.vue | 原生 Page、Vben Form/VxeGrid/Modal | 旧模型凭据空值保持、清除、测试权限和 CAS 保留 | 进行中：已改待查 |
| ai-prompt/index.vue | 原生 Page、Vben Form/VxeGrid/Modal | 旧场景草稿/快照/变量预览兼容保留，不加 Key 配置 | 进行中：已改待查 |
| ai-application/index.vue | 复用迁移后的提示词原生 Page/业务子页 | 兼容入口保留路由行为 | 已审阅保留，待回归 |
| ai-application/KeyPolicyEditor.vue | Vben Form、Element Select/Collapse | 原 Key 策略编辑合同保留 | 进行中：已改待查 |
| ai-template/index.vue | 原生 Page title/description/extra | 提示词配置独立，新增/刷新权限保持 | 进行中：已改待查 |
| ai-template/LegacyWorkspace.vue | Vben Form/VxeGrid/Modal | 老模板、数据资源、不可变历史与审批仍可访问 | 进行中：已改待查 |
| ai-template/workspace/SceneWorkspace.vue | Vben Modal/Loading、Element 布局 | 提示词主从编辑、脏状态、自动保存/CAS/离开保护保留 | 进行中：已改待查 |
| ai-template/workspace/SceneSidebar.vue | Element Input/Buttons/Scrollbar | 场景选择是编辑导航，不强制变成管理表格 | 进行中：已改待查 |
| ai-template/workspace/PromptEditor.vue | Vben Form、Element 控件与业务编辑器 | 业务值与有色变量标记不可退化为通用文本框 | 进行中：已改待查 |
| ai-template/workspace/PromptTextEditor.vue | 保留专用 textarea/镜像：双语有色变量、光标与撤销重做，通用输入框无法等价表达 | 中英双语变量、颜色、光标、撤销/重做为已确认能力 | 已审阅保留，待回归 |
| ai-template/workspace/DataReferenceTable.vue | Vxe 有界引用表、Vben 弹层 | 数据说明与变量依赖顺序保留 | 进行中：已改待查 |
| ai-template/workspace/DatabaseBindingEditor.vue | Vben Modal/Form | 数据库只读字段/主键/关系编辑保留，不改后端数据策略 | 进行中：已改待查 |
| ai-template/workspace/DatabaseRecordPicker.vue | 保留现有 Element Remote Select/Popover/Input：授权字段类型筛选与精确记录引用；limit<=200 的候选选择，不是全量分页管理表 | 精确主键与当前记录选择保留 | 已审阅保留，待回归 |
| ai-template/workspace/SceneDataSelector.vue | Vben Form、Element Select/Descriptions | 数据来源选择与授权错误保留 | 进行中：已改待查 |
| ai-template/workspace/SceneFormPicker.vue | Vxe 或 Element 有界选择列表 | 精确表单 ID 不转 number 丢失精度 | 进行中：已改待查 |
| ai-template/workspace/SceneSaveStatus.vue | 保留现有 Element Button 和 aria-live 保存状态：反映自动保存/CAS业务阶段，不是手制加载动画 | 自动保存失败/冲突状态是真实业务状态 | 已审阅保留，待回归 |
| ai-template/workspace/SceneTrialPanel.vue | Vben Modal/Loading、Element 控件 | 试运行/停止/请求状态及失败输入保留，不调用真实模型验 UI | 进行中：已改待查 |
| ai-template/workspace/SqlReferenceAssistant.vue | Vben Modal/Form/Loading | SQL 编辑与显式只读试查、保存编译依赖保留 | 进行中：已改待查 |
| ai-request/index.vue | 原生 Page title/description | 对话与详细调用日志标签保留 | 进行中：已改待查 |
| ai-request/MonitorWorkspace.vue | Element Tabs，Vben Loading | 原权限与标签切换清理语义保留 | 进行中：已改待查 |
| ai-request/CallLogPanel.vue | Vben Form/VxeGrid/Drawer | 权限清空/回包代次/统计与脱敏导出保留 | 进行中：已改待查 |
| ai-request/CallLogDetail.vue | Element Descriptions/Collapse | 按权限展示上游响应与未知 Token/费用，不伪造值 | 进行中：已改待查 |
| ai-request/RequestLogPanel.vue | Vben Form/VxeGrid/Modal | 历史请求/阶段/尝试兼容及只读权限保留 | 进行中：已改待查 |
| ai-request/conversations/ConversationBoard.vue | Vben Form/Drawer/Loading、Element 导航 | 连续会话选择是会话体验，不强制改管理表格 | 进行中：已改待查 |
| ai-request/conversations/ConversationWorkspace.vue | 保留现有 Element Select/Pagination 与业务发送/恢复/停止协调，子页已用原生 Form/Loading；对话不强制表格化 | 发送/停止/恢复、流式上下文和审计范围保留 | 已审阅保留，待回归 |
| ai-request/conversations/ConversationTimeline.vue | 保留业务时间线，成熟 Collapse/Loading | 真实消息与请求关联应以对话呈现 | 进行中：已改待查 |
| business-config/index.vue | 原生 Page、Vben 动态 Form、Element Collapse/Alert | 同页五分区独立保存；服务商/密钥身份切换、加密与失败保留保持 | 进行中：已改待查 |
| wechat/index.vue | 原生 Loading 与现有管理兼容重定向 | 保留业务配置分区定位，不改认证微信入口 | 进行中：已改待查 |

## 排序与验收安排

- 真实分页列表在本人后端资源加可选 orderBy/order 白名单，SQL 排序后分页并追加稳定 ID；原过滤/权限/租户/软删除不变。仅真实可排序列展示排序能力。
- B 独占适配层；已发出 Grid 远程排序/代理与 Form/Modal/Drawer 准确合同请求。新依赖/lock 无计划变更。
- 定向轻量检查先行；完整 typecheck/test/build、真实浏览器、后端运行等候 PM 排程与新隔离 QA。不得清旧库或乱序补 V320。
- 尚未运行本轮构建、交互或数据库验收，不沿用旧 USE-03 34/10 项作为本轮成功记录。
- 所有未完成页面与未覆盖交互在交付时保留，不以测试绿代替组件来源/实际体验验收。

## 2026-10-24 实施进度（未验收）

- 已改入口 Page、ModelHub Layout/Tabs；模型、旧场景、调用日志、请求日志、渠道已保存目录、KeySettings 兼容配置和 Legacy 模板/资源接 Vxe；有界汇总、路由、引用、阶段/尝试、历史/证据接 Vxe。
- 主要编辑/测试/SQL/数据绑定工作区弹层已直接用 useVbenModal/useVbenDrawer；旧模型/凭据、旧场景/预览、模型元数据、业务配置动态分区已接 useVbenForm。复杂多 Key/引用数组仍保留既有 Element Plus 控件，继续检查字段与父级离开保护。
- 只执行了修改文件 Prettier/ESLint；尚未进行本轮 typecheck、Maven、构建、真实浏览器或新隔离库操作。模型 27 行跨三页与稳定同值/非法列定向测试已写，未运行。
- B 已核对本仓 reload 会 clearAll；查询使用 setLatestSubmissionValues → setCurrentPage(1) → query。重置清序；toolbar refresh 已按 B 真实浏览器版本勘误显式配置 `{code: query}` 保页保序（默认 true 实际 reload 清序）。正在按 B b5bf968 的原生页码/排序/提交值接续模式处理加载中的切换。
- QA 使用 PM 新 helper UI-NATIVE-03：127.0.0.1:13323/ui_native_03_20261024、Redis 16383 DB12、BE17873/FE15173。重型窗口仍由 B 使用，C 未启动重型进程。

### 已写后端排序扩展（待编译、集成及权限验收）

| 资源 | 允许 orderBy | 原默认 | 稳定尾列 |
| --- | --- | --- | --- |
| models | providerCode, modelCode, timeoutSeconds, enabled, createTime, updateTime | model_id DESC | model_id |
| prompt-scenes | appKey, sceneKey, enabled, draftRevision, publishedRevision, updateTime | s.scene_id DESC | s.scene_id |
| configurations / settings | name, providerCode, environment, status, revision, updateTime | config_id DESC | config_id |
| model-hub/models | name（固定 JSON 路径）, slug, updateTime | model_id ASC | model_id |
| scenes | name, updatedAt, enabled, kind | update_time DESC, scene_key ASC | scene_key |
| conversations | title, updatedAt, createdAt | c.update_time DESC, c.conversation_id DESC | c.conversation_id |
| templates / data-resources | name, revision, enabled, create_time, update_time | id DESC | id |
| templates/legacy-scenes | app_key, scene_key, published_revision | scene_id DESC | scene_id |
| requests | requestId, status, stage, attemptCount, inputTokens, outputTokens, createdAt | r.request_id DESC | r.request_id |
| call-logs | startedAt, entryType, status, inputTokens, outputTokens, elapsedMs, firstOutputMs | c.started_at DESC, c.call_id DESC | c.call_id |
| stored model catalog | code, name, contextLength, maxCompletionTokens（完整有界目录比较后分页） | 原 code 顺序 | code |

方向仅 asc/desc；未知字段/方向拒绝。旧 service overload 保留。原权限/租户/软删除/CAS/runtime/只读授权不改，统计与导出仍按原合同。

## 阶段检查与具体未验证项

- 第一阶段完整 vue-tsc（Node 单进程 2GiB）已通过，日志根 QA `c-typecheck.log`。本轮首次错误只来自 C 的 proxy 回调隐式 any、排序字面量和 nullable 展示，均已修复。
- 使用真实 Vben Form/Grid 与 Element Plus 的定向 mounted 测试：业务配置 2 项、旧模型 4 项、调用日志 10 项通过（16 项，真实组件）；已发现并修复挂载前 grid={} 方法保护、默认表单值不误清结果、同请求筛选异步状态同步。没有删除这些旧业务断言。
- Maven AI 模型/日志排序及原方法权限六类 32 项通过（0 失败/0 跳过）；新增排序参数使 3 份权限测试调用签名需增加 null/null，原禁止租户/逐操作权限与 verifyNoInteractions 断言全部保留。
- 慢请求指标已拆为总耗时/首输出两排序列，固定 metadata JSON 路径先 NULLIF(JSON null)，再 DECIMAL 数值排序。首输出种类与 unknown 呈现保留，费用不提供跨币种排序。
- 新增 `AiCallLogMysqlSortIT` 为显式原生库门禁，普通 *Test 不执行。非 CI 只允许 DB_URL 为 127.0.0.1:13323/ui_native_03_20261024；CI=true 时只允许 127.0.0.1:3306/xinghui_oss04_ci；两者都要求 UI_NATIVE_MYSQL_SORT=1，并核对 SELECT DATABASE()。连接使用既有生产一致 Druid stat + MySQL Wall（不放宽）；只写本次随机 provider/逐 ID 夹具，finally 精确清理。尚未执行，不把 H2 当作该 JSON 表达式的 MySQL 证据。
- 尚未执行：该候选正式构建、真实新库正常迁移及 MySQL sort IT、浏览器 1440×900/1366×768/手机/明暗、逐范围 CRUD/慢请求列头/快速切换与权限闭环。其余 AI 旧 mounted 测试仍需按实际组件初始化与 Portal 查询适配，完整 test/lint/build 门禁尚未通过，未宣称覆盖已验收。
- 复杂 Key/SQL/引用数组和对话控件继续用成熟 Element Plus 及既有业务编辑器；按 PM 明确要求不为追求 useVbenForm 字样机械改写有色提示词、SQL、对话和已确认只读行为。

- 同参数慢列表期间写成功刷新：5 个有写动作的 Grid 在业务 load 入口递增单个 refreshRevision，proxy 返回前纳入对照接续；循环不自增，无新队列。读日志不机械扩展。定向真实组件用例已通过：慢列表期间创建成功，最终第三次列表查询显示新模型，异步表单双击只产生一次写入。

- 32 项定向 Maven 通过后，新增 IT 的 CI 分支与生产一致 Druid stat/MySQL Wall 连接也已 test-compile 通过；尚未连接新库执行 IT。

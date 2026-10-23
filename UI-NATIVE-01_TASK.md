# UI-NATIVE-01 本人任务板与交付记录

## 领取

- 2026-10-24，原B，任务进行中；中央板由PM维护。
- 两端分支feature/UI-NATIVE-01，复用原B worktrees/USE-05/backend、frontend；旧USE05功能分支与证据保留。
- BE基线94a01795113b1d0f43684271d809528f1da9f486；FE基线565bb61dfc2707cd9e5bde40880f293ad4ba8cad；fetch核对与当时master/main内容相同。
- 已读UI-VBEN_NATIVE_REUSE_TASK全文、中央本轮任务/通信、Gitflow及本人相关原业务合同。沿用真实权限/租户/数据；不恢复退役模块，不修改A/C独占文件及公共packages。

| 阶段 | 当前状态 | 验收 |
|---|---|---|
| 适配层准确合同及依赖沟通 | 已向PM发送当前Grid/Form用法，Modal/Drawer继续核对 | 尚未运行 |
| 用户原生Page/Form/Grid/Modal参考闭环 | 实施中，保留导入导出/角色/重置密码/启用等事件 | 未验收 |
| 对应系统/调度/日志服务端白名单排序 | 实施中，先核对现有orderBy/order | 未验收 |
| 本人其它系统与运维页面迁移 | 待按用户参考闭环覆盖 | 未验收 |
| bootstrap/loading/认证/profile/about/dashboard | 待覆盖 | 未验收 |
| 定向测试、排序跨页实库与页面 | QA及重型/唯一浏览器由PM排程 | 未验收 |
| 完整门禁与交付 | 待实现后排程 | 未验收 |

## 组件原则和通信

直接复用#/adapter/form、#/adapter/vxe-table及@vben/common-ui Page、Modal/Drawer/Loading；不新建表格/分页/页头二次壳。AdminPageHeader暂不删除，待三方引用归零由PM统一删除。

2026-10-24：已通知PM转A/C，本仓VxeTableGridOptions是bare gridOptions，VxeGridProps是外层选项；原生proxy查询第二参数接最近提交表单，records接口局部适配items/安全total；原生toolbar/pager负责交互。暂无breaking适配修改。

## 2026-10-24 实施记录（未验收）

- 新 QA：通过根目录 `.qa-local/20261024-vben-reuse/Set-QaEnvironment.ps1` 的 `Set-VbenUiQaEnvironment -Task UI-NATIVE-01` 注入，MySQL 127.0.0.1:13323 本人新空 schema、Redis 16383 DB10、BE17871 / FE15171；不记录密码、不复用旧库。候选应正常执行43条迁移，未启动应用。
- 实际改页：`system/user/index.vue`；新增业务弹窗 `system/user/user-modal.vue`。实际 imports：`@vben/common-ui` 的 Page/useVbenModal，`#/adapter/vxe-table` 的 useVbenVxeGrid，`#/adapter/form` 的 useVbenForm/z。查询使用 Grid.formOptions，导入结果也使用 VxeGrid。删除该页自制页头及独立Element表格/分页/编辑表单。
- 保留导入模板、逐行导入结果与错误行报告、筛选导出、详情/新增/编辑/角色/状态、删除、一次性密码及未保存确认。使用原生Modal加载/提交锁，接口失败保留表单；最新请求结果优先，ID保留字符串。
- 用户BE：DTO白名单username/nickname/mobile/email/status/createTime/updateTime；列表与导出共用排序，分页前处理，ID升序打破相同值。
- 小范围ESLint（user页面、modal、user API及测试）0错误。首轮vue-tsc仅6处本次隐式any，已修后复查；没有依赖错误。不将静态检查当作UI验收。
- PM批准独占检查窗口：单任务vue-tsc上限2GiB，然后单JVM Maven用户排序测试上限384m/CPU2，严格串行。无生产构建、常驻Java或浏览器。
- 仍缺：角色/菜单/字典/配置/任务/任务日志/操作日志全部原生迁移及排序；bootstrap/loading/认证/个人中心/关于/工作台核对；实库跨页排序、CRUD权限租户失败场景、实际分辨率及主题验收；全量门禁。

## 用户样板阶段门禁

- 应用vue-tsc最终通过（stage-final日志）；当前类型检查覆盖用户样板、bootstrap原生loading、个人信息Descriptions、Dashboard原生Page。
- FE定向：Grid5/API9/个人信息3共17通过（vitest-stage.log），角色权限边界另3通过（vitest-role-boundary.log）；总20项，无失败。无role:list仍可详情/普通编辑，角色保持原ID；可选角色请求失败不阻断编辑。
- BE定向：Ordering12、ImportExport4、RoleValidation1共17通过；maven-user-ordering-agent.log。无fork单JVM需MAVEN_OPTS显式BOM代理1.14.19；首轮attach失败中止，后续成功不掩盖该过程。
- 当前Vxe源码协议：reload会clearAll清排序，tableLoading时query/reload被忽略；用户查询覆盖原生Form提交为setCurrentPage(1)+query，reset才clearSort/恢复默认，原生toolbar refresh保页保排序。query返回前核对原生当前页/排序/最近提交表单并接续请求，防止忙碌期间新操作丢失；该风险已做行为回归。
- PM批准独占样板打包及QA窗口；冻结本阶段实现，先本地commit再串行BE clean package、FE正式build-quality；候选精确SHA与哈希、43迁移及浏览器验收待记录。未推送、未全量完成。

## 暂停交接（2026-10-24，最新状态优先于上文）

状态：**暂停**。PM 转达用户停止其它开发并交接的要求；不再推进页面、测试、构建或浏览器。本次只保存已存在的本人改动，不代表完整交付或可发布。

### 分支、提交与恢复点

- 两端均 feature/UI-NATIVE-01；原 B 目录 worktrees/USE-05/backend、frontend。
- FE 最终暂停提交：1f5ae12eb23838697b488974361f3aec9f5ca690。此 WIP 包含下列第二阶段源码，未做本阶段完整类型检查、测试或生产构建。
- BE 最终暂停提交：本节所在提交（以 git rev-parse HEAD 获取精确 SHA）；包含角色白名单排序及省略 dataScope 时保留既有值。未为本阶段新增/运行角色测试，未编译此阶段。
- 已验证用户样板恢复点：BE e4de798bf5c3c7c30e39f8f95c328f74739540b6；FE b4d0fa33f380ed282ee4b545e6bf399d9d4b91cd（前序 b5bf968 的 refresh 缺陷由 b4 修复）。新暂停提交不得混同该候选的验证结果。

### 已完成及做到位置

| 范围 | 当前代码 | 实际验证 |
|---|---|---|
| 用户样板/BE用户排序 | 已完成阶段候选，保留导入导出、CRUD及权限边界 | 样板类型检查、FE20测试、BE17测试、正式构建、实库及浏览器检查通过，见下文 |
| bootstrap loading、个人信息、dashboard | 已提交原生 Loading/Descriptions/Page | 样板类型检查/构建覆盖；不等同所有页面全面交互验收 |
| adapter/vxe-table | 新 createVbenGridQuery 读取原生页/排序/最近提交表单/resourceKey，忙碌中接续最新查询；默认 refreshOptions.code=query | 当前暂停版未运行回归，尚需生命周期及并发测试 |
| 用户第二阶段 | 采用上述 helper；工具栏帮助入 Popover；移动端操作列不固定；详情 Descriptions；可选字段修正规则及加载禁用 | 源码已保存，未重新编译/浏览器验收 |
| 角色 | 原生 Page/Grid/Form/Modal；仅真实字段；可选菜单目录失败时保留原菜单 IDs；BE 全结果排序并 roleId ASC 稳定次序；省略 dataScope 时保留既有值 | 源码未编译/测试；不能声称旧实现已证明清空数据库 dataScope |
| 菜单 | 原生树 Grid/Form/Modal；全量树同级排序，不打平；过滤保留祖先；移除不存在字段编辑 | 源码未编译/测试 |
| 字典 | 原生双 Grid、业务 Form/Modal；全量本地排序；Long ID 保留；不可编辑 code/value 不进入更新 payload | 源码未编译/测试 |
| 配置 | 原生 Grid/Form/Modal；五项受管目录全量本地排序，无分页 | 源码未编译/测试；仍需 helper/身份请求隔离 |
| 任务/任务日志/操作日志 | 只完成原接口/权限/实体合同阅读，未改页、未改对应 BE | 未完成 |
| 关于/登录 | 登录已原生 AuthenticationLogin，保留真实能力；About 自制附加区尚未迁移 | 待接手复核 |

### 交接源码范围

FE：adapter/vxe-table.ts；api/system/{dict,menu,role}.ts；views/system/{config,dict,menu,role,user} 页面；新增 dict/dictionary-modal.vue、dictionary-order.ts，menu/menu-modal.vue、menu-order.ts，role/role-modal.vue。此前用户 API/测试、bootstrap、profile、dashboard 位于样板提交。
BE：domain/dto/SysRoleQueryDTO.java、service/impl/SysRoleServiceImpl.java、本任务记录；此前用户 DTO/service/OrderingTest 在 e4de798。
不涉及 A/C 独占源码、公共 packages、依赖锁、CI 或门禁；不 push、PR、合并、部署。

### 下一位接手的明确未完成项

1. helper read() 增加原生 Grid 已卸载判定；覆盖旧成功/失败切换最新查询、最新错误原样抛出、scope dispose、resourceKey 变化、并发共用 Promise、无 Form、同参写入后 refresh revision。
2. 用户/角色删除末页时，在 fetch 中修正原生当前页并由 helper 接续；queryError 清旧可见数据；身份/权限变化清表、重置 Form/默认排序，处理 KeepAlive。
3. 所有编辑弹窗 save 捕获 request epoch/token/业务目标，防止旧保存完成后影响新弹窗或新身份；当前 prepare 多处已有 token/request 防护，但 save 尚未补齐。
4. Config 接入无 Form helper、JWT/canQuery/revision，补 identity watch、打开/保存 epoch、busy 禁用；字典核对 Grid mount 时机。
5. 调整原有用户 index/modal 测试 mock 以支持原生 API、helper、usePreferences、optional 规则及只读 Descriptions；新增菜单/字典同级和超大 ID 排序测试。不得删降原行为断言。
6. 角色后端新增全结果排序/白名单和省略 dataScope 保持测试，测试类名交 PM 纳入门禁，B 不改门禁。
7. 完成 monitor/job、jobLog、operlog 原生迁移及后端分页前白名单排序；保留平台租户约束、受控 handler、任务日志30/90日保留和500条清理限制。JobFireLogService 现为 fire_id DESC，须安全表达式映射；OperLog 当前仅时间排序；JobController 当前无显式排序。保留旧测试直接调用签名兼容性。
8. About 改用现成 Alert/Link 与 About props；补本范围所有页面权限、失败、CRUD、跨页排序及主题/尺寸验收。
9. 重型检查/Java/浏览器需重新向 PM 领取串行窗口；暂停前窗口已交 C 后 A，不得自行启动。

### 已发生的检查、失败与候选证据

- 样板最终 vue-tsc 通过；FE Grid5/API9/profile3 + 权限边界3，共20通过；BE Ordering12/ImportExport4/RoleValidation1，共17通过。
- 首轮 Mockito attach 失败后中止，改为 BOM bytebuddy-agent 1.14.19 单 JVM 后通过；首轮 FE sandbox pnpm EPERM 在编译前失败，批准环境重试后正式11/11任务通过。
- 早期 b5 原生 refresh 默认 reload 清排序，真实 GUI 揭示后 b4 修复并正式重建；探索失败日志保留，不能抹去。
- 新空 schema ui_native_01_20261024 正常43/43 Flyway；31用户夹具，6字段双向12组合共48页与直接 MySQL 全结果排序比对通过。
- 用户实际 UI 新增/编辑及请求+服务端回读、详情、无 role:list 的普通编辑保留原角色通过；1440x900/1366x768 深浅色、375窄屏无文档横向溢出。
- PM 样板遗留两项：工具栏帮助拥挤、详情密码及可选星号，已在暂停源码修但未再验收。最初名字带 light 的截图实际深色，正确浅色另名 actual-light，勿误引用。
- BE .qa-local/ui-native-01/candidate-state.json、api-sort-results.json、各日志及固定 static-b4d0fa3、manifest 保留；FE output/playwright 与 .playwright-cli 为未跟踪 QA 证据，不提交。
- 固定旧候选 JAR SHA256：4342ADD056F48099CF0E2EEDDBD817790929DEF6669006C251B2CB194E2A20EF。
- FE b4 dist.zip SHA256：56579C7935ECFB169FB854F2EADCC0C59F45DCD8129E9E066563C5D86989D677；manifest SHA256：444D8034AC9B637A4D9EC189F49EC9A21679DF5CB4F777B5FB5F8E0634131A1A。
- 自有 Java PID30256（17871）、preview PID38292（15171）已按精确身份停止，PM 当时独立确认；本次暂停检查两 PID 均不存在。命名浏览器 ui-native01 已关闭。本轮没有新增运行进程；不触碰共享 MySQL/Redis或他人进程。
- schema、夹具、固定候选、私有 QA 指针均原地保留。私有文件含凭据，不打印/提交；用现有 PM 环境 helper 恢复。

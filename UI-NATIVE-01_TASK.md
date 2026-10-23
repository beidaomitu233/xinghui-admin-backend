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

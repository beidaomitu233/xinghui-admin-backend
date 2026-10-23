# UI-NATIVE-02 本人任务板

领取：2026-10-24，原A。状态：进行中，未验收。

- FE原工作树worktrees/USE-04/frontend，feature/UI-NATIVE-02，origin/develop基线565bb61dfc2707cd9e5bde40880f293ad4ba8cad。
- BE原工作树worktrees/USE-04/backend，同名分支，origin/dev基线94a01795113b1d0f43684271d809528f1da9f486。
- 范围：全部lowcode；system file/notice/notification兼容页；layouts notification-center/personal-notification-entry；BE lowcode/Oss/公告/本人通知排序。共享adapter、packages、全局样式、认证及其他负责人的源码不修改。
- 顺序：记录页原生Page/useVbenVxeGrid（内置useVbenForm查询）、Vben表单/Modal编辑与原版本详情；真正远程排序后分页，schema可排序字段类型白名单；并行开展设计器官方Vue3/ElementPlus版本评估和其余页面改造。成熟设计器包/lock待PM统一确认后写入，不复用原自制画布冒称成熟。
- 安全合同：权限、租户/creator来源、revision、历史schema/fieldKey/已发布版本、分享token撤销/轮换和幂等账本保持；不恢复个人左菜单/已删资讯或模板。
- 排序：沿可选orderBy/order，固定SQL列或服务端schema字段表达式、方向校验、稳定ID，非当前页sort；多选暂不提供语义不明排序。Long ID为字符串，total安全转换。查询/排序/分页/刷新状态与迟到回包需一致。
- 验证：重型build/types/真实Chrome/BE运行先向PM排期；隔离QA等PM分配，旧库/迁移不清不乱序。先实现与有针对性的轻量检查，测试与实际验收分别记录。

## 本人通信

2026-10-24：中央组件原则/排序/范围与Gitflow已按分段读取。双树干净起步并核对基线，已回报PM。现有Vxe适配层默认response.items/total，query需在API边界适配records；原生Page有title/extra，不引入通用wrapper。B先提供准确适配用法，当前源码可继续实现。

## 进度与交付

开发中。尚无本轮运行/迁移/重型检查或部署结果，不沿用旧USE04通过作为本轮原生组件验收。

### 本轮实现更新（未验收）

- 依赖采用PM统一提交：designer 3.5.0 / renderer 3.3.5，已离线 frozen-lockfile 安装；不更改其它锁节点。
- 所属列表已切 Native Page/useVbenVxeGrid/Form；记录、公告、分享、摘要为 useVbenModal，通知中心为 useVbenDrawer。RuntimeFields 改 Native Form，保留业务数据校验。
- 自制设计器画布已替换为官方 form-create，七类 DragRule/属性菜单、Schema双向映射与懒加载。现有字段标识受保护，新增字段可配置；不开放嵌套/脚本/远程导入。
- PM批准 packages 例外只限现有 Notification 的兼容扩展：隐藏无接口动作、可控 open、点击不自动导航、empty槽、当前列表已读提示及禁用态。业务复用原生 Bell/Popover，不再自建通知壳。
- 按B当前安装版源码处理：表单 submit 先保存最近提交值→setCurrentPage(1)→query；reset才清排序；toolbar refresh:{code:'query'}。回包前核对原生当前状态，接续 loading 期间被忽略的新操作。
- 小范围测试：designer-schema 11/11 passed；真实 Native Form 共享填写页5/5 passed（撤销/幂等/冲突确认断言保留）。记录父页权限/历史schema 8/9，发现详情页仍挂载隐藏编辑表单，已改只读不挂载且跳过reset，待复跑。设计器/分享真实组件测试正在迁移核验。
- 重型窗口仍由B占用；本轮尚未启动正式QA后端、全量类型/Maven/build/浏览器，所有绿灯以本轮实际证据为准。

阶段进度：关键源码已达到可聚合审查阶段，新增同参数业务刷新版本接续与双击写前busy复核；记录save成功先解除saving再关闭Native Modal，路由失效主动解锁关闭。字段标识规则已按PM确认撤回“保存即只读”的额外限制。定向测试及待验范围见 UI-NATIVE-02_DELIVERY.md；本轮尚未重型验收。

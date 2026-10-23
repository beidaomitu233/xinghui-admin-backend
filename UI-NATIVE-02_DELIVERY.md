# UI-NATIVE-02 阶段交付（进行中，非验收结论）

## 范围与实现

本阶段沿 feature/UI-NATIVE-02 原工作树，保留既有业务/接口/历史Schema/分享幂等合同。完整vue-tsc已在2GiB单进程通过；Java17/768MiB、forkCount=0的7类Maven定向回归40项通过，失败/错误/跳过均0。尚未正式构建、完整回归、分配MySQL或真实Chrome验收；重型窗口由PM串行协调。

| 页面或业务 | 本阶段组件来源 |
| --- | --- |
| lowcode forms / records | 现有 Page + useVbenVxeGrid 内置 Form/分页/toolbar；记录编辑 useVbenForm/useVbenModal |
| lowcode runtime / public fill | 原生 Page；业务 Schema 到现有 useVbenForm 的字段映射 |
| 表单设计 | 官方 @form-create/designer 3.5.0 画布/属性面板；@form-create/element-ui 3.3.5；Metadata/Preview 使用现有 Form/Modal |
| 分享填写 / AI摘要 | 现有 Vben Modal；分享使用现有 Vben Form；AI流式业务逻辑保留 |
| 文件 / 公告 | 现有 Page/Grid/Form；公告编辑与详情使用现有 Modal/Form |
| 顶部通知 / 通知中心 | 现有 @vben/layouts Notification；现有 Drawer、Grid/Form、详情 Modal |

设计器七类规则只映射既有业务 Schema。映射/拖动/重读不自动改变fieldKey；用户仍可主动修改草稿，历史发布兼容由既有服务校验。未开放嵌套、脚本、远程字段或执行式剪贴板导入。仅在业务scoped CSS隐藏固定3.5.0中无配置开关的隐藏字段控件和树更多菜单，保留原生拖动/删除/选项操作；真实DOM命中待Chrome复核。按App WeakSet只安装一次，依赖模块仅打开编辑器时加载。

## PM核对的最小公共组件例外

仅修改原有 Notification：showClear/showRemove默认true；navigateOnClick默认true；itemClick；标准v-model:open；empty槽；markAllText、markAllDisabled默认false；triggerLabel默认原本地化通知。业务隐藏无接口清空/删除，点击走原安全详情/目标流程，提示“当前列表已读”。复用原Bell/dot，不另造通用壳。头像缺失用原Bell兜底。

## 查询与写入

BE为lowcode/Oss/notice/本人通知增加可选orderBy/order，固定列或已发布Schema白名单、稳定ID次序、全结果排序再分页。动态多选不支持排序，数值按DECIMAL排序；显式JSON null与缺键按SQL NULL，文本null保留为文本。

FE按当前安装版Vxe：submit保存最近提交值→第一页→query；reset才清序；toolbar refresh:true + refreshOptions:{code:'query'}。回包前读取原生页/序/已提交筛选进行接续；业务写后刷新单独改变一个版本计数，避免同参数慢请求覆盖写后结果。不新增调度组件。Long IDs保持字符串；total安全转换。写前再次检查权限/版本/页面与busy；失效页面关闭编辑器并忽略迟到回包。

## 已有本轮定向证据（不可替代原生Chrome）

| 测试文件 | 实际例数 / 最新结果 |
| --- | --- |
| lowcode/forms/designer-schema.test.ts | 13 passed |
| lowcode/forms/FormDesigner.test.ts | 4 passed，真实官方画布/属性面板与Vben Metadata Form；Popup为测试端口 |
| lowcode/records/index.test.ts | 11 passed，历史Schema/权限/迟到/双击及关闭 |
| system/notification/index.test.ts | 14 passed，含Vxe忙时忽略新命令及同参数写后刷新 |
| lowcode/RuntimeFields.test.ts | 2 passed，真实Vben Form，星号/0/false/schema切换 |
| lowcode/fill/index.test.ts | 5 passed，真实Vben Form，撤销/幂等/显式冲突确认 |
| lowcode/forms/FormSharing.test.ts | 2 passed，真实Vben Form，公开确认/撤销/旧token丢弃 |
| lowcode/records/AiSummaryDialog.test.ts | 8 passed，原取消/迟到/只读合同 |
| layouts/personal-notification-entry.test.ts | 1 passed，未读计数/退出清理/兼容打开 |
| layouts/native-notification.test.ts | 3 passed，真实Notification逻辑，Popover/Button为测试端口 |

原生Modal在Happy DOM中遇到Portal/焦点递归，单测使用显式Popup端口；不据此宣称真实弹窗或样式已通过。必须Chrome验证实际Modal/Drawer/Notification开合、遮挡和焦点，及成熟Designer拖动/字段设置。

## 后端定向验证与MySQL待验

- LowcodeServiceTest共10例（含新增2例）通过：跨页/稳定ID/白名单/租户。
- NotificationInboxServiceTest共5例（含新增1例）通过：三页标题排序/owner/伪造字段方向。
- 新LowcodeSortingMySqlIT：仅本地ui_native_02_20261024或既有CI服务库；正常43迁移，生产stat+wall Druid；35条4页的六类动态字段双向排序与独立直接DB排序核对；覆盖中文/精度/日期/boolean/旧版本缺键/JSON null/文本null/同值ID/跨租户跨表单/隐藏多选未发布字段拒绝。仅删除本用例确切form IDs。实际MySQL5.7/8.0及CI纳入由PM统一安排，不修改共享gate。

## 尚需完成

现有全套回归/生产build与依赖增量；本轮MySQL；所有所属页面正式Chrome CRUD、跨页排序及权限交互。上述阶段提交不代表完成，不推主干或部署。



# USE-02 页面一致性交付（B，2026-10-23）

## 基线与提交

- 分支：两端 `feature/USE-02-page-consistency`。
- 后端基线：`9a385d8c36447091a712c2dd692abb51665eb7d5`（origin/dev）；本任务后端仅新增本交付文档。
- 前端基线：`065467a8035c4e6dd1e6abed1749aa17d51c7e3f`（origin/develop）。
- 前端公共组件：`43546e5a6e4949b6f03ca40cf4714c971689925b`。
- 前端页面接入：`05a255f1e4cf5dc63d79a3a42fd3ecaf56f4a110`。
- 两基线分别与生产 82fa96b / 9f0ea20 内容对比为空。未推送、合并、部署。

## 文件范围与接入约定

公共组件在 `apps/web-ele/src/components/admin-page-header.vue`，配套 `admin-page-header.test.ts`。约定：

```vue
<Page content-class="admin-page">
  <AdminPageHeader title="页面名称" description="可选短说明">
    <template #actions>页面操作</template>
  </AdminPageHeader>
  <!-- 页面内容 -->
</Page>
```

- 桌面外边距24px，窄屏16px；标题20px/28px/600，说明14px/20px，页内间距16px。
- actions 支持换行，主题使用现有 foreground/muted token；无新增全局 overflow:hidden。
- 业务配置页面由C接入公共组件，B未改C文件；菜单/偏好/图标/公开路由由A负责。
- 普通管理页：`system/{dict,user,role,file,config,notice,news,notification-template,notification}/index.vue`、`monitor/{job,jobLog,operlog}/index.vue`。统一页头，页面级创建/导入导出等动作移入actions，局部条目动作保留原位置。保留原权限和事件。
- AI配置：`system/ai-config/{index,ModelHubLayout}.vue`。保留供应商/模型中心及路由配置布局。
- 提示词：`system/ai-template/{index,LegacyWorkspace}.vue`、`workspace/{SceneWorkspace,SceneSidebar}.vue`。页头标题为“提示词配置”；新建场景仅保留页头入口，公开 openCreate 内仍检查原 add 权限；旧模板分支保留。
- 调用监控：`system/ai-request/{index,MonitorWorkspace,CallLogPanel}.vue`、`conversations/ConversationBoard.vue`。保留对话与日志页签；局部标题改h2。
- `SceneWorkspace.test.ts`同步h2选择器，并从公开openCreate触发原创建流程，保留原保存/CAS/脏状态测试内容。
- `components.d.ts`自动生成噪声已还原，未提交缓存、账号、截图或浏览器状态。

## API、数据与边界

API/权限标识/请求格式/Migration/数据库表均未修改。未连接生产。未实施USE-05删除；news、notification-template、notification仅做页头接入。未改后端业务、菜单或系统业务配置。

本地QA由PM提供17864后端，B前端15162，使用B独立私有账号（未输出或提交凭据）。共享基线后端不能用作C候选后端功能验收。

## 已完成验证

1. 公共组件根Vitest：2/2通过。
2. 应用定向8文件初跑：58/59通过，唯一失败为场景h1选择器；改h2后该文件19/19通过。
3. PM指出重复新建入口后，移除侧栏入口，再跑SceneWorkspace：19/19通过。其余7文件初跑通过，最终覆盖合计59项（不是一次全量重跑）。实际8文件为公共页头、ModelHubWorkspace、SceneWorkspace、MonitorWorkspace、ConversationBoard及monitor/job、jobLog、operlog；用户/角色/index与文件/index不存在对应测试，筛选参数未产生这些测试，不计覆盖。
4. 改动文件Prettier、ESLint、Stylelint最终均exit0；git diff --check通过。定向日志位于前端 `.qa-local/use02/{check-final,scene-test}.log`。
5. PM独立浏览器在1440×900对字典、角色、AI配置、提示词、调用监控五页复核：h1均x248/y112、20px、无横向溢出、0pageerror。PM证据在主工作区 `.qa-local/20261023-usability/output/playwright/USE-PM-layout/`。
6. B第一轮五页×1440×900/1920×1080/390×844×双主题，共30次真实截图及DOM测量完成：visualViewport.scale=1、devicePixelRatio=1，scrollWidth均等于viewport，0pageerror，新建场景仅1入口。截图保存在下列本机目录。窄屏h1 x16/y104；桌面随侧栏折叠为x84/y112，初始展开为x248/y112，字体均20px。部分采集发生在侧栏动画中，浮点x不作为最终对齐证据。

## 截图位置与已知限制

B截图目录（未加入Git）：
`D:/changhuiengineer/DevProject/xinghui-admin2/worktrees/USE-02/frontend/output/playwright/`

文件规则：`use02-_<system_dict|system_role|ai_configurations|ai_templates|ai_requests>-<1440|1920|390>-<light|dark>.png`。

已人工查看字典1440深色、AI配置390浅色、监控390深色、提示词1920浅色。未对30张逐张做最终视觉验收。部分截图含“加载菜单中...”toast，窄屏含旧公共侧栏残留；该公共导航由A整合修复/复核，B未改A文件。最早 `use02-dict-1440-light.png` 实为深色，不作为主题证据。

首轮采集中遇过Vite首次依赖optimize的504、Chrome ERR_INSUFFICIENT_RESOURCES导致bootstrap抓取失败；重载后首轮30组合完成。稳定等待toast消失的第二轮未完成，随后按PM要求关闭浏览器和15162 Vite，不将失败记成功。第二轮错误日志在前端 `.qa-local/use02/matrix.log`（首轮详细JSON保留于本会话工具输出）。

最终稳定双主题/尺寸截图、实际有数据的AI编辑与日志视觉、C业务配置页对齐、A公共导航窄屏表现仍需PM在统一生产预览组合验收。完整typecheck/生产build未在B并行执行，PM明确安排统一组合门禁；不宣称已通过。既有定向测试验证业务行为保持，未向真实供应商发送请求。

## 后续

交PM串行整合两个FE提交及本BE文档提交，完成组合门禁和最终预览验收。USE-05待PM接受USE-02并指定基线后领取；不能据此文档提前删除任何模块。

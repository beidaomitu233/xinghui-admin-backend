# USE-03 业务配置与微信授权

- 领取：2026-10-23；负责人 C。
- BE：`feature/USE-03-business-config`，基线 `9a385d8c36447091a712c2dd692abb51665eb7d5`。
- FE：`feature/USE-03-business-config`，基线 `065467a8035c4e6dd1e6abed1749aa17d51c7e3f`（本项目 origin）。
- 工作树：`worktrees/USE-03/backend`、`worktrees/USE-03/frontend`。
- 迁移仅使用 `V20261023_310`；端口预留 BE 17863 / FE 15163。

## 实现范围

1. 业务配置保持一个纵向页面，五个分区独立保存：微信网站、微信内 H5、短信、邮件、OSS。平台身份及 query/edit 权限保持原规则；OSS 历史配置与旧文件逻辑保持原实现。
2. SMS 接入既有 sms4j：阿里云区域/AccessKey，与腾讯云 SDK AppID/SecretId/SecretKey 差异字段。供应商切换清除旧密钥；同供应商空值保留，显式清除，CAS 冲突和失败不更新数据库。
3. Mail 使用 QQ/163 固定 SSL 465 预设、可编辑的企业邮箱与通用 SMTP。RuntimeMailService 每次发送重新解析数据库快照；数据库显式停用覆盖部署配置；无数据库记录时保留原部署回退。SSL/STARTTLS 必须选其一，STARTTLS required、证书主机校验、连接/读写超时。密钥身份绑定 provider+host+user；变更后不继承旧凭据。
4. 微信网站继续使用独立 sys_wechat_website_config；H5 服务号使用 sys_business_config.wechatMp，各自加密密钥、AppID、回调与 scope。网站 snsapi_login，服务号 snsapi_base/snsapi_userinfo；不按 UnionID 自动合并身份。
5. 新流程在 MySQL 保存流程/浏览器/身份摘要及原业务返回路由；Redis 保留限流和单次 state 原子消费。state 为 43 位安全随机字母数字，300 秒到期。回调先验证浏览器、场景、配置 revision 与 state，再从固定微信 HTTPS 端点交换 code；不存 code/access_token/refresh_token，不请求头像昵称或业务数据。
6. 未绑定：用户通过原手机号/密码/验证码登录已有产品账号，再明确点击关联；绑定接口必须有正常认证。已绑定：页面自动调用单次完成接口，重载精确用户/租户及权限、检查账号启用状态，然后签发现有 JWT/Redis 会话并返回原业务。无创建用户/管理员路径。
7. 返回路由为规范化站内相对路径，拒绝外站、协议相对、反斜杠、编码路径、控制字符、点段和登录循环；保留 query，支持 workspace/analytics/lowcode 等任意正常业务路径，权限由正常路由守卫检查。
8. 没有自行生成“已扫码/待确认”状态：PC 使用微信托管扫码页，页面只展示 WAITING、实际验证结果、失败/取消/过期/完成。外部手机浏览器提供手机号登录与微信内打开指引，不混用网站 QR 和 H5 scope。

FE 依赖两个独立来源提交：B 页头 `43546e5`（本树 `43dd490`）；PM 通知 store $reset 修复 `06a48ba`（本树 `d91f556`）。没有另建公共页头，也没有在认证代码中吞掉 store reset 错误。

FE 功能提交：`e117d9d33c8d3abc41e99b9fc6542d7335a1f574`。PM 合并时识别上述两个前置提交来源，避免重复引入。BE 功能提交以包含本文件的 Git 提交为准。均仅本地提交，未推送、合并或发布。

## API 与回调部署

- GET/PUT `/system/businessConfig/{wechat,wechatMp,sms,mail,oss}`；响应只含参数、版本与密钥存在布尔值。
- GET `/auth/wechat/login-capability`；POST `/auth/wechat/flow` 接收 channel/returnPath，返回随机流程票据、微信授权 URL 与 TTL。
- GET `/auth/wechat/flow/status?flow=...`；POST `/auth/wechat/flow/{cancel,complete,bind}`。bind 有正常认证要求，其余匿名路径逐一列入 SecurityConfig；所有流程操作还验证 HttpOnly SameSite=Lax 浏览器 cookie，写操作校验配置同源 Origin。
- 本项目 nginx/Vite 仅代理 `/api/`，正式回调须为 `https://本站/api/auth/wechat/callback` 或 `https://本站/api/auth/wechat/mp-callback`，由代理剥离 `/api` 后到后端。旧网站非 API 配置读取时规范为 `/api/auth/wechat/callback`，下一次保存持久化；旧在途授权请到期后重新发起。
- 正式 hash 前端保留 `wechat.result-use-hash=true`；本地 history 前端验收用 false。回调 303 只返回固定结果页和状态；缺失配置使用固定相对结果路径，不使用请求 Host 拼外站。
- 新迁移只新增 `sys_wechat_identity_binding` 与 `sys_wechat_login_flow`；旧迁移文件未修改。未改共享网关。

## 微信官方依据（2026-10-23 直接 HTTPS 读取）

- [网站应用微信登录开发指南](https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html)：网站应用审核、snsapi_login、qrconnect、code 交换、微信托管扫码/快速登录。WxLogin onReady/onQRcodeReady 是页面/二维码加载回调，未作为扫码身份凭证。
- [服务号网页授权](https://developers.weixin.qq.com/doc/service/guide/h5/auth.html)：支持授权的已认证服务号、网页授权域名、微信内浏览器、snsapi_base/snsapi_userinfo、纯字母数字 state、code 换用户授权 token。旧公众号文档地址已跳到服务号导航，已继续读取此新正文。

## 验证边界

本批可执行后端定向编译测试；不启动 Docker，隔离 MySQL/Redis 由 PM 排程。旧 aiux_c/V220 授权不作为本批新迁移授权。禁止生产验证、真实模型调用及真实业务数据外发。

真实微信、短信、邮件供应商凭据未提供，供应商身份交换/投递效果保持未验证。官方文档已读取；受控回调和本地数据库测试不代表官方微信扫码成功。

## 验证记录

1. 新建专属 MySQL `127.0.0.1:13323/use03_20261023` 从空库迁移至 `20261023.310` 并 Flyway validate 通过；专属 Redis 为 `127.0.0.1:16383` DB3。DPAPI helper 只在进程加载，未输出凭据。主实例由 PM 管理，不由 C 停止。
2. 首次 native 验证：真实数据库密文写入、重建服务解密到 MailAccount、显式清除、revision 冲突通过；仅新建一个精确本机账号，不修改原种子账号密码。
3. 后端第二轮定向 package：29 项通过，0 failed/error/skipped。包含真实 MySQL 两线程仅一次 complete、唯一绑定冲突和禁用完成回滚；身份交换明确用受控替身。日志 `.qa-local/20261023-usability/use03-final-backend.log`。
4. 前端最终定向五文件 10 项通过：页头、配置独立保存/失败保留、URL/返回路由规则、授权入口、结果自动登录与显式绑定；日志 `use03-frontend-tests-final.log`。最后仅补分区 scroll-margin 避免锚点落到固定工具栏下，组合视觉验收交 PM。
5. 实际 UI 已验证：新建验收账号登录→邮件表单→API→MySQL revision1/encrypted1/plaintext_absent1；重新读取密码空白；无效发件邮箱保存失败仍保留输入。腾讯云选择后出现 SecretId/SecretKey/SDK AppID，隐藏阿里云区域。390 像素无页面横向溢出，移动表单调整为顶置标签。
6. 改动 FE 文件 ESLint 最终 0 errors（既有测试文件两个多组件 warning），Prettier 通过。typecheck 曾以 768 MiB 堆上限 OOM，未记为通过；完整类型/生产构建与组合门禁由 PM/CI 排程。

7. 最终窄 package 成功：34 项通过，0 failed/error/skipped，覆盖最新 API canonical/空 MP callback fallback/H5 state 字符集、服务号独立 scope/凭据、邮件停用和动态快照。日志 `use03-package-final.log`。产物 SHA256：`67AA8A54AD28DABB4967C97181EB94081464F332D83988663BF48A94196B166E`。
8. 该最终包通过真实同源代理验收：15163 `/api/auth/wechat/callback` →17863→303→history 结果页→同浏览器 `flow/status=CANCELLED`，query 原业务上下文保留。省略 code，不调用微信；未配置 MP/非法 state 返回固定相对结果路径，匿名 bind=401。
9. 另以明确的本机受控身份夹具验证实际产品链路：新建测试账号在真实 MySQL 被停用时，auto complete 拒绝且停留结果页；恢复启用后自动重载账号/权限、建立 JWT/Redis 会话并返回 `/system/business-config?section=mail`，确认 PM $reset 补丁实际生效。未绑定受控夹具不会自动关联；点击“确认关联当前账号”后，真实认证 bind 接口写入精确用户/租户，再回原业务 query。数据库状态夹具直接设置，绝不作为真实微信身份或扫码成功证据。
10. 已精确清理：本次绑定、3 个流程、邮件配置/历史、新建测试账号/角色关联/三个操作审计记录、该账号 Redis 会话/信息/路由及对应 state/browser 摘要；剩余夹具计数均 0。虚构微信配置已停用并清空密钥；旧种子账号密码未变。私有临时登录文件及该测试账号 DPAPI 文件已删除。
11. C 专属 Playwright 浏览器已关闭；按实例 PID、命令行和端口核实后停止 15163/17863，未停止 PM 共享 MySQL/Redis。`use03-process-cleanup.json` 保留收尾记录。BE secret_scanner 与两端 git diff --check 通过。

本机截图在 `worktrees/USE-03/frontend/output/playwright/`，未加入 Git；截图只作为表单差异和手机排版记录，部分元素截图包含固定工具栏，完整组合视觉验收由 PM 统一预览完成。CLI 材料移至 `.qa-local/20261023-usability/use03-playwright-cli/`，所有私有材料未加入 Git。

## 交付限制与后续门禁

- 真实微信应用、域名登记、官方身份交换、短信投递、邮件投递均未验证；当前成果为实际代码链路、安全检查及明确的本机受控验收，不标为真实供应商生产可用证明。
- 完整 typecheck/生产 build/组合回归与公网回调可达性由 PM/CI 统一排程。C 本机 typecheck OOM 未记成功，未在资源紧张环境反复重跑或启动 Docker。
- 手机外部浏览器只有手机号登录/微信内打开指引；未实现跨设备转移 state，也未伪造扫码回执。绑定按场景+AppID 隔离，不自动跨应用合并。
- 本批只交付 USE-03；未开始后续低代码/通知删除或 Agent 二期，未动中央任务板和共享菜单/偏好文件。

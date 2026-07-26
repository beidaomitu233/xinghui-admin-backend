# 已知问题清单（Known Issues）

深度代码审查（2026-07-26）确认但本轮未修复的问题，按优先级排列。
修复时请从 dev 拉 feature/fix 分支，完成后勾选并注明提交。

## P1 功能缺陷

- [ ] **定时任务模块未接入调度器**：`/monitor/job` 仅对 sys_job 表做 CRUD，
  全工程无 Quartz Scheduler / 动态 @Scheduled 注册，新增的任务永远不会执行。
  需集成 Quartz（增删改时同步 scheduleJob/rescheduleJob/deleteJob），或下线该菜单。
- [ ] **逻辑删除与唯一索引冲突**：sys_user 逻辑删除（is_deleted=1）后，
  `idx_username` 唯一索引仍占用旧用户名，同名用户永远无法再创建。
  方案：唯一索引改为 (username, is_deleted) 组合或删除时改写 username 加后缀。
- [ ] **sys_news / sys_download / sys_page / sys_file 四张表无后端实现**：
  表和种子菜单存在（system:news:* 等），但无对应实体/Mapper/Controller，
  前端点击相关菜单会 404。需补齐实现或从基线菜单中移除。

## P2 健壮性 / 性能

- [ ] **exportUsers N+1 查询且无上限**：每个用户循环查 2 次角色表，万级用户导出会拖垮连接池。
  应批量查询后内存组装，并限制单次导出行数。
- [ ] **验证码校验非原子**：`validateCaptcha` 先 get 后 delete，并发请求可复用同一验证码。
  应改用 Redis GETDEL 或以 delete 返回值判定。
- [ ] **check-then-insert 竞态**：addUser/addRole 的唯一性校验非原子。
  用户名有唯一索引兜底（现已转为友好提示）；sys_role 无唯一索引，
  应为 (tenant_id, role_key)、(tenant_id, role_name) 建唯一索引。
- [ ] **batchAddUser 静默跳过重复行**：Excel 导入时已存在的用户名被 continue 跳过，
  不计入成功也不计入失败，导入统计误导。应返回跳过清单由监听器计入失败明细。
- [ ] **updateMenu 父级校验错位**：parentId 为 null（不修改父级）时按 parentId=0 校验重名，
  应以 existMenu.getParentId() 为基准。
- [ ] **sys_user.mobile 无唯一索引**：代码层已加唯一性校验（add/update/updateProfile/登录多行报错），
  但缺数据库级兜底。需先明确空手机号语义（'' 改 NULL）再建唯一索引。

## 安全加固（长期）

- [ ] JWT 无撤销机制（当前靠 Redis 登录态缓存变相失效，24h TTL）；可考虑 jti 黑名单。
- [ ] `JwtConstants.TOKEN_SIGN_KEY` 有硬编码默认值，生产必须设置环境变量 `JWT_SECRET`；
  建议 prod profile 下未设置时启动失败。
- [ ] 多租户默认关闭（tenant.enable=false）、验证码默认关闭（captcha.enabled=false），
  生产部署检查清单需要求开启。

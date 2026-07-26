-- =====================================================================
-- XingHui Admin - Flyway V2 基线种子数据
-- 菜单/角色/租户/参数/演示数据；默认账号（登录使用手机号）: admin=13800000000/admin123, user=13800000001/admin123（生产环境务必修改）
-- 由 sql/xinghui_admin.sql 历史全量脚本整理生成；此后所有变更须通过
-- 新增 V<yyyyMMdd>_<nn>__<描述>.sql 迁移脚本演进，禁止直接修改本文件。
-- =====================================================================

SET NAMES utf8mb4;

-- ----------------------------
-- Records of sys_config
-- ----------------------------
INSERT INTO `sys_config` VALUES (1, '000000', '主框架页-默认皮肤样式名称', 'sys.index.skinName', 'skin-blue', 'Y', '蓝色 skin-blue、绿色 skin-green、紫色 skin-purple、红色 skin-red、黄色 skin-yellow', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (2, '000000', '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', '初始化密码 123456', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (3, '000000', '主框架页-侧边栏主题', 'sys.index.sideTheme', 'theme-dark', 'Y', '深色主题theme-dark，浅色主题theme-light', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (4, '000000', '账号自助-验证码开关', 'sys.account.captchaOnOff', 'true', 'Y', '是否开启验证码功能（true开启，false关闭）', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (5, '000000', '账号自助-是否开启用户注册功能', 'sys.account.registerUser', 'false', 'Y', '是否开启注册用户功能（true开启，false关闭）', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);

-- ----------------------------
-- Records of sys_download
-- ----------------------------
INSERT INTO `sys_download` VALUES (1, 'MinerU Desktop', 'v2.5.0', 'windows', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-Setup-2.5.0.exe', 268435456, '支持 Windows 10/11 x64', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 2, '2026-05-08 20:11:37', '2026-05-08 20:11:37');
INSERT INTO `sys_download` VALUES (2, 'MinerU Desktop', 'v2.5.0', 'macos', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-2.5.0.dmg', 293601280, '支持 macOS 12+ (Intel/Apple Silicon)', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 0, '2026-05-08 20:11:37', '2026-05-08 20:11:37');
INSERT INTO `sys_download` VALUES (3, 'MinerU Desktop', 'v2.5.0', 'linux', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-2.5.0.AppImage', 251658240, '支持 Ubuntu 20.04+ / CentOS 7+', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 0, '2026-05-08 20:11:37', '2026-05-08 20:11:37');

-- ----------------------------
-- Records of sys_job
-- ----------------------------
INSERT INTO `sys_job` VALUES (1, '000000', '数据库备份', 'DEFAULT', 'dbBackupTask.execute', '0 0 2 * * ?', '3', '1', '1', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0, '每天凌晨2点执行数据库备份');
INSERT INTO `sys_job` VALUES (2, '000000', 'AI健康检测', 'DEFAULT', 'aiHealthTask.check', '0 */10 * * * ?', '3', '1', '0', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0, '每10分钟检测AI服务健康状态');

-- ----------------------------
-- Records of sys_menu
-- ----------------------------
INSERT INTO `sys_menu` VALUES (1, '系统管理', 0, 1, 'system', NULL, 1, 'M', '0', '0', '', 'carbon:settings', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (2, '系统监控', 0, 2, 'monitor', NULL, 1, 'M', '0', '0', '', 'carbon:activity', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (3, '基础设施', 0, 3, 'infra', NULL, 1, 'M', '0', '0', '', 'carbon:cube', '2025-12-25 22:36:47', NULL, '2026-01-31 03:16:33', NULL, 0);
INSERT INTO `sys_menu` VALUES (4, 'AI助手', 0, 4, 'ai', NULL, 1, 'M', '1', '1', '', 'carbon:bot', '2025-12-25 22:36:47', NULL, '2026-01-31 03:35:15', NULL, 0);
INSERT INTO `sys_menu` VALUES (100, '用户管理', 1, 1, 'user', 'system/user/index', 1, 'C', '0', '0', 'system:user:list', 'carbon:user', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (101, '角色管理', 1, 2, 'role', 'system/role/index', 1, 'C', '0', '0', 'system:role:list', 'carbon:user-role', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (102, '菜单管理', 1, 3, 'menu', 'system/menu/index', 1, 'C', '0', '0', 'system:menu:list', 'carbon:menu', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (103, '参数设置', 1, 4, 'config', 'system/config/index', 1, 'C', '0', '0', 'system:config:list', 'carbon:document-configuration', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (200, '定时任务', 2, 1, 'job', 'monitor/job/index', 1, 'C', '0', '0', 'monitor:job:list', 'carbon:time', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (201, '调度日志', 2, 2, 'jobLog', 'monitor/jobLog/index', 1, 'C', '0', '0', 'monitor:jobLog:list', 'carbon:notebook', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (202, '操作日志', 2, 3, 'operlog', 'monitor/operlog/index', 1, 'C', '0', '0', 'monitor:operlog:list', 'carbon:catalog', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (300, '文件管理', 3, 1, 'file', 'system/file/index', 1, 'C', '0', '0', 'infra:file:list', 'carbon:folder', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (301, '通知公告', 3, 2, 'notice', 'system/notice/index', 1, 'C', '0', '0', 'system:notice:list', 'carbon:notification', '2025-12-25 22:36:47', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (1000, '用户查询', 100, 1, '', '', 1, 'F', '0', '0', 'system:user:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1001, '用户新增', 100, 2, '', '', 1, 'F', '0', '0', 'system:user:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1002, '用户修改', 100, 3, '', '', 1, 'F', '0', '0', 'system:user:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1003, '用户删除', 100, 4, '', '', 1, 'F', '0', '0', 'system:user:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1004, '用户导出', 100, 5, '', '', 1, 'F', '0', '0', 'system:user:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1005, '用户导入', 100, 6, '', '', 1, 'F', '0', '0', 'system:user:import', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1006, '重置密码', 100, 7, '', '', 1, 'F', '0', '0', 'system:user:resetPwd', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1007, '角色查询', 101, 1, '', '', 1, 'F', '0', '0', 'system:role:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1008, '角色新增', 101, 2, '', '', 1, 'F', '0', '0', 'system:role:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1009, '角色修改', 101, 3, '', '', 1, 'F', '0', '0', 'system:role:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1010, '角色删除', 101, 4, '', '', 1, 'F', '0', '0', 'system:role:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1011, '角色导出', 101, 5, '', '', 1, 'F', '0', '0', 'system:role:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1012, '数据权限', 101, 6, '', '', 1, 'F', '0', '0', 'system:role:dataScope', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1013, '菜单查询', 102, 1, '', '', 1, 'F', '0', '0', 'system:menu:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1014, '菜单新增', 102, 2, '', '', 1, 'F', '0', '0', 'system:menu:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1015, '菜单修改', 102, 3, '', '', 1, 'F', '0', '0', 'system:menu:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1016, '菜单删除', 102, 4, '', '', 1, 'F', '0', '0', 'system:menu:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1017, '参数查询', 103, 1, '', '', 1, 'F', '0', '0', 'system:config:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1018, '参数新增', 103, 2, '', '', 1, 'F', '0', '0', 'system:config:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1019, '参数修改', 103, 3, '', '', 1, 'F', '0', '0', 'system:config:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1020, '参数删除', 103, 4, '', '', 1, 'F', '0', '0', 'system:config:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (1021, '参数导出', 103, 5, '', '', 1, 'F', '0', '0', 'system:config:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2000, '任务查询', 200, 1, '', '', 1, 'F', '0', '0', 'monitor:job:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2001, '任务新增', 200, 2, '', '', 1, 'F', '0', '0', 'monitor:job:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2002, '任务修改', 200, 3, '', '', 1, 'F', '0', '0', 'monitor:job:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2003, '任务删除', 200, 4, '', '', 1, 'F', '0', '0', 'monitor:job:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2004, '状态修改', 200, 5, '', '', 1, 'F', '0', '0', 'monitor:job:changeStatus', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2005, '任务导出', 200, 6, '', '', 1, 'F', '0', '0', 'monitor:job:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2006, '任务执行', 200, 7, '', '', 1, 'F', '0', '0', 'monitor:job:run', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2007, '日志查询', 201, 1, '', '', 1, 'F', '0', '0', 'monitor:jobLog:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2008, '日志删除', 201, 2, '', '', 1, 'F', '0', '0', 'monitor:jobLog:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2009, '日志清空', 201, 3, '', '', 1, 'F', '0', '0', 'monitor:jobLog:clean', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2010, '日志导出', 201, 4, '', '', 1, 'F', '0', '0', 'monitor:jobLog:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2011, '日志查询', 202, 1, '', '', 1, 'F', '0', '0', 'monitor:operlog:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2012, '日志删除', 202, 2, '', '', 1, 'F', '0', '0', 'monitor:operlog:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2013, '日志清空', 202, 3, '', '', 1, 'F', '0', '0', 'monitor:operlog:clean', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (2014, '日志导出', 202, 4, '', '', 1, 'F', '0', '0', 'monitor:operlog:export', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (3000, '文件查询', 300, 1, '', '', 1, 'F', '0', '0', 'system:file:query', '#', '2025-12-25 22:36:47', NULL, '2026-01-30 04:15:53', NULL, 0);
INSERT INTO `sys_menu` VALUES (3001, '文件上传', 300, 2, '', '', 1, 'F', '0', '0', 'system:file:upload', '#', '2025-12-25 22:36:47', NULL, '2026-01-30 04:15:24', NULL, 0);
INSERT INTO `sys_menu` VALUES (3002, '文件删除', 300, 3, '', '', 1, 'F', '0', '0', 'system:file:remove', '#', '2025-12-25 22:36:47', NULL, '2026-01-30 04:15:40', NULL, 0);
INSERT INTO `sys_menu` VALUES (3003, '公告查询', 301, 1, '', '', 1, 'F', '0', '0', 'system:notice:query', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (3004, '公告新增', 301, 2, '', '', 1, 'F', '0', '0', 'system:notice:add', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (3005, '公告修改', 301, 3, '', '', 1, 'F', '0', '0', 'system:notice:edit', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (3006, '公告删除', 301, 4, '', '', 1, 'F', '0', '0', 'system:notice:remove', '#', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_menu` VALUES (3007, '文件列表', 300, 4, '', '', 1, 'F', '0', '0', 'system:file:list', '#', '2025-12-25 22:36:47', NULL, '2026-01-30 04:15:33', NULL, 0);
INSERT INTO `sys_menu` VALUES (3008, '文件下载', 300, 4, '', '', 1, 'F', '0', '0', 'system:file:download', '#', '2025-12-25 22:36:47', NULL, '2026-01-30 04:15:24', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539780, '工作台', 0, 0, 'workspace', 'dashboard/workspace/index', 1, 'C', '0', '0', NULL, 'carbon:home', '2026-01-30 04:08:42', NULL, '2026-01-31 02:56:07', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539781, '看板', 0, 0, 'analytics', 'dashboard/analytics/index', 0, 'C', '0', '0', '', 'carbon:dashboard', '2026-01-30 17:55:35', NULL, '2026-01-31 02:56:43', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539787, '下载管理', 0, 6, 'system/download', 'system/download/index', 1, 'C', '0', '0', 'system:download:list', 'carbon:download', '2026-05-08 20:24:10', NULL, '2026-05-08 21:58:03', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539788, '下载查询', 2015147980830539787, 1, '', '', 1, 'F', '0', '0', 'system:download:query', '#', '2026-05-08 20:24:10', NULL, '2026-05-08 20:24:10', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539789, '下载新增', 2015147980830539787, 2, '', '', 1, 'F', '0', '0', 'system:download:add', '#', '2026-05-08 20:24:10', NULL, '2026-05-08 20:24:10', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539790, '下载修改', 2015147980830539787, 3, '', '', 1, 'F', '0', '0', 'system:download:edit', '#', '2026-05-08 20:24:10', NULL, '2026-05-08 20:24:10', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539791, '下载删除', 2015147980830539787, 4, '', '', 1, 'F', '0', '0', 'system:download:remove', '#', '2026-05-08 20:24:10', NULL, '2026-05-08 20:24:10', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539792, '资讯管理', 0, 5, 'system/news', 'system/news/index', 1, 'C', '0', '0', 'system:news:list', 'documentation', '2026-05-08 21:39:43', NULL, '2026-05-08 21:39:43', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539793, '资讯查询', 2015147980830539792, 1, '', '', 1, 'F', '0', '0', 'system:news:query', '#', '2026-05-08 21:39:53', NULL, '2026-05-08 21:39:53', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539794, '资讯新增', 2015147980830539792, 2, '', '', 1, 'F', '0', '0', 'system:news:add', '#', '2026-05-08 21:39:53', NULL, '2026-05-08 21:39:53', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539795, '资讯修改', 2015147980830539792, 3, '', '', 1, 'F', '0', '0', 'system:news:edit', '#', '2026-05-08 21:39:53', NULL, '2026-05-08 21:39:53', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539796, '资讯删除', 2015147980830539792, 4, '', '', 1, 'F', '0', '0', 'system:news:remove', '#', '2026-05-08 21:39:53', NULL, '2026-05-08 21:39:53', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539801, '页面管理', 0, 7, 'system/page', 'system/page/index', 1, 'C', '0', '0', 'system:page:list', 'carbon:data-viewer', '2026-05-08 21:58:03', NULL, '2026-05-08 21:58:03', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539802, '页面查询', 2015147980830539801, 1, '', '', 1, 'F', '0', '0', 'system:page:query', '#', '2026-05-08 21:58:03', NULL, '2026-05-08 21:58:03', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539803, '页面编辑', 2015147980830539801, 2, '', '', 1, 'F', '0', '0', 'system:page:edit', '#', '2026-05-08 21:58:03', NULL, '2026-05-08 21:58:03', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539804, '网站管理', 0, 8, '/site', NULL, 1, 'M', '0', '0', NULL, 'carbon:cics-sit', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539805, '网站设置', 2015147980830539804, 1, 'config', 'system/site/config/index', 1, 'C', '0', '0', 'system:siteConfig:list', 'carbon:settings', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539806, '导航管理', 2015147980830539804, 2, 'navigation', 'system/site/navigation/index', 1, 'C', '0', '0', 'system:navigation:list', 'carbon:collapse-categories', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539807, 'Banner管理', 2015147980830539804, 3, 'banner', 'system/site/banner/index', 1, 'C', '0', '0', 'system:banner:list', 'carbon:flag', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539808, '产品管理', 2015147980830539804, 4, 'ecosystem', 'system/site/ecosystem/index', 1, 'C', '0', '0', 'system:ecosystem:list', 'carbon:application-web', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539809, 'API文档', 2015147980830539804, 5, 'apiDoc', 'system/site/apiDoc/index', 1, 'C', '0', '0', 'system:apiDoc:list', 'carbon:api', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539810, '网站设置查询', 2015147980830539805, 1, '', '', 1, 'F', '0', '0', 'system:siteConfig:query', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539811, '网站设置编辑', 2015147980830539805, 2, '', '', 1, 'F', '0', '0', 'system:siteConfig:edit', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539812, '导航查询', 2015147980830539806, 1, '', '', 1, 'F', '0', '0', 'system:navigation:query', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539813, '导航新增', 2015147980830539806, 2, '', '', 1, 'F', '0', '0', 'system:navigation:add', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539814, '导航编辑', 2015147980830539806, 3, '', '', 1, 'F', '0', '0', 'system:navigation:edit', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539815, '导航删除', 2015147980830539806, 4, '', '', 1, 'F', '0', '0', 'system:navigation:remove', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539816, 'Banner查询', 2015147980830539807, 1, '', '', 1, 'F', '0', '0', 'system:banner:query', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539817, 'Banner新增', 2015147980830539807, 2, '', '', 1, 'F', '0', '0', 'system:banner:add', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539818, 'Banner编辑', 2015147980830539807, 3, '', '', 1, 'F', '0', '0', 'system:banner:edit', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539819, 'Banner删除', 2015147980830539807, 4, '', '', 1, 'F', '0', '0', 'system:banner:remove', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539820, '产品查询', 2015147980830539808, 1, '', '', 1, 'F', '0', '0', 'system:ecosystem:query', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539821, '产品新增', 2015147980830539808, 2, '', '', 1, 'F', '0', '0', 'system:ecosystem:add', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539822, '产品编辑', 2015147980830539808, 3, '', '', 1, 'F', '0', '0', 'system:ecosystem:edit', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539823, '产品删除', 2015147980830539808, 4, '', '', 1, 'F', '0', '0', 'system:ecosystem:remove', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539824, 'API文档查询', 2015147980830539809, 1, '', '', 1, 'F', '0', '0', 'system:apiDoc:query', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);
INSERT INTO `sys_menu` VALUES (2015147980830539825, 'API文档编辑', 2015147980830539809, 2, '', '', 1, 'F', '0', '0', 'system:apiDoc:edit', '#', '2026-05-09 00:21:13', NULL, '2026-05-09 00:21:13', NULL, 0);

-- ----------------------------
-- Records of sys_news
-- ----------------------------
INSERT INTO `sys_news` VALUES (1, 'MinerU v2.5 正式发布：支持化学分子式识别与 MCP 协议', '本次更新带来了全新的化学分子式识别能力，并原生支持 MCP 协议，让 AI Agent 可以直接调用文档解析能力。', '## 概述\n\nMinerU v2.5 是一次重大版本更新，带来了多项突破性功能。\n\n### 化学分子式识别\n\n全新的化学分子式识别模块，能够精确检测和识别文档中的分子结构图：\n\n- **分子检测**：精确的分子检测与定位\n- **结构识别**：SOTA 性能的分子结构图识别能力\n- **原子与键**：原子和键的识别与原始图像严格对应\n\n### MCP 协议支持\n\n原生支持 Model Context Protocol (MCP)，让 AI Agent 可以直接调用 MinerU 的文档解析能力：\n\n- Claude Desktop 可通过 MCP 直接调用\n- 支持 Dify、Coze 等平台的 Agent 集成\n- 免登录 Flash 模式，适合高频自动化工作流\n\n### 性能优化\n\n- 大文档处理速度提升 40%\n- 内存占用降低 25%\n- 支持批量并发处理\n\n### 更多格式支持\n\n- 新增 PPT 文档解析\n- 改进图片 OCR 识别\n- 优化网页 URL 内容提取', NULL, '版本发布', '[\"MinerU\",\"v2.5\",\"化学识别\",\"MCP\"]', '0', 12581, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:44:56', '2026-04-15 10:00:00');
INSERT INTO `sys_news` VALUES (2, '如何用 MinerU 构建 RAG 知识库的文档预处理管道', '本文详细介绍如何使用 MinerU 将 PDF 文档转换为结构化 Markdown，为 RAG 系统提供高质量的数据输入。', '## 背景\n\nRAG（检索增强生成）系统对文档质量要求极高。传统的 PDF 解析工具往往丢失排版结构，导致文本块破碎、表格数据混乱。MinerU 专为 AI 应用设计，可以输出高度结构化的 Markdown/JSON 格式。\n\n## 核心步骤\n\n### 1. 文档解析\n\n使用 MinerU 将 PDF 转换为结构化 Markdown：\n\n```bash\ncurl -X POST https://api.xinghuitec.com/v1/parse \\\n  -H \"Authorization: Bearer YOUR_API_KEY\" \\\n  -F \"file=@document.pdf\" \\\n  -F \"output_format=markdown\"\n```\n\n### 2. 文本分块\n\n解析得到的 Markdown 保留了完整的标题层级，可以按标题自然分块。\n\n### 3. 向量化与存储\n\n将分块后的文本进行 Embedding 并存入向量数据库。\n\n## 总结\n\nMinerU 的结构化输出使得 RAG 系统的文档预处理变得简单高效。', NULL, '技术分享', '[\"RAG\",\"知识库\",\"文档解析\",\"Embedding\"]', '0', 8920, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-04-10 14:30:00');
INSERT INTO `sys_news` VALUES (3, 'MinerU 与 Dify 深度集成指南', '通过 MinerU 插件，你可以在 Dify 工作流中直接解析 PDF 文档，提取结构化信息用于后续处理。', '## 集成概述\n\nMinerU 已上架 Dify 插件市场，安装后即可在工作流中使用。\n\n## 安装与配置\n\n1. 在 Dify 插件市场中搜索 \"MinerU\"\n2. 点击安装并配置 API Key\n3. 在工作流中拖入 MinerU 节点\n\n## 使用示例\n\n将 MinerU 节点放置在知识库导入流程之前，可以实现：\n\n- 自动将上传的 PDF 转换为结构化 Markdown\n- 表格自动提取为 CSV 格式\n- 公式转换为 LaTeX\n\n这大大提升了 Dify 知识库的文档处理能力。', NULL, '产品动态', '[\"Dify\",\"集成\",\"工作流\",\"插件\"]', '0', 6540, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-04-05 09:15:00');
INSERT INTO `sys_news` VALUES (4, '文档解析技术综述：从传统 OCR 到多模态大模型', '回顾文档解析技术的发展历程，探讨多模态大模型在文档理解领域的最新进展和未来方向。', '## 技术演进\n\n### 第一代：规则引擎\n\n基于模板匹配和规则的方法，适用于固定格式文档。\n\n### 第二代：OCR + NLP\n\n结合光学字符识别和自然语言处理，可以处理扫描件和图片。\n\n### 第三代：深度学习\n\n基于 CNN/RNN 的文档布局分析，显著提升了复杂排版的识别能力。\n\n### 第四代：多模态大模型\n\nMinerU 采用最新的多模态大模型技术，能够同时理解文档中的文字、表格、公式、图片等多种元素，并输出高度结构化的机器可读数据。\n\n## 未来展望\n\n随着 Agent 技术的兴起，文档解析正在从\"人工辅助\"向\"全自动 Agent 处理\"转变。MinerU 原生支持 MCP 协议，正是为了满足这一趋势。', NULL, '行业资讯', '[\"OCR\",\"多模态\",\"大模型\",\"技术综述\"]', '0', 4320, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-03-28 16:00:00');
INSERT INTO `sys_news` VALUES (5, 'MinerU 在学术论文解析中的应用实践', '多家高校和研究机构采用 MinerU 进行学术论文批量解析，显著提升了文献综述和研究数据提取的效率。', '## 应用场景\n\n### 文献综述\n\n研究人员使用 MinerU 批量解析 arXiv 上的论文 PDF，将公式和表格提取为结构化数据，大大加快了文献综述的效率。\n\n### 实验数据提取\n\n化学和生物学研究者利用 MinerU 的分子式识别功能，从大量论文中自动提取化合物结构和实验参数。\n\n### 知识图谱构建\n\n基于 MinerU 的结构化输出，可以自动构建学术知识图谱，发现跨学科的研究关联。\n\n## 用户反馈\n\n> \"MinerU 让我们的文献处理效率提升了 10 倍以上。\" —— 某高校 AI Lab 研究员', NULL, '产品动态', '[\"学术\",\"论文解析\",\"知识图谱\",\"文献综述\"]', '0', 3560, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-03-20 11:30:00');

-- ----------------------------
-- Records of sys_notice
-- ----------------------------

-- ----------------------------
-- Records of sys_page
-- ----------------------------
INSERT INTO `sys_page` VALUES (1, 'home', 'hero', 'title', 'text', '智能文档解析', 1, '0', '首页Hero标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (2, 'home', 'hero', 'subtitle', 'text', '基于多模态大模型的新一代文档解析引擎，支持PDF/图片/PPT等多种格式，输出结构化Markdown/JSON', 2, '0', '首页Hero副标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (3, 'home', 'hero', 'cta_download', 'text', '下载客户端', 3, '0', '下载按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (4, 'home', 'hero', 'cta_online', 'text', '在线使用', 4, '0', '在线使用按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (5, 'home', 'features', 'title', 'text', '核心能力', 1, '0', '特性区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (6, 'home', 'features', 'description', 'text', '六大核心能力，全面覆盖文档解析场景', 2, '0', '特性区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (7, 'home', 'extraction', 'title', 'text', '复杂内容提取', 1, '0', '提取区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (8, 'home', 'extraction', 'description', 'text', '表格还原、公式转换、化学分子式识别', 2, '0', '提取区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (9, 'home', 'community', 'title', 'text', '加入社区', 1, '0', '社区区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (10, 'home', 'community', 'description', 'text', 'MinerU 已在 GitHub 开源，获得 60k+ Stars，欢迎加入社区', 2, '0', '社区区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (11, 'home', 'community', 'cta_text', 'text', '访问 GitHub', 3, '0', '社区按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (12, 'home', 'community', 'cta_url', 'text', 'https://github.com/opendatalab/MinerU', 4, '0', '社区按钮链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (13, 'footer', 'tools', 'title', 'text', '工具', 1, '0', '页脚工具区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (14, 'footer', 'tools', 'links', 'json', '[{\"text\":\"在线体验\",\"url\":\"/\"},{\"text\":\"客户端下载\",\"url\":\"/client\"},{\"text\":\"API服务\",\"url\":\"/api\"}]', 2, '0', '页脚工具链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (15, 'footer', 'legal', 'title', 'text', '法律协议', 3, '0', '页脚法律区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (16, 'footer', 'legal', 'links', 'json', '[{\"text\":\"服务协议\",\"url\":\"/agreement\"},{\"text\":\"隐私政策\",\"url\":\"/privacy\"}]', 4, '0', '页脚法律链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (17, 'footer', 'contact', 'title', 'text', '联系我们', 5, '0', '页脚联系区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (18, 'footer', 'contact', 'content', 'text', '邮箱：contact@xinghuitec.com', 6, '0', '页脚联系方式', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (19, 'footer', 'qrcode', 'image_url', 'image', '', 7, '0', '页脚二维码图片URL', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (20, 'api', 'intro', 'title', 'text', 'API 服务', 1, '0', 'API页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (21, 'api', 'intro', 'description', 'text', '通过 RESTful API 将 MinerU 的文档解析能力集成到您的应用中', 2, '0', 'API页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (22, 'client', 'intro', 'title', 'text', '客户端下载', 1, '0', '下载页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (23, 'client', 'intro', 'description', 'text', '选择适合您平台的 MinerU 桌面客户端', 2, '0', '下载页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (24, 'ecosystem', 'intro', 'title', 'text', '生态集成', 1, '0', '生态页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (25, 'ecosystem', 'intro', 'description', 'text', 'MinerU 与主流 AI Agent 平台的深度集成', 2, '0', '生态页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');

-- ----------------------------
-- Records of sys_role
-- ----------------------------
INSERT INTO `sys_role` VALUES (2016901862971731969, '000000', '管理员', 'admin', '1', 1, '2026-01-29 23:50:51', NULL, '2026-01-29 23:52:30', NULL, 0);
INSERT INTO `sys_role` VALUES (2016902236600291329, '000000', '超级管理员', 'super_admin', '1', 1, '2026-01-29 23:52:20', NULL, '2026-01-29 23:52:20', NULL, 0);
INSERT INTO `sys_role` VALUES (2016956169284788226, '000000', '用户', 'user', '1', 1, '2026-01-30 03:26:39', NULL, '2026-01-30 03:26:38', NULL, 0);

-- ----------------------------
-- Records of sys_role_menu
-- ----------------------------
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 100);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 100);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539804);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539804);
INSERT INTO `sys_role_menu` VALUES (2016956169284788226, 1);
INSERT INTO `sys_role_menu` VALUES (2016956169284788226, 100);
INSERT INTO `sys_role_menu` VALUES (2016956169284788226, 1000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 4);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 101);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 102);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 103);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 200);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 201);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 202);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 300);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 301);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1007);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1008);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1009);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1010);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1011);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1012);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1013);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1014);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1015);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1016);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1017);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1018);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1019);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1020);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1021);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2007);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2008);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2009);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2010);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2011);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2012);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2013);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2014);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539787);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539788);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539789);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539790);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539791);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539792);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539793);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539794);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539795);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539796);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539801);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539802);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539803);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539805);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539806);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539807);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539808);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539809);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539810);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539811);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539812);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539813);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539814);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539815);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539816);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539817);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539818);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539819);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539820);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539821);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539822);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539823);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539824);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539825);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 4);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 101);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 102);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 103);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 200);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 201);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 202);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 300);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 301);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1009);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1010);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1011);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1012);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1013);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1014);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1015);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1016);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1017);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1018);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1019);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1020);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1021);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2009);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2010);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2011);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2012);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2013);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2014);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539780);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539781);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539787);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539788);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539789);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539790);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539791);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539792);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539793);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539794);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539795);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539796);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539801);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539802);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539803);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539805);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539806);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539807);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539808);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539809);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539810);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539811);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539812);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539813);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539814);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539815);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539816);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539817);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539818);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539819);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539820);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539821);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539822);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539823);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539824);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539825);

-- ----------------------------
-- Records of sys_tenant
-- ----------------------------
INSERT INTO `sys_tenant` VALUES (1, '000000', '', '', '默认租户', '', '', '', '', NULL, NULL, -1, '0', '2026-05-17 22:39:42', NULL, NULL, NULL, 0);

-- ----------------------------
-- Records of sys_tenant_package
-- ----------------------------
INSERT INTO `sys_tenant_package` VALUES (1, '默认套餐', NULL, '', '0', '2026-05-17 22:39:42', NULL, NULL, NULL, 0);

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, '000000', 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '超级管理员', 'admin@xinghui.local', '13800000000', '', 1, '', NULL, '2026-01-01 00:00:00', '-1', '2026-01-01 00:00:00', '-1', 0);
INSERT INTO `sys_user` VALUES (2, '000000', 'user', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '演示账号', 'user@xinghui.local', '13800000001', '', 1, '', NULL, '2026-01-01 00:00:00', '-1', '2026-01-01 00:00:00', '-1', 0);

-- ----------------------------
-- Records of sys_user_role
-- ----------------------------
INSERT INTO `sys_user_role` VALUES (1, 2016902236600291329);
INSERT INTO `sys_user_role` VALUES (1, 2016901862971731969);
INSERT INTO `sys_user_role` VALUES (2, 2016956169284788226);


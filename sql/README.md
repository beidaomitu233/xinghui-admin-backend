# 数据库脚本说明（Flyway 版本化管理）

自本版本起，数据库结构与种子数据统一由 **Flyway** 管理，应用启动时自动执行迁移。
本目录下的 `xinghui_admin.sql` 为历史全量导出脚本，仅作归档参考，**不再维护**。

## 目录结构

```
system-admin/src/main/resources/db/migration/   # Flyway 迁移脚本（随应用打包）
├── V1__baseline_schema.sql                     # 基线：全部 sys_* 表结构（19 张）
├── V2__baseline_data.sql                       # 基线：菜单/角色/租户/参数种子数据
└── V<yyyyMMdd>_<nn>__<描述>.sql                # 后续增量迁移（示例见下）
sql/
├── README.md                                   # 本说明
└── xinghui_admin.sql                           # 历史全量脚本（归档，勿再使用）
```

## 使用方式

### 全新环境初始化

只需创建空数据库，应用启动时 Flyway 自动建表并写入种子数据：

```sql
CREATE DATABASE xinghui_admin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

默认账号（登录使用**手机号+密码**，**生产环境务必立即修改**）：

| 账号 | 登录手机号 | 密码 | 角色 |
| :--- | :--- | :--- | :--- |
| admin | 13800000000 | admin123 | 超级管理员、管理员 |
| user | 13800000001 | admin123 | 用户（演示） |

### 存量环境接入

已有数据的库首次启动时，Flyway 检测到非空 schema 会自动打基线（`baseline-version=2`），
跳过 V1/V2，只执行之后的增量脚本，存量数据不受影响。

> ⚠️ 历史遗留：旧版 `sys_user.user_id` 为 varchar(64) UUID，当前代码已重构为 bigint 雪花ID。
> 使用旧 UUID 数据的开发库与新代码不兼容，建议删除重建（由 Flyway 重新初始化），
> 或自行编写数据迁移完成 UUID → 雪花ID 转换（需同步更新 sys_user_role.user_id）。

## 新增迁移脚本规范

1. 文件放在 `system-admin/src/main/resources/db/migration/`；
2. 命名：`V<yyyyMMdd>_<序号>__<英文小写下划线描述>.sql`，例如
   `V20260726_01__add_login_log_table.sql`；
3. **已合并进 dev 的脚本一律不可修改**（Flyway 校验 checksum，改动会导致启动失败），
   写错了就再加一个新脚本修正；
4. 脚本须兼容 MySQL 8.0，避免依赖特定库名；
5. DDL 与 DML 尽量分开为两个脚本，便于回溯。

## 相关配置

Flyway 配置位于 `system-admin/src/main/resources/application.yml` 的 `spring.flyway` 节点，
连接信息复用主库环境变量：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`。
设置 `FLYWAY_ENABLED=false` 可临时关闭自动迁移。

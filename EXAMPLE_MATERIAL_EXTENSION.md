# 资料示例已退役

2026-10-12，UI-04 按用户确认移除资料示例页面、API、权限和 example_material 表。不要按旧教程创建模块或执行字段扩展练习。

历史 versioned 迁移保持不变。安装过可选示例的库升级时继续配置 db/optional-migration，校验历史 R__example_material.sql；同名 repeatable 现只执行 DROP TABLE IF EXISTS example_material，避免 V140 清理后重建。新库是否加载可选 location，最终都没有资料示例表或菜单。

文件管理与 OSS 上传、归属检查及下载接口保留。业务扩展参考 docs/extensions.md 和资讯模块。

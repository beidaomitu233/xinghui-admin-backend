# 本地构建与自动部署

在本机编译后端 JAR、前端和文档，并构建 Linux amd64 镜像。镜像标签使用 `ghcr.io/仓库:ci-<CI运行号>-<源码SHA>`，标签中的来源、源码版本和 CI 运行号须写入镜像标签。将单个镜像 `docker save` 后压缩为 `image-backend-<源码SHA>.tar.gz` 或 `image-web-<源码SHA>.tar.gz`，计算文件 SHA256，作为对应仓库的 Release 资源上传。

在对应仓库运行 **Deploy locally built backend/web**，填写受保护分支的源码 SHA、该提交成功的 push CI 运行号、Release 标签和归档 SHA256。Actions 校验当前分支、CI、归档、镜像来源及架构，再上传到镜像仓库并通过受限 SSH 命令部署。`SERVER_DEPLOY_ENABLED` 为 `false` 时只发布镜像。

部署服务器只拉取已完成的镜像、启动服务并执行健康检查。前后端分别运行发布流程，先后端、再前端；后端迁移失败不会自动回退数据库。

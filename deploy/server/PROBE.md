# 首步：只读探测

服务器参数尚未提供。请用户在目标服务器自己的终端运行 `probe.sh` 的已审核内容（或将整段粘贴给 `sh`），把输出回传 PM。脚本不安装、不使用 sudo、不修改文件、不调用模型、不向外部站点发请求。Docker 仅显式读取本机 Unix socket；无权限时跳过。

未将此脚本上传或远程执行，也不要求把任何真实秘密放进命令。没有脚本文件时，可先仅运行：

```sh
uname -srm
sed -n '/^\(ID\|VERSION_ID\|PRETTY_NAME\)=/p' /etc/os-release
id
free -m
df -h / /var /opt
command -v bash curl python3 flock jq openssl docker nginx caddy
docker --version
docker compose version --short
ss -ltn
```

命令缺失、路径不存在或 Docker 权限不足如实回传，不要为使探测通过而安装软件。额外只需说明：计划域名和公网入口/TLS由谁管理、现有 MySQL/Redis/MinIO 是否同机或外部、是否已有需迁移的数据（不发送连接密码）。Windows/非 Linux 服务器请仅回传系统版本，当前工件的 Linux amd64/Bash/Docker Compose 形态不能直接套用。

不要回传 `/etc/shadow`、SSH 私钥、`.env`、完整 `env`、`docker inspect` 或业务数据库内容。域名/DNS/端口开放及 SSH 已知主机指纹必须经服务器控制台等可信渠道核对后才能配置部署。

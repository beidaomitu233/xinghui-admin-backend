# 服务器断连处理

适用：OpenCloudOS 9、systemd、Docker、宿主机 Nginx。先记录故障时间，再按入口逐层检查。

## 先判断哪一层故障

| 现象 | 检查位置 |
| --- | --- |
| SSH、HTTP、HTTPS 同时无响应 | 腾讯云 VNC/自动化助手；整机压力、磁盘、带宽和连接队列 |
| 公网失败，本机 HTTP 正常 | 宿主机 Nginx、云安全组、带宽超限/丢包及云平台事件 |
| SSH 正常，应用端口失败 | 对应容器、依赖服务与应用日志 |
| 域名失败，固定源站 IP 与正确 Host/SNI 正常 | DNS、IPv6、证书和域名代理 |

公网 SSH 失败时，使用腾讯云控制台 VNC 或自动化助手。重启前能采集就先采集；已重启则优先检查上一次启动记录。

## 一次性采集

在服务器 root 控制台执行已安装的采集器：

```bash
python3 -I /usr/local/libexec/xinghui-host/collect-host.py --record
```

完整 JSON 保存在 `/var/lib/xinghui-host-ops/incidents/`，终端返回文件位置与状态概览。采集器读取上一启动内核/SSH 事件、CPU/内存/I/O 压力、磁盘与 inode、主要进程、端口/队列计数、容器限额与 OOM 状态，Docker 固定读取本机 Unix socket。报告不包含应用配置、环境变量、数据库、私钥或进程完整参数。

仓库版本可直接运行 `python3 collect-host.py`，输出 JSON；`--summary` 仅使用普通账号可读的系统计数和本机公开 HTTP 接口。

## Actions 状态检查

在后端仓库打开 **Actions → Server diagnostics → Run workflow → master**。工作流复用现有部署连接，执行固定 `diagnose` 命令，显示内存、磁盘、负载和本机 Web/API 状态，不发布或重启服务。

`diagnose` 以部署账号自身身份运行，没有新增 sudo 规则。它不会获取完整日志；完整记录仍通过 root 控制台采集。SSH 本身无响应时，改用腾讯云 VNC/自动化助手。

服务器需安装 root 所有的 `collect-host.py` 至 `/usr/local/libexec/xinghui-host/`（目录、脚本 0755），更新固定入口 `ssh-entry.sh`。原部署命令及已有账号密钥继续沿用。工作流使用已有 `production` Environment 的五项 `DEPLOY_*` Secrets。

也可先执行这组简短检查：

```bash
date -Is
uptime
free -m
vmstat 1 5
df -h / /var /www
df -i / /var /www
cat /proc/pressure/{cpu,memory,io}
journalctl --list-boots --no-pager
journalctl -k -b -1 --no-pager -n 200
ss -s
```

## 定位和恢复

| 证据 | 处理 |
| --- | --- |
| OOM / Killed process | 定位被杀进程及所属应用，核对堆、原生内存和容器限额；为各业务分配资源预算 |
| MemAvailable 下降、持续换页、memory PSI 升高 | 查增长进程、堆外内存和并发任务；限制对应业务的内存与并发 |
| 大量 D 状态、wa/io PSI 升高、磁盘错误 | 查磁盘延迟、云硬盘限额、备份/日志任务；出现设备或文件系统错误时转云平台处理 |
| inode/磁盘接近满 | 按目录确定占用来源，先归档，再设置日志轮转和保留期 |
| 出带宽顶到购买上限、丢包 | 定位流量来源，限制对应下载/同步任务；按实际业务需要调整带宽 |
| ListenDrops/ListenOverflows、conntrack 满 | 查连接来源、连接数量和对应服务，修复异常连接或限流；按容量调整队列 |
| 仅 sshd 异常 | 保留控制台会话，先运行 `sshd -t`，再处理配置或服务；不要一律增大 MaxStartups |
| 单个容器异常、整机正常 | 保存该容器状态和日志，处理依赖或资源问题，再恢复该服务 |

重启后看到资源空闲，不能据此排除重启前的瞬时故障。容器 OOM 与整机 OOM 分开判断。

星辉本机入口检查：

```bash
curl --noproxy '*' -sS --connect-timeout 2 --max-time 5 -o /dev/null -w 'WEB=%{http_code}\n' http://127.0.0.1:18180/
curl --noproxy '*' -sS --connect-timeout 2 --max-time 5 -o /dev/null -w 'API=%{http_code}\n' http://127.0.0.1:17799/auth/wechat/capability
docker ps -a --filter label=com.docker.compose.project=xinghui-server --format 'table {{.Names}}\t{{.Status}}'
```

## 留下下次故障的证据

- journald 持久化并设置总量、保留期和同步间隔。调整前归档原始日志，验证归档后再清理旧记录；确认重启后仍可读取历史。
- 使用发行版 sysstat 按分钟采集 CPU、内存、换页、I/O、网络和 TCP 指标。`HISTORY>28` 使用 `saYYYYMMDD` 文件，可与既有 `saDD` 历史并存；`SADC_OPTIONS="-S DISK,SNMP"` 包含磁盘和 TCP 数据。
- PSI 读数需同时核对 `/sys/fs/cgroup/cgroup.pressure`，关闭时的全零不代表没有压力。按内核支持启用根组统计即可，无需启用全部子组。
- 腾讯云监控关注 CPU、内存、磁盘、出带宽利用率及带宽超限丢包，并配置告警接收人。
- 外部监控分别检测 HTTPS 和 SSH 握手，结合本机 HTTP 检查区分应用故障与整机/网络故障。
- 日志与指标采集需要验证落盘和重启后的可读性；不要只确认进程已启动。

资源、并发和超时参数根据采样调整。保留 GitHub Actions 的暂停开关，确认服务器稳定后恢复部署。

后台计算/渲染服务优先采用 `MemoryHigh` 柔性节流和 CPU/I/O 权重。设置硬上限前先测量启动峰值；SSH 等管理入口可保留适当的内存保护和调度权重。使用 systemd drop-in 保存，运行时应用后核对进程 PID 与业务健康。

参考：[腾讯云无法登录排查](https://cloud.tencent.com/document/product/213/35574)、[带宽占满排查](https://cloud.tencent.com/document/product/213/10334)、[Linux PSI](https://docs.kernel.org/accounting/psi.html)、[journald 持久化](https://www.freedesktop.org/software/systemd/man/latest/journald.conf.html)、[sysstat](https://sysstat.github.io/)。

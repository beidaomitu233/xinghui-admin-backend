#!/usr/bin/env python3
"""Read-only host incident snapshot. Never reads application config or process argv."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def run(*args, timeout=8, limit=65536):
    if not shutil.which(args[0]):
        return {"unavailable": args[0]}
    try:
        result = subprocess.run(args, capture_output=True, text=True,
                                errors="replace", timeout=timeout,
                                env=dict(os.environ, LC_ALL="C"))
        return {"exit": result.returncode, "stdout": result.stdout[:limit],
                "truncated": len(result.stdout) > limit,
                "stderr": result.stderr[-2048:]}
    except subprocess.TimeoutExpired:
        return {"timeoutSeconds": timeout}
    except OSError as error:
        return {"error": str(error)}


def network():
    rows = {}
    for line in (read('/proc/net/dev') or '').splitlines()[2:]:
        name, values = line.split(':', 1)
        values = list(map(int, values.split()))
        rows[name.strip()] = dict(rxBytes=values[0], rxErrors=values[2],
                                  rxDrops=values[3], txBytes=values[8],
                                  txErrors=values[10], txDrops=values[11])
    return rows


def kernel_events(boot):
    result = run('journalctl', '-k', '-b', str(boot), '--no-pager',
                 '-n', '3000', '-o', 'short-iso', timeout=12, limit=1024 * 1024)
    pattern = re.compile(r'oom|out of memory|killed process|blocked for more than|'
                         r'lockup|watchdog|hung task|I/O error|EXT4-fs error|'
                         r'XFS.*error|nvme.*timeout|virtio.*error|'
                         r'NETDEV WATCHDOG|nf_conntrack.*full|SYN flooding', re.I)
    result['stdout'] = '\n'.join(line for line in result.get('stdout', '').splitlines()
                                 if pattern.search(line))[-24000:]
    return result


def containers():
    docker = ('docker', '--host', 'unix:///var/run/docker.sock')
    names = run(*docker, 'ps', '-a', '--format', '{{.Names}}')
    items = []
    for name in names.get('stdout', '').splitlines()[:30]:
        result = run(*docker, 'inspect', '--format',
                     '{{json .State}}|{{.RestartCount}}|{{.HostConfig.Memory}}|'
                     '{{.HostConfig.MemorySwap}}|{{.HostConfig.PidsLimit}}|'
                     '{{.HostConfig.NanoCpus}}', name)
        if 'timeoutSeconds' in result:
            items.append(dict(name=name, inspection='docker daemon timeout'))
            break
        try:
            state, restart, memory, swap, pids, cpu = result['stdout'].strip().split('|')
            state = json.loads(state)
            items.append(dict(name=name, running=state.get('Running'),
                              status=state.get('Status'), oomKilled=state.get('OOMKilled'),
                              exitCode=state.get('ExitCode'),
                              health=state.get('Health', {}).get('Status'),
                              startedAt=state.get('StartedAt'), restartCount=int(restart),
                              memoryLimit=int(memory), memorySwapLimit=int(swap),
                              pidsLimit=pids, nanoCpus=int(cpu)))
        except (KeyError, ValueError):
            items.append(dict(name=name, inspection='unavailable', exit=result.get('exit')))
    return dict(listing=names, containers=items,
                usage=run(*docker, 'stats', '--no-stream', '--format',
                          '{{.Name}} {{.CPUPerc}} {{.MemUsage}} {{.PIDs}}', timeout=12))


def status_summary():
    metrics = {key: int(value) for key, value in re.findall(
        r'^(\w+):\s+(\d+)', read('/proc/meminfo') or '', re.M)}
    disk = shutil.disk_usage('/')
    endpoints = {}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for name, url in (
            ('web', 'http://127.0.0.1:18180/'),
            ('api', 'http://127.0.0.1:17799/auth/wechat/capability')):
        try:
            with opener.open(urllib.request.Request(url, method='HEAD'), timeout=3) as response:
                endpoints[name] = response.status
        except Exception:
            endpoints[name] = None
    return dict(schemaVersion=1, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                uptimeSeconds=float(read('/proc/uptime').split()[0]),
                cpuCount=os.cpu_count(), load=list(os.getloadavg()),
                availableMemoryMiB=round(metrics.get('MemAvailable', 0) / 1024),
                swapUsedMiB=round((metrics.get('SwapTotal', 0) - metrics.get('SwapFree', 0)) / 1024),
                diskUsedPercent=round(disk.used / disk.total * 100, 1),
                endpoints=endpoints, privileged=os.geteuid() == 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--record', action='store_true',
                      help='Save the full report on the server and print a safe status summary')
    mode.add_argument('--summary', action='store_true',
                      help='Read public host counters and local HTTP status without elevated privileges')
    options = parser.parse_args()
    if sys.platform != 'linux':
        raise SystemExit('Run on the affected Linux host with python3 collect-host.py')
    if options.summary:
        print(json.dumps(status_summary()))
        return
    if options.record and os.geteuid() != 0:
        raise SystemExit('--record requires root')
    if options.record:
        import fcntl
        descriptor = os.open('/run/lock/xinghui-host-diagnose.lock',
                             os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        record_lock = os.fdopen(descriptor, 'w')
        try:
            fcntl.flock(record_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit('A host diagnostic is already running')
    before = network()
    started = time.monotonic()
    report = dict(timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  root=os.geteuid() == 0, kernel=os.uname().release,
                  cpuCount=os.cpu_count(), bootId=read('/proc/sys/kernel/random/boot_id'),
                  uptime=read('/proc/uptime'), load=read('/proc/loadavg'),
                  memory=read('/proc/meminfo'), swaps=read('/proc/swaps'),
                  pressureAccountingEnabled=read('/sys/fs/cgroup/cgroup.pressure'),
                  pressure={key: read('/proc/pressure/' + key) for key in ('cpu', 'memory', 'io')})
    report['boots'] = run('journalctl', '--list-boots', '--no-pager', '-n', '5')
    report['clock'] = run('timedatectl', 'show', '-p', 'Timezone', '-p', 'NTPSynchronized')
    report['kernelPreviousBoot'] = kernel_events(-1)
    report['kernelCurrentBoot'] = kernel_events(0)
    report['sshPreviousBoot'] = run('journalctl', '-b', '-1', '-u', 'sshd',
                                    '--no-pager', '-n', '100', '-o', 'short-iso')
    report['disk'] = run('df', '-hPT', '/', '/var', '/opt', '/www')
    report['inodes'] = run('df', '-iP', '/', '/var', '/opt', '/www')
    report['vmstat'] = run('vmstat', '-w', '1', '4')
    report['iostat'] = run('iostat', '-xz', '1', '2')
    # comm and wchan only: arguments/environment can contain credentials.
    columns = 'pid,ppid,user,comm,stat,nlwp,pcpu,pmem,rss,wchan:24'
    report['processMemory'] = run('ps', '-eo', columns,
                                    '--sort=-rss')
    report['processMemory']['stdout'] = '\n'.join(
        report['processMemory'].get('stdout', '').splitlines()[:26])
    report['processCpu'] = run('ps', '-eo', columns, '--sort=-pcpu')
    report['processCpu']['stdout'] = '\n'.join(
        report['processCpu'].get('stdout', '').splitlines()[:16])
    report['socketSummary'] = run('ss', '-s')
    report['listeners'] = run('ss', '-lntp')
    report['networkCounters'] = run('nstat', '-asz', 'TcpExtListenOverflows',
                                     'TcpExtListenDrops', 'TcpExtTCPReqQFullDrop',
                                     'TcpExtTCPTimeouts', 'TcpRetransSegs')
    report['limits'] = {key: read('/proc/sys/' + key) for key in (
        'net/netfilter/nf_conntrack_count', 'net/netfilter/nf_conntrack_max',
        'net/core/somaxconn', 'fs/file-nr', 'fs/file-max', 'kernel/threads-max')}
    report['sshService'] = run('systemctl', 'show', 'sshd', '-p', 'TasksCurrent',
                                '-p', 'TasksMax', '-p', 'LimitNOFILE', '-p', 'MemoryCurrent')
    config = run('sshd', '-T')
    allowed = {'maxstartups', 'persourcemaxstartups', 'logingracetime', 'maxsessions',
               'usedns', 'usepam', 'clientaliveinterval', 'clientalivecountmax'}
    config['stdout'] = '\n'.join(line for line in config.get('stdout', '').splitlines()
                                 if line.split() and line.split()[0] in allowed)
    report['sshLimits'] = config
    report['failedUnits'] = run('systemctl', '--failed', '--no-pager')
    report['containers'] = containers()
    report['journalUsage'] = run('journalctl', '--disk-usage')
    report['persistentJournalDirectory'] = Path('/var/log/journal').is_dir()
    report['sysstat'] = dict(installed=shutil.which('sar') is not None,
                            files=[p.name for p in Path('/var/log/sa').glob('sa*')][:40])
    after = network()
    duration = time.monotonic() - started
    report['networkSample'] = dict(seconds=round(duration, 2), interfaces={
        name: {key + 'PerSecond': round((values[key] - before.get(name, values)[key]) / duration, 2)
               for key in values} for name, values in after.items()})
    report['seconds'] = round(duration, 2)
    if options.record:
        folder = Path('/var/lib/xinghui-host-ops/incidents')
        assert not folder.is_symlink()
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        assert folder.stat().st_uid == 0 and not folder.stat().st_mode & 0o022
        file = folder / ('incident-' + datetime.datetime.now(datetime.timezone.utc)
                         .strftime('%Y%m%dT%H%M%S') + '-' + str(time.time_ns()) + '.json')
        os.umask(0o077)
        with file.open('x') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
        summary = status_summary()
        summary['reportPath'] = str(file)
        print(json.dumps(summary))
        return
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

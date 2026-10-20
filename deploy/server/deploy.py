#!/usr/bin/env python3
"""Root-owned, fixed-path server executor. No build, DB rollback or shell evaluation."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path("/opt/xinghui")
STATE = Path("/var/lib/xinghui")
POLICY = Path("/etc/xinghui/deploy-policy.json")
LOCK = Path("/var/lock/xinghui-deploy.lock")


def command(*args, capture=False, env=None):
    return subprocess.run(args, check=True, text=True, env=env,
                          stdout=subprocess.PIPE if capture else None).stdout


def receipt(component, value, policy):
    assert component in ("backend", "web"), "invalid component"
    assert set(value) == {"image", "sha", "ciRun"}, "unexpected receipt fields"
    repo = policy[component]["repository"]
    assert re.fullmatch(re.escape(repo) + r"@sha256:[0-9a-f]{64}", value["image"]), "invalid image"
    assert re.fullmatch(r"[0-9a-f]{40}", value["sha"]), "invalid source SHA"
    assert re.fullmatch(r"[0-9]+", str(value["ciRun"])), "invalid CI run"


def save(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)


def pull(component, value, policy):
    receipt(component, value, policy)
    command("docker", "pull", "--platform", "linux/amd64", value["image"])
    info = json.loads(command("docker", "image", "inspect", value["image"], capture=True))[0]
    labels = info["Config"].get("Labels") or {}
    assert info["Architecture"] == "amd64" and info["Os"] == "linux", "image platform mismatch"
    assert labels.get("org.opencontainers.image.source") == policy[component]["source"], "source label mismatch"
    assert labels.get("org.opencontainers.image.revision") == value["sha"], "revision label mismatch"
    assert labels.get("io.xinghui.ci-run") == str(value["ciRun"]), "CI receipt mismatch"


def compose(manifest, policy, *args, capture=False):
    env = dict(os.environ, BACKEND_IMAGE=manifest["backend"]["image"],
               WEB_IMAGE=manifest["web"]["image"])
    return command("docker", "compose", "--project-name", "xinghui-server", "--project-directory", str(ROOT),
                   "-f", str(ROOT / "compose.yaml"), *args, capture=capture, env=env)


def healthy(manifest, policy, service, seconds=210):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        ids = compose(manifest, policy, "ps", "-q", service, capture=True).split()
        if len(ids) == 1:
            info = json.loads(command("docker", "inspect", ids[0], capture=True))[0]
            if info["State"].get("Health", {}).get("Status") == "healthy":
                return
        time.sleep(5)
    raise RuntimeError(service + " health timed out")


def check_http(manifest, policy):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f"http://127.0.0.1:{policy['port']}"
    for route in ("/", "/docs/", "/api/auth/wechat/capability", "/__release.json"):
        with opener.open(base + route, timeout=8) as response:
            assert response.status == 200, "HTTP probe failed"
            body = response.read(1024 * 1024)
        if route.startswith("/api/"):
            data = json.loads(body)
            assert data["code"] == 200 and data.get("data", {}).get("reason") != "CONFIG_UNAVAILABLE", "config readiness failed"
        if route == "/__release.json":
            data = json.loads(body)
            assert data["sha"] == manifest["web"]["sha"] and str(data["ciRun"]) == str(manifest["web"]["ciRun"]), "served web revision mismatch"


def deploy(args):
    import fcntl
    assert os.geteuid() == 0, "root executor required"
    if args and args[0] == "--remote":
        args = args[1:]
        assert len(args) == 4 and args[0] in ("backend", "web"), "remote bootstrap forbidden"
    os.umask(0o077)
    policy = json.loads(POLICY.read_text())
    assert policy["port"] == 18180, "port must match reviewed host-network nginx.conf"
    STATE.mkdir(mode=0o700, exist_ok=True)
    (STATE / "releases").mkdir(mode=0o700, exist_ok=True)
    with LOCK.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        assert not (STATE / "BLOCKED.json").exists(), "previous backend failure: operator schema review required"
        pending = STATE / "IN_PROGRESS.json"
        assert not pending.exists(), "interrupted release: operator state/schema review required"
        current = STATE / "current.json"
        previous = json.loads(current.read_text()) if current.exists() else None
        if len(args) == 2 and args[0] == "bootstrap":
            assert previous is None, "bootstrap only before first successful release"
            manifest = json.loads(Path(args[1]).read_text())
            changed = "backend"
        else:
            assert len(args) == 4 and previous is not None, "deploy requires existing paired manifest"
            changed, digest, sha, run = args
            assert changed in ("backend", "web") and re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "invalid deployment request"
            manifest = json.loads(json.dumps(previous))
            manifest[changed] = dict(image=policy[changed]["repository"] + "@" + digest, sha=sha, ciRun=run)
        assert set(manifest) == {"backend", "web"}, "paired manifest required"
        for component in ("backend", "web"):
            receipt(component, manifest[component], policy)
        # All image verification happens before compose can start Flyway.
        for component in (("backend", "web") if previous is None else (changed,)):
            pull(component, manifest[component], policy)
        release = STATE / "releases" / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + str(time.time_ns()))
        release.mkdir(mode=0o700)
        save(release / "requested.json", manifest)
        if previous:
            save(release / "previous.json", previous)
        # Survives SSH/process interruption before an exception handler can run.
        save(pending, {"release": str(release), "component": changed})
        try:
            if changed == "backend":
                compose(manifest, policy, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--force-recreate", "backend")
                healthy(manifest, policy, "backend")
            # Restart the paired static server and verify the complete ingress path.
            compose(manifest, policy, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--force-recreate", "web")
            healthy(manifest, policy, "web", 90)
            check_http(manifest, policy)
            save(release / "accepted.json", manifest)
            save(current, manifest)
        except Exception as error:
            save(release / "failed.json", {"error": str(error), "component": changed})
            if changed == "web" and previous is not None:
                try:
                    compose(previous, policy, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--force-recreate", "web")
                    healthy(previous, policy, "web", 90)
                    check_http(previous, policy)
                    pending.unlink()
                except Exception as rollback_error:
                    save(STATE / "BLOCKED.json", {"release": str(release), "error": str(rollback_error)})
                    raise
            else:
                # Backend may already have migrated the database. Never automatically downgrade it.
                save(STATE / "BLOCKED.json", {"release": str(release), "error": str(error)})
            raise
        else:
            pending.unlink()
        print("accepted paired release: " + str(release))


if __name__ == "__main__":
    deploy(sys.argv[1:])

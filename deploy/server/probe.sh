#!/bin/sh
# Run locally on the intended server. Read-only: no sudo, package install, file writes or external requests.
set -u
printf '%s\n' '=== OS / architecture / current identity ==='
uname -srm
if [ -r /etc/os-release ]; then sed -n '/^\(ID\|VERSION_ID\|PRETTY_NAME\)=/p' /etc/os-release; fi
id
printf '%s\n' '=== Memory / disk / available commands ==='
if command -v free >/dev/null 2>&1; then free -m; fi
df -h / /var /opt 2>/dev/null || df -h /
for name in bash curl python3 flock jq openssl docker nginx caddy; do
  command -v "$name" 2>/dev/null || true
done
printf '%s\n' '=== Installed client and local Docker versions (permission denial is acceptable) ==='
if command -v docker >/dev/null 2>&1; then
  docker --version
  docker compose version --short 2>/dev/null || true
  # Never follow an inherited DOCKER_HOST/context to another machine.
  docker --host unix:///var/run/docker.sock version --format 'Local daemon: {{.Server.Version}} / {{.Server.Os}} / {{.Server.Arch}}' 2>/dev/null || true
fi
if command -v openssl >/dev/null 2>&1; then openssl version; fi
printf '%s\n' '=== Listening TCP ports (no process arguments / environment) ==='
if command -v ss >/dev/null 2>&1; then ss -ltn; fi
printf '%s\n' '=== SSH public host-key fingerprints (not private keys) ==='
if command -v ssh-keygen >/dev/null 2>&1; then
  for public_key in /etc/ssh/ssh_host_*_key.pub; do
    if [ -r "$public_key" ]; then ssh-keygen -lf "$public_key"; fi
  done
fi
printf '%s\n' 'Probe complete. Do not send passwords, private keys, tokens, .env files or docker inspect output.'

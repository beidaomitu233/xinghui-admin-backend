#!/usr/bin/env bash
set -euo pipefail
[[ ${DEPLOY_HOST:?} =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]
[[ ${DEPLOY_USER:?} =~ ^[a-z_][a-z0-9_-]*$ ]]
[[ ${DEPLOY_PORT:?} =~ ^[0-9]+$ ]]
private=$(mktemp -d)
trap 'rm -rf -- "$private"' EXIT
umask 077
printf '%s\n' "${DEPLOY_SSH_KEY:?}" > "$private/key"
printf '%s\n' "${DEPLOY_KNOWN_HOSTS:?}" > "$private/known_hosts"
unset DEPLOY_SSH_KEY DEPLOY_KNOWN_HOSTS
ssh -q -T -F /dev/null -i "$private/key" -p "$DEPLOY_PORT" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$private/known_hosts" -o ConnectTimeout=10 \
  -o ServerAliveInterval=15 -o ServerAliveCountMax=2 \
  "$DEPLOY_USER@$DEPLOY_HOST" diagnose

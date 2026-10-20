#!/usr/bin/env bash
# Root-owned forced-command entry. Dedicated SSH user has no general sudo/docker access.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
request=${SSH_ORIGINAL_COMMAND:-}
if [[ $request =~ ^deploy\ (backend|web)\ (sha256:[0-9a-f]{64})\ ([0-9a-f]{40})\ ([0-9]+)$ ]]; then
  exec sudo -n /usr/bin/python3 -I /opt/xinghui/deploy.py --remote "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"
fi
printf '%s\n' 'Rejected deployment command' >&2
exit 64

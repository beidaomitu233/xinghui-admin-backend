#!/usr/bin/env bash
# GitHub-hosted runner only. Fixture pages test image startup, not the real production build.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true ]]
[[ ${NGINX_IMAGE:?} =~ ^docker.io/nginxinc/nginx-unprivileged@sha256:[0-9a-f]{64}$ ]]
tooling=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
context=$(mktemp -d)
name="xinghui-nginx-check-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
image="xinghui-nginx-check:$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
cleanup() {
  docker logs "$name" 2>/dev/null || true
  docker rm -f "$name" >/dev/null 2>&1 || true
  rm -rf -- "$context"
}
trap cleanup EXIT
mkdir -p "$context/web" "$context/docs"
printf 'application fixture\n' > "$context/web/index.html"
printf 'documentation fixture\n' > "$context/docs/index.html"
printf '{"sha":"fixture","ciRun":"fixture"}\n' > "$context/release.json"
cp "$tooling/nginx.conf" "$context/"
docker build --platform linux/amd64 --build-arg "NGINX_IMAGE=$NGINX_IMAGE" \
  --build-arg "SOURCE_SHA=$GITHUB_SHA" --build-arg "CI_RUN_ID=$GITHUB_RUN_ID" \
  -f "$tooling/Dockerfile.web" -t "$image" "$context"
limits=(--platform linux/amd64 --read-only --tmpfs /tmp:rw,nosuid,nodev,size=64m \
  --cap-drop ALL --security-opt no-new-privileges:true --memory 128m --cpus 0.5 \
  --network host --user 101:101)
docker run --rm "${limits[@]}" "$image" nginx -t
docker run -d --name "$name" "${limits[@]}" "$image"
for attempt in $(seq 1 30); do
  if [[ $(docker inspect -f '{{.State.Health.Status}}' "$name") == healthy ]]; then break; fi
  [[ $(docker inspect -f '{{.State.Running}}' "$name") == true ]]
  sleep 2
done
[[ $(docker inspect -f '{{.State.Health.Status}}' "$name") == healthy ]]
curl --noproxy '*' -fsS http://127.0.0.1:18080/ | grep -Fx 'application fixture'
curl --noproxy '*' -fsS http://127.0.0.1:18080/docs/ | grep -Fx 'documentation fixture'
curl --noproxy '*' -fsS http://127.0.0.1:18080/__release.json | grep -F '"sha":"fixture"'
docker exec "$name" wget -q --spider http://127.0.0.1:18080/
printf 'Nginx configuration, read-only startup, static routes and image healthcheck passed.\n'

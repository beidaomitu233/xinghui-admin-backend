#!/usr/bin/env bash
# Run only on a GitHub-hosted Linux build runner, from the exact CI source checkout.
set -euo pipefail
tooling=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ ${SOURCE_SHA:?} =~ ^[0-9a-f]{40}$ && ${CI_RUN_ID:?} =~ ^[0-9]+$ ]]
[[ $(git rev-parse HEAD) == "$SOURCE_SHA" ]]
context=$(mktemp -d)
trap 'rm -rf -- "$context"' EXIT
cp "$tooling/HealthProbe.java" "$tooling/nginx.conf" "$context/"
build_args=(--build-arg "SOURCE_SHA=$SOURCE_SHA" --build-arg "CI_RUN_ID=$CI_RUN_ID")
pinned() { [[ $1 =~ ^[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]]; }
case ${COMPONENT:?} in
  backend)
    [[ ${GITHUB_REPOSITORY:?} == beidaomitu233/xinghui-admin-backend ]]
    pinned "${JDK_IMAGE:?}" && pinned "${JRE_IMAGE:?}"
    pwsh -NoProfile -File scripts/quality/Verify-Ai-TestInventory.ps1 -SelfTest
    pwsh -NoProfile -File scripts/quality/Verify-Ai-TestInventory.ps1
    # Neutral fixture values for packaging tests, never production runtime credentials.
    export JWT_SECRET=oss04_ci_neutral_jwt_fixture_32_characters
    export CONFIG_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
    mvn -B -ntp clean package "-Dtest=$(paste -sd, scripts/quality/required-tests.txt)" -Dsurefire.failIfNoSpecifiedTests=false
    pwsh -NoProfile -File scripts/quality/Verify-Build.ps1
    pwsh -NoProfile -File scripts/quality/Test-ReportGate.ps1
    cp system-admin/target/system-admin.jar "$context/"
    jar_hash=$(sha256sum "$context/system-admin.jar" | cut -d' ' -f1)
    cp "$tooling/Dockerfile.backend" "$context/Dockerfile"
    build_args+=(--build-arg "JDK_IMAGE=$JDK_IMAGE" --build-arg "JRE_IMAGE=$JRE_IMAGE" --build-arg "JAR_SHA256=$jar_hash")
    image=ghcr.io/beidaomitu233/xinghui-admin-backend
    ;;
  web)
    [[ ${GITHUB_REPOSITORY:?} == beidaomitu233/xinghui-admin-vue3 ]]
    pinned "${NGINX_IMAGE:?}"
    export NODE_OPTIONS=--max-old-space-size=2048 DOCS_BASE=/docs/ VITE_APP_DEMO=false
    corepack enable
    corepack prepare pnpm@10.28.1 --activate
    pnpm install --frozen-lockfile
    node scripts/build-quality.mjs
    pnpm --filter @xinghui/docs test
    pnpm docs:check
    pnpm build:docs
    test -s apps/web-ele/dist/index.html
    test -s apps/web-ele/dist/_app.config.js
    test -s apps/docs/.vitepress/dist/index.html
    cp -a apps/web-ele/dist "$context/web"
    cp -a apps/docs/.vitepress/dist "$context/docs"
    python3 -c 'import json,os; print(json.dumps({"sha":os.environ["SOURCE_SHA"],"ciRun":os.environ["CI_RUN_ID"]}))' > "$context/release.json"
    cp "$tooling/Dockerfile.web" "$context/Dockerfile"
    build_args+=(--build-arg "NGINX_IMAGE=$NGINX_IMAGE")
    image=ghcr.io/beidaomitu233/xinghui-admin-vue3-web
    ;;
  *) exit 64 ;;
esac
# Only this run's build result digest is promoted; no latest-tag lookup.
docker buildx build --platform linux/amd64 --push --tag "$image:ci-$CI_RUN_ID-$SOURCE_SHA" \
  "${build_args[@]}" --metadata-file "$context/metadata.json" "$context"
digest=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containerimage.digest"])' "$context/metadata.json")
[[ $digest =~ ^sha256:[0-9a-f]{64}$ ]]
printf 'digest=%s\n' "$digest" >> "${GITHUB_OUTPUT:?}"
printf 'Published `%s@%s`\n\nSource: `%s`\n\nCI: https://github.com/%s/actions/runs/%s\n' \
  "$image" "$digest" "$SOURCE_SHA" "$GITHUB_REPOSITORY" "$CI_RUN_ID" >> "${GITHUB_STEP_SUMMARY:?}"

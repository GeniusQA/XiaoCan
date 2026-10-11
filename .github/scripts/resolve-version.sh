#!/usr/bin/env bash
# Resolve build channel / version / gate for XiaoCanPurify.
# Outputs (appended to $GITHUB_OUTPUT):
#   channel         canary | beta | stable
#   full_name       versionName used for artifact / release naming
#   code            versionCode
#   prev_stable_tag last tag matching v* (empty if none)
#   skip            true | false  (whether to skip the build)
set -euo pipefail

EVENT_NAME="${EVENT_NAME:-workflow_dispatch}"
CHANNEL="${CHANNEL:-canary}"

CURRENT_NAME="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=.*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n1)"
CURRENT_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=*[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n1)"

full_name="$CURRENT_NAME"
code="$CURRENT_CODE"
prev_stable_tag="$(git describe --tags --match 'v[0-9]*.*' --abbrev=0 2>/dev/null || true)"

skip="false"
if [[ "$EVENT_NAME" == "workflow_dispatch" || "$EVENT_NAME" == "pull_request" ]]; then
  skip="false"
elif git rev-parse HEAD^ >/dev/null 2>&1; then
  prev_name="$(git show HEAD^:app/build.gradle.kts 2>/dev/null | sed -nE 's/^[[:space:]]*versionName[[:space:]]*=.*"([^"]+)".*/\1/p' | head -n1 || true)"
  prev_code="$(git show HEAD^:app/build.gradle.kts 2>/dev/null | sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=*[[:space:]]*([0-9]+).*/\1/p' | head -n1 || true)"
  if [[ "$CURRENT_NAME" != "$prev_name" || "$CURRENT_CODE" != "$prev_code" ]]; then
    skip="false"
  else
    skip="true"
  fi
fi

# beta 渠道固定展示版本号（避免暴露真实版本线）
if [[ "$CHANNEL" == "beta" ]]; then
  full_name="1.0.0"
fi

{
  echo "channel=$CHANNEL"
  echo "full_name=$full_name"
  echo "code=$code"
  echo "prev_stable_tag=$prev_stable_tag"
  echo "skip=$skip"
} >> "$GITHUB_OUTPUT"

#!/usr/bin/env bash
# XiaoCanPurify CI 辅助脚本（合并原 resolve-version.sh / generate-release-body.sh）
# Usage:
#   ci.sh resolve                                # 输出构建判定到 $GITHUB_OUTPUT
#   ci.sh body <prev_tag> <head_ref> <channel>   # 输出 stable 发布说明到 stdout
set -euo pipefail

CMD="${1:-}"

case "$CMD" in
  resolve)
    # Resolve build channel / version / gate.
    # Outputs (appended to $GITHUB_OUTPUT):
    #   channel         canary | beta | stable
    #   full_name       versionName used for artifact / release naming
    #   code            versionCode
    #   prev_stable_tag last tag matching v* (empty if none)
    #   skip            true | false  (whether to skip the build)
    EVENT_NAME="${EVENT_NAME:-workflow_dispatch}"
    CHANNEL="${CHANNEL:-canary}"
    # push / 其他事件未提供渠道输入时，默认走 canary 构建
    if [[ -z "$CHANNEL" ]]; then CHANNEL="canary"; fi

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
    ;;

  body)
    # Generate release notes for a XiaoCanPurify stable release.
    # Usage: ci.sh body <prev_tag> <head_ref> <channel>
    PREV_TAG="${2:-}"
    HEAD_REF="${3:-HEAD}"
    CHANNEL="${4:-stable}"

    if [[ -n "$PREV_TAG" ]]; then
      range="$PREV_TAG..$HEAD_REF"
      title="自 $PREV_TAG 以来的改动"
    else
      range="$HEAD_REF~10..$HEAD_REF"
      title="最近改动"
    fi

    echo "## 更新内容 ($CHANNEL)"
    echo
    echo "### $title"
    echo
    git log "$range" --pretty=format:'- `%h` %s' --no-merges 2>/dev/null || true
    echo
    ;;

  *)
    echo "Usage: ci.sh resolve | ci.sh body <prev_tag> <head_ref> <channel>" >&2
    exit 1
    ;;
esac

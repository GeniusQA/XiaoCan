#!/usr/bin/env bash
# Generate release notes for a XiaoCanPurify stable release.
# Usage: generate-release-body.sh <prev_tag> <head_ref> <channel>
set -euo pipefail

PREV_TAG="${1:-}"
HEAD_REF="${2:-HEAD}"
CHANNEL="${3:-stable}"

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

#!/usr/bin/env bash
set -euo pipefail

expected_sha="${1:?expected evaluated GitOps SHA is required}"
mode="${2:-push}"
case "$expected_sha" in
  *[!0-9a-f]*|'') echo "invalid evaluated GitOps SHA" >&2; exit 2 ;;
esac
test "${#expected_sha}" -ge 40
git fetch --no-tags origin refs/heads/main:refs/remotes/origin/main
test "$(git rev-parse refs/remotes/origin/main)" = "$expected_sha"
if test "$mode" = verify-only; then
  test "$(git rev-parse HEAD)" = "$expected_sha"
  exit 0
fi
test "$mode" = push
test "$(git rev-parse HEAD^)" = "$expected_sha"
git push --force-with-lease=refs/heads/main:$expected_sha origin HEAD:refs/heads/main

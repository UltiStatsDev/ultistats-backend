#!/usr/bin/env bash
# Run inside a full-history checkout with the authoritative origin/master ref.
set -Eeuo pipefail
release_sha="${1:-}"
fail() {
    printf 'release selection failed: %s\n' "$*" >&2
    exit 1
}
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]] \
    || fail 'release_sha must be a full lowercase 40-character commit SHA'
[[ "$(git cat-file -t "$release_sha" 2>/dev/null)" == commit ]] \
    || fail 'release_sha must identify an existing commit object'
git merge-base --is-ancestor "$release_sha" refs/remotes/origin/master \
    || fail 'release_sha must be reachable from origin/master'
printf '%s\n' "$release_sha"

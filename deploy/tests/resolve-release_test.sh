#!/usr/bin/env bash
set -Eeuo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
resolver="$repository_root/deploy/resolve-release.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
cd "$fixture"
git init -q --initial-branch=master
git config user.email fixture@example.invalid
git config user.name Fixture

git commit -qm initial --allow-empty
ancestor="$(git rev-parse HEAD)"
git commit -qm tip --allow-empty
tip="$(git rev-parse HEAD)"
git update-ref refs/remotes/origin/master "$tip"
git checkout -qb off-master
git commit -qm off-master --allow-empty
off_master="$(git rev-parse HEAD)"
git tag -a release -m release "$tip"

accept() {
    local actual
    actual="$(bash "$resolver" "$1")" || { echo "rejected valid SHA: $1" >&2; exit 1; }
    [[ "$actual" == "$1" ]] || { echo 'resolver output differs from selected SHA' >&2; exit 1; }
}
reject() {
    if bash "$resolver" "$1" >result 2>error; then
        echo "accepted unsafe selection: $1" >&2
        exit 1
    fi
    [[ ! -s result ]] || { echo 'failed selection emitted a SHA' >&2; exit 1; }
}
accept "$ancestor"
accept "$tip"
for invalid in '' master latest "${tip:0:7}" "${tip^^}" \
    '0123456789abcdef0123456789abcdef01234567;touch pwned' \
    '0000000000000000000000000000000000000000' "$off_master" \
    "$(git rev-parse HEAD^{tree})" "$(git rev-parse release)"; do
    reject "$invalid"
done
[[ ! -e pwned ]] || { echo 'input executed as shell code' >&2; exit 1; }
git update-ref -d refs/remotes/origin/master
reject "$tip"
echo 'release selection tests passed (ancestor/tip and 11 rejection cases)'

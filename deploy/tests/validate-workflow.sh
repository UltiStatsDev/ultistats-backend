#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repository_root/.github/workflows/cd.yml"

fail() {
    echo "workflow validation failed: $*" >&2
    exit 1
}

require_text() {
    grep -Fq -- "$1" "$workflow" || fail "missing: $1"
}

[[ -f "$workflow" ]] || fail "$workflow does not exist"

require_text 'workflow_dispatch:'
require_text 'release_sha:'
require_text "if: github.ref == 'refs/heads/master'"
require_text 'ref: ${{ needs.resolve-release.outputs.release_sha }}'
require_text 'RELEASE_SHA: ${{ needs.resolve-release.outputs.release_sha }}'
require_text 'bash deploy/resolve-release.sh'
! grep -Eq '^  (push|pull_request|schedule|workflow_run|workflow_call):' "$workflow" \
    || fail 'production promotion must be manual only'
! grep -Fq ':latest' "$workflow" || fail 'promotion must never publish latest'

require_text 'contents: read'
require_text 'packages: write'
require_text "java-version: '21'"
require_text 'platforms: linux/amd64'
require_text 'ghcr.io/ultistatsdev/ultistats-backend:${{ needs.resolve-release.outputs.release_sha }}'
require_text 'environment: production'
require_text 'group: production'
require_text 'cancel-in-progress: false'
require_text 'PROD_SSH_PRIVATE_KEY'
require_text 'PROD_SSH_KNOWN_HOSTS'
require_text 'IdentitiesOnly yes'
require_text 'deploy/compose.prod.yml'
require_text 'deploy/deploy.sh'
require_text '/opt/ultistats/deploy.sh'
require_text "rm -f '\$staging_directory/compose.prod.yml' '\$staging_directory/deploy.sh'"
require_text 'bash deploy/tests/validate-compose.sh'
require_text 'bash deploy/tests/deploy_test.sh'
require_text 'bash deploy/tests/validate-workflow.sh'
require_text 'bash deploy/tests/resolve-release_test.sh'
[[ "$(grep -Fc 'ref: ${{ needs.resolve-release.outputs.release_sha }}' "$workflow")" == 2 ]] \
    || fail 'build and deploy must both check out the selected release'
! grep -Eq 'github\.sha|GITHUB_SHA' "$workflow" \
    || fail 'workflow execution SHA must not replace the selected release'
require_text "/opt/ultistats/deploy.sh '\$IMAGE' '\$RELEASE_SHA'"

ci_workflow="$repository_root/.github/workflows/ci.yml"
grep -Fq 'bash deploy/tests/validate-compose.sh' "$ci_workflow" \
    || fail 'PR CI must validate production Compose'
grep -Fq 'bash deploy/tests/deploy_test.sh' "$ci_workflow" \
    || fail 'PR CI must test deployment rollback behavior'
grep -Fq 'bash deploy/tests/validate-workflow.sh' "$ci_workflow" \
    || fail 'PR CI must validate the CD workflow'

grep -Fq 'bash deploy/tests/resolve-release_test.sh' "$ci_workflow" \
    || fail 'PR CI must test explicit release selection'

! grep -Eq 'StrictHostKeyChecking[= ]no' "$workflow" \
    || fail 'SSH host verification must not be disabled'
! grep -Eq 'GHCR_(TOKEN|PASSWORD)|packages:[[:space:]]*read' "$workflow" \
    || fail 'workflow must not forward a registry credential to production'

echo 'CD workflow validation passed'

#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
mode=${1:-staged}
set -- --config .gitleaks.toml --redact --no-banner --exit-code 3
case "$mode" in
    staged) exec gitleaks git --pre-commit --staged "$@" . ;;
    snapshot) exec gitleaks dir "$@" . ;;
    *) printf 'Usage: sh scripts/check-secrets.sh [staged|snapshot]\n' >&2; exit 2 ;;
esac

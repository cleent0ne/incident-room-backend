#!/usr/bin/env bash
#
# Logs in as an existing ADMIN account and imports + publishes every scenario YAML file under
# seed-data/. Assumes that account already exists, has verified its email, and has been promoted
# to ADMIN in Mongo directly (see the main README's "Seeding content" section — this script does
# not perform any of those steps itself).
#
# Usage:
#   ADMIN_EMAIL=you@example.com ADMIN_PASSWORD=your-password ./scripts/seed-scenarios.sh
#
# Optional:
#   BASE_URL   defaults to http://localhost:8080
#   SEED_DIR   defaults to <repo-root>/seed-data
#
# Requires: curl, jq

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="${SEED_DIR:-${SCRIPT_DIR}/../seed-data}"

if [[ -z "${ADMIN_EMAIL:-}" || -z "${ADMIN_PASSWORD:-}" ]]; then
  echo "Error: ADMIN_EMAIL and ADMIN_PASSWORD must be set." >&2
  echo "Usage: ADMIN_EMAIL=you@example.com ADMIN_PASSWORD=your-password $0" >&2
  exit 1
fi

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "Error: '$bin' is required but not installed." >&2
    exit 1
  fi
done

if [[ ! -d "$SEED_DIR" ]]; then
  echo "Error: seed directory not found at $SEED_DIR" >&2
  exit 1
fi

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

echo "Logging in as ${ADMIN_EMAIL} at ${BASE_URL} ..."
login_body=$(jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" \
  '{email: $email, password: $password}')

login_status=$(curl -sS -o /tmp/seed-login-response.json -w '%{http_code}' \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -X POST "${BASE_URL}/api/v1/auth/login" \
  -d "$login_body")

if [[ "$login_status" != "200" ]]; then
  echo "Login failed (HTTP ${login_status}):" >&2
  cat /tmp/seed-login-response.json >&2
  echo >&2
  echo "Make sure this account exists, has a verified email, and has ADMIN role in Mongo." >&2
  exit 1
fi
echo "Logged in."

shopt -s nullglob
yaml_files=("$SEED_DIR"/*.yaml "$SEED_DIR"/*.yml)
shopt -u nullglob

if [[ ${#yaml_files[@]} -eq 0 ]]; then
  echo "No .yaml/.yml files found in $SEED_DIR" >&2
  exit 1
fi

failures=0

for file in "${yaml_files[@]}"; do
  name="$(basename "$file")"
  echo
  echo "== ${name} =="

  import_status=$(curl -sS -o /tmp/seed-import-response.json -w '%{http_code}' \
    -b "$COOKIE_JAR" \
    -H 'Content-Type: text/plain' \
    -X POST "${BASE_URL}/api/v1/admin/scenarios/import" \
    --data-binary @"$file")

  if [[ "$import_status" != "200" ]]; then
    echo "  Import failed (HTTP ${import_status}):"
    cat /tmp/seed-import-response.json
    failures=$((failures + 1))
    continue
  fi

  scenario_id=$(jq -r '.id' /tmp/seed-import-response.json)
  slug=$(jq -r '.slug' /tmp/seed-import-response.json)
  warnings=$(jq -r '.validationWarnings // [] | length' /tmp/seed-import-response.json)

  echo "  Imported as draft: slug=${slug} id=${scenario_id}"
  if [[ "$warnings" != "0" ]]; then
    echo "  Import-time validation warnings:"
    jq -r '.validationWarnings[]' /tmp/seed-import-response.json | sed 's/^/    - /'
  fi

  publish_status=$(curl -sS -o /tmp/seed-publish-response.json -w '%{http_code}' \
    -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -X PATCH "${BASE_URL}/api/v1/admin/scenarios/${scenario_id}/publish" \
    -d '{"publish": true}')

  if [[ "$publish_status" != "200" ]]; then
    echo "  Publish failed (HTTP ${publish_status}):"
    cat /tmp/seed-publish-response.json
    failures=$((failures + 1))
    continue
  fi

  echo "  Published."
done

echo
if [[ "$failures" -gt 0 ]]; then
  echo "Done with ${failures} failure(s) — see above." >&2
  exit 1
fi
echo "All scenarios imported and published successfully."

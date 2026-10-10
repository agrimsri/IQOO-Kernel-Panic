#!/usr/bin/env bash
# Pushes the demo voice switch (api.env) to a phone, so the online Sarvam voice can be turned on, off, or tested without a key.
#
#   tools/push-api-env.sh online   USE_API=true plus SARVAM_API_KEY read from a .env file  -> the online voice speaks
#   tools/push-api-env.sh nokey    USE_API=true and no key                                 -> offline voice (proves the fallback)
#   tools/push-api-env.sh offline  USE_API=false                                           -> offline voice
#   tools/push-api-env.sh remove   deletes api.env from the phone
#
# Environment: ADB (adb binary, default "adb"), SERIAL (device serial; required when several devices are attached),
# ENV_FILE (where the key lives; default ./.env, then ../.env). The key is never printed, never written inside the
# repository, and the temporary copy is deleted on exit.
set -euo pipefail

MODE="${1:-}"
ADB="${ADB:-adb}"
DEST="/sdcard/Android/data/com.dpdpxray.app/files"
adb_() { if [ -n "${SERIAL:-}" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

case "$MODE" in
  online|nokey|offline|remove) ;;
  *) echo "usage: $0 online|nokey|offline|remove" >&2; exit 2 ;;
esac

if [ "$MODE" = "remove" ]; then
  adb_ shell rm -f "$DEST/api.env"
  echo "api.env removed from the phone: the offline voice speaks"
  exit 0
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"

case "$MODE" in
  offline) printf 'USE_API=false\n' >"$TMP" ;;
  nokey)   printf 'USE_API=true\n'  >"$TMP" ;;
  online)
    ENV_FILE="${ENV_FILE:-}"
    if [ -z "$ENV_FILE" ]; then
      for c in ./.env ../.env; do [ -f "$c" ] && ENV_FILE="$c" && break; done
    fi
    [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ] || { echo "no .env file found (set ENV_FILE); not pushing" >&2; exit 1; }
    KEY="$(sed -n -E "s/^(export +)?SARVAM_API_KEY=//p" "$ENV_FILE" | head -n1 | sed -E "s/^[\"']//; s/[\"']\$//; s/[[:space:]]+\$//")"
    if [ -z "$KEY" ]; then
      # No key means the offline voice, so clear any api.env (and any older key) already on the phone instead of leaving it.
      adb_ shell rm -f "$DEST/api.env"
      echo "SARVAM_API_KEY is missing or empty in $ENV_FILE: removed api.env from the phone, so the offline voice speaks" >&2
      exit 1
    fi
    printf 'USE_API=true\nSARVAM_API_KEY=%s\n' "$KEY" >"$TMP"
    unset KEY ;;
esac

SRC="$TMP"
case "$ADB" in *.exe) SRC="$(wslpath -w "$TMP")" ;; esac
adb_ shell mkdir -p "$DEST"
adb_ push "$SRC" "$DEST/api.env" >/dev/null
echo "pushed api.env ($MODE) to the phone; the key, if any, was not printed"

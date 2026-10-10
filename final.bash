#!/usr/bin/env bash
# Final check of the published package: install traceveil from PyPI into a throwaway venv (no repo code used),
# then audit the app you give as the first argument and compare the SDKs bundled in its APK with what the audit saw.
#
# Usage: bash final.bash <app.apk | package.name> [extra args for `traceveil audit`, e.g. --plans silent --auto-drive]
# Env:   SERIAL=<adb serial>  ADB=<path to adb>  VERSION=<pypi version, default 0.3.0>  KEEP_VENV=1 to keep the venv
set -uo pipefail
VERSION="${VERSION:-0.3.0}"
APP="${1:-}"; [ -n "$APP" ] || { echo "usage: bash final.bash <app.apk | package.name> [audit args...]" >&2; exit 4; }
shift

WORK="$(mktemp -d)"; [ -n "${KEEP_VENV:-}" ] || trap 'rm -rf "$WORK"' EXIT
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'error: %s\n' "$1" >&2; exit "${2:-1}"; }

step "1/6 Installing traceveil==$VERSION from PyPI into a clean venv"
python3 -m venv "$WORK/venv" || die "cannot create venv" 1
"$WORK/venv/bin/pip" install -q --no-cache-dir "traceveil==$VERSION" || die "pip install traceveil==$VERSION failed" 1
cd "$WORK"   # run outside any checkout so only the installed package is used
TV=("$WORK/venv/bin/traceveil"); [ -n "${ADB:-}" ] && TV+=(--adb "$ADB"); [ -n "${SERIAL:-}" ] && TV+=(--serial "$SERIAL")
"${TV[0]}" --version || die "installed command does not run" 1
echo "  installed from: $("$WORK/venv/bin/python" -c 'import traceveil;print(traceveil.__file__)')"

step "2/6 Resolving the app"
CALLER_DIR="$OLDPWD"
case "$APP" in
  *.apk)
    [[ "$APP" = /* ]] || APP="$CALLER_DIR/$APP"
    [ -f "$APP" ] || die "APK not found: $APP" 4
    PKG=""
    for t in aapt2 aapt; do
      command -v "$t" >/dev/null && { PKG="$("$t" dump packagename "$APP" 2>/dev/null | head -1)"; [ -n "$PKG" ] || PKG="$("$t" dump badging "$APP" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"; [ -n "$PKG" ] && break; }
    done
    [ -n "$PKG" ] || echo "  aapt/aapt2 not on PATH: the package name will be taken from the phone after install" ;;
  *) PKG="$APP"; APP="" ;;
esac
[ -z "$PKG" ] || echo "  package: $PKG"

step "3/6 Phone readiness (traceveil doctor)"
"${TV[@]}" doctor; rc=$?
[ "$rc" -eq 0 ] || echo "  doctor reported problems (exit $rc); continuing so the audit shows the exact error"

if [ -n "$APP" ]; then
  step "4/6 Installing the APK on the phone"
  ADBBIN="${ADB:-adb}"; [ -n "${SERIAL:-}" ] && ADBBIN="$ADBBIN -s $SERIAL"
  case "$ADBBIN" in *.exe*) APKARG="$(wslpath -w "$APP" 2>/dev/null || echo "$APP")" ;; *) APKARG="$APP" ;; esac
  pkgs() { $ADBBIN shell pm list packages -3 | tr -d '\r' | sort; }
  BEFORE="$(pkgs)"
  $ADBBIN install -r "$APKARG" || die "adb install failed (signature mismatch? uninstall the old copy yourself)" 3
  if [ -z "$PKG" ]; then
    PKG="$(comm -13 <(printf '%s\n' "$BEFORE") <(pkgs) | sed 's/^package://' | head -1)"
    [ -n "$PKG" ] || die "could not tell which package the APK installed (it was already installed). Re-run with the package name instead." 4
    echo "  package: $PKG"
  fi
else
  step "4/6 Using the package already installed on the phone (pulling its APK for the SDK check)"
  ADBBIN="${ADB:-adb}"; [ -n "${SERIAL:-}" ] && ADBBIN="$ADBBIN -s $SERIAL"
  REMOTE="$($ADBBIN shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | grep -m1 'base.apk$')"
  if [ -n "$REMOTE" ]; then
    case "$ADBBIN" in *.exe*) DEST="$(wslpath -w "$WORK/base.apk")" ;; *) DEST="$WORK/base.apk" ;; esac
    $ADBBIN pull "$REMOTE" "$DEST" >/dev/null && APP="$WORK/base.apk" && echo "  pulled $REMOTE"
  fi
  [ -n "$APP" ] || echo "  could not pull the APK of $PKG; the SDK check will be skipped"
fi

REPORT="$CALLER_DIR/traceveil-final-report.json"
SDKS="$CALLER_DIR/traceveil-final-sdks.json"
rm -f "$REPORT" "$SDKS"   # never compare against a report left over from an earlier run

step "5/6 traceveil audit --package $PKG"
"${TV[@]}" audit --package "$PKG" --out "$REPORT" "$@"; code=$?
echo; echo "audit exit code: $code  (0 pass, 1 findings, 2 inconclusive, 3 device/trigger, 6 timeout)"

step "6/6 traceveil sdks: SDK found in APK vs tracker observed live"
sdk_code=0
if [ -z "$APP" ]; then
  echo "  skipped: no APK to inspect"
elif [ -f "$REPORT" ]; then
  "${TV[@]}" sdks --apk "$APP" --report "$REPORT" --package "$PKG" --fail-on-consent --out "$SDKS"; sdk_code=$?
else
  echo "  no audit report (audit exit $code): showing the APK's bundled SDKs only"
  "${TV[@]}" sdks --apk "$APP" --out "$SDKS"; sdk_code=$?
fi

echo
[ -f "$REPORT" ] && echo "audit report: $REPORT"
[ -f "$SDKS" ] && echo "sdk report:   $SDKS"
echo "sdks exit code: $sdk_code  (0 ok, 1 a bundled SDK was looked up before consent or after Reject)"
# The audit result decides the exit code; a consent problem found only by the SDK check still fails a passing audit.
[ "$code" -eq 0 ] && [ "$sdk_code" -eq 1 ] && code=1
exit "$code"

#!/usr/bin/env bash
# One-command developer pipeline for the LeakyShop demo apps on a USB-connected phone:
#   build APKs -> install over adb (no manual transfer) -> run Traceveil audits -> JSON reports on this laptop.
# You only do three things by hand on the phone: USB debugging (+ Allow prompt), Traceveil VPN permission, Traceveil accessibility service.
#
# Usage: bash tools/demo.sh [--skip-build] [--no-fixed] [--manual-taps] [--plans silent,reject,accept]
# Env:   SERIAL=<adb serial>  ADB=<path to adb>  WAIT=<seconds per audit, default 600>
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export PYTHONPATH="$ROOT/src${PYTHONPATH:+:$PYTHONPATH}"   # run the checkout copy; no pip install needed

SKIP_BUILD=0; RUN_FIXED=1; AUTO_DRIVE=1; PLANS="silent,reject,accept"; WAIT="${WAIT:-600}"
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --no-fixed) RUN_FIXED=0 ;;
    --manual-taps) AUTO_DRIVE=0 ;;
    --plans) PLANS="$2"; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 4 ;;
  esac
  shift
done

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then B=$'\e[1m'; G=$'\e[1;32m'; Y=$'\e[1;33m'; R=$'\e[1;31m'; C=$'\e[1;36m'; N=$'\e[0m'; else B=; G=; Y=; R=; C=; N=; fi
step() { printf '\n%s== %s%s\n' "$B" "$*" "$N"; }
ok()   { printf '%s ok %s %s\n' "$G" "$N" "$*"; }
warn() { printf '%s !! %s %s\n' "$Y" "$N" "$*"; }
die()  { printf '%s error:%s %s\n' "$R" "$N" "$*" >&2; exit "${2:-1}"; }

# ---- adb: PATH first, else the Windows adb.exe (WSL2 cannot see USB phones) ----
find_adb() {
  [ -n "${ADB:-}" ] && { echo "$ADB"; return; }
  if command -v adb >/dev/null && [ -n "$(adb devices 2>/dev/null | sed 1d | grep -w device)" ]; then command -v adb; return; fi
  local w; w="$(ls /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe 2>/dev/null | head -1)"
  [ -n "$w" ] && { echo "$w"; return; }
  command -v adb || true
}
ADB="$(find_adb)"; [ -n "$ADB" ] || die "adb not found: install Android platform-tools or set ADB=/path/to/adb" 3
IS_WIN_ADB=0; case "$ADB" in *.exe) IS_WIN_ADB=1 ;; esac

step "1/6 Waiting for the phone"
SERIAL="${SERIAL:-}"
for i in $(seq 1 60); do
  mapfile -t READY < <("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device"{print $1}')
  UNAUTH="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="unauthorized"{print $1}')"
  if [ -n "$SERIAL" ]; then printf '%s\n' "${READY[@]:-}" | grep -qx "$SERIAL" && break
  elif [ "${#READY[@]}" -eq 1 ]; then SERIAL="${READY[0]}"; break
  elif [ "${#READY[@]}" -gt 1 ]; then die "several phones attached (${READY[*]}); run again with SERIAL=<serial>" 3
  fi
  if [ -n "$UNAUTH" ]; then warn "Phone is unauthorized: unlock it and tap ALLOW on the USB debugging prompt"
  else warn "No phone found: plug in the USB cable and enable Developer options > USB debugging"; fi
  sleep 3
done
[ -n "$SERIAL" ] && printf '%s\n' "${READY[@]:-}" | grep -qx "$SERIAL" || die "no authorized phone after 3 minutes" 3
A() { "$ADB" -s "$SERIAL" "$@"; }
MODEL="$(A shell getprop ro.product.model | tr -d '\r')"; ANDROID="$(A shell getprop ro.build.version.release | tr -d '\r')"
ok "$MODEL, Android $ANDROID ($ADB)"

# ---- build ----
step "2/6 Building APKs"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
LEAKY_APK=leakyshop/build/outputs/apk/leaky/debug/leakyshop-leaky-debug.apk
FIXED_APK=leakyshop/build/outputs/apk/fixed/debug/leakyshop-fixed-debug.apk
if [ "$SKIP_BUILD" -eq 1 ]; then warn "skipping build (--skip-build)"
else bash ./gradlew --offline :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain -q \
  || bash ./gradlew :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain -q \
  || die "Gradle build failed" 1; fi
for f in "$APP_APK" "$LEAKY_APK" "$FIXED_APK"; do [ -f "$f" ] || die "missing $f (run without --skip-build)" 1; done
ok "3 debug APKs ready"

# ---- install (copies to a Windows-visible folder automatically when using adb.exe) ----
step "3/6 Installing on the phone"
STAGE=""
if [ "$IS_WIN_ADB" -eq 1 ]; then
  WTEMP="$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')"
  STAGE="$(wslpath -u "$WTEMP" 2>/dev/null)/traceveil-demo"; mkdir -p "$STAGE" || die "cannot create staging folder $STAGE" 3
fi
install_apk() {
  local apk="$1" path="$1" out
  if [ -n "$STAGE" ]; then cp "$apk" "$STAGE/" && path="$(wslpath -w "$STAGE/$(basename "$apk")")"; fi
  out="$(A install -r "$path" 2>&1 | tr -d '\r')"
  case "$out" in
    *Success*) ok "installed $(basename "$apk")" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*SIGNATURE*) die "$(basename "$apk"): signature differs from the installed copy. Uninstall that app on the phone yourself, then re-run (the script never uninstalls for you)." 3 ;;
    *) die "install of $(basename "$apk") failed: $out" 3 ;;
  esac
}
install_apk "$APP_APK"; install_apk "$LEAKY_APK"; [ "$RUN_FIXED" -eq 1 ] && install_apk "$FIXED_APK"

# ---- the three manual phone settings ----
step "4/6 Phone readiness (the only manual part)"
PDNS="$(A shell settings get global private_dns_mode | tr -d '\r')"
[ "$PDNS" = "off" ] || warn "Private DNS is '$PDNS': encrypted DNS hides lookups. Set it to Off (Settings > Network) for a valid audit."
A shell am start -n com.dpdpxray.app/.MainActivity >/dev/null 2>&1   # opening the app also lets Android deliver the trigger
a11y_on() { A shell settings get secure enabled_accessibility_services | tr -d '\r' | grep -q 'com.dpdpxray.app'; }
ready_prompt() {
  cat <<MSG
  On the phone, in TraceVeil:
    1. Allow the VPN permission when asked
    2. Settings > Accessibility > TraceVeil consent watcher > ON
  Optional: start/authorize Shizuku so the demo apps are reset to a fresh state before each plan.
MSG
  a11y_on && ok "accessibility service is on" || warn "accessibility service is OFF"
  read -r -p "  Press Enter when done (Ctrl-C to stop) " _ </dev/tty || true
}
a11y_on || ready_prompt

# Shizuku lets Traceveil reset the demo apps to a fresh state before every plan. Without it the apps remember their
# consent choice and later plans never see a consent screen, so scores are NOT comparable to the baseline.
shizuku_up() { [ -n "$(A shell 'ps -A 2>/dev/null | grep -i shizuku_server' | tr -d '\r')" ]; }
if ! shizuku_up; then
  START=/sdcard/Android/data/moe.shizuku.privileged.api/start.sh
  if A shell "ls $START" >/dev/null 2>&1; then A shell "sh $START" >/dev/null 2>&1; sleep 3; fi
fi
if shizuku_up; then ok "Shizuku is running (authorize Traceveil in Shizuku if it asks)"
else warn "Shizuku is NOT running: audits will still work, but app data is not reset between plans and results may be degraded.
        Fix: open the Shizuku app once on the phone and start it (Wireless debugging), then re-run."; fi

# ---- audits ----
TV=(python3 -m traceveil --adb "$ADB" --serial "$SERIAL")
DRIVE=(); [ "$AUTO_DRIVE" -eq 1 ] && DRIVE=(--auto-drive)
mkdir -p .traceveil/reports
declare -A SCORE EXIT
run_one() {  # label package
  local label="$1" pkg="$2" try out code
  for try in 1 2 3; do
    echo "  audit: $label ($pkg), plans $PLANS$([ "$AUTO_DRIVE" -eq 1 ] && echo ', agent taps' || echo ', you tap on the phone')"
    out="$("${TV[@]}" audit --package "$pkg" --plans "$PLANS" "${DRIVE[@]}" --reset --wait "$WAIT" 2>&1 | tee /dev/stderr)"; code=${PIPESTATUS[0]}
    code=$(printf '%s' "$out" | grep -q 'timed out' && echo 6 || echo "$code")
    if [ "$code" = 3 ] && printf '%s' "$out" | grep -qiE 'VPN permission|accessibility'; then warn "TraceVeil needs a permission on the phone"; ready_prompt; continue; fi
    break
  done
  EXIT[$label]=$code
  SCORE[$label]="$(printf '%s' "$out" | sed -n 's/.*score \([0-9A-Za-z]*\) \/ \([A-Z_]*\).*/\1 (\2)/p' | head -1)"
  LAST_REPORT="$(printf '%s' "$out" | sed -n 's/.*report saved: //p' | tail -1)"
  REPORT[$label]="$LAST_REPORT"
  DEGRADED[$label]=""
  if [ -f "$LAST_REPORT" ]; then
    DEGRADED[$label]="$(python3 - "$LAST_REPORT" <<'PY'
import json, sys
notes = [n for s in json.load(open(sys.argv[1]))["sessions"] for n in s["notes"]]
bits = []
if any("not cleared" in n for n in notes): bits.append("app data not reset (Shizuku)")
if any("No consent screen detected" in n for n in notes): bits.append("consent screen missed in some plan")
print("; ".join(bits))
PY
)"
  fi
}
declare -A REPORT DEGRADED
step "5/6 Auditing LeakyShop (deliberately leaky)"; run_one leaky com.leakyshop.demo
[ "$RUN_FIXED" -eq 1 ] && { step "5/6 Auditing LeakyShop Fixed (compliant)"; run_one fixed com.leakyshop.demo.fixed; }

# ---- summary ----
step "6/6 Results (JSON reports are on this laptop)"
printf '  %-8s %-18s %-6s %s\n' APP SCORE EXIT REPORT
for k in leaky fixed; do [ -n "${EXIT[$k]+x}" ] && printf '  %-8s %-18s %-6s %s\n' "$k" "${SCORE[$k]:-n/a}" "${EXIT[$k]}" "${REPORT[$k]:-none}"; done
echo "  exit codes: 0 pass, 1 findings (expected for leaky), 2 inconclusive/failed, 3 device/trigger, 6 timeout"
echo "  re-read a report any time:  python3 -m traceveil audit --resume <request_id>"
for k in leaky fixed; do [ -n "${DEGRADED[$k]:-}" ] && warn "$k run is DEGRADED: ${DEGRADED[$k]}. Scores are not comparable to the 21 / 100 baseline."; done
[ "${EXIT[leaky]:-9}" = 1 ] && ok "leaky app: privacy bugs found, as designed" || warn "leaky app did not return findings (exit ${EXIT[leaky]:-?}); see the output above"
[ "$RUN_FIXED" -eq 1 ] && { [ "${EXIT[fixed]:-9}" = 0 ] && ok "fixed app: clean" || warn "fixed app exit ${EXIT[fixed]:-?}"; }
exit 0

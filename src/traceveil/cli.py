"""traceveil command line: doctor, env, init, audit."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from . import (audit, EXIT_DEVICE, EXIT_FINDINGS, EXIT_INCONCLUSIVE, EXIT_PASS, EXIT_POLICY, EXIT_USAGE, __version__, adb, config, egress,
               laws, netenv, report, sdks, shizuku)
from .colors import Style, enabled_for


def _common(default) -> argparse.ArgumentParser:
    """Options valid before or after the subcommand (`traceveil --adb X audit` and `traceveil audit --adb X`)."""
    c = argparse.ArgumentParser(add_help=False)
    c.add_argument("--config", default=default, help="path to traceveil.toml / traceveil.json")
    c.add_argument("--serial", default=default, help="device serial (default: the only attached device, or $SERIAL)")
    c.add_argument("--adb", default=default, help="adb binary (default: $ADB, PATH, then Windows adb.exe under WSL)")
    c.add_argument("--timeout", type=float, default=20.0 if default is None else default, help="seconds per adb command")
    return c


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="traceveil", description="Traceveil developer CLI (iQOO/Android over adb).", parents=[_common(None)])
    p.add_argument("--version", action="version", version=f"traceveil {__version__}")
    shared = _common(argparse.SUPPRESS)   # SUPPRESS: a subcommand only overrides the value when the flag is actually given
    sub = p.add_subparsers(dest="command", required=True)

    d = sub.add_parser("doctor", parents=[shared], help="check adb, device and Traceveil readiness (starts Shizuku over adb if it is down)")
    d.add_argument("--no-start-shizuku", action="store_true", help="only report the Shizuku server state; do not start it")
    sub.add_parser("init", parents=[shared], help="write a traceveil.toml with defaults").add_argument("--force", action="store_true")

    a = sub.add_parser("audit", parents=[shared], help="run an audit on the phone (debug build), wait, and save its JSON report")
    a.add_argument("--package", help="app to audit (default: target.package)")
    a.add_argument("--plans", help="comma list of silent,reject,accept (default: all three)")
    a.add_argument("--auto-drive", action="store_true", help="let Traceveil tap Accept/Reject itself (default: you tap on the phone)")
    a.add_argument("--reset", action="store_true", help="clear the app's data first; only for developer-owned test apps; needs Shizuku")
    a.add_argument("--wait", type=float, default=900.0, help="seconds to wait for the audit (default 900)")
    a.add_argument("--resume", metavar="REQUEST_ID", help="fetch the result of an earlier request instead of starting a new audit")
    a.add_argument("--json", action="store_true", help="print the JSON report instead of the summary")
    a.add_argument("--out", help="also write the JSON report to this file")
    a.add_argument("--lens", "--profile", dest="lenses",
                   help="legal lenses to read the result under: comma list (INDIA,EU,UK,US_CALIFORNIA,US_CHILDREN,BRAZIL,SINGAPORE,"
                        "GLOBAL_BASELINE; short: in,eu,us-ca,coppa,br,sg) or 'all' (default: audit.lenses)")
    a.add_argument("--policy", help="lens whose score decides the exit code (default: audit.policy = INDIA)")

    lw = sub.add_parser("lens", aliases=["laws"], parents=[shared],
                        help="read a saved audit report under the app's legal lenses (several countries' laws; no phone needed)")
    lw.add_argument("--report", help="audit report: a JSON file, or a request id saved under output.dir (default: the newest)")
    lw.add_argument("--lens", "--profile", dest="lenses", help="comma list of lenses or 'all' (default: audit.lenses)")
    lw.add_argument("--policy", help="lens that decides the exit code (default: audit.policy = INDIA)")
    lw.add_argument("--detail", action="store_true", help="show each finding's relation, the law's note and the cited passages")
    lw.add_argument("--list", action="store_true", help="list the lenses and their laws, then exit")
    lw.add_argument("--json", action="store_true", help="print the JSON assessment instead of the summary")
    lw.add_argument("--out", help="also write the JSON assessment to this file")

    k = sub.add_parser("sdks", parents=[shared], help="list tracking SDKs bundled in an APK and compare them with an audit's live lookups")
    k.add_argument("--apk", required=True, help="APK to inspect (no phone needed for this part)")
    k.add_argument("--report", help="audit report to compare with: a JSON file, or a request id saved under output.dir")
    k.add_argument("--package", help="also read the app's requested permissions from the phone (needs adb)")
    k.add_argument("--fail-on-consent", action="store_true", help="exit 1 if a bundled SDK was looked up before consent or after Reject")
    k.add_argument("--json", action="store_true", help="print the JSON inventory instead of the summary")
    k.add_argument("--out", help="also write the JSON inventory to this file")

    e = sub.add_parser("env", parents=[shared], help="network-environment snapshot (VPN/proxy/geography signals)")
    e.add_argument("--check-vpn", choices=config.CHECK_VPN_MODES, help="off | warn | fail (exit 5 on a likely VPN/proxy)")
    e.add_argument("--online-ip", action="store_true", help="one cached lookup to network.endpoint; sends the public IP to that service")
    e.add_argument("--egress-from", choices=("phone", "host"), default="phone",
                   help="where the lookup runs: phone (device curl, default) or host (this machine's network)")
    e.add_argument("--keep-ip", action="store_true", help="store the full public IP instead of a redacted prefix")
    e.add_argument("--include-own-vpn", action="store_true", help="count Traceveil's own capture VPN (debugging aid)")
    e.add_argument("--json", action="store_true", help="print the JSON report instead of the summary")
    e.add_argument("--out", help="also write the JSON report to this file")
    return p


def _device_info(dev: adb.Device) -> dict:
    return {"model": dev.getprop("ro.product.model"), "androidVersion": dev.getprop("ro.build.version.release"),
            "serialHash": report.serial_hash(dev.serial)}


def cmd_doctor(args, cfg, dev: adb.Device) -> int:
    pkgs = dev.shell("pm list packages").replace("package:", "")
    installed = lambda name: f"\n{name}\n" in f"\n{pkgs}\n"  # noqa: E731
    rows = [("adb", True, dev.adb), ("device", True, f"{dev.getprop('ro.product.brand')} {dev.getprop('ro.product.model')}, Android {dev.getprop('ro.build.version.release')}"),
            ("Traceveil installed", installed(netenv.OWN_PACKAGE), netenv.OWN_PACKAGE),
            ("Shizuku installed", installed(shizuku.PACKAGE), "needed for fresh-install resets"),
            ("USB install allowed", (dev.setting("secure", "install_non_market_apps") or "") == "1", "Developer options > Install via USB")]
    if installed(shizuku.PACKAGE):
        if args.no_start_shizuku:
            running, note = shizuku.is_running(dev), "not started (--no-start-shizuku)"
        else:
            running, note = shizuku.start(dev)
        rows.append(("Shizuku server running", running, note))
    pdns = dev.setting("global", "private_dns_mode")
    # An unset mode is Android's "Automatic": encrypted DNS can be used, which hides lookups from capture.
    rows.append(("Private DNS off", pdns == "off", f"mode={pdns or 'unset (Automatic)'}; set Private DNS to Off so lookups stay visible"))
    bad = 0
    st = Style(enabled_for(sys.stdout))
    for name, ok, note in rows:
        bad += 0 if ok else 1
        print(f"  [{st.ok('ok') if ok else st.warn('!!')}] {name:<22} {note}")
    print("  note: VPN permission, accessibility service and Shizuku grant are app-side and are checked from the Traceveil home screen.")
    return EXIT_PASS if bad == 0 else EXIT_DEVICE


def cmd_env(args, cfg, dev: adb.Device) -> int:
    net = cfg["network"]
    mode = args.check_vpn or net["check_vpn"]
    snap = netenv.collect(dev)
    signals = netenv.derive_signals(snap, exclude_own_vpn=not args.include_own_vpn)
    vpn = netenv.check_vpn_proxy(snap, signals)
    eg = None
    if args.online_ip or net["online_ip"]:
        if not net["endpoint"]:
            print("error: --online-ip needs network.endpoint in traceveil.toml (no default service is baked in)", file=sys.stderr)
            return EXIT_USAGE
        redact = net["redact_ip"] and not args.keep_ip
        fetch = (lambda url, t: egress.phone_fetch(dev, url, t)) if args.egress_from == "phone" else None
        eg = egress.lookup(net["endpoint"], Path(".traceveil/cache.json"), net["cache_ttl_hours"], redact,
                           fetch=fetch, vantage=args.egress_from)
    geo = netenv.check_geo(snap, eg["country"] if eg else None, vpn, args.egress_from)
    policy_hit = mode == "fail" and vpn.status == "likely"
    verdict = {"status": "fail" if policy_hit else "pass",
               "reasons": ["VPN/proxy signal present and --check-vpn=fail"] if policy_hit else [],
               "exitCode": EXIT_POLICY if policy_hit else EXIT_PASS}
    rep = report.build_env_report(_device_info(dev), snap, signals, [vpn, geo], eg, verdict)
    print(report.dumps(rep) if args.json else report.summary(rep))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(report.dumps(rep), encoding="utf-8")
    return verdict["exitCode"]


def cmd_audit(args, cfg, dev: adb.Device) -> int:
    io = audit.Io(interactive=sys.stdin.isatty() and sys.stdout.isatty(), color=enabled_for(sys.stderr),
                  color_out=enabled_for(sys.stdout))
    plans = args.plans or None
    return audit.run_audit(dev, cfg, io, package=args.package or "", plans=plans, auto_drive=args.auto_drive, reset=args.reset,
                           wait_s=args.wait, resume=args.resume, as_json=args.json, out_file=args.out,
                           lenses=args.lenses, policy=args.policy)


def _load_report(ref: str | None, cfg) -> dict:
    if ref is None:
        saved = sorted(Path(cfg["output"]["dir"]).glob("*.json"), key=lambda p: p.stat().st_mtime)
        if not saved:
            raise FileNotFoundError(f"no saved audit reports in {cfg['output']['dir']}; pass --report")
        ref = str(saved[-1])
    path = Path(ref)
    if not path.exists():
        path = Path(cfg["output"]["dir"]) / f"{ref}.json"
    if not path.exists():
        raise FileNotFoundError(f"no audit report at {ref} or {path}")
    rep = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(rep, dict) and rep.get("schema") == APP_EXPORT_SCHEMA:
        rep = from_app_export(rep)
    if not isinstance(rep, dict) or "findings" not in rep:
        raise ValueError(f"{path} is not a traceveil audit report")
    return rep


APP_EXPORT_SCHEMA = "traceveil.audit.v1"


def from_app_export(bundle: dict) -> dict:
    """The app's "Export audit data (JSON)" file (`traceveil.audit.v1`, also what traceveil-ci and the Console read)
    in the report shape this CLI uses. Only fields the bundle carries are filled; nothing is invented."""
    meta, result, app = bundle.get("meta") or {}, bundle.get("result") or {}, bundle.get("app") or {}
    # The app's findings name their check "checkId"; this CLI's reports call it "check". Keep both keys.
    findings = [dict(f, check=f.get("check") or f.get("checkId")) for f in (result.get("findings") or []) if isinstance(f, dict)]
    groups = (bundle.get("sdkAttribution") or {}).get("groups") or []
    observed = []
    for g in groups:
        entity = g.get("name") or g.get("entity") if isinstance(g, dict) else None
        if not entity or g.get("known") is False:
            continue
        hosts = [h for h in (g.get("hosts") or []) if isinstance(h, dict)]
        phases = {str(r) for h in hosts for r in (h.get("relations") or [])}
        observed.append({
            "entity": entity,
            "category": g.get("category"),
            "lookups": sum(int(h.get("lookups") or 0) for h in hosts) or None,
            "phases": sorted(phases),
        })
    rep = {
        "schemaVersion": 1,
        # An exported audit has no CLI request id; its audit id identifies it in headers instead.
        "requestId": meta.get("id"),
        "auditId": meta.get("id"),
        "target": {"package": app.get("package") or meta.get("targetPackage"), "label": app.get("label") or meta.get("targetLabel")},
        "score": result.get("score"),
        "band": result.get("band"),
        "findingCount": len(findings),
        "plans": meta.get("plans") or [],
        "findings": findings,
        "sessions": bundle.get("sessions") or [],
        "source": APP_EXPORT_SCHEMA,
    }
    if observed:
        rep["observedTrackers"] = observed
    return rep


def cmd_sdks(args, cfg, dev=None) -> int:
    try:
        rep = _load_report(args.report, cfg) if args.report else None
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_USAGE
    perms = None
    if args.package:
        dev = dev or adb.connect(args.adb or cfg["device"]["adb"] or None, args.serial or cfg["device"]["serial"] or None, args.timeout)
        dump = dev.shell(f"dumpsys package {args.package}")
        if "Unable to find package" in dump or f"Package [{args.package}]" not in dump:
            print(f"error: {args.package} is not installed on the device", file=sys.stderr)
            return EXIT_DEVICE
        perms = sdks.parse_requested_permissions(dump)
    try:
        inv = sdks.build(Path(args.apk), sdks.load_signatures(), rep, perms)
    except sdks.ApkError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_INCONCLUSIVE
    print(report.dumps(inv) if args.json else sdks.summary(inv, Style(enabled_for(sys.stdout))))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(report.dumps(inv), encoding="utf-8")
    flagged = sdks.needs_attention(inv) if args.fail_on_consent else []
    if flagged:
        print(f"policy: {len(flagged)} bundled SDK(s) looked up before consent or after Reject: {', '.join(flagged)}", file=sys.stderr)
    return EXIT_FINDINGS if flagged else EXIT_PASS


def cmd_laws(args, cfg, dev=None) -> int:
    st = Style(enabled_for(sys.stdout))
    if args.list:
        for p in laws.load_profiles():
            pid = f"{p['id']:<16}"
            print(f"  {st.bold(pid)} {p['name']:<20} {p['laws']}")
            print(f"  {'':<16} {st.dim(p.get('status', ''))}")
        return EXIT_PASS
    try:
        rep = _load_report(args.report, cfg)
        block = laws.assess_all(rep, args.lenses or cfg["audit"]["lenses"], args.policy or cfg["audit"]["policy"])
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_USAGE
    code, reasons = audit.exit_for_report(laws.policy_view(block), cfg["audit"]["fail_on"], int(cfg["audit"]["min_score"]))
    if args.json:
        print(report.dumps(block))
    else:
        t = rep.get("target", {})
        print(f"Traceveil lens  {st.bold(t.get('label', '?'))} ({t.get('package', '?')})  report {rep.get('requestId', '?')}")
        print(laws.detail(block, st) if args.detail else "\n".join(laws.table(block, st)))
        for r in reasons:
            print(f"  {st.warn('policy:')} {r}")
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(report.dumps(block), encoding="utf-8")
    return code


def cmd_init(args, cfg, dev=None) -> int:
    target = Path("traceveil.toml")
    if target.exists() and not args.force:
        print("traceveil.toml already exists; use --force to overwrite", file=sys.stderr)
        return EXIT_USAGE
    target.write_text(config.TEMPLATE, encoding="utf-8")
    gi = Path(".gitignore")
    existing = gi.read_text(encoding="utf-8") if gi.exists() else ""
    if ".traceveil/" not in existing.splitlines():
        gi.write_text(existing + ("" if existing.endswith("\n") or not existing else "\n") + ".traceveil/\n", encoding="utf-8")
    print("wrote traceveil.toml and ignored .traceveil/")
    return EXIT_PASS


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        cfg = config.load(args.config)
        if args.command == "init":
            return cmd_init(args, cfg)
        if args.command == "sdks":
            return cmd_sdks(args, cfg)
        if args.command in ("lens", "laws"):
            return cmd_laws(args, cfg)
        dev = adb.connect(args.adb or cfg["device"]["adb"] or None, args.serial or cfg["device"]["serial"] or None, args.timeout)
        return {"doctor": cmd_doctor, "env": cmd_env, "audit": cmd_audit}[args.command](args, cfg, dev)
    except config.ConfigError as e:
        print(f"config error: {e}", file=sys.stderr)
        return EXIT_USAGE
    except adb.AdbError as e:
        print(f"device error: {e}", file=sys.stderr)
        return EXIT_DEVICE

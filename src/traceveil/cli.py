"""traceveil command line: doctor, env, init, audit, watch, lens, fix, sdks."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from . import (audit, EXIT_DEVICE, EXIT_FINDINGS, EXIT_INCONCLUSIVE, EXIT_PASS, EXIT_POLICY, EXIT_USAGE, __version__, adb, apkfile, config,
               egress, fixes, laws, netenv, netwatch, report, sdks, shizuku)
from .colors import Style, enabled_for

APK_CACHE = Path(".traceveil/apks")  # split bundles (.xapk/.apks/.apkm) are unpacked here once


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
    a.add_argument("--apk", "--path", dest="apk", metavar="FILE",
                   help="app file to install and audit: .apk, or a split bundle .xapk/.apks/.apkm (package name is read from it)")
    a.add_argument("--reinstall", action="store_true", help="with --apk: install even when the same version is already on the phone")
    a.add_argument("--no-install", action="store_true", help="with --apk: do not install; only read the package and scan its SDKs")
    a.add_argument("--package", help="app to audit (default: read from --apk, else target.package)")
    a.add_argument("--plans", help="comma list of silent,reject,accept (default: all three)")
    a.add_argument("--auto-drive", action="store_true", help="let Traceveil tap Accept/Reject itself (default: you tap on the phone)")
    a.add_argument("--reset", action="store_true", help="clear the app's data first; only for developer-owned test apps; needs Shizuku")
    a.add_argument("--wait", type=float, default=900.0, help="seconds to wait for the audit (default 900)")
    a.add_argument("--resume", metavar="REQUEST_ID", help="fetch the result of an earlier request instead of starting a new audit")
    a.add_argument("--format", choices=("text", "json", "md"), default="text",
                   help="text summary (default), json report (fixes in fixPlan), or md fix plan")
    a.add_argument("--json", dest="format", action="store_const", const="json", help="same as --format json")
    a.add_argument("--out", help="also write the JSON report (with fixPlan) to this file; a .md path writes the fix plan as Markdown")
    a.add_argument("--lens", "--profile", dest="lenses",
                   help="legal lenses to read the result under: comma list (INDIA,EU,UK,US_CALIFORNIA,US_CHILDREN,BRAZIL,SINGAPORE,"
                        "GLOBAL_BASELINE; short: in,eu,us-ca,coppa,br,sg) or 'all' (default: audit.lenses)")
    a.add_argument("--policy", help="lens whose score decides the exit code (default: audit.policy = INDIA)")
    a.add_argument("--no-net-watch", dest="watch_network", action="store_false", default=None,
                   help="do not sample the app's sockets during the audit (default: audit.watch_network = true)")
    a.add_argument("--fail-on-hidden", action="store_true",
                   help="exit 1 when the app connected through encrypted DNS, direct DNS or to addresses no visible lookup returned")

    w = sub.add_parser("watch", parents=[shared], help="sample an app's live connections for a while and flag traffic the DNS capture cannot see")
    w.add_argument("--package", help="app to watch (default: target.package)")
    w.add_argument("--seconds", type=float, default=60.0, help="how long to sample (default 60)")
    w.add_argument("--interval", type=float, default=1.0, help="seconds between samples (default 1)")
    w.add_argument("--report", help="audit report whose DNS answers the connections are matched against (file or request id)")
    w.add_argument("--fail-on-hidden", action="store_true", help="exit 1 when hidden traffic is found")
    w.add_argument("--json", action="store_true", help="print the JSON result instead of the summary")
    w.add_argument("--out", help="also write the JSON result to this file")

    lw = sub.add_parser("lens", aliases=["laws"], parents=[shared],
                        help="read a saved audit report under the app's legal lenses (several countries' laws; no phone needed)")
    lw.add_argument("--report", help="audit report: a JSON file, or a request id saved under output.dir (default: the newest)")
    lw.add_argument("--lens", "--profile", dest="lenses", help="comma list of lenses or 'all' (default: audit.lenses)")
    lw.add_argument("--policy", help="lens that decides the exit code (default: audit.policy = INDIA)")
    lw.add_argument("--detail", action="store_true", help="show each finding's relation, the law's note and the cited passages")
    lw.add_argument("--list", action="store_true", help="list the lenses and their laws, then exit")
    lw.add_argument("--json", action="store_true", help="print the JSON assessment instead of the summary")
    lw.add_argument("--out", help="also write the JSON assessment to this file")

    fx = sub.add_parser("fix", aliases=["fixes"], parents=[shared],
                        help="how to fix a saved audit: per finding, the code/manifest change and the countries whose law requires it "
                             "(JSON or Markdown a coding agent can work from; no phone needed)")
    fx.add_argument("--report", help="audit report: a JSON file, or a request id saved under output.dir (default: the newest)")
    fx.add_argument("--lens", "--profile", dest="lenses", default="all", help="lenses for the country view (default: all)")
    fx.add_argument("--policy", help="lens that decides the exit code (default: audit.policy = INDIA)")
    fx.add_argument("--format", choices=("text", "json", "md"), default="text",
                    help="text summary (default), json report with fixPlan, or md fix plan")
    fx.add_argument("--json", dest="format", action="store_const", const="json", help="same as --format json")
    fx.add_argument("--out", help="also write the JSON report with fixPlan to this file (a .md path writes the plan as Markdown)")

    k = sub.add_parser("sdks", parents=[shared], help="list tracking SDKs bundled in an APK and compare them with an audit's live lookups")
    k.add_argument("--apk", "--path", dest="apk", required=True, metavar="FILE",
                   help="APK or .xapk/.apks/.apkm bundle to inspect (no phone needed for this part)")
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
    app = None
    if args.apk:
        try:
            app = apkfile.open_app(args.apk, APK_CACHE)
        except apkfile.ApkFileError as e:
            print(f"error: {e}", file=sys.stderr)
            return EXIT_USAGE
        if args.package and args.package != app.package:
            print(f"error: --package {args.package} does not match {Path(args.apk).name} ({app.package})", file=sys.stderr)
            return EXIT_USAGE
        print(f"app: {app.label or app.package} ({app.package} {app.version_name or ''}) from {app.source.name}"
              f"{f', {len(app.apks)} APKs' if len(app.apks) > 1 else ''}", file=sys.stderr)
        if not args.no_install:
            state = apkfile.install(dev, app, force=args.reinstall, log=lambda m: print(m, file=sys.stderr))
            print(f"{app.package}: {state}", file=sys.stderr)
    return audit.run_audit(dev, cfg, io, package=(app.package if app else args.package) or "", app_file=app, plans=plans, auto_drive=args.auto_drive, reset=args.reset,
                           wait_s=args.wait, resume=args.resume, as_json=args.format == "json", as_md=args.format == "md", out_file=args.out,
                           lenses=args.lenses, policy=args.policy, watch_network=args.watch_network,
                           fail_on_hidden=args.fail_on_hidden)


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
    if not isinstance(rep, dict) or "findings" not in rep:
        raise ValueError(f"{path} is not a traceveil audit report")
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
        inv = sdks.build(apkfile.code_apk(args.apk, APK_CACHE), sdks.load_signatures(), rep, perms)
    except (sdks.ApkError, apkfile.ApkFileError) as e:
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


def cmd_fix(args, cfg, dev=None) -> int:
    try:
        rep = _load_report(args.report, cfg)
        policy = args.policy or cfg["audit"]["policy"]
        block = laws.assess_all(rep, args.lenses, policy)
        plan = fixes.build(rep, block)
        code, _ = audit.exit_for_report(laws.policy_view(block), cfg["audit"]["fail_on"], int(cfg["audit"]["min_score"]))
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_USAGE
    full = {**rep, "fixPlan": plan}  # the report itself carries the fixes; an older report gains them here
    if args.format == "json":
        print(report.dumps(full))
    elif args.format == "md":
        print(fixes.markdown(plan))
    else:
        print(fixes.summary(plan, Style(enabled_for(sys.stdout))))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(fixes.markdown(plan) if args.out.endswith(".md") else report.dumps(full), encoding="utf-8")
    return code


def cmd_watch(args, cfg, dev) -> int:
    import time
    st = Style(enabled_for(sys.stdout))
    package = args.package or cfg["target"]["package"]
    if not package:
        print("error: no app: pass --package or set target.package in traceveil.toml", file=sys.stderr)
        return EXIT_USAGE
    try:
        rep = _load_report(args.report, cfg) if args.report else None
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_USAGE
    uid = netwatch.uid_of(dev, package)
    if uid is None:
        print(f"error: {package} is not installed on the device", file=sys.stderr)
        return EXIT_DEVICE
    print(f"watching {package} (uid {uid}) for {args.seconds:.0f}s: use the app now", file=sys.stderr)
    sampler = netwatch.Sampler(dev, uid)
    end = time.monotonic() + args.seconds
    while time.monotonic() < end:
        sampler.sample()
        time.sleep(args.interval)
    net = netwatch.analyze(sampler.seen, rep, sampler.as_dict(args.interval),
                           netwatch.declares_vpn_service(dev.shell(f"dumpsys package {package}")))
    if args.json:
        print(report.dumps(net))
    else:
        print(f"Traceveil watch  {st.bold(package)}" + (f"  matched against {rep.get('requestId')}" if rep else "  (no report: lookups unknown)"))
        print("\n".join(netwatch.summary_lines(net, st, max_rows=40)))
        print(st.dim("  note: " + " ".join(net["limitations"])))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(report.dumps(net), encoding="utf-8")
    return EXIT_FINDINGS if args.fail_on_hidden and netwatch.hidden(net) else EXIT_PASS


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
        if args.command in ("fix", "fixes"):
            return cmd_fix(args, cfg)
        dev = adb.connect(args.adb or cfg["device"]["adb"] or None, args.serial or cfg["device"]["serial"] or None, args.timeout)
        return {"doctor": cmd_doctor, "env": cmd_env, "audit": cmd_audit, "watch": cmd_watch}[args.command](args, cfg, dev)
    except config.ConfigError as e:
        print(f"config error: {e}", file=sys.stderr)
        return EXIT_USAGE
    except adb.AdbError as e:
        print(f"device error: {e}", file=sys.stderr)
        return EXIT_DEVICE

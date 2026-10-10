import { useMemo } from "react";
import { Boxes, ScrollText, AlertOctagon, Gauge as GaugeIcon, ShieldBan, TrendingUp, BarChart3, Building2, Activity, BellRing, FileUp, ArrowRight, Lock } from "lucide-react";
import { Card, PageHead, Fade, Hud } from "../ui";
import { SeverityBadge, ScoreChip, SampleBadge, EmptyState, AppMark, EvidenceChip } from "../ui/tv";
import { ScoreTrend, HBars, seriesColor } from "../components/Charts";
import { useData } from "../data/DataContext";
import { latestByPackage, median, findingsByCheck, trackersByCompany } from "../data/derive";
import { evaluatePolicy } from "../data/policy";
import { sevInfo, PHASE, fmtDate, plural, AUDIT_SCHEMA, QUARANTINE_SCHEMA, bandInfo } from "../data/model";
import { navigate } from "../lib/router";

export default function Portfolio({ onImport }) {
  const { audits, quarantines, policy } = useData();
  const latest = useMemo(() => latestByPackage(audits), [audits]);
  const apps = new Set([...audits.map((a) => a.pkg), ...quarantines.map((q) => q.pkg)]);
  const critical = latest.reduce((n, a) => n + a.findings.filter((f) => f.severity === "CRITICAL").length, 0);
  const med = median(latest.map((a) => a.score));

  const series = useMemo(() => {
    const byPkg = new Map();
    for (const a of [...audits].sort((x, y) => x.createdAtMs - y.createdAtMs)) {
      const s = byPkg.get(a.pkg) || { key: a.pkg, label: a.label, points: [] };
      s.points.push({ t: a.createdAtMs, score: a.score, band: a.band, id: a.id, findings: a.findings.length });
      byPkg.set(a.pkg, s);
    }
    return [...byPkg.values()].map((s, i) => ({ ...s, color: seriesColor(i) }));
  }, [audits]);

  const checks = useMemo(() => findingsByCheck(latest), [latest]);
  const companies = useMemo(() => trackersByCompany(latest), [latest]);
  const attention = useMemo(() => needsAttention(latest, quarantines, policy), [latest, quarantines, policy]);
  const activity = useMemo(() => [
    ...audits.map((a) => ({ t: a.createdAtMs, kind: "audit", a })),
    ...quarantines.map((q) => ({ t: q.startedAtMs, kind: "quarantine", q })),
  ].sort((x, y) => y.t - x.t).slice(0, 6), [audits, quarantines]);

  const empty = !audits.length && !quarantines.length;

  return (
    <div>
      <PageHead
        kicker="Portfolio"
        title="Privacy posture across your apps"
        sub="Computed in this browser from TraceVeil exports. Scores reflect captured evidence only; they are not legal advice or a certificate."
      />

      <div className="grid g5" style={{ marginBottom: 16 }} data-tour="kpis">
        <Kpi icon={Boxes} value={apps.size} label="Apps checked" sub={`${latest.length} audited · ${new Set(quarantines.map((q) => q.pkg)).size} quarantined`} />
        <Kpi icon={ScrollText} value={audits.length} label="Consent audits" sub="3-run X-ray each" />
        <Kpi icon={AlertOctagon} value={critical} label="Critical findings open" sub="latest audit per app" alert={critical > 0} />
        <Kpi icon={GaugeIcon} value={med == null ? "–" : Number.isInteger(med) ? med : med.toFixed(1)} unit={med == null ? "" : "/100"} label="Median score" sub="latest audit per app" />
        <Kpi icon={ShieldBan} value={quarantines.length} label="Quarantine runs" sub="network blocked" />
      </div>

      {empty ? (
        <Card><EmptyState icon={FileUp} title="No exports yet" body={`Drop ${AUDIT_SCHEMA} or ${QUARANTINE_SCHEMA} files anywhere on this page, or load the samples from Settings.`}><button className="btn primary" onClick={onImport}>Import exports</button></EmptyState></Card>
      ) : (
        <div className="grid g-12">
          <Fade className="span-7"><Card title="Score by app over time" icon={TrendingUp} right={<Hud>0–100 · higher is fewer findings</Hud>} style={{ height: "100%" }}>
            {series.length ? <ScoreTrend series={series} tour="trend" /> : <EmptyState title="No audits yet" body="Import an audit export to see scores." />}
            {series.length > 0 && series.every((s) => s.points.length === 1) && <p className="pill-src" style={{ marginTop: 8 }}>One audit per app so far. Re-audit the same package to draw its trend line.</p>}
          </Card></Fade>

          <Fade className="span-5" delay={0.04}><Card title="Needs attention" icon={BellRing} tour="attention" right={<Hud>{attention.length} items</Hud>} style={{ height: "100%" }}>
            {attention.length ? (
              <div className="list">
                {attention.slice(0, 6).map((it, i) => (
                  <button key={i} className="li" onClick={() => navigate(it.to)}>
                    <AppMark label={it.app} size="sm" />
                    <span className="grow"><div className="t" style={{ whiteSpace: "normal" }}>{it.title}</div><div className="s">{it.sub}</div></span>
                    {it.badge}
                  </button>
                ))}
              </div>
            ) : <EmptyState title="Nothing flagged" body="No critical findings, signals or incomplete runs in the latest data." />}
          </Card></Fade>

          <Fade className="span-6" delay={0.06}><Card title="Findings by check" icon={BarChart3} right={<Hud>runs affected · latest audits</Hud>} style={{ height: "100%" }}>
            {checks.length ? (
              <>
                <HBars unit={["run", "runs"]} rows={checks.map((c) => ({
                  key: c.checkId, code: c.checkId, label: c.title,
                  segments: [{ name: c.severity, value: c.runs, color: sevInfo(c.severity).color }],
                  tip: (<><div className="tt">{c.checkId} · {c.title}</div><div className="tr"><span>Severity</span><b>{sevInfo(c.severity).label}</b></div><div className="tr"><span>Apps</span><b>{[...c.apps].join(", ")}</b></div><div className="tr"><span>Runs affected</span><b>{c.runs}</b></div><div className="tr"><span>Evidence</span><b>{[...c.evidence].map((e) => e.toLowerCase()).join(", ")}</b></div></>),
                }))} />
                <div className="chart-legend" style={{ marginTop: 14 }}>
                  {["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((s) => <span key={s}><i style={{ background: sevInfo(s).color }} />{sevInfo(s).label}</span>)}
                </div>
              </>
            ) : <EmptyState title="No findings in the latest audits" body="A clean score covers captured evidence only." />}
          </Card></Fade>

          <Fade className="span-6" delay={0.08}><Card title="Tracker lookups by company" icon={Building2} right={<Hud>by consent phase</Hud>} style={{ height: "100%" }}>
            {companies.length ? (
              <>
                <HBars unit={["lookup", "lookups"]} rows={companies.map((c) => ({
                  key: c.company, label: c.company,
                  segments: ["BEFORE_CONSENT", "AFTER_REJECT", "AFTER_ACCEPT", "NO_CONSENT_SEEN"].map((ph) => ({ name: ph, value: c.phases[ph] || 0, color: PHASE[ph].color })),
                  tip: (<><div className="tt">{c.company}</div><div className="tr"><span>SDKs</span><b>{[...c.entities].join(", ") || "–"}</b></div>{Object.entries(c.phases).map(([ph, n]) => <div key={ph} className="tr"><span>{PHASE[ph]?.short}</span><b>{n}</b></div>)}<div className="tr"><span>Apps</span><b>{[...c.apps].join(", ")}</b></div></>),
                }))} />
                <div className="chart-legend" style={{ marginTop: 14 }}>
                  {["BEFORE_CONSENT", "AFTER_REJECT", "AFTER_ACCEPT"].map((ph) => <span key={ph}><i style={{ background: PHASE[ph].color }} />{PHASE[ph].short}</span>)}
                </div>
                <p className="pill-src" style={{ marginTop: 8 }}>Tracking categories from TraceVeil's tracker catalogue (analytics, advertising, attribution, social, engagement). Crash reporting and first-party hosts are not counted.</p>
              </>
            ) : <EmptyState title="No catalogued trackers" body="No lookup matched a tracking entry in the catalogue." />}
          </Card></Fade>

          <Fade className="span-7" delay={0.1}><Card title="Recent activity" icon={Activity}>
            <div className="list">
              {activity.map((x, i) => x.kind === "audit" ? (
                <button key={i} className="li" onClick={() => navigate(`/audits/${encodeURIComponent(x.a.id)}`)}>
                  <AppMark label={x.a.label} size="sm" />
                  <span className="grow"><div className="t">Consent audit · {x.a.label}</div><div className="s">{fmtDate(x.t)} · {plural(x.a.findings.length, "finding")} · {plural(x.a.lookups, "lookup")}</div></span>
                  {x.a.sample && <span className="desktop-only"><SampleBadge info={x.a.sampleInfo} /></span>}
                  <ScoreChip score={x.a.score} band={x.a.band} />
                </button>
              ) : (
                <button key={i} className="li" onClick={() => navigate(`/quarantine/${encodeURIComponent(x.q.id)}`)}>
                  <AppMark label={x.q.label} size="sm" />
                  <span className="grow"><div className="t">Quarantine run · {x.q.label}</div><div className="s">{fmtDate(x.t)} · {plural(x.q.summary.uniqueDomains, "name")} · {plural(x.q.summary.uniqueIps, "address", "addresses")} · {plural(x.q.dialogs.length, "dialog")}</div></span>
                  {x.q.sample && <span className="desktop-only"><SampleBadge info={x.q.sampleInfo} /></span>}
                </button>
              ))}
            </div>
          </Card></Fade>

          <Fade className="span-5" delay={0.12}><div className="stack" style={{ height: "100%" }}>
            <button className="import-card" onClick={onImport} data-tour="import">
              <span className="ic"><FileUp size={19} /></span>
              <span className="grow" style={{ textAlign: "left" }}><b>Import exports</b><div className="small muted">Drop .json files anywhere, or click to choose. Audit and quarantine exports, plus console backups.</div></span>
              <ArrowRight size={16} className="muted" />
            </button>
            <Card className="good" style={{ flex: 1 }}>
              <div className="row" style={{ marginBottom: 8 }}><Lock size={16} color="var(--clear)" /><b>Local-first by design</b></div>
              <p className="small text-2">Imported files are parsed and stored in this browser's IndexedDB. The console has no server, no analytics and no fonts or scripts from the internet. Clear everything in Settings.</p>
              <div className="row wrap" style={{ marginTop: 10, gap: 6 }}><EvidenceChip type="OBSERVED" small /><EvidenceChip type="INFERRED" small /><EvidenceChip type="NOT_ESTABLISHED" small /></div>
            </Card>
          </div></Fade>
        </div>
      )}
    </div>
  );
}

function Kpi({ icon: I, value, unit, label, sub, alert }) {
  return (
    <div className={`card tight kpi ${alert ? "alert" : ""}`}>
      <div className="kpi-top"><span className="l">{label}</span><span className="kpi-ic"><I size={15} /></span></div>
      <div className="v">{value}{unit && <small>{unit}</small>}</div>
      <div className="sub">{sub}</div>
    </div>
  );
}

function needsAttention(latest, quarantines, policy) {
  const items = [];
  for (const a of latest) {
    const to = `/audits/${encodeURIComponent(a.id)}`;
    for (const f of a.findings.filter((x) => x.severity === "CRITICAL")) {
      items.push({ rank: 0, app: a.label, to, title: `${a.label}: ${f.title.toLowerCase()}`, sub: `${f.checkId} · ${f.evidenceType?.toLowerCase()} · ${f.entities.join(", ") || "see evidence"}`, badge: <SeverityBadge severity="CRITICAL" /> });
    }
    const high = a.findings.filter((x) => x.severity === "HIGH").length;
    if (high) items.push({ rank: 1, app: a.label, to, title: `${a.label}: ${plural(high, "high finding")}`, sub: a.findings.filter((x) => x.severity === "HIGH").map((x) => x.checkId).join(" · "), badge: <SeverityBadge severity="HIGH" count={high} /> });
    if (a.gaps.length) items.push({ rank: 1, app: a.label, to, title: `${a.label}: incomplete runs`, sub: a.gaps[0], badge: <span className="verdict warn">Coverage</span> });
    if (a.score == null) items.push({ rank: 2, app: a.label, to, title: `${a.label}: inconclusive audit`, sub: "Too little network visibility to score", badge: <span className="verdict warn">{bandInfo("INCONCLUSIVE").label}</span> });
    const verdict = evaluatePolicy(policy, a).verdict;
    if (verdict === "fail") items.push({ rank: 3, app: a.label, to: "/policy", title: `${a.label} would fail the release policy`, sub: "Open the policy preview for the reasons", badge: <span className="verdict fail">Gate</span> });
  }
  for (const q of quarantines) {
    const to = `/quarantine/${encodeURIComponent(q.id)}`;
    if (q.summary.dnsBypassSignal) items.push({ rank: 1, app: q.label, to, title: `${q.label}: tried an address without any lookup`, sub: `${q.summary.addressesWithoutLookup.join(", ")} · possible hard-coded address`, badge: <EvidenceChip type="INFERRED" small /> });
    if (q.dialogs.length) items.push({ rank: 2, app: q.label, to, title: `${q.label}: ${plural(q.dialogs.length, "permission dialog")} during quarantine`, sub: q.dialogs[0].text, badge: <EvidenceChip type="OBSERVED" small /> });
    if (q.settings.length) items.push({ rank: 2, app: q.label, to, title: `${q.label}: sensitive settings screen opened`, sub: q.settings.map((s) => s.title || s.kind).join(", "), badge: <EvidenceChip type="OBSERVED" small /> });
  }
  return items.sort((a, z) => a.rank - z.rank);
}

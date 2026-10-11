import { useMemo } from "react";
import { GitCompareArrows, CheckCircle2, PlusCircle, MinusCircle, Equal, AlertTriangle, Info, Globe2 } from "lucide-react";
import { Card, PageHead, Fade, Hud } from "../ui";
import { ScoreChip, SeverityBadge, EvidenceChip, EmptyState, SampleBadge, AppMark, PlanTag } from "../ui/tv";
import { useData } from "../data/DataContext";
import { compareAudits } from "../data/derive";
import { fmtDate, plural, bandInfo } from "../data/model";
import { navigate } from "../lib/router";

function defaults(audits) {
  // Prefer two audits of the same package (older → newer); otherwise the lowest and highest score.
  const byPkg = new Map();
  for (const a of audits) byPkg.set(a.pkg, [...(byPkg.get(a.pkg) || []), a]);
  const multi = [...byPkg.values()].find((l) => l.length > 1);
  if (multi) { const s = [...multi].sort((x, y) => x.createdAtMs - y.createdAtMs); return [s[s.length - 2], s[s.length - 1]]; }
  const s = [...audits].sort((x, y) => (x.score ?? -1) - (y.score ?? -1));
  return [s[0], s[s.length - 1]];
}

export default function Compare({ query }) {
  const { audits } = useData();
  const [dA, dB] = defaults(audits);
  const base = audits.find((a) => a.id === query.a) || dA;
  const cand = audits.find((a) => a.id === query.b) || (dB !== base ? dB : audits.find((a) => a !== base));
  const cmp = useMemo(() => (base && cand && base !== cand ? compareAudits(base, cand) : null), [base, cand]);
  const set = (key, id) => {
    const q = { a: base?.id, b: cand?.id, [key]: id };
    navigate(`/compare?a=${encodeURIComponent(q.a)}&b=${encodeURIComponent(q.b)}`);
  };

  if (audits.length < 2) {
    return (
      <div>
        <PageHead kicker="Compare" title="Compare two audits" sub="Import at least two audits, ideally of the same package before and after a fix." />
        <Card><EmptyState icon={GitCompareArrows} title="Two audits needed" body="Re-audit the same app after a change and import both exports." /></Card>
      </div>
    );
  }

  const deltaClass = cmp?.delta == null ? "flat" : cmp.delta > 0 ? "up" : cmp.delta < 0 ? "down" : "flat";
  return (
    <div>
      <PageHead kicker="Compare" title="What changed between two audits" sub="Findings are matched by check. A score change only means something when both audits ran the same plans, timing and package." />

      <div className="grid g2" style={{ marginBottom: 16 }}>
        <Picker label="Baseline" value={base?.id} audits={audits} onChange={(id) => set("a", id)} />
        <Picker label="Candidate" value={cand?.id} audits={audits} onChange={(id) => set("b", id)} />
      </div>

      {!cmp ? <Card><EmptyState title="Pick two different audits" /></Card> : (
        <>
          <Fade><Card className={cmp.samePkg ? "glow" : ""} tour="compare-delta">
            <div className="grid g-12" style={{ alignItems: "center" }}>
              <div className="span-4"><Side a={base} label="Baseline" /></div>
              <div className="span-4" style={{ textAlign: "center" }}>
                <Hud>Score change</Hud>
                <div className={`delta ${deltaClass}`} style={{ marginTop: 8 }}>{cmp.delta == null ? "n/a" : `${cmp.delta > 0 ? "+" : ""}${cmp.delta}`}</div>
                <div className="small muted" style={{ marginTop: 6 }}>{base.score ?? "–"} → {cand.score ?? "–"} · {bandInfo(base.band).short} → {bandInfo(cand.band).short}</div>
                {base.versionName && cand.versionName && (
                  <div className="small cmp-version" style={{ marginTop: 6 }}>
                    Version {base.versionName} → {cand.versionName}
                    {base.versionCode != null && cand.versionCode != null && <span className="muted"> (version code {base.versionCode} → {cand.versionCode})</span>}
                  </div>
                )}
                <div className="row" style={{ justifyContent: "center", marginTop: 10 }}>
                  {cmp.samePkg ? <span className="verdict pass"><CheckCircle2 size={13} />Same package</span> : <span className="verdict warn"><AlertTriangle size={13} />Different packages</span>}
                </div>
              </div>
              <div className="span-4"><Side a={cand} label="Candidate" right /></div>
            </div>
            {cmp.caveats.length > 0 && (
              <div className="stack-sm" style={{ marginTop: 16 }}>
                {cmp.caveats.map((c, i) => (
                  <div key={i} className="callout" style={c.tone === "amber" ? { borderColor: "rgba(242,181,68,0.35)", background: "rgba(242,181,68,0.06)" } : undefined}>
                    {c.tone === "amber" ? <AlertTriangle size={16} color="var(--sev-medium)" style={{ flex: "none", marginTop: 2 }} /> : <Info size={16} color="var(--scan)" style={{ flex: "none", marginTop: 2 }} />}
                    <span className="text-2">{c.text}</span>
                  </div>
                ))}
              </div>
            )}
          </Card></Fade>

          <div className="grid g3" style={{ marginTop: 16 }}>
            <Fade delay={0.04}><Column icon={MinusCircle} tone="var(--clear)" title="Resolved" sub="recorded before, not now" items={cmp.resolved} /></Fade>
            <Fade delay={0.06}><Column icon={PlusCircle} tone="var(--sev-critical)" title="New" sub="recorded now, not before" items={cmp.added} /></Fade>
            <Fade delay={0.08}><Column icon={Equal} tone="var(--muted)" title="Unchanged" sub="recorded in both" items={cmp.unchanged} /></Fade>
          </div>

          <Fade delay={0.1}><Card title="Hosts looked up" icon={Globe2} style={{ marginTop: 16 }} right={<Hud>by host name</Hud>}>
            <div className="grid g3">
              <HostCol title="Only in baseline" hosts={cmp.hostsOnlyBase} />
              <HostCol title="Only in candidate" hosts={cmp.hostsOnlyCand} />
              <HostCol title="In both" hosts={cmp.hostsBoth} />
            </div>
            <div className="table-wrap" style={{ marginTop: 16 }}>
              <table className="tbl">
                <thead><tr><th>Plan</th><th className="num">Baseline lookups</th><th className="num">Candidate lookups</th><th>Baseline choice</th><th>Candidate choice</th></tr></thead>
                <tbody>
                  {["SILENT", "REJECT", "ACCEPT"].map((p) => {
                    const sb = base.sessions.find((s) => s.plan === p);
                    const sc = cand.sessions.find((s) => s.plan === p);
                    if (!sb && !sc) return null;
                    const ch = (s) => (!s ? "not run" : s.choice ? `"${s.choice.nodeText || s.choice.kind}" at ${s.choice.t.toFixed(1)} s` : s.expects ? "not recorded" : "none (by design)");
                    return <tr key={p}><td><PlanTag plan={p} /></td><td className="num mono-s">{sb ? sb.events.length : "–"}</td><td className="num mono-s">{sc ? sc.events.length : "–"}</td><td className="small">{ch(sb)}</td><td className="small">{ch(sc)}</td></tr>;
                  })}
                </tbody>
              </table>
            </div>
          </Card></Fade>
        </>
      )}
    </div>
  );
}

function Picker({ label, value, audits, onChange }) {
  return (
    <label className="field">
      <Hud>{label}</Hud>
      <select className="select" value={value || ""} onChange={(e) => onChange(e.target.value)}>
        {audits.map((a) => <option key={a.id} value={a.id}>{a.label} · {a.pkg}{a.versionName ? ` v${a.versionName}` : ""} · {fmtDate(a.createdAtMs)} · {a.score ?? "–"}/100{a.sample ? " · sample" : ""}</option>)}
      </select>
    </label>
  );
}

function Side({ a, label, right }) {
  return (
    <div className="row" style={{ justifyContent: right ? "flex-end" : "flex-start", textAlign: right ? "right" : "left", gap: 12 }}>
      {!right && <AppMark label={a.label} />}
      <div style={{ minWidth: 0 }}>
        <Hud>{label}</Hud>
        <div className="row" style={{ gap: 8, justifyContent: right ? "flex-end" : "flex-start", marginTop: 4 }}><b className="display" style={{ fontSize: 17 }}>{a.label}</b><ScoreChip score={a.score} band={a.band} /></div>
        <div className="mono-s muted" style={{ marginTop: 2, overflowWrap: "anywhere" }}>{a.pkg}</div>
        <div className="small muted">{fmtDate(a.createdAtMs)} {a.sample && <SampleBadge info={a.sampleInfo} />}</div>
      </div>
      {right && <AppMark label={a.label} />}
    </div>
  );
}

function Column({ icon: I, tone, title, sub, items }) {
  return (
    <Card style={{ height: "100%" }}>
      <div className="row" style={{ marginBottom: 12 }}><I size={18} color={tone} /><b className="display" style={{ fontSize: 16 }}>{title}</b><span className="badge">{items.length}</span><span className="grow" /><span className="pill-src">{sub}</span></div>
      <div className="cmp-col">
        {items.length ? items.map((f) => (
          <div key={f.checkId} className="cmp-item" title={f.explanation}>
            <span className="f-code mono-s" style={{ fontWeight: 700 }}>{f.checkId}</span>
            <span className="t">{f.title}</span>
            <SeverityBadge severity={f.severity} />
          </div>
        )) : <p className="small muted">None.</p>}
      </div>
      {items.some((f) => f.evidenceType === "INFERRED") && <div className="row" style={{ marginTop: 10, gap: 6 }}><EvidenceChip type="INFERRED" small /><span className="pill-src">some of these are inferred</span></div>}
    </Card>
  );
}

function HostCol({ title, hosts }) {
  return (
    <div>
      <Hud>{title} · {hosts.length}</Hud>
      <div className="stack-sm" style={{ marginTop: 8 }}>
        {hosts.length ? hosts.map((h) => (
          <div key={h.host} className="row" style={{ gap: 8, minWidth: 0 }}>
            <i style={{ width: 8, height: 8, borderRadius: "50%", flex: "none", background: h.tracking ? "var(--sev-high)" : "var(--lav)" }} />
            <span className="host" style={{ minWidth: 0 }}>{h.host}</span>
            <span className="pill-src" style={{ marginLeft: "auto", flex: "none" }}>{plural(h.count, "lookup")}</span>
          </div>
        )) : <span className="small muted">None</span>}
      </div>
    </div>
  );
}

import { Fragment, useState } from "react";
import { ClipboardCheck, Gauge as GaugeIcon, Waypoints, ListChecks, Globe2, Smartphone, GitCompareArrows, ChevronDown, ChevronRight, Wrench, ExternalLink, CheckCircle2, AlertTriangle, HelpCircle, FlaskConical, ScanEye, Trash2, Scale, Boxes } from "lucide-react";
import { Card, PageHead, Fade, Hud, Sheet, toast } from "../ui";
import { Gauge } from "../ui/charts";
import { EvidenceChip, SeverityBadge, BandBadge, PlanTag, SampleBadge, CodeBlock, DownloadButton, EmptyState, Truth, Meta, AppMark } from "../ui/tv";
import ConsentLanes from "../components/ConsentLanes";
import { useData } from "../data/DataContext";
import { flattenTree } from "../data/derive";
import { sevInfo, bandInfo, fmtDate, fmtS, plural, fixesForFinding, passageFor, refLabel, CATEGORY_LABEL, PHASE, planInfo, LENS, LENS_ORDER, lensMapping, relationLabel, lensPassageFor, lensExplanation, lensScore, PASSAGE_KIND, CHECKS, TRACKING, fixTemplate, sdkFirstSeenLine, sdkHeadline, SDK_LOOKUP_CAVEAT, SDK_BEFORE_CONSENT, SDK_AFTER_REJECT } from "../data/model";
import { navigate } from "../lib/router";

export default function AuditDetail({ id }) {
  const { audits, deleteRecord } = useData();
  const a = audits.find((x) => x.id === id);
  const [ref, setRef] = useState(null);
  if (!a) return <Card><EmptyState icon={HelpCircle} title="Audit not found" body="It may have been removed from this browser."><button className="btn" onClick={() => navigate("/audits")}>All audits</button></EmptyState></Card>;
  const others = audits.filter((x) => x.id !== a.id);
  const compareTo = others.find((x) => x.pkg === a.pkg) || others[0];
  const b = bandInfo(a.band);
  const fileName = `${a.pkg}-${a.id}.traceveil.json`;
  const remove = async () => {
    if (!window.confirm(`Remove this audit of ${a.label} from this browser?`)) return;
    await deleteRecord("audit", a.id); toast("Audit removed from this browser", "ok"); navigate("/audits");
  };

  return (
    <div>
      <PageHead
        kicker={<span className="row" style={{ gap: 8, display: "inline-flex" }}>Consent audit {a.sample && <SampleBadge info={a.sampleInfo} />}</span>}
        title={<span className="row" style={{ gap: 12, display: "inline-flex" }}><AppMark label={a.label} size="lg" />{a.label}</span>}
        sub={<Meta items={[<span className="mono-s" key="p">{a.pkg}</span>, a.device && <span key="d"><Smartphone size={13} />{a.device}{a.android ? ` · Android ${a.android}` : ""}</span>, <span key="t">{fmtDate(a.createdAtMs)}</span>, a.versionName && <span key="v">Version {a.versionName}{a.versionCode != null ? ` (${a.versionCode})` : ""}</span>, <span key="l">{plural(a.lookups, "lookup")}</span>]} />}
        right={(
          <div className="row wrap">
            {compareTo && <button className="btn sm" onClick={() => navigate(`/compare?a=${encodeURIComponent(a.id)}&b=${encodeURIComponent(compareTo.id)}`)}><GitCompareArrows size={15} />Compare</button>}
            <DownloadButton name={fileName} text={JSON.stringify(a.rec.bundle, null, 2)} label="Export JSON" />
            <button className="btn sm icon ghost" onClick={remove} aria-label="Remove audit" title="Remove from this browser"><Trash2 size={15} /></button>
          </div>
        )}
      />

      {a.sample && a.sampleInfo?.note && (
        <div className="callout" style={{ marginBottom: 16, borderStyle: "dashed", borderColor: "rgba(159,160,195,0.45)" }}>
          <FlaskConical size={17} color="var(--lav)" style={{ flex: "none", marginTop: 2 }} />
          <div>{!/^sample/i.test(a.sampleInfo.note) && <b>Sample data. </b>}<span className="text-2">{a.sampleInfo.note}</span>{a.sampleInfo.sources?.length > 0 && <div className="pill-src" style={{ marginTop: 6 }}>Sources: {a.sampleInfo.sources.join(" · ")}</div>}</div>
        </div>
      )}

      <div className="grid g-12">
        <Fade className="span-8"><Coverage a={a} /></Fade>
        <Fade className="span-4" delay={0.04}>
          <Card title="Score" icon={GaugeIcon} className="glow" right={<BandBadge band={a.band} />} style={{ height: "100%" }}>
            {a.score == null ? (
              <div className="center" style={{ height: 210, textAlign: "center" }}><div><div className="num" style={{ fontSize: 46 }}>–</div><div style={{ color: b.color, fontWeight: 700 }}>{b.label}</div><p className="small muted" style={{ marginTop: 6 }}>Too little network visibility to score. Unknown is not zero.</p></div></div>
            ) : <Gauge score={a.score} label={b.label} sub="captured evidence" color={b.color} size={208} />}
            <div className="row wrap" style={{ justifyContent: "center", gap: 6, marginTop: 6 }}>
              {Object.entries(a.sevCounts).sort((x, y) => sevInfo(x[0]).rank - sevInfo(y[0]).rank).map(([s, n]) => <SeverityBadge key={s} severity={s} count={n} />)}
              {!a.findings.length && <span className="small muted">No finding in the captured evidence</span>}
            </div>
            <p className="pill-src" style={{ marginTop: 12, textAlign: "center" }}>Consent readiness: 100 minus severity weights; inferred findings count half. Not a certificate.</p>
            <LensLine a={a} />
          </Card>
        </Fade>

        <Fade className="span-12" delay={0.06}>
          <Card title="Consent timeline" icon={Waypoints} right={<Hud>DNS lookups vs. consent choice</Hud>}>
            <ConsentLanes sessions={a.sessions} tour="lanes" />
          </Card>
        </Fade>
      </div>

      <div className="section-title"><ListChecks size={18} /><h2>Findings</h2><span className="badge">{a.findings.length}</span><span className="grow" /></div>
      <div className="grid g-12">
        <div className="span-8 stack">
          {a.findings.length ? a.findings.map((f, i) => <FindingCard key={`${f.checkId}${i}`} f={f} index={i} onRef={setRef} lens={a.lens.id} />) : (
            <Card className="good"><EmptyState icon={CheckCircle2} title="No findings in the captured evidence" body="TraceVeil found nothing to flag in these runs. That covers what was captured on this device during these plans; it is not a certificate that the app is compliant or safe." /></Card>
          )}
        </div>
        <div className="span-4 stack">
          <ScreenCard a={a} />
          <Card title="Evidence labels" icon={ScanEye}>
            <div className="stack-sm">
              <div className="row-top"><EvidenceChip type="OBSERVED" /><span className="small text-2">Seen on the device: captured lookups or the consent screen's accessibility tree.</span></div>
              <div className="row-top"><EvidenceChip type="INFERRED" /><span className="small text-2">Reasoned from vendor documentation or the on-device AI. Counts half in the score.</span></div>
              <div className="row-top"><EvidenceChip type="NOT_ESTABLISHED" /><span className="small text-2">This capture cannot tell. Unknown stays unknown, never zero.</span></div>
            </div>
          </Card>
        </div>
        {a.sdkGroups && <Fade className="span-12"><SdkCard a={a} /></Fade>}
        <Fade className="span-12"><HostsCard a={a} /></Fade>
      </div>

      <Sheet open={!!ref} onClose={() => setRef(null)} title={ref ? (lensPassageFor(ref)?.source || refLabel(ref)) : ""}>
        {ref && (lensPassageFor(ref) ? <LensRefBody p={lensPassageFor(ref)} /> : <RefBody id={ref} />)}
      </Sheet>
    </div>
  );
}

function Coverage({ a }) {
  const complete = !a.gaps.length;
  const fresh = a.sessions.map((s) => s.freshState);
  const freshText = fresh.every((x) => x === true) ? "Every plan started from a cleared app" : fresh.every((x) => x == null) ? "Not recorded" : a.sessions.map((s) => `${s.code}: ${s.freshState === true ? "cleared" : s.freshState === false ? "kept data" : "not recorded"}`).join(" · ");
  const health = a.sessions.filter((s) => s.health).length;
  const pdns = a.privateDns === true ? "On (lookups may bypass capture)" : a.privateDns === false ? "Off" : "Not recorded";
  return (
    <Card title="Audit coverage" icon={ClipboardCheck} tour="coverage" right={complete ? <span className="verdict pass"><CheckCircle2 size={13} />All planned choices recorded</span> : <span className="verdict warn"><AlertTriangle size={13} />Incomplete</span>} style={{ height: "100%" }}>
      <div>
        {a.sessions.map((s) => (
          <div key={s.id} className="cov-row">
            {s.completed ? <CheckCircle2 size={18} className="cov-ok" /> : <AlertTriangle size={18} className="cov-warn" />}
            <div style={{ minWidth: 0 }}>
              <div className="row" style={{ gap: 8, marginBottom: 4 }}><PlanTag plan={s.plan} /></div>
              <div className="cov-steps">
                <span>Launch</span>
                {s.consent.map((c, i) => (
                  <span key={i} className="row" style={{ gap: 6 }}><span className="arrow">→</span>
                    <span>{c.kind === "SCREEN_APPEARED" ? "Consent screen" : c.kind === "SETTINGS" ? `"${c.nodeText || "Settings"}"` : c.kind === "MANUAL_MARK" ? "Marked" : c.kind === "PERMISSION_DIALOG" ? "Android permission dialog" : `"${c.nodeText || c.kind}" tapped`} <span className="mono-s muted">{fmtS(c.t)}</span></span>
                  </span>
                ))}
                {!s.screenSeen && <span><span className="arrow">→ </span>No consent screen seen</span>}
                {!s.completed && <span style={{ color: "var(--sev-medium)" }}>· planned {s.expects === "ACCEPT" ? "Accept" : "Reject"} not recorded</span>}
                {s.unexpectedChoice && <span style={{ color: "var(--sev-medium)" }}>· a choice was recorded in the no-interaction plan</span>}
              </div>
            </div>
            <div style={{ textAlign: "right" }}><div className="num" style={{ fontSize: 18 }}>{s.events.length}</div><div className="pill-src">lookups{s.windowS ? ` · ${fmtS(s.windowS)}` : ""}</div></div>
          </div>
        ))}
      </div>
      <dl className="kv" style={{ marginTop: 14 }}>
        <dt>Fresh app state</dt><dd>{freshText}</dd>
        <dt>Capture health</dt><dd>{health ? `Recorded for ${health} of ${a.sessions.length} plans` : "Not recorded"}{a.sessions.some((s) => s.health?.notes?.length) ? " · with notes" : ""}</dd>
        <dt>Private DNS</dt><dd>{pdns}</dd>
        <dt>Timing profile</dt><dd>{a.timingProfile === "normal" ? "Normal" : a.timingProfile === "fast_preview" ? "Fast preview (shorter windows)" : "Not recorded"}</dd>
        <dt>Upload readings</dt><dd>{a.networkReadings.length ? `${a.networkReadings.length} per-plan readings in the export` : "Not included in this export"}</dd>
        {a.tool && <><dt>Exported by</dt><dd>{[a.tool.name, a.tool.version].filter(Boolean).join(" ")}{a.exportedAt ? ` · exported ${exportedText(a.exportedAt)}` : ""}</dd></>}
      </dl>
      <div style={{ marginTop: 12 }}><Truth>Coverage describes this capture on this device. It does not certify the app or prove complete network visibility.</Truth></div>
    </Card>
  );
}

const exportedText = (v) => { const t = typeof v === "number" ? v : Date.parse(v); return Number.isFinite(t) ? fmtDate(t) : String(v); };

function evidenceLine(line) {
  const i = line.indexOf(" — ");
  if (i < 0) return line;
  return <><b>{line.slice(0, i)}</b>{line.slice(i)}</>;
}

/** The legal lens named in the bundle (else the global baseline) and the score under it. Not a verdict. */
function LensLine({ a }) {
  const p = LENS[a.lens.id];
  const score = lensScore(a.findings, a.lens.id);
  const na = a.findings.filter((f) => lensMapping(a.lens.id, f.checkId).relation === "NOT_APPLICABLE").map((f) => f.checkId);
  const from = a.lens.source === "bundle" ? "named in the export" : a.lens.source === "unknown" ? `the export names "${a.lens.named}", unknown here` : "the export names no lens";
  return (
    <div className="lens-line" data-lens={a.lens.id} style={{ marginTop: 10, textAlign: "center" }}>
      <div className="small"><b>Legal lens: {p.name}</b> <span className="muted">({from})</span></div>
      <div className="small text-2">Under this lens: {score == null ? "inconclusive" : `${score}/100`}{na.length ? ` · skips ${na.join(", ")} (not applicable)` : ""}</div>
      <p className="pill-src" style={{ marginTop: 4 }}>{p.laws}. A reading of the findings, not a legal verdict.</p>
    </div>
  );
}

function FindingCard({ f, index, onRef, lens }) {
  const [all, setAll] = useState(false);
  const [fixOpen, setFixOpen] = useState(index === 0);
  const fixes = fixesForFinding(f);
  const s = sevInfo(f.severity);
  const lines = all ? f.evidence : f.evidence.slice(0, 3);
  const m = lensMapping(lens, f.checkId);
  const expl = lensExplanation(f.explanation, lens);
  const refs = lens === "INDIA" ? f.dpdpRefs : (m.refs || []);
  return (
    <section className="finding" style={{ "--c": s.color }} data-tour={`finding-${index}`}>
      <div className="f-head">
        <span className="f-code">{f.checkId}</span>
        <div className="grow">
          <div className="f-title">{f.title}</div>
          <div className="f-tags">
            <SeverityBadge severity={f.severity} />
            <EvidenceChip type={f.evidenceType} />
            {f.confidence < 1 && <span className="pill-src">confidence {Math.round(f.confidence * 100)}%</span>}
            <span className="row" style={{ gap: 4 }}>{f.plans.map((p) => <PlanTag key={p} plan={p} withLabel={false} />)}</span>
            <span className={`lens-rel rel-${m.relation.toLowerCase()}`} title={m.note}>{LENS[lens].name}: {relationLabel(lens, m.relation)}</span>
          </div>
        </div>
      </div>
      <div className="f-body">
        <p className="f-expl">{expl.text}</p>
        {expl.citesIndia && <p className="small muted">This explanation cites India's DPDP Act; the selected lens's law is listed below.</p>}
        {f.evidence.length > 0 && (
          <div>
            <Hud>Evidence · {f.evidence.length} line{f.evidence.length === 1 ? "" : "s"}</Hud>
            <ul className="ev-lines" style={{ marginTop: 6 }}>{lines.map((l, i) => <li key={i}>{evidenceLine(l)}</li>)}</ul>
            {f.evidence.length > 3 && <button className="link small" style={{ marginTop: 6 }} onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${f.evidence.length}`}</button>}
          </div>
        )}
        <p className="small text-2" style={{ margin: 0 }}><Scale size={13} className="muted" /> <b>{LENS[lens].name}:</b> {m.note}</p>
        {refs.length > 0 && (
          <div className="refs"><Scale size={14} className="muted" /><span className="small muted">Legal context:</span>
            {refs.map((r) => <button key={r} className="ref" onClick={() => onRef(r)} title={(lensPassageFor(r) || passageFor(r))?.title || r}>{lensPassageFor(r)?.source || refLabel(r)}</button>)}
          </div>
        )}
        <details className="compare-laws"><summary className="small muted">Compare laws</summary>
          <ul className="small">{LENS_ORDER.map((id) => { const x = lensMapping(id, f.checkId); return <li key={id}><b>{LENS[id].name}</b>: {relationLabel(id, x.relation)}. <span className="text-2">{x.note}</span></li>; })}</ul>
        </details>
        {fixes.length > 0 && (
          <div className="fix">
            <button className="fix-toggle" onClick={() => setFixOpen(!fixOpen)}>{fixOpen ? <ChevronDown size={16} /> : <ChevronRight size={16} />}<Wrench size={15} />How to fix · {fixes.length}</button>
            {fixOpen && fixes.map((t) => <FixItem key={t.id} t={t} />)}
          </div>
        )}
      </div>
    </section>
  );
}

function FixItem({ t }) {
  return (
    <div className="fix-item">
      <div className="row between wrap"><b>{t.title}</b>{!t.verified && <span className="status dev">check your SDK version</span>}</div>
      {t.manifest && <CodeBlock title="AndroidManifest.xml" code={t.manifest} />}
      {t.code && <CodeBlock title="Kotlin" code={t.code} />}
      {t.steps?.length > 0 && <ul>{t.steps.map((x, i) => <li key={i}>{x}</li>)}</ul>}
      {t.docUrl && <a className="link small row" style={{ gap: 6 }} href={t.docUrl} target="_blank" rel="noreferrer noopener"><ExternalLink size={13} />Vendor documentation</a>}
    </div>
  );
}

/** "Which SDK caused this?": the export's sdkAttribution groups (core SdkAttribution), shown as exported. */
function SdkCard({ a }) {
  const groups = a.sdkGroups;
  const known = groups.filter((g) => g.known !== false);
  const linked = known.filter((g) => (g.checks || []).length > 0);
  const quiet = known.filter((g) => !(g.checks || []).length);
  const unknown = groups.find((g) => g.known === false);
  const summary = sdkHeadline(groups) || (groups.length ? "No finding in this audit names a catalogued SDK." : "No lookups were captured in this audit.");
  return (
    <Card title="Which SDK caused this?" icon={Boxes} tour="sdk" right={<Hud>{plural(known.length, "catalogued service")} · from the export</Hud>}>
      <p className="small text-2" style={{ margin: "0 0 10px" }}>{summary}</p>
      <Truth>{SDK_LOOKUP_CAVEAT}</Truth>
      {linked.length > 0 && <div className="sdk-groups">{linked.map((g) => <SdkGroupRow key={g.name} g={g} findings={a.findings} />)}</div>}
      {quiet.length > 0 && (
        <details className="sdk-more">
          <summary className="small muted"><ChevronRight size={14} className="chev" /><span>Also contacted, no finding ({quiet.length})</span></summary>
          <div className="sdk-groups">{quiet.map((g) => <SdkGroupRow key={g.name} g={g} findings={a.findings} />)}</div>
        </details>
      )}
      {unknown && <div className="sdk-groups"><SdkGroupRow g={unknown} findings={a.findings} /></div>}
      {a.sdkBasis && <p className="pill-src" style={{ marginTop: 10 }}>{a.sdkBasis}</p>}
    </Card>
  );
}

function SdkGroupRow({ g, findings }) {
  const isKnown = g.known !== false;
  const first = sdkFirstSeenLine(g);
  const early = isKnown && (first.endsWith(SDK_BEFORE_CONSENT) || first.endsWith(SDK_AFTER_REJECT));
  const hosts = Array.isArray(g.hosts) ? g.hosts : [];
  const lookups = hosts.reduce((n, h) => n + (h.lookups || 0), 0);
  const fixes = (g.fixIds || []).map((id) => fixTemplate(id) || { id, title: id, verified: true });
  const tone = !isKnown ? "var(--lav)" : TRACKING.has(g.category) ? "var(--sev-high)" : "var(--scan)";
  const who = [g.company && `Company: ${g.company}`, g.sameCompanyAsApp && "same company as the app"].filter(Boolean).join(" · ");
  return (
    <div className="sdk-group" style={{ "--c": tone }}>
      <div className="row between wrap" style={{ gap: 8 }}>
        <b className="sdk-name">{g.name}</b>
        <span className="lens-rel">{isKnown ? CATEGORY_LABEL[g.category] || g.category : "Not in catalogue"}</span>
      </div>
      {who && <div className="small muted">{who}</div>}
      {first && <div className="small" style={{ color: early ? "var(--sev-medium)" : "var(--text-2)" }}>{first}</div>}
      <div className="f-tags">
        {(g.checks || []).length > 0 && <span className="small muted">Checks:</span>}
        {(g.checks || []).map((id) => {
          const f = findings.find((x) => x.checkId === id);
          return <span key={id} className={`sev-badge sev-${String(f?.severity || "info").toLowerCase()}`} title={f?.title || CHECKS[id] || id}>{id}</span>;
        })}
        <EvidenceChip type={g.evidence} small />
        {g.evidence === "OBSERVED" && <span className="pill-src">{plural(lookups, "lookup")}</span>}
      </div>
      {hosts.length > 0 && (
        <ul className="sdk-hosts">
          {hosts.map((h) => (
            <li key={h.host}>
              <span className="host">{h.host}</span>{" "}
              <span className="pill-src">
                {plural(h.lookups || 0, "lookup")} · first {fmtS(h.firstSeenSeconds)} after launch, {h.firstRelation} ({planInfo(h.firstPlan).label})
                {(h.relations || []).filter((r) => r !== h.firstRelation).length > 0 ? ` · also ${(h.relations || []).filter((r) => r !== h.firstRelation).join(", ")}` : ""}
                {h.viaAlias ? ` · through the alias ${h.viaAlias}` : ""}
                {h.viaPackage ? ` · looked up by ${h.viaPackage}` : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
      {g.catalogueMatch && <p className="small text-2" style={{ margin: 0 }}>{g.catalogueMatch}</p>}
      {fixes.length > 0 && (
        <details className="sdk-fix">
          <summary className="small"><ChevronRight size={14} className="chev" /><Wrench size={13} /><span>How to fix: {fixes.map((t, i) => <Fragment key={t.id}>{i > 0 ? " · " : ""}{t.title}</Fragment>)}</span></summary>
          {fixes.map((t) => <FixItem key={t.id} t={t} />)}
        </details>
      )}
    </div>
  );
}

function ScreenCard({ a }) {
  const s = a.sessions.find((x) => x.screenTree) || null;
  const sa = a.sessions.find((x) => x.screenAudit)?.screenAudit || null;
  const rows = s ? flattenTree(s.screenTree) : [];
  const obs = new Set(sa?.observedFields || []);
  const field = (name, v, fmt) => (
    <span className="row wrap" style={{ gap: 6 }}><span className="nowrap">{fmt(v)}</span><EvidenceChip type={v == null ? "NOT_ESTABLISHED" : obs.has(name) ? "OBSERVED" : "INFERRED"} small /></span>
  );
  return (
    <Card title="Consent screen" icon={Smartphone} right={<Hud>accessibility tree</Hud>}>
      {rows.length ? (
        <div className="tree-mock" aria-label="Consent screen as captured from the accessibility tree">
          {rows.map((r, i) => (
            <div key={i} className={`tree-row ${i === 0 ? "title" : ""}`}>
              {r.checkable && <span className={`cb ${r.checked ? "on" : ""}`}>{r.checked ? "✓" : ""}</span>}
              {r.cls === "Button" ? <span className="btnlike">{r.label}</span> : r.clickable && !r.checkable ? <span className="small-link">{r.label}</span> : <span>{r.label || (r.checkable ? <span className="muted">{r.cls}</span> : null)}</span>}
            </div>
          ))}
        </div>
      ) : <p className="small muted">No consent-screen tree was captured in this export.</p>}
      {sa && (
        <dl className="kv" style={{ marginTop: 12 }}>
          <dt>Pre-ticked option</dt><dd>{field("preTicked", sa.preTicked, (v) => (v == null ? "Unknown" : v ? "Yes" : "No"))}</dd>
          <dt>Reject option</dt><dd>{field("rejectOption", sa.rejectOption === "UNKNOWN" ? null : sa.rejectOption, (v) => (v == null ? "Unknown" : String(v).toLowerCase()))}</dd>
          <dt>Languages</dt><dd>{field("languageOptions", sa.languageOptions, (v) => (v == null ? "Unknown" : v.join(", ")))}</dd>
          <dt>Withdrawal info</dt><dd>{field("withdrawalInfo", sa.withdrawalInfo, (v) => (v == null ? "Unknown" : v ? "Shown" : "Not shown"))}</dd>
        </dl>
      )}
    </Card>
  );
}

function HostsCard({ a }) {
  return (
    <Card title="Hosts looked up" icon={Globe2} right={<Hud>{plural(a.hosts.length, "host")} · {plural(a.lookups, "lookup")}</Hud>}>
      <div className="table-wrap">
        <table className="tbl">
          <thead><tr><th>Host</th><th>Who (tracker catalogue)</th><th>Category</th><th>Phases</th><th>Plans</th><th className="num">Lookups</th></tr></thead>
          <tbody>
            {a.hosts.map((h) => (
              <tr key={h.host}>
                <td className="host">{h.host}</td>
                <td>{h.entity ? <>{h.entity}{h.company && h.company !== h.entity ? <span className="muted"> · {h.company}</span> : null}</> : <span className="muted">Not in catalogue</span>}</td>
                <td><span className={h.tracking ? "" : "muted"}>{CATEGORY_LABEL[h.category] || h.category}</span></td>
                <td><div className="row wrap" style={{ gap: 4 }}>{Object.entries(h.phases).map(([ph, n]) => (
                  <span key={ph} className="badge" style={{ padding: "2px 8px", fontSize: 11 }}><i style={{ width: 7, height: 7, borderRadius: 2, background: PHASE[ph]?.color, display: "inline-block" }} />{PHASE[ph]?.short} {n}</span>
                ))}</div></td>
                <td><div className="row" style={{ gap: 4 }}>{[...h.plans].sort((x, y) => planInfo(x).order - planInfo(y).order).map((p) => <PlanTag key={p} plan={p} withLabel={false} />)}</div></td>
                <td className="num mono-s">{h.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="pill-src" style={{ marginTop: 10 }}>A lookup shows the app tried to contact a host. Payloads are not decrypted, so a lookup is not proof that personal data was sent.</p>
    </Card>
  );
}

function LensRefBody({ p }) {
  return (
    <div className="stack">
      <Hud tone="cyan">{p.source}</Hud>
      <h3>{p.title}</h3>
      <blockquote lang={p.language} style={{ margin: 0, paddingLeft: 14, borderLeft: "2px solid var(--line-2)", color: "var(--text-2)" }}>{p.text}</blockquote>
      {p.englishSummary && <p className="small text-2">English summary, not an official translation: {p.englishSummary}</p>}
      <p className="small muted">{p.verbatim ? "Verbatim" : "Summary, not verbatim"}{p.kind !== "LAW" ? ` · ${PASSAGE_KIND[p.kind] || p.kind}` : ""}{p.retrieved ? ` · read ${p.retrieved}` : ""}</p>
      {p.url && <a className="link small row" style={{ gap: 6 }} href={p.url} target="_blank" rel="noreferrer noopener"><ExternalLink size={13} />Official source</a>}
      <Truth>Legal text is context, not a verdict. This is not legal advice. Confirm applicability with a qualified reviewer.</Truth>
    </div>
  );
}

function RefBody({ id }) {
  const p = passageFor(id);
  return (
    <div className="stack">
      {p ? (
        <>
          <Hud tone="cyan">{p.source}</Hud>
          <h3>{p.title}</h3>
          <blockquote style={{ margin: 0, paddingLeft: 14, borderLeft: "2px solid var(--line-2)", color: "var(--text-2)" }}>{p.text}</blockquote>
          {!p.verbatim && <p className="small muted">Paraphrased, not verbatim.</p>}
        </>
      ) : <p className="text-2">No passage for {id} is bundled with this console.</p>}
      <Truth>Legal text is context, not a verdict. This is not legal advice. The DPDP Act's main provisions commence on 13 May 2027, so findings describe readiness.</Truth>
    </div>
  );
}

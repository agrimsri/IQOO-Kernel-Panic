import { useState } from "react";
import { ShieldBan, Globe2, Network, MessageSquareWarning, Clock, AlertTriangle, ListTree, Smartphone, Search, Ban, AppWindow, Settings2, FlaskConical, Info, FileUp, Trash2, ChevronDown, ChevronRight } from "lucide-react";
import { Card, PageHead, Fade, Hud, toast } from "../ui";
import { EvidenceChip, SampleBadge, EmptyState, Truth, Meta, AppMark, DownloadButton } from "../ui/tv";
import { useData } from "../data/DataContext";
import { fmtDate, fmtDuration, plural, STOP_REASON, SETTINGS_TITLE } from "../data/model";
import { navigate } from "../lib/router";

const KIND_ICON = { APP_ON_SCREEN: AppWindow, LOOKUP: Search, BLOCKED: Ban, PERMISSION_DIALOG: MessageSquareWarning, SETTINGS_SCREEN: Settings2 };
const KIND_COLOR = { APP_ON_SCREEN: "var(--scan)", LOOKUP: "var(--ev-inferred)", BLOCKED: "var(--sev-critical)", PERMISSION_DIALOG: "var(--sev-medium)", SETTINGS_SCREEN: "var(--sev-medium)" };
const ms = (v) => (v == null ? "–" : v < 10_000 ? `${(v / 1000).toFixed(1)} s` : `${Math.round(v / 1000)} s`);

export default function Quarantine({ id, onImport }) {
  const { quarantines, deleteRecord } = useData();
  const q = (id && quarantines.find((x) => x.id === id)) || (!id ? quarantines[0] : null);
  const [limitsOpen, setLimitsOpen] = useState(false);

  if (!quarantines.length) {
    return (
      <div>
        <PageHead kicker="Quarantine" title="Quarantine runs" sub="A quarantine run cuts one app's internet off and records what it tries to reach, plus permission dialogs and sensitive settings screens." />
        <Card><EmptyState icon={FileUp} title="No quarantine reports yet" body="Export a quarantine report from the TraceVeil app (traceveil.quarantine.v1) and drop it here."><button className="btn primary" onClick={onImport}>Import exports</button></EmptyState></Card>
      </div>
    );
  }
  if (!q) return <Card><EmptyState title="Report not found" body="It may have been removed from this browser."><button className="btn" onClick={() => navigate("/quarantine")}>All runs</button></EmptyState></Card>;

  const s = q.summary;
  const plannedPct = q.plannedMs && q.observedMs != null ? Math.min(100, (q.observedMs / q.plannedMs) * 100) : null;
  const remove = async () => {
    if (!window.confirm(`Remove this quarantine report for ${q.label} from this browser?`)) return;
    await deleteRecord("quarantine", q.id); toast("Report removed from this browser", "ok"); navigate("/quarantine");
  };

  return (
    <div>
      <PageHead
        kicker={<span className="row" style={{ gap: 8, display: "inline-flex" }}>Quarantine run {q.sample && <SampleBadge info={q.sampleInfo} label={q.sampleInfo?.kind === "illustrative" ? "Illustrative sample" : "Sample"} />}</span>}
        title={<span className="row" style={{ gap: 12, display: "inline-flex" }}><AppMark label={q.label} size="lg" />{q.label}</span>}
        sub={<Meta items={[<span className="mono-s" key="p">{q.pkg}</span>, <span key="d">{fmtDate(q.startedAtMs)}</span>, <span key="o"><Clock size={13} />{fmtDuration(q.observedMs)} of {fmtDuration(q.plannedMs)} planned</span>, <span key="r">{STOP_REASON[q.stopReason] || q.stopReason}</span>, q.device && <span key="dv"><Smartphone size={13} />{q.device}{q.android ? ` · Android ${q.android}` : ""}</span>]} />}
        right={<div className="row wrap"><DownloadButton name={`${q.pkg}-${q.id}.quarantine.json`} text={JSON.stringify(q.rec.bundle, null, 2)} label="Export JSON" /><button className="btn sm icon ghost" onClick={remove} aria-label="Remove report" title="Remove from this browser"><Trash2 size={15} /></button></div>}
      />

      {quarantines.length > 1 && (
        <div className="row wrap" style={{ marginBottom: 14, gap: 8 }}>
          {quarantines.map((x) => <button key={x.id} className={`chip ${x.id === q.id ? "on" : ""}`} onClick={() => navigate(`/quarantine/${encodeURIComponent(x.id)}`)}>{x.label} · {fmtDate(x.startedAtMs, false)}</button>)}
        </div>
      )}

      {q.sample && q.sampleInfo?.note && (
        <div className="callout" style={{ marginBottom: 12, borderStyle: "dashed", borderColor: "rgba(159,160,195,0.45)" }}>
          <FlaskConical size={17} color="var(--lav)" style={{ flex: "none", marginTop: 2 }} />
          <div>{!/^(illustrative|sample)/i.test(q.sampleInfo.note) && <b>Sample data. </b>}<span className="text-2">{q.sampleInfo.note}</span>{q.sampleInfo.sources?.length > 0 && <div className="pill-src" style={{ marginTop: 6 }}>Sources: {q.sampleInfo.sources.join(" · ")}</div>}</div>
        </div>
      )}
      <div style={{ marginBottom: 16 }}><Truth icon={ShieldBan}>{q.limits[0] || "Not a malware verdict and not a full sandbox: it records what this app tried to reach during this run only."} Attempts are not proof that any data would have been sent.</Truth></div>

      <div className="grid g4 keep-2" style={{ marginBottom: 16 }}>
        <Stat icon={Globe2} v={s.uniqueDomains} l="Names it tried to look up" sub={`${plural(s.dnsQueries, "DNS query", "DNS queries")} · all answered "does not exist"`} />
        <Stat icon={Network} v={s.uniqueIps} l="Addresses it tried to reach" sub={`${plural(s.uniqueDestinations, "address:port pair")}`} />
        <Stat icon={Ban} v={s.blockedPackets} l="Packets blocked" sub="includes Android's automatic retries" />
        <Stat icon={MessageSquareWarning} v={s.permissionDialogs + (s.settingsScreens?.length || 0)} l="Dialogs & settings screens" sub={`${plural(s.permissionDialogs, "permission dialog")} · ${plural(s.settingsScreens?.length || 0, "settings screen")}`} />
      </div>

      <div className="grid g-12">
        <Fade className="span-7"><Card title="Signals" icon={AlertTriangle} tour="q-signals" style={{ height: "100%" }}>
          <div className="stack">
            {s.dnsBypassSignal && (
              <div className="signal">
                <span className="s-ic"><Network size={18} /></span>
                <div className="grow">
                  <div className="row wrap" style={{ gap: 8 }}><b>Possible hard-coded address</b><EvidenceChip type="INFERRED" small /></div>
                  <p className="small text-2" style={{ marginTop: 4 }}>
                    The app tried to reach {s.addressesWithoutLookup.map((a, i) => <span key={a}>{i ? ", " : ""}<span className="host">{q.blocked.find((b) => b.address === a)?.endpoint || a}</span></span>)} although no DNS answer during the run contained {s.addressesWithoutLookup.length === 1 ? "it" : "them"}. Every lookup was answered "does not exist", so the address came from somewhere else: built into the app, cached from before the run, or obtained another way. A signal, not proof.
                  </p>
                </div>
              </div>
            )}
            {q.dialogs.map((d, i) => (
              <div key={i} className="callout">
                <MessageSquareWarning size={17} color="var(--sev-medium)" style={{ flex: "none", marginTop: 2 }} />
                <div className="grow">
                  <div className="row wrap" style={{ gap: 8 }}><b>Permission dialog at {ms(d.firstMs)}</b><EvidenceChip type="OBSERVED" small />{!d.namesTarget && <span className="pill-src">does not name this app</span>}</div>
                  <p className="small" style={{ marginTop: 4 }}>"{d.text}"</p>
                  <p className="pill-src" style={{ marginTop: 4 }}>{d.packageName}{d.timesShown > 1 ? ` · shown ${d.timesShown} times` : ""}</p>
                </div>
              </div>
            ))}
            {q.settings.map((st, i) => (
              <div key={i} className="callout">
                <Settings2 size={17} color="var(--sev-medium)" style={{ flex: "none", marginTop: 2 }} />
                <div className="grow"><div className="row wrap" style={{ gap: 8 }}><b>{SETTINGS_TITLE[st.kind] || st.title} opened at {ms(st.firstMs)}</b><EvidenceChip type="OBSERVED" small /></div>
                  <p className="small text-2" style={{ marginTop: 4 }}>Who opened it (the app or the tester) is not known.</p></div>
              </div>
            ))}
            {s.directResolvers?.length > 0 && <div className="callout"><Info size={16} color="var(--scan)" style={{ flex: "none", marginTop: 2 }} /><span className="small">Sent plain DNS straight to its own resolver{s.directResolvers.length > 1 ? "s" : ""}: <span className="host">{s.directResolvers.join(", ")}</span> <EvidenceChip type="OBSERVED" small /></span></div>}
            {s.encryptedDnsDestinations?.length > 0 && <div className="callout"><Info size={16} color="var(--scan)" style={{ flex: "none", marginTop: 2 }} /><span className="small">DNS over TLS or TCP attempts: <span className="host">{s.encryptedDnsDestinations.join(", ")}</span>. From the app or from Android's Private DNS on its behalf; this run cannot tell which. <EvidenceChip type="NOT_ESTABLISHED" small /></span></div>}
            {!s.dnsBypassSignal && !q.dialogs.length && !q.settings.length && <p className="small muted">No inferred signal in this run.</p>}
            {s.nothingObserved && <Truth>Nothing was observed. That is not proof the app does nothing: it may wait longer than the run, need a login or wait for a remote command.</Truth>}
            <div className="row wrap" style={{ gap: 6 }}><span className="pill-src">Settings screens watched: Accessibility, Display over other apps.</span>{!q.settings.length && <span className="pill-src">None opened during this run.</span>}</div>
          </div>
        </Card></Fade>

        <Fade className="span-5" delay={0.04}><Card title="Behaviour timeline" icon={ListTree} right={<Hud>first time of each thing</Hud>} style={{ height: "100%" }}>
          {plannedPct != null && (
            <div style={{ marginBottom: 12 }}>
              <div className="row between"><span className="pill-src">run length</span><span className="pill-src">{fmtDuration(q.observedMs)} / {fmtDuration(q.plannedMs)}</span></div>
              <div className="progress" style={{ marginTop: 6, height: 6 }}><i style={{ width: `${plannedPct}%` }} /></div>
            </div>
          )}
          {q.timeline.length ? (
            <ul className="q-timeline">
              {q.timeline.map((t, i) => {
                const I = KIND_ICON[t.kind] || Info;
                return (
                  <li key={i}>
                    <span className="q-ic" style={{ color: KIND_COLOR[t.kind] }}><I size={15} /></span>
                    <span className="q-t">{ms(t.atMs)}</span>
                    <span className="q-x">{t.text} {t.evidence !== "OBSERVED" && <EvidenceChip type={t.evidence} small />}</span>
                  </li>
                );
              })}
            </ul>
          ) : <p className="small muted">The timeline is empty.</p>}
        </Card></Fade>

        <Fade className="span-6" delay={0.06}><Card title="Blocked lookups" icon={Search} right={<Hud>{plural(q.domains.length, "name")}</Hud>} style={{ height: "100%" }}>
          <div className="table-wrap">
            <table className="tbl" style={{ minWidth: 440 }}>
              <thead><tr><th>Name</th><th>Types</th><th>First</th><th className="num">Lookups</th></tr></thead>
              <tbody>
                {q.domains.map((d) => (
                  <tr key={d.host}>
                    <td><div className="host">{d.host}</div>{d.resolvers.some((r) => r.own) && <div className="small" style={{ color: "var(--sev-medium)" }}>own resolver: {d.resolvers.filter((r) => r.own).map((r) => r.addr).join(", ")}</div>}</td>
                    <td className="mono-s">{d.types.join(", ")}</td>
                    <td className="mono-s">{ms(d.firstMs)}</td>
                    <td className="num mono-s">{d.count}</td>
                  </tr>
                ))}
                {!q.domains.length && <tr><td colSpan={4} className="small muted">No lookups.</td></tr>}
              </tbody>
            </table>
          </div>
          <p className="pill-src" style={{ marginTop: 8 }}>Every lookup was answered "does not exist" on the phone; none was forwarded to the internet.</p>
        </Card></Fade>

        <Fade className="span-6" delay={0.08}><Card title="Blocked connections" icon={Ban} right={<Hud>{plural(q.blocked.length, "destination")}</Hud>} style={{ height: "100%" }}>
          <div className="table-wrap">
            <table className="tbl" style={{ minWidth: 440 }}>
              <thead><tr><th>Address : port</th><th>Protocol</th><th>First</th><th className="num">Packets</th></tr></thead>
              <tbody>
                {q.blocked.map((b) => (
                  <tr key={`${b.endpoint}${b.protocol}`}>
                    <td>
                      <div className="host">{b.endpoint}</div>
                      <div className="row wrap" style={{ gap: 6, marginTop: 4 }}>
                        {b.hardcoded && <span className="row" style={{ gap: 6 }}><EvidenceChip type="INFERRED" small /><span className="small" style={{ color: "var(--ev-inferred)" }}>hard-coded address?</span></span>}
                        {b.role === "PHONE_RESOLVER" && <span className="pill-src">the phone's own resolver</span>}
                        <span className="pill-src">{String(b.scope).toLowerCase()}</span>
                      </div>
                    </td>
                    <td className="mono-s">{b.protocol}</td>
                    <td className="mono-s">{ms(b.firstMs)}</td>
                    <td className="num mono-s">{b.packets}</td>
                  </tr>
                ))}
                {!q.blocked.length && <tr><td colSpan={4} className="small muted">No connection attempts were blocked.</td></tr>}
              </tbody>
            </table>
          </div>
          <p className="pill-src" style={{ marginTop: 8 }}>Packet counts include automatic retries. Only headers are read; no payload byte is ever inspected.{q.controlPackets ? ` ${q.controlPackets} network-control packets from Android itself are not counted.` : ""}</p>
        </Card></Fade>

        <Fade className="span-12" delay={0.1}><Card title="What this run cannot tell" icon={Info} right={<button className="btn sm ghost" onClick={() => setLimitsOpen(!limitsOpen)}>{limitsOpen ? <ChevronDown size={15} /> : <ChevronRight size={15} />}{limitsOpen ? "Hide" : `Show all ${q.limits.length}`}</button>}>
          <ul className="rule-list">
            {(limitsOpen ? q.limits : q.limits.slice(0, 3)).map((l, i) => <li key={i}><Info size={14} color="var(--lav)" /><span>{l}</span></li>)}
          </ul>
          {q.notes.length > 0 && <div className="stack-sm" style={{ marginTop: 12 }}>{q.notes.map((n, i) => <Truth key={i}>{n}</Truth>)}</div>}
          {q.overflow > 0 && <div style={{ marginTop: 12 }}><Truth icon={AlertTriangle}>{q.overflow} items arrived after the run's lists were full; they were counted but not listed.</Truth></div>}
        </Card></Fade>
      </div>
    </div>
  );
}

function Stat({ icon: I, v, l, sub }) {
  return (
    <div className="card tight kpi">
      <div className="kpi-top"><span className="l">{l}</span><span className="kpi-ic"><I size={15} /></span></div>
      <div className="v">{v}</div>
      <div className="sub">{sub}</div>
    </div>
  );
}

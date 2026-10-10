import { useEffect, useState } from "react";
import { HardDrive, Palette, Check, Trash2, FlaskConical, Download, ShieldAlert, PlayCircle, Lock, FileUp, Scale } from "lucide-react";
import { Card, PageHead, Fade, toast, Toggle } from "../ui";
import { Truth, downloadText } from "../ui/tv";
import { THEMES, getTheme, setTheme } from "../lib/theme";
import { useData } from "../data/DataContext";
import { DB_NAME, estimate, requestPersist } from "../data/store";
import { start, toggleNarrate, useTour } from "../tour/engine";
import { AUDIT_SCHEMA, QUARANTINE_SCHEMA } from "../data/model";

const kb = (n) => (n == null ? "unknown" : n >= 1024 ** 3 ? `${(n / 1024 ** 3).toFixed(1)} GB` : n >= 1024 ** 2 ? `${(n / 1024 ** 2).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);

export default function Settings({ onImport }) {
  const data = useData();
  const [cur, setCur] = useState(getTheme());
  const [est, setEst] = useState(null);
  const [voice, setVoice] = useState(() => { try { return localStorage.getItem("tour-voice") !== "off"; } catch { return false; } });
  const tour = useTour();
  useEffect(() => { estimate().then(setEst); }, [data.audits.length, data.quarantines.length]);
  const pick = (id) => { setTheme(id); setCur(id); toast(`Theme: ${THEMES.find((t) => t.id === id).label}`, "ok"); };
  const origin = typeof location !== "undefined" ? (location.protocol === "file:" ? "this file (file://)" : location.origin) : "";
  const sampleCount = data.audits.filter((a) => a.sample).length + data.quarantines.filter((q) => q.sample).length;

  const clear = async () => {
    if (!window.confirm("Delete every imported audit, quarantine report and the saved policy from this browser? This cannot be undone.")) return;
    await data.clearEverything(); toast("All console data deleted from this browser", "ok");
  };

  return (
    <div>
      <PageHead kicker="Settings & privacy" title="Your data stays in this browser" sub="TraceVeil Console has no server. Imports are parsed here and kept in this browser's storage until you delete them." />
      <div className="grid g-12">
        <Fade className="span-7"><Card title="Storage" icon={HardDrive} tour="storage">
          <dl className="kv">
            <dt>Where</dt><dd>{data.mode === "memory" ? "Memory of this tab only (this browser blocked IndexedDB). Closing the tab forgets everything." : <>IndexedDB database <span className="mono-s">{DB_NAME}</span> in this browser, for {origin}</>}</dd>
            <dt>Contents</dt><dd>{data.audits.length} audits · {data.quarantines.length} quarantine reports{sampleCount ? ` (${sampleCount} samples)` : ""} · release policy</dd>
            <dt>Space used</dt><dd>{est ? `${kb(est.usage)} of ${kb(est.quota)} available to this page` : "Not reported by this browser"}</dd>
            <dt>Kept when space is low</dt><dd>{est?.persisted ? "Yes (persistent storage granted)" : "Not guaranteed"} {!est?.persisted && <button className="link small" onClick={async () => { const ok = await requestPersist(); toast(ok ? "The browser will keep this data" : "The browser did not grant persistent storage", ok ? "ok" : "warn"); setEst(await estimate()); }}>Ask the browser to keep it</button>}</dd>
            <dt>Network</dt><dd>None. No analytics, no telemetry, no fonts or scripts from the internet. Fix-snippet links to vendor documentation open only when you click them.</dd>
          </dl>
          <div className="row wrap" style={{ marginTop: 16, gap: 8 }}>
            <button className="btn sm" onClick={onImport}><FileUp size={15} />Import exports</button>
            <button className="btn sm" onClick={() => downloadText(`traceveil-console-backup-${new Date().toISOString().slice(0, 10)}.json`, data.backupText())}><Download size={15} />Download all data</button>
            {data.hasSamples
              ? <button className="btn sm" onClick={async () => { await data.removeSamples(); toast("Samples removed", "ok"); }}><FlaskConical size={15} />Remove samples</button>
              : <button className="btn sm" onClick={async () => { await data.loadSamples(); toast("Samples loaded and labelled as samples", "ok"); }}><FlaskConical size={15} />Load samples</button>}
            <button className="btn sm danger" onClick={clear}><Trash2 size={15} />Delete all data</button>
          </div>
          <p className="pill-src" style={{ marginTop: 12 }}>Reads {AUDIT_SCHEMA}, {QUARANTINE_SCHEMA} and this console's own backup files. Browser storage can be wiped by private windows or "clear site data", so keep the original exports.</p>
        </Card></Fade>

        <Fade className="span-5" delay={0.04}><Card title="What TraceVeil does not claim" icon={Scale} style={{ height: "100%" }}>
          <div className="stack-sm">
            <Truth icon={Scale}>Not legal advice. Legal text is context, not a verdict. Each audit is read under the legal lens named in its export (else the global baseline). Under India's DPDP Act, whose main provisions commence on 13 May 2027, findings describe readiness.</Truth>
            <Truth icon={ShieldAlert}>Not a malware verdict. APK Guard reads what an APK declares and Quarantine records one run; neither calls an app safe, unsafe or malicious.</Truth>
            <Truth icon={Lock}>A DNS lookup is an attempt to contact a host, not proof that personal data was sent. Payloads are never decrypted.</Truth>
            <Truth>Scores cover captured evidence on one device during the recorded runs. 100 means no finding in that evidence, not a certificate. Unknown stays unknown, never zero.</Truth>
          </div>
        </Card></Fade>

        <Fade className="span-7" delay={0.06}><Card title="Theme" icon={Palette}>
          <div className="grid g3 keep-2">
            {THEMES.map((t) => (
              <button key={t.id} className={`card tight interactive ${cur === t.id ? "glow" : ""}`} onClick={() => pick(t.id)} style={{ textAlign: "left" }}>
                <div style={{ height: 46, borderRadius: 12, background: t.swatch }} />
                <div className="row between" style={{ marginTop: 10 }}><b>{t.label}</b>{cur === t.id && <Check size={16} color="var(--green)" />}</div>
              </button>
            ))}
          </div>
          <p className="pill-src" style={{ marginTop: 10 }}>Severity, evidence and phase colours stay the same in every theme because they carry meaning.</p>
        </Card></Fade>

        <Fade className="span-5" delay={0.08}><Card title="Guided tour" icon={PlayCircle} style={{ height: "100%" }}>
          <p className="small text-2">A two-minute walk through the console using whatever data is loaded. It starts once on the first visit.</p>
          <div className="row between" style={{ marginTop: 14 }}>
            <span className="small">Spoken narration (browser speech, if available)</span>
            <Toggle on={voice} onChange={(v) => { try { localStorage.setItem("tour-voice", v ? "on" : "off"); } catch { /* blocked */ } if (tour.active && tour.narrate !== v) toggleNarrate(); setVoice(v); }} label="Narration" />
          </div>
          <button className="btn block" style={{ marginTop: 14 }} onClick={() => start(0)}><PlayCircle size={16} />Start the tour</button>
        </Card></Fade>
      </div>
    </div>
  );
}

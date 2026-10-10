// TraceVeil Console building blocks on top of the UI kit (src/ui/index.jsx). Colours come from tokens only.
import { useState } from "react";
import { Check, Copy, AlertOctagon, AlertTriangle, AlertCircle, Info, CircleDot, FlaskConical, Download } from "lucide-react";
import { EVIDENCE, sevInfo, bandInfo, planInfo } from "../data/model";
import { toast } from "./index";

export function EvidenceChip({ type, small }) {
  const key = type === "NOT_ESTABLISHED" || type === "NOT ESTABLISHED" ? "NOT_ESTABLISHED" : type;
  const e = EVIDENCE[key] || EVIDENCE.NOT_ESTABLISHED;
  return (
    <span className={`ev-chip ev-${key.toLowerCase()} ${small ? "sm" : ""}`} title={e.help}>
      <i />{e.label}
    </span>
  );
}

const SEV_ICON = { CRITICAL: AlertOctagon, HIGH: AlertTriangle, MEDIUM: AlertCircle, LOW: Info, INFO: CircleDot };
export function SeverityBadge({ severity, count }) {
  const s = sevInfo(severity);
  const I = SEV_ICON[severity] || CircleDot;
  return (
    <span className={`sev-badge sev-${String(severity).toLowerCase()}`}>
      <I size={13} strokeWidth={2.4} />{count != null ? `${count} ${s.label.toLowerCase()}` : s.label}
    </span>
  );
}

export function BandBadge({ band }) {
  const b = bandInfo(band);
  return <span className={`band-badge band-${String(band).toLowerCase()}`}><i />{b.label}</span>;
}

export function ScoreChip({ score, band, size = "" }) {
  const b = bandInfo(band);
  return (
    <span className={`score-chip ${size}`} style={{ "--c": b.color }} title={`${score == null ? "No score" : `${score}/100`} · ${b.label}`}>
      <b>{score == null ? "–" : score}</b>{score != null && <small>/100</small>}
    </span>
  );
}

export function PlanTag({ plan, withLabel = true }) {
  const p = planInfo(plan);
  return <span className="plan-tag" title={p.label}><b>{p.code}</b>{withLabel && p.short}</span>;
}

export function SampleBadge({ info, label = "Sample" }) {
  return (
    <span className="sample-badge" title={info?.note || "Sample data shipped with the console, clearly not a live audit."}>
      <FlaskConical size={12} />{label}
    </span>
  );
}

export async function copyText(text) {
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); return true; }
  } catch { /* fall back below */ }
  try {
    const ta = document.createElement("textarea");
    ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.select();
    const ok = document.execCommand("copy");
    ta.remove();
    return ok;
  } catch { return false; }
}

export function CopyButton({ text, label = "Copy", className = "" }) {
  const [done, setDone] = useState(false);
  const go = async () => {
    const ok = await copyText(text);
    if (ok) { setDone(true); setTimeout(() => setDone(false), 1600); } else toast("Copy is blocked in this browser. Select the text instead.", "warn");
  };
  return (
    <button type="button" className={`btn sm ghost copy-btn ${className}`} onClick={go} aria-label={label}>
      {done ? <Check size={14} /> : <Copy size={14} />}{done ? "Copied" : label}
    </button>
  );
}

export function CodeBlock({ code, title, lang }) {
  return (
    <div className="code-block">
      <div className="code-head">
        <span className="pill-src">{title || lang || "code"}</span>
        <CopyButton text={code} />
      </div>
      <pre><code>{code}</code></pre>
    </div>
  );
}

export function downloadText(name, text, type = "application/json") {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = name; a.rel = "noopener";
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export function DownloadButton({ name, text, label = "Download", className = "btn sm", type }) {
  return <button type="button" className={className} onClick={() => downloadText(name, text, type)}><Download size={15} />{label}</button>;
}

export function EmptyState({ icon: I, title, body, children }) {
  return (
    <div className="empty-state">
      {I && <span className="es-ic"><I size={22} /></span>}
      <h3>{title}</h3>
      {body && <p className="text-2 small">{body}</p>}
      {children && <div className="row wrap" style={{ justifyContent: "center", marginTop: 12 }}>{children}</div>}
    </div>
  );
}

export function Meta({ items }) {
  return (
    <div className="meta-row">
      {items.filter(Boolean).map((it, i) => <span key={i}>{it}</span>)}
    </div>
  );
}

export function Truth({ children, icon: I = Info }) {
  return <div className="truth"><I size={15} /><span>{children}</span></div>;
}

export const AppMark = ({ label, size = "" }) => {
  const letters = String(label || "?").replace(/[^A-Za-z0-9 ]/g, " ").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join("").toUpperCase() || "?";
  let h = 0; for (const ch of String(label)) h = (h * 31 + ch.charCodeAt(0)) % 360;
  return <span className={`app-mark ${size}`} style={{ "--h": h }}>{letters}</span>;
};

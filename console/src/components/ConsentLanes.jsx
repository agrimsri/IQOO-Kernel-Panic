// Consent timeline: one lane per plan (C · B · A). Each DNS lookup is a mark; the consent screen and the exact
// recorded tap are vertical markers. Marks after a Reject are red diamonds. Shared time axis across lanes.
import { useMemo, useRef, useState } from "react";
import { Seg } from "../ui";
import { useWidth, niceStep } from "../ui/useWidth";
import { PHASE, CATEGORY_LABEL, fmtS } from "../data/model";
import { PlanTag } from "../ui/tv";

const H = 104, TOP = 26, MID = 64, ROW = 10.5, PAD_L = 8, PAD_R = 14;

function markStyle(e) {
  if (!e.tracking) return { shape: "ring", color: "var(--lav)" };
  if (e.phase === "AFTER_REJECT") return { shape: "diamond", color: "var(--sev-critical)" };
  if (e.phase === "AFTER_ACCEPT") return { shape: "dot", color: "var(--scan)" };
  return { shape: "dot", color: "var(--sev-high)" };
}

export default function ConsentLanes({ sessions, tour }) {
  const [ref, width] = useWidth(900);
  const wrap = useRef(null);
  const [tip, setTip] = useState(null);
  const [sel, setSel] = useState(null);
  const full = Math.max(1, ...sessions.map((s) => s.duration));
  const lastActivity = Math.max(0, ...sessions.flatMap((s) => [...s.events.map((e) => e.t), ...s.consent.map((c) => c.t)]));
  const focusEnd = Math.min(full, Math.max(4, Math.ceil(lastActivity * 1.18 + 0.6)));
  const canFocus = focusEnd < full * 0.6;
  const [zoom, setZoom] = useState(canFocus ? "focus" : "full");
  const range = zoom === "focus" && canFocus ? focusEnd : full;
  const narrow = width < 520;
  const laneW = narrow ? width : width - 180;
  // narrow: lane titles sit above each lane instead of beside it
  const x = (t) => PAD_L + (Math.min(t, range) / range) * (laneW - PAD_L - PAD_R);
  const step = niceStep(range, narrow ? 4 : 8);
  const ticks = [];
  for (let t = 0; t <= range + 1e-9; t += step) ticks.push(Math.round(t * 1000) / 1000);

  const show = (evt, content, key) => {
    const host = wrap.current?.getBoundingClientRect();
    const r = evt.currentTarget.getBoundingClientRect();
    if (!host) return;
    const left = Math.min(Math.max(8, r.left - host.left + r.width / 2 - 140), host.width - 290);
    const top = r.top - host.top + r.height + 10;
    setTip({ left, top, content, key });
  };
  const hide = () => setTip(null);

  return (
    <div data-tour={tour}>
      <div className="row between wrap" style={{ marginBottom: 12, gap: 12 }}>
        <div className="chart-legend">
          <span><i className="dot" style={{ background: "var(--sev-high)" }} />Tracker before the choice</span>
          <span><i className="diamond" style={{ background: "var(--sev-critical)" }} />Tracker after Reject</span>
          <span><i className="dot" style={{ background: "var(--scan)" }} />Tracker after Accept</span>
          <span><i className="ring" />Other host</span>
          <span><i style={{ width: 2, height: 12, background: "var(--text-2)", borderRadius: 1 }} />Consent screen / recorded tap</span>
        </div>
        {canFocus && <Seg options={[{ value: "focus", label: `First ${focusEnd} s` }, { value: "full", label: `Full run · ${Math.round(full)} s` }]} value={zoom} onChange={setZoom} />}
      </div>
      <div className={`lanes ${narrow ? "narrow" : ""}`} ref={wrap} onMouseLeave={hide}>
        <div ref={ref} style={{ position: "absolute", left: 0, right: 0, height: 0 }} />
        {sessions.map((s) => (
          <Lane key={s.id} s={s} x={x} range={range} laneW={laneW} narrow={narrow} show={show} hide={hide} sel={sel} setSel={setSel} />
        ))}
        <div className="lane-axis">
          <span />
          <svg width={laneW} height={22} style={{ display: "block", overflow: "visible" }} aria-hidden="true">
            <line x1={PAD_L} x2={laneW - PAD_R} y1={1} y2={1} className="grid-line" />
            {ticks.map((t) => (
              <g key={t}>
                <line x1={x(t)} x2={x(t)} y1={1} y2={5} className="grid-line" />
                <text x={x(t)} y={17} textAnchor="middle" className="axis-text">{t === 0 ? "0" : `${Number(t.toFixed(2))} s`}</text>
              </g>
            ))}
          </svg>
        </div>
        {tip && <div className="chart-tip" style={{ left: tip.left, top: tip.top }}>{tip.content}</div>}
      </div>
      <p className="pill-src" style={{ marginTop: 8 }}>Time is seconds after launch in each plan. A lookup is an attempt to contact a host, not proof that data was sent.</p>
    </div>
  );
}

function Lane({ s, x, range, laneW, narrow, show, hide, sel, setSel }) {
  const placed = useMemo(() => {
    const occupied = [];
    return s.events.filter((e) => e.t <= range).map((e, i) => {
      const px = x(e.t);
      let k = 0;
      for (const cand of [0, 1, -1, 2, -2, 3, -3]) {
        if (!occupied.some((o) => o.k === cand && Math.abs(o.px - px) < 12)) { k = cand; break; }
        k = cand;
      }
      occupied.push({ k, px });
      return { e, i, px, py: MID + k * ROW, ...markStyle(e) };
    });
  }, [s.events, x, range]);

  const choice = s.choice;
  const xEnd = laneW - PAD_R;
  const xc = choice ? x(choice.t) : null;
  const regionAfter = choice ? (choice.kind === "ACCEPT" ? "reg-accept" : "reg-reject") : null;
  const pills = [];
  const addPill = (cx, text, cls, priority) => pills.push({ cx, text, cls, priority, w: text.length * 6.6 + 14 });
  if (choice) addPill(xc, `${choice.kind} · ${fmtS(choice.t)}`, choice.kind === "ACCEPT" ? "accept" : "reject", 0);
  for (const c of s.consent) {
    if (c === choice || c.t > range) continue;
    if (c.kind === "SCREEN_APPEARED") addPill(x(c.t), `SCREEN ${fmtS(c.t)}`, "screen", 1);
    else if (c.kind === "SETTINGS") addPill(x(c.t), `${(c.nodeText || "SETTINGS").toUpperCase()} ${fmtS(c.t)}`, "screen", 2);
  }
  // keep pills that fit without overlapping a higher-priority pill
  const kept = [];
  for (const p of pills.sort((a, b) => a.priority - b.priority)) {
    let left = p.cx - p.w / 2;
    left = Math.max(2, Math.min(left, xEnd - p.w));
    const box = { ...p, left };
    if (!kept.some((k) => box.left < k.left + k.w + 4 && k.left < box.left + box.w + 4)) kept.push(box);
  }

  const consentTip = (c) => (
    <>
      <div className="tt">{c.kind === "SCREEN_APPEARED" ? "Consent screen appeared" : c.kind === "SETTINGS" ? `Opened "${c.nodeText || "settings"}"` : c.kind === "MANUAL_MARK" ? "Marked by the tester" : `Tapped "${c.nodeText || c.kind}"`}</div>
      <div className="tr"><span>Time</span><b>{fmtS(c.t)} after launch</b></div>
      <div className="tr"><span>Plan</span><b>{s.planLabel}</b></div>
      {(c.kind === "ACCEPT" || c.kind === "REJECT") && <div className="tr"><span>Recorded</span><b>exact tap, {c.kind.toLowerCase()}</b></div>}
    </>
  );
  const eventTip = (e) => (
    <>
      <div className="tt mono-s">{e.host}</div>
      <div className="tr"><span>Who</span><b>{e.entity ? `${e.entity}` : "Not in the tracker catalogue"}</b></div>
      {e.company && e.company !== e.entity && <div className="tr"><span>Company</span><b>{e.company}</b></div>}
      <div className="tr"><span>Category</span><b>{CATEGORY_LABEL[e.category] || e.category}</b></div>
      <div className="tr"><span>Time</span><b>{fmtS(e.t)} after launch</b></div>
      <div className="tr"><span>Phase</span><b>{PHASE[e.phase]?.label || e.phase}</b></div>
      <div className="tr"><span>Relative</span><b>{e.relation}</b></div>
      {e.viaPackage && <div className="tr"><span>Via</span><b>{e.viaPackage}</b></div>}
    </>
  );

  const label = `${s.planLabel}: ${s.events.length} lookups${choice ? `, ${choice.kind.toLowerCase()} tapped at ${fmtS(choice.t)}` : ""}.`;
  return (
    <div className="lane">
      <div className="lane-head">
        <div className="lh-t"><PlanTag plan={s.plan} /></div>
        <div className="lh-s">{s.events.length} lookups{s.windowS ? ` · ${fmtS(s.windowS)} window` : ""}</div>
      </div>
      <svg className="lane-svg" width={laneW} height={H} role="img" aria-label={label} style={{ width: laneW }}>
        {choice ? (
          <>
            <rect x={PAD_L} y={TOP - 4} width={Math.max(0, xc - PAD_L)} height={H - TOP} className="reg-before" />
            <rect x={xc} y={TOP - 4} width={Math.max(0, xEnd - xc)} height={H - TOP} className={regionAfter} />
          </>
        ) : (
          <rect x={PAD_L} y={TOP - 4} width={xEnd - PAD_L} height={H - TOP} className={s.screenSeen ? "reg-before" : "reg-none"} />
        )}
        <line x1={PAD_L} x2={xEnd} y1={H - 1} y2={H - 1} className="base" />
        {s.consent.filter((c) => c.t <= range).map((c, i) => {
          const cx = x(c.t);
          const isChoice = c === choice;
          const cls = isChoice ? `choice-line ${c.kind === "ACCEPT" ? "accept" : "reject"}` : c.kind === "SETTINGS" ? "settings-line" : "screen-line";
          return (
            <g key={`c${i}`} className="mk" tabIndex={0} aria-label={`${c.kind} at ${fmtS(c.t)}`}
              onMouseEnter={(ev) => show(ev, consentTip(c))} onFocus={(ev) => show(ev, consentTip(c))} onBlur={hide}
              onClick={(ev) => show(ev, consentTip(c))}>
              <line x1={cx} x2={cx} y1={TOP - 4} y2={H - 1} className={cls} />
              <rect x={cx - 7} y={TOP - 4} width={14} height={H - TOP} fill="transparent" />
            </g>
          );
        })}
        {kept.map((p, i) => (
          <g key={`p${i}`} aria-hidden="true">
            <rect x={p.left} y={2} width={p.w} height={18} rx={9} className={`pill-bg ${p.cls}`} />
            <text x={p.left + 7} y={14.5} className="pill-tx">{p.text}</text>
          </g>
        ))}
        {placed.map((m) => {
          const k = `${s.id}-${m.i}`;
          const on = sel === k;
          return (
            <g key={k} className={`mk ${on ? "on" : ""}`} tabIndex={0} aria-label={`${m.e.host} at ${fmtS(m.e.t)}, ${PHASE[m.e.phase]?.short}`}
              onMouseEnter={(ev) => show(ev, eventTip(m.e))} onFocus={(ev) => show(ev, eventTip(m.e))} onBlur={hide}
              onClick={(ev) => { setSel(on ? null : k); show(ev, eventTip(m.e)); }}>
              <circle cx={m.px} cy={m.py} r={9} fill="transparent" />
              {m.shape === "diamond" ? (
                <path d={`M${m.px},${m.py - 6.5} L${m.px + 6.5},${m.py} L${m.px},${m.py + 6.5} L${m.px - 6.5},${m.py} Z`} fill={m.color} stroke="var(--bg)" strokeWidth={2} />
              ) : m.shape === "ring" ? (
                <circle cx={m.px} cy={m.py} r={4.6} fill="var(--bg)" stroke={m.color} strokeWidth={2} />
              ) : (
                <circle cx={m.px} cy={m.py} r={5.2} fill={m.color} stroke="var(--bg)" strokeWidth={2} />
              )}
              <circle cx={m.px} cy={m.py} r={8} className="ring" />
            </g>
          );
        })}
      </svg>
    </div>
  );
}

// Console charts: score over time per app (one shared 0–100 axis) and horizontal bars with hover tooltips.
import { useState } from "react";
import { navigate } from "../lib/router";
import { useWidth } from "../ui/useWidth";
import { bandInfo, fmtDate, plural } from "../data/model";

export const SERIES = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-4)"];
export const seriesColor = (i) => SERIES[i] || "var(--series-other)";

/** series: [{ key, label, color, points: [{ t, score, band, id, findings }] }] (points sorted by t). */
export function ScoreTrend({ series, height = 230, tour }) {
  const [ref, width] = useWidth(640);
  const [hover, setHover] = useState(null);
  const P = { l: 34, r: 16, t: 14, b: 28 };
  const all = series.flatMap((s) => s.points);
  const scored = all.filter((p) => p.score != null);
  let t0 = Math.min(...all.map((p) => p.t));
  let t1 = Math.max(...all.map((p) => p.t));
  const span = Math.max(t1 - t0, 60_000);
  t0 -= span * 0.12; t1 += span * 0.12;
  const x = (t) => P.l + ((t - t0) / (t1 - t0)) * (width - P.l - P.r);
  const y = (v) => P.t + (1 - v / 100) * (height - P.t - P.b);
  const sameDay = new Date(t0).toDateString() === new Date(t1).toDateString();
  const fmtX = (t) => (sameDay ? new Date(t).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short" }));
  const nTicks = width < 480 ? 3 : 5;
  const xticks = Array.from({ length: nTicks }, (_, i) => t0 + ((t1 - t0) * (i + 0.5)) / nTicks);

  return (
    <div data-tour={tour} ref={ref} style={{ position: "relative" }} onMouseLeave={() => setHover(null)}>
      {series.length > 1 && (
        <div className="chart-legend" style={{ marginBottom: 10 }}>
          {series.map((s) => <span key={s.key}><i className="dot" style={{ background: s.color }} />{s.label}</span>)}
        </div>
      )}
      <svg width={width} height={height} role="img" aria-label={`Score over time for ${plural(series.length, "app")}`} style={{ display: "block", overflow: "visible" }}>
        {[0, 25, 50, 75, 100].map((v) => (
          <g key={v}>
            <line x1={P.l} x2={width - P.r} y1={y(v)} y2={y(v)} className="grid-line" />
            <text x={P.l - 8} y={y(v) + 3.5} textAnchor="end" className="axis-text">{v}</text>
          </g>
        ))}
        {[[85, "Ready ≥ 85", "var(--clear)"], [60, "Needs work ≥ 60", "var(--sev-medium)"]].map(([v, l, c]) => (
          <g key={v}>
            <line x1={P.l} x2={width - P.r} y1={y(v)} y2={y(v)} stroke={c} strokeOpacity={0.35} strokeWidth={1} />
            <text x={width - P.r} y={y(v) - 5} textAnchor="end" className="axis-text">{l}</text>
          </g>
        ))}
        {xticks.map((t) => <text key={t} x={x(t)} y={height - 8} textAnchor="middle" className="axis-text">{fmtX(t)}</text>)}
        {series.map((s) => {
          const pts = s.points.filter((p) => p.score != null);
          const d = pts.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(p.score).toFixed(1)}`).join("");
          return pts.length > 1 ? <path key={`l${s.key}`} d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" /> : null;
        })}
        {series.map((s) => s.points.map((p, i) => {
          const isLast = i === s.points.length - 1;
          const cy = p.score == null ? y(0) : y(p.score);
          const cx = x(p.t);
          const right = cx < width - 150;
          return (
            <g key={`${s.key}${p.id}`} style={{ cursor: "pointer" }} onClick={() => navigate(`/audits/${encodeURIComponent(p.id)}`)}
              onMouseEnter={() => setHover({ s, p, cx, cy })} tabIndex={0} onFocus={() => setHover({ s, p, cx, cy })}
              aria-label={`${s.label}: ${p.score ?? "no score"} on ${fmtDate(p.t)}`}>
              <circle cx={cx} cy={cy} r={12} fill="transparent" />
              {p.score == null
                ? <circle cx={cx} cy={cy} r={5} fill="var(--bg)" stroke={s.color} strokeWidth={2} strokeDasharray="2 2" />
                : <circle cx={cx} cy={cy} r={5.5} fill={s.color} stroke="var(--bg)" strokeWidth={2} />}
              {isLast && (
                <text x={right ? cx + 11 : cx - 11} y={cy + 4} textAnchor={right ? "start" : "end"} style={{ font: "600 12px var(--font-ui)", fill: "var(--text)" }}>
                  {s.label} <tspan style={{ fill: "var(--muted)", fontWeight: 500 }}>{p.score ?? "–"}</tspan>
                </text>
              )}
            </g>
          );
        }))}
      </svg>
      {hover && (
        <div className="chart-tip" style={{ left: Math.min(Math.max(0, hover.cx - 100), width - 230), top: Math.max(0, hover.cy - 120) }}>
          <div className="tt">{hover.s.label}</div>
          <div className="tr"><span>Score</span><b>{hover.p.score == null ? "Inconclusive" : `${hover.p.score}/100`}</b></div>
          <div className="tr"><span>Band</span><b>{bandInfo(hover.p.band).label}</b></div>
          <div className="tr"><span>Findings</span><b>{hover.p.findings}</b></div>
          <div className="tr"><span>Audited</span><b>{fmtDate(hover.p.t)}</b></div>
        </div>
      )}
      {!scored.length && <div className="pill-src">No scored audits yet.</div>}
    </div>
  );
}

/** rows: [{ key, code?, label, segments: [{ value, color, name }], tip: ReactNode, onClick? }]; unit: [singular, plural] */
export function HBars({ rows, unit = null, tour }) {
  const [hover, setHover] = useState(null);
  const max = Math.max(1, ...rows.map((r) => r.segments.reduce((a, s) => a + s.value, 0)));
  return (
    <div className="hbars" data-tour={tour} style={{ position: "relative" }} onMouseLeave={() => setHover(null)}>
      {rows.map((r) => {
        const total = r.segments.reduce((a, s) => a + s.value, 0);
        return (
          <div key={r.key} className="hbar" onMouseEnter={() => setHover(r.key)} onClick={r.onClick} style={r.onClick ? { cursor: "pointer" } : undefined}
            tabIndex={0} onFocus={() => setHover(r.key)} onBlur={() => setHover(null)}>
            <div className="hb-label">{r.code && <span className="code">{r.code}</span>}<span className="t">{r.label}</span></div>
            <div className="hb-track">
              <div className="hb-bar" style={{ width: `calc((100% - 96px) * ${total / max})` }}>
                {r.segments.filter((s) => s.value > 0).map((s) => <i key={s.name} style={{ flex: s.value, background: s.color }} />)}
              </div>
              <span className="hb-val">{total}{unit && <span className="muted" style={{ fontWeight: 500 }}> {total === 1 ? unit[0] : unit[1]}</span>}</span>
            </div>
            {hover === r.key && r.tip && <div className="chart-tip" style={{ right: 0, top: "100%", marginTop: 4 }}>{r.tip}</div>}
          </div>
        );
      })}
    </div>
  );
}

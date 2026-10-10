import { useState } from "react";
import { ScrollText, Search, FileUp, GitCompareArrows } from "lucide-react";
import { Card, PageHead, Fade } from "../ui";
import { ScoreChip, BandBadge, SeverityBadge, SampleBadge, EmptyState, AppMark, PlanTag } from "../ui/tv";
import { useData } from "../data/DataContext";
import { fmtDate, plural, sevInfo } from "../data/model";
import { navigate } from "../lib/router";

export default function Audits({ onImport }) {
  const { audits } = useData();
  const [q, setQ] = useState("");
  const shown = audits.filter((a) => `${a.label} ${a.pkg}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <div>
      <PageHead kicker="Consent X-ray" title="Audits" sub="Each audit runs the app three times: no interaction (C), Reject (B) and Accept (A), and records every DNS lookup against the consent choice."
        right={<button className="btn" onClick={() => navigate("/compare")}><GitCompareArrows size={16} />Compare two</button>} />
      <Fade><Card title={plural(audits.length, "audit")} icon={ScrollText} right={audits.length > 3 && (
        <div className="row" style={{ minWidth: 0 }}><Search size={15} className="muted" /><input className="input" style={{ padding: "7px 10px" }} placeholder="Filter by app or package" value={q} onChange={(e) => setQ(e.target.value)} /></div>
      )}>
        {!audits.length ? (
          <EmptyState icon={FileUp} title="No audits yet" body="Export an audit from the TraceVeil app and drop the JSON file here."><button className="btn primary" onClick={onImport}>Import exports</button></EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="tbl">
              <thead><tr><th>App</th><th>Audited</th><th>Plans</th><th>Findings</th><th>Score</th><th>Band</th></tr></thead>
              <tbody>
                {shown.map((a) => {
                  const sev = Object.entries(a.sevCounts).sort((x, y) => sevInfo(x[0]).rank - sevInfo(y[0]).rank);
                  return (
                    <tr key={a.id} className="click" onClick={() => navigate(`/audits/${encodeURIComponent(a.id)}`)}>
                      <td><div className="row"><AppMark label={a.label} size="sm" /><div style={{ minWidth: 0 }}><div className="row" style={{ gap: 8 }}><b>{a.label}</b>{a.sample && <SampleBadge info={a.sampleInfo} />}</div><div className="mono-s muted">{a.pkg}</div></div></div></td>
                      <td className="nowrap">{fmtDate(a.createdAtMs)}<div className="small muted">{a.device || "Device not recorded"}</div></td>
                      <td><div className="row" style={{ gap: 4 }}>{a.sessions.map((s) => <PlanTag key={s.id} plan={s.plan} withLabel={false} />)}</div>{a.gaps.length > 0 && <div className="small" style={{ color: "var(--sev-medium)" }}>incomplete</div>}</td>
                      <td><div className="row wrap" style={{ gap: 4 }}>{sev.length ? sev.map(([s, n]) => <SeverityBadge key={s} severity={s} count={n} />) : <span className="small muted">None in captured evidence</span>}</div></td>
                      <td><ScoreChip score={a.score} band={a.band} /></td>
                      <td><BandBadge band={a.band} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card></Fade>
    </div>
  );
}

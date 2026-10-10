import { ShieldCheck, SlidersHorizontal, Terminal, Github, FileJson, CheckCircle2, XCircle, AlertTriangle, RotateCcw, Check } from "lucide-react";
import { Card, PageHead, Fade, Hud, Toggle, toast } from "../ui";
import { CodeBlock, DownloadButton, ScoreChip, SampleBadge, EmptyState, AppMark, Truth } from "../ui/tv";
import { useData } from "../data/DataContext";
import { evaluatePolicy, policyJson, cliCommand, githubActions, DEFAULT_POLICY } from "../data/policy";
import { CHECKS, CHECK_ORDER, fmtDate } from "../data/model";
import { navigate } from "../lib/router";

const ICON = { pass: CheckCircle2, warn: AlertTriangle, fail: XCircle };
const COLOR = { pass: "var(--clear)", warn: "var(--sev-medium)", fail: "var(--sev-critical)" };

export default function Policy() {
  const { policy, setPolicy, audits } = useData();
  const set = (patch) => setPolicy({ ...policy, ...patch });
  const toggleCheck = (c) => set({ failOn: policy.failOn.includes(c) ? policy.failOn.filter((x) => x !== c) : [...policy.failOn, c] });
  const json = policyJson(policy);
  const results = audits.map((a) => ({ a, r: evaluatePolicy(policy, a) }));
  const passCount = results.filter((x) => x.r.verdict !== "fail").length;

  return (
    <div>
      <PageHead kicker="Release policy" title="Gate releases on privacy evidence" sub="Decide which findings block a release. The console previews the result against every audit loaded here; the same policy file drives the CI check."
        right={<DownloadButton name="traceveil-policy.json" text={json} label="Download policy.json" className="btn primary" />} />

      <div className="grid g-12">
        <Fade className="span-5"><Card title="Policy" icon={SlidersHorizontal} right={<button className="btn sm ghost" onClick={() => { setPolicy(DEFAULT_POLICY); toast("Policy reset to the defaults", "info"); }}><RotateCcw size={14} />Reset</button>}>
          <div className="stack">
            <div className="field">
              <span>Fail the build if any of these checks is found <span className="pill-src">failOn</span></span>
              <div className="check-grid">
                {CHECK_ORDER.map((c) => (
                  <button key={c} type="button" className={`check-opt ${policy.failOn.includes(c) ? "on" : ""}`} onClick={() => toggleCheck(c)} aria-pressed={policy.failOn.includes(c)} title={CHECKS[c]}>
                    <span className="box">{policy.failOn.includes(c) && <Check size={12} strokeWidth={3} />}</span><b>{c}</b><span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{CHECKS[c]}</span>
                  </button>
                ))}
              </div>
            </div>
            <div className="field">
              <span>Minimum score <span className="pill-src">minScore</span></span>
              <div className="num-input">
                <input className="slider" type="range" min={0} max={100} step={1} value={policy.minScore} onChange={(e) => set({ minScore: Number(e.target.value) })} aria-label="Minimum score" />
                <input className="input" type="number" min={0} max={100} value={policy.minScore} onChange={(e) => set({ minScore: e.target.value })} aria-label="Minimum score value" />
              </div>
            </div>
            <div className="field">
              <span>High or critical findings allowed <span className="pill-src">maxHigh</span></span>
              <div className="num-input">
                <button className="btn icon sm" onClick={() => set({ maxHigh: Math.max(0, policy.maxHigh - 1) })} aria-label="Fewer">−</button>
                <input className="input" type="number" min={0} max={99} value={policy.maxHigh} onChange={(e) => set({ maxHigh: e.target.value })} aria-label="High findings allowed" />
                <button className="btn icon sm" onClick={() => set({ maxHigh: policy.maxHigh + 1 })} aria-label="More">+</button>
              </div>
            </div>
            <div className="row between" style={{ gap: 16 }}>
              <div><div className="small" style={{ fontWeight: 600 }}>Require a fresh app state <span className="pill-src">requireFreshState</span></div><div className="small muted">Every plan must start from an approved reset that really cleared the app. "Not recorded" fails.</div></div>
              <Toggle on={policy.requireFreshState} onChange={(v) => set({ requireFreshState: v })} label="Require fresh state" />
            </div>
            <div className="row between" style={{ gap: 16 }}>
              <div><div className="small" style={{ fontWeight: 600 }}>Allow inconclusive audits <span className="pill-src">allowInconclusive</span></div><div className="small muted">When on, an audit with too little network visibility passes with a warning instead of failing.</div></div>
              <Toggle on={policy.allowInconclusive} onChange={(v) => set({ allowInconclusive: v })} label="Allow inconclusive" />
            </div>
            <Truth>The policy is saved in this browser as you edit it. A passing gate means no blocked finding in the captured evidence, not that the app is compliant.</Truth>
          </div>
        </Card></Fade>

        <div className="span-7 stack">
          <Fade delay={0.04}><Card title="Would these audits pass?" icon={ShieldCheck} tour="policy-preview" right={audits.length ? <Hud>{passCount}/{audits.length} pass</Hud> : null}>
            {!audits.length ? <EmptyState title="No audits loaded" body="Import audits to preview the gate." /> : (
              <div className="stack-sm">
                {results.map(({ a, r }) => {
                  const I = ICON[r.verdict];
                  return (
                    <div key={a.id} className="card tight" style={{ background: "rgba(0,0,0,0.12)" }}>
                      <div className="row" style={{ gap: 10 }}>
                        <AppMark label={a.label} size="sm" />
                        <button className="grow" style={{ textAlign: "left", minWidth: 0 }} onClick={() => navigate(`/audits/${encodeURIComponent(a.id)}`)}>
                          <div className="row" style={{ gap: 8 }}><b>{a.label}</b>{a.sample && <SampleBadge info={a.sampleInfo} />}</div>
                          <div className="small muted">{a.pkg} · {fmtDate(a.createdAtMs)}</div>
                        </button>
                        <ScoreChip score={a.score} band={a.band} />
                        <span className={`verdict ${r.verdict}`}><I size={13} />{r.verdict === "warn" ? "pass · warnings" : r.verdict}</span>
                      </div>
                      <ul className="rule-list" style={{ marginTop: 10 }}>
                        {r.rules.map((x) => { const RI = ICON[x.status]; return <li key={x.id}><RI size={14} color={COLOR[x.status]} /><span>{x.text}</span></li>; })}
                      </ul>
                    </div>
                  );
                })}
              </div>
            )}
          </Card></Fade>
          <Fade delay={0.06}><Card title="policy.json" icon={FileJson}><CodeBlock title="traceveil-policy.json" code={json} /></Card></Fade>
          <Fade delay={0.08}><Card title="Run it in CI" icon={Terminal} right={<span className="status dev">CLI in development</span>}>
            <div className="stack">
              <p className="small text-2">Intended usage of the <span className="mono-s">traceveil-ci</span> command: exit code 0 when the audit passes, 1 when it fails, with the same reasons as the preview above.</p>
              <CodeBlock title="shell" code={cliCommand()} />
              <div className="row" style={{ gap: 8 }}><Github size={16} /><b className="small">GitHub Actions</b></div>
              <CodeBlock title=".github/workflows/traceveil.yml" code={githubActions()} />
            </div>
          </Card></Fade>
        </div>
      </div>
    </div>
  );
}

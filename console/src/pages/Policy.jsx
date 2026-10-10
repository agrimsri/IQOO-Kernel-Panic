import { ShieldCheck, SlidersHorizontal, Terminal, Github, FileJson, CheckCircle2, XCircle, AlertTriangle, MinusCircle, RotateCcw, Check } from "lucide-react";
import { Card, PageHead, Fade, Hud, Toggle, toast } from "../ui";
import { CodeBlock, DownloadButton, ScoreChip, SampleBadge, EmptyState, AppMark, Truth } from "../ui/tv";
import { useData } from "../data/DataContext";
import { evaluatePolicy, policyJson, cliCommand, githubActions, DEFAULT_POLICY, PLAN_KEYS } from "../data/policy";
import { CHECKS, CHECK_ORDER, PLAN, fmtDate } from "../data/model";
import { navigate } from "../lib/router";

const ICON = { pass: CheckCircle2, warn: AlertTriangle, fail: XCircle, off: MinusCircle };
const COLOR = { pass: "var(--clear)", warn: "var(--sev-medium)", fail: "var(--sev-critical)", off: "var(--faint)" };

function Switch({ title, src, body, on, onChange }) {
  return (
    <div className="row between" style={{ gap: 16 }}>
      <div><div className="small" style={{ fontWeight: 600 }}>{title} <span className="pill-src">{src}</span></div><div className="small muted">{body}</div></div>
      <Toggle on={on} onChange={onChange} label={title} />
    </div>
  );
}

export default function Policy() {
  const { policy, setPolicy, audits } = useData();
  const set = (patch) => setPolicy({ ...policy, ...patch });
  const toggleIn = (key, v) => set({ [key]: policy[key].includes(v) ? policy[key].filter((x) => x !== v) : [...policy[key], v] });
  const json = policyJson(policy);
  const results = audits.map((a) => ({ a, r: evaluatePolicy(policy, a) }));
  const passCount = results.filter((x) => x.r.verdict !== "fail").length;
  const minOn = policy.minScore != null;
  const highOn = policy.maxHigh != null;

  return (
    <div>
      <PageHead kicker="Release policy" title="Gate releases on privacy evidence" sub="Decide what blocks a release. The console previews the result against every audit loaded here with the same keys, defaults and rules as traceveil-ci check."
        right={<DownloadButton name="traceveil-policy.json" text={json} label="Download policy.json" className="btn primary" />} />

      <div className="grid g-12">
        <Fade className="span-5"><Card title="Policy" icon={SlidersHorizontal} right={<button className="btn sm ghost" onClick={() => { setPolicy(DEFAULT_POLICY); toast("Policy reset to the traceveil-ci defaults", "info"); }}><RotateCcw size={14} />Reset</button>}>
          <div className="stack">
            <div className="field">
              <span>Fail the build if any of these checks is found, or could not be tested <span className="pill-src">failOn</span></span>
              <div className="check-grid">
                {CHECK_ORDER.map((c) => (
                  <button key={c} type="button" className={`check-opt ${policy.failOn.includes(c) ? "on" : ""}`} onClick={() => toggleIn("failOn", c)} aria-pressed={policy.failOn.includes(c)} title={CHECKS[c]}>
                    <span className="box">{policy.failOn.includes(c) && <Check size={12} strokeWidth={3} />}</span><b>{c}</b><span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{CHECKS[c]}</span>
                  </button>
                ))}
              </div>
            </div>
            <Switch title="Allow checks that could not be tested" src="allowNotEstablished" on={policy.allowNotEstablished} onChange={(v) => set({ allowNotEstablished: v })}
              body="Off (default): a failOn check the captured plans could not test, such as C2 without a recorded Reject or C9 without a child-facing declaration, is NOT ESTABLISHED and fails." />
            <div className="field">
              <div className="row between" style={{ gap: 16 }}>
                <span>Minimum score <span className="pill-src">minScore</span></span>
                <Toggle on={minOn} onChange={(v) => set({ minScore: v ? 85 : null })} label="Set a minimum score" />
              </div>
              {minOn ? (
                <div className="num-input">
                  <input className="slider" type="range" min={0} max={100} step={1} value={policy.minScore} onChange={(e) => set({ minScore: Number(e.target.value) })} aria-label="Minimum score" />
                  <input className="input" type="number" min={0} max={100} value={policy.minScore} onChange={(e) => set({ minScore: e.target.value })} aria-label="Minimum score value" />
                </div>
              ) : <div className="small muted">Not set (the traceveil-ci default). Not evaluated when a result is inconclusive.</div>}
            </div>
            <div className="field">
              <div className="row between" style={{ gap: 16 }}>
                <span>High or Critical findings allowed <span className="pill-src">maxHigh</span></span>
                <Toggle on={highOn} onChange={(v) => set({ maxHigh: v ? 0 : null })} label="Limit High or Critical findings" />
              </div>
              {highOn ? (
                <div className="num-input">
                  <button className="btn icon sm" onClick={() => set({ maxHigh: Math.max(0, policy.maxHigh - 1) })} aria-label="Fewer">−</button>
                  <input className="input" type="number" min={0} max={99} value={policy.maxHigh} onChange={(e) => set({ maxHigh: e.target.value })} aria-label="High or Critical findings allowed" />
                  <button className="btn icon sm" onClick={() => set({ maxHigh: policy.maxHigh + 1 })} aria-label="More">+</button>
                </div>
              ) : <div className="small muted">Not set (the traceveil-ci default). When set, counts findings at High and Critical severity.</div>}
            </div>
            <Switch title="Require a fresh app state" src="requireFreshState" on={policy.requireFreshState} onChange={(v) => set({ requireFreshState: v })}
              body={'Every plan must start from an approved reset that really cleared the app. Kept data fails, and so does "not recorded".'} />
            <Switch title="Allow inconclusive audits" src="allowInconclusive" on={policy.allowInconclusive} onChange={(v) => set({ allowInconclusive: v })}
              body="Off (default): an audit with too little network visibility (V0) fails." />
            <div className="field">
              <span>Plans that must be captured <span className="pill-src">requirePlans</span></span>
              <div className="check-grid">
                {PLAN_KEYS.map((k) => (
                  <button key={k} type="button" className={`check-opt ${policy.requirePlans.includes(k) ? "on" : ""}`} onClick={() => toggleIn("requirePlans", k)} aria-pressed={policy.requirePlans.includes(k)}>
                    <span className="box">{policy.requirePlans.includes(k) && <Check size={12} strokeWidth={3} />}</span><b>{PLAN[k].code}</b><span>{PLAN[k].label}{PLAN[k].expects ? ", with the choice recorded" : ""}</span>
                  </button>
                ))}
              </div>
              <div className="small muted">An Accept or Reject plan counts only when that choice was recorded; a run with no consent screen has no choice.</div>
            </div>
            <Truth>The policy is saved in this browser as you edit it. The preview uses the findings stored in each export; traceveil-ci re-scores the sessions with its own rules first, so an export scored by an older app version can differ. A passing gate means no blocking evidence in the capture, not that the app is compliant.</Truth>
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
                        {r.rules.map((x, i) => { const RI = ICON[x.status]; return <li key={`${x.id}-${i}`}><RI size={14} color={COLOR[x.status]} /><span><span className="pill-src">{x.id}</span> {x.text}</span></li>; })}
                      </ul>
                    </div>
                  );
                })}
              </div>
            )}
          </Card></Fade>
          <Fade delay={0.06}><Card title="policy.json" icon={FileJson}><CodeBlock title="traceveil-policy.json" code={json} /></Card></Fade>
          <Fade delay={0.08}><Card title="Run it in CI" icon={Terminal} right={<span className="row" style={{ gap: 6, flexWrap: "wrap", justifyContent: "flex-end" }}><span className="status live">CLI built</span><span className="status dev">Workflow not yet run on GitHub</span></span>}>
            <div className="stack">
              <p className="small text-2">The <span className="mono-s">traceveil-ci</span> tool is built from the TraceVeil repository (<span className="mono-s">cli/</span>) and tested there. It re-scores each exported audit with its own rules, applies this policy, and exits 0 when every bundle passes, 1 on a violation and 2 on unusable input. The workflow below is the repository's example; it has not yet been run on GitHub.</p>
              <CodeBlock title="shell" code={cliCommand()} />
              <div className="row" style={{ gap: 8 }}><Github size={16} /><b className="small">GitHub Actions (docs/ci/traceveil-gate.yml)</b></div>
              <CodeBlock title=".github/workflows/traceveil-gate.yml" code={githubActions()} />
            </div>
          </Card></Fade>
        </div>
      </div>
    </div>
  );
}

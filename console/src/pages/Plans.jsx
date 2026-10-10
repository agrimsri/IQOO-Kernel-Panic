import { useState } from "react";
import { Smartphone, Users, Building2, Check, Clock, Wrench, Hammer, Info } from "lucide-react";
import { Card, PageHead, Fade, Hud, Sheet } from "../ui";
import { Truth } from "../ui/tv";
import { CATALOGUE_SIZE } from "../data/model";

// "Available" only for what has been verified on a phone (or, for the console, by its own smoke test). Built features
// that have unit tests but no phone check yet say so; a third tuple element overrides the label.
const LIVE = "live", BUILT = "built", DEV = "dev", SOON = "soon";
const STATUS_LABEL = { live: "Available", built: "Built · phone check pending", dev: "In development", soon: "Coming soon" };

const PLANS = [
  {
    id: "free", icon: Smartphone, name: "Free", price: "No account needed", priceSub: "for individuals and testers",
    blurb: "The on-phone Android App Trust Lab and this local console.",
    features: [
      ["Consent X-ray: three runs (no interaction, Reject, Accept) with DNS evidence", LIVE],
      ["Live consent timeline and findings labelled observed or inferred", LIVE],
      ["Fix snippets for each finding", LIVE],
      ["Voice copilot: ask about an audit's evidence on the phone", LIVE],
      ["Network Proof: per-plan upload estimate with its accounting caveat", LIVE],
      ["Map of the endpoints an app contacted", LIVE],
      ["Draft notice to the app's developer", LIVE],
      ["APK Guard: an app's powers before you install or open it", BUILT],
      ["Quarantine: run an app with its internet cut off and get a behaviour report", BUILT],
      ["Hey TraceVeil wake word and assistant shortcuts (opt-in, offline)", BUILT],
      ["Guided UI: onboarding, step guidance and spoken guidance", BUILT],
      ["Offline HTML evidence report from the phone", BUILT],
      ["On-device AI explanations of findings", BUILT],
      ["Local console: import, portfolio, compare, policy preview (this page)", LIVE],
    ],
    cta: null,
  },
  {
    id: "pro", icon: Users, name: "Pro team", price: "Free during beta", priceSub: "pricing to be announced",
    blurb: "For app teams that want privacy evidence in every release.", highlight: true,
    features: [
      ["CI release gate: traceveil-ci check and diff with your policy.json, SARIF for code scanning", BUILT, "Built · GitHub run pending"],
      ["Opt-in, end-to-end encrypted report sync between teammates (off by default)", SOON],
      ["Legal lenses: India (DPDP, readiness), EU (GDPR, ePrivacy), UK (UK GDPR, PECR), California (CCPA), US children (COPPA), Brazil (LGPD), Singapore (PDPA), global baseline", LIVE],
      [`Signed tracker-catalogue updates (today: ${CATALOGUE_SIZE} sourced entries bundled with the app)`, SOON],
    ],
    cta: "Join the beta",
  },
  {
    id: "ent", icon: Building2, name: "Enterprise", price: "Contact", priceSub: "for security and compliance teams",
    blurb: "Run TraceVeil across a fleet and keep everything inside your network.",
    features: [
      ["On-premises console and storage", SOON],
      ["Single sign-on (SAML / OIDC)", SOON],
      ["MDM pilot: TraceVeil on managed test devices", SOON],
      ["Everything in Pro team", SOON],
    ],
    cta: "Contact the team",
  },
];

export default function Plans() {
  const [ask, setAsk] = useState(null);
  return (
    <div>
      <PageHead kicker="Plans" title="Start free on the phone, grow into a team gate" sub="TraceVeil is in beta. Labels below say exactly what exists today and what is still being built." />
      <div className="grid g3">
        {PLANS.map((p, i) => (
          <Fade key={p.id} delay={i * 0.04}>
            <Card className={p.highlight ? "glow" : ""} style={{ height: "100%" }}>
              <div className="plan-card">
                <div className="row between">
                  <div className="row" style={{ gap: 10 }}><span className="kpi-ic" style={{ width: 36, height: 36, borderRadius: 11, display: "grid", placeItems: "center", background: "var(--surface-2)" }}><p.icon size={18} /></span><h2>{p.name}</h2></div>
                  {p.highlight && <Hud tone="pink">For teams</Hud>}
                </div>
                <div><div className="price">{p.price}</div><div className="small muted">{p.priceSub}</div></div>
                <p className="small text-2">{p.blurb}</p>
                <ul className="feat">
                  {p.features.map(([f, st, label]) => (
                    <li key={f}>
                      {st === LIVE ? <Check size={15} color="var(--clear)" /> : st === BUILT ? <Hammer size={15} color="var(--sev-medium)" /> : st === DEV ? <Wrench size={15} color="var(--sev-medium)" /> : <Clock size={15} color="var(--lav)" />}
                      <span>{f}</span>
                      <span className={`status ${st}`}>{label || STATUS_LABEL[st]}</span>
                    </li>
                  ))}
                </ul>
                <div style={{ marginTop: "auto" }}>
                  {p.cta ? <button className={`btn block ${p.highlight ? "primary" : ""}`} onClick={() => setAsk(p)}>{p.cta}</button>
                    : <button className="btn block" disabled>You are using it</button>}
                </div>
              </div>
            </Card>
          </Fade>
        ))}
      </div>
      <div className="grid g3" style={{ marginTop: 16 }}>
        <Truth icon={Info}>No customer logos or testimonials are shown because there are none to show yet. Prices are not set; "Free during beta" and "Contact" are the only commitments on this page.</Truth>
        <Truth icon={Info}>"Available" means verified on a phone (the console: by its own end-to-end test). "Built · phone check pending" means the feature is in the current build and unit-tested, but nobody has checked it on a phone yet.</Truth>
        <Truth icon={Info}>Every plan keeps the same rules: evidence is labelled observed, inferred or not established; nothing leaves the phone or this browser unless a team turns sync on; findings are context, not legal advice or a malware verdict.</Truth>
      </div>
      <Sheet open={!!ask} onClose={() => setAsk(null)} title={ask?.name || ""}>
        <div className="stack">
          <p className="text-2">There is no sign-up form or payment yet. Pilot teams are onboarded by talking to the TraceVeil team directly.</p>
          <p className="text-2">Until then, everything on the Free plan works today: the phone app and this console, entirely offline.</p>
          <button className="btn block" onClick={() => setAsk(null)}>Got it</button>
        </div>
      </Sheet>
    </div>
  );
}

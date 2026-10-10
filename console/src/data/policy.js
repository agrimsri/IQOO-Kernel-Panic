// Release-gate policy: the same rules the planned `traceveil-ci check` command applies to an exported audit.
import { POLICY_SCHEMA, CHECKS, sevInfo, planInfo } from "./model";

export const DEFAULT_POLICY = {
  schema: POLICY_SCHEMA,
  failOn: ["C2", "C9", "NC"],
  minScore: 70,
  maxHigh: 0,
  requireFreshState: false,
  allowInconclusive: false,
};

export function normalisePolicy(p) {
  const x = { ...DEFAULT_POLICY, ...(p || {}) };
  return {
    schema: POLICY_SCHEMA,
    failOn: [...new Set((Array.isArray(x.failOn) ? x.failOn : []).filter((c) => CHECKS[c]))],
    minScore: clampInt(x.minScore, 0, 100, DEFAULT_POLICY.minScore),
    maxHigh: clampInt(x.maxHigh, 0, 99, DEFAULT_POLICY.maxHigh),
    requireFreshState: !!x.requireFreshState,
    allowInconclusive: !!x.allowInconclusive,
  };
}
const clampInt = (v, lo, hi, d) => (Number.isFinite(Number(v)) ? Math.max(lo, Math.min(hi, Math.round(Number(v)))) : d);

/**
 * Evaluates one audit view. Returns { verdict: "pass" | "warn" | "fail", rules: [{ id, status: "pass"|"warn"|"fail", text }] }.
 * "High findings" counts every finding at High or Critical severity.
 */
export function evaluatePolicy(policy, a) {
  const rules = [];
  const add = (id, status, text) => rules.push({ id, status, text });
  const inconclusive = a.score == null || a.band === "INCONCLUSIVE";

  const hits = a.findings.filter((f) => policy.failOn.includes(f.checkId));
  if (!policy.failOn.length) add("failOn", "pass", "No check is set to fail the build on its own.");
  else if (hits.length) add("failOn", "fail", `Blocked checks found: ${hits.map((f) => `${f.checkId} (${CHECKS[f.checkId] || f.title})`).join(", ")}.`);
  else add("failOn", "pass", `None of ${policy.failOn.join(", ")} was found.`);

  if (inconclusive) add("minScore", policy.allowInconclusive ? "warn" : "fail", `No score: the audit is inconclusive${policy.allowInconclusive ? " (allowed by this policy)" : ""}.`);
  else if (a.score < policy.minScore) add("minScore", "fail", `Score ${a.score} is below the minimum ${policy.minScore}.`);
  else add("minScore", "pass", `Score ${a.score} meets the minimum ${policy.minScore}.`);

  const high = a.findings.filter((f) => sevInfo(f.severity).rank <= 1).length;
  add("maxHigh", high > policy.maxHigh ? "fail" : "pass", `${high} high or critical finding${high === 1 ? "" : "s"} (allowed: ${policy.maxHigh}).`);

  if (policy.requireFreshState) {
    const notFresh = a.sessions.filter((s) => s.freshState !== true);
    if (notFresh.length) {
      const why = notFresh.map((s) => `${planInfo(s.plan).label}: ${s.freshState === false ? "app kept its data" : "not recorded"}`).join("; ");
      add("requireFreshState", "fail", `A fresh app state is required but not established — ${why}.`);
    } else add("requireFreshState", "pass", "Every plan started from an approved, cleared app state.");
  }

  if (a.gaps.length) add("coverage", "warn", `Incomplete runs: ${a.gaps.join(" ")}`);
  if (a.sample) add("sample", "warn", "This is sample data, not a live audit.");

  const verdict = rules.some((r) => r.status === "fail") ? "fail" : rules.some((r) => r.status === "warn") ? "warn" : "pass";
  return { verdict, rules };
}

export const policyJson = (p) => JSON.stringify(normalisePolicy(p), null, 2) + "\n";

export const cliCommand = (file = "traceveil-policy.json") => `traceveil-ci check --policy ${file} audits/latest.traceveil.json`;

export const githubActions = (file = "traceveil-policy.json") => `# .github/workflows/traceveil.yml
name: TraceVeil privacy gate
on:
  pull_request:
  workflow_dispatch:

jobs:
  privacy-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      # traceveil-ci is in development: put the CLI on PATH before this step.
      # The audit is recorded on a test phone with the TraceVeil app and committed
      # (or uploaded as a build artifact) as audits/latest.traceveil.json.
      - name: Check the latest TraceVeil audit against the release policy
        run: ${cliCommand(`.traceveil/${file}`)}
`;

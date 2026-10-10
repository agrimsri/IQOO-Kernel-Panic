// Release-gate policy: the same keys, defaults and rules as `traceveil-ci check`
// (cli/src/main/kotlin/com/dpdpxray/cli/Policy.kt and Gate.kt; documented in docs/CI_MODE.md).
// The preview applies them to the findings stored in each export. traceveil-ci first re-scores the sessions with its
// own rules engine, so an export scored by an older app version can differ; the coverage rules below read the
// recorded sessions exactly as the CLI does.
import { POLICY_SCHEMA, CHECKS, PLAN, planInfo } from "./model";
import gateWorkflow from "@repo-docs/ci/traceveil-gate.yml?raw";

export const PLAN_KEYS = ["ACCEPT", "REJECT", "SILENT"];

/** traceveil-ci defaults (Policy.kt). minScore and maxHigh are not set (null) unless the team sets them. */
export const DEFAULT_POLICY = {
  failOn: ["C2", "C9"],
  minScore: null,
  maxHigh: null,
  requireFreshState: true,
  allowInconclusive: false,
  allowNotEstablished: false,
  requirePlans: [...PLAN_KEYS],
};

const arr = (x) => (Array.isArray(x) ? x : []);
const optInt = (v, lo, hi) => {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(lo, Math.min(hi, Math.round(n))) : null;
};

export function normalisePolicy(p) {
  const x = { ...DEFAULT_POLICY, ...(p || {}) };
  return {
    failOn: [...new Set(arr(x.failOn).filter((c) => CHECKS[c]))],
    minScore: optInt(x.minScore, 0, 100),
    maxHigh: optInt(x.maxHigh, 0, 9999),
    requireFreshState: !!x.requireFreshState,
    allowInconclusive: !!x.allowInconclusive,
    allowNotEstablished: !!x.allowNotEstablished,
    requirePlans: PLAN_KEYS.filter((k) => arr(x.requirePlans).includes(k)),
  };
}

// --- what the recorded sessions can show (Gate.kt Coverage, PlanCoverage) ---------------------------------------
const KEPT_DATA_NOTES = ["App data was not cleared", "Could not clear app data"];
const choiceOf = (s) => arr(s.consentEvents).find((c) => c.kind === "ACCEPT" || c.kind === "REJECT")?.kind || null;
const screenSeen = (s) => arr(s.consentEvents).some((c) => c.kind === "SCREEN_APPEARED" || c.kind === "MANUAL_MARK") || !!choiceOf(s);
const appDataKept = (s) => (s.freshState != null ? !s.freshState : arr(s.notes).some((n) => KEPT_DATA_NOTES.some((k) => String(n).toLowerCase().includes(k.toLowerCase()))));
const reviewedScreen = (sessions) => sessions.map((s) => s.screenAudit).find((x) => x && x.isConsentScreen) || null;

/** Why the captured plans could not show `id` at all, or null when they could. Unknown stays unknown. */
export function untested(id, sessions) {
  if (!sessions.length) return "no plans were captured";
  const historyUnknown = sessions.every((s) => appDataKept(s) && !screenSeen(s));
  switch (id) {
    case "C2":
      return sessions.some((s) => choiceOf(s) === "REJECT") ? null : "no Reject choice was recorded, so behaviour after refusal was not observed";
    case "C1": case "C10": case "NC":
      return historyUnknown ? "every plan ran on the app's kept data without a consent screen (consent history unknown)" : null;
    case "C3": case "C4": case "C5": case "C6": case "C7": case "C8":
      return sessions.some((s) => s.screenAudit?.isConsentScreen === true) ? null : "no consent screen was reviewed";
    case "C9":
      return !sessions.some((s) => s.childFacing) && reviewedScreen(sessions)?.childAudienceSignals == null
        ? "the app was not declared child-facing in this capture and no consent screen was reviewed for child-audience signals" : null;
    default:
      return null;
  }
}

const SEV_LABEL = { CRITICAL: "Critical", HIGH: "High", MEDIUM: "Medium", LOW: "Low", INFO: "Info" };

/**
 * Evaluates one audit view like `traceveil-ci check`. Returns { verdict: "pass" | "warn" | "fail", rules: [{ id, status, text }] }.
 * status: "pass" | "fail" | "warn" (NOT ESTABLISHED, reported without failing, or a console-only note) | "off" (not set).
 */
export function evaluatePolicy(policy, a) {
  const p = normalisePolicy(policy);
  const sessions = arr(a.rec?.bundle?.sessions);
  const rules = [];
  const add = (id, status, text) => rules.push({ id, status, text });
  const inconclusive = a.score == null || a.band === "INCONCLUSIVE";

  if (!inconclusive) add("allowInconclusive", "pass", "Result is conclusive.");
  else if (p.allowInconclusive) add("allowInconclusive", "pass", "Result is inconclusive (low network visibility); the policy accepts that.");
  else add("allowInconclusive", "fail", "Result is inconclusive (V0: almost no lookups were captured), so this capture cannot pass; re-run it.");

  for (const id of p.failOn) {
    const f = a.findings.find((x) => x.checkId === id);
    if (f) { add(`failOn ${id}`, "fail", `${id} ${f.title || CHECKS[id]} (${SEV_LABEL[f.severity] || f.severity}, ${f.evidenceType || "unlabelled"} evidence).`); continue; }
    const why = untested(id, sessions);
    if (why) {
      if (p.allowNotEstablished) add(`failOn ${id}`, "warn", `${id} NOT ESTABLISHED: ${why} (reported, not failed: allowNotEstablished is on).`);
      else add(`failOn ${id}`, "fail", `${id} NOT ESTABLISHED: ${why}. A failOn check that could not be tested fails unless allowNotEstablished is on.`);
      continue;
    }
    let context = "";
    if (id === "C9") {
      context = sessions.some((s) => s.childFacing) ? " (the app was declared child-facing in this capture)"
        : ` (not declared child-facing; the consent-screen review found ${reviewedScreen(sessions)?.childAudienceSignals === true ? "" : "no "}child-audience signals)`;
    }
    add(`failOn ${id}`, "pass", `No ${id} finding in the captured evidence${context}.`);
  }
  if (!p.failOn.length) add("failOn", "off", "No check is set to fail the build on its own.");

  if (p.minScore == null) add("minScore", "off", "minScore not set.");
  else if (inconclusive) add("minScore", "warn", "Score not established (inconclusive result).");
  else if (a.score >= p.minScore) add("minScore", "pass", `Score ${a.score} is at least ${p.minScore}.`);
  else add("minScore", "fail", `Score ${a.score} is below the minimum ${p.minScore}.`);

  if (p.maxHigh == null) add("maxHigh", "off", "maxHigh not set.");
  else {
    const high = a.findings.filter((f) => f.severity === "CRITICAL" || f.severity === "HIGH");
    const list = high.length ? ` (${high.map((f) => f.checkId).join(", ")})` : "";
    if (high.length <= p.maxHigh) add("maxHigh", "pass", `${high.length} at High or Critical severity${list}; at most ${p.maxHigh} allowed.`);
    else add("maxHigh", "fail", `${high.length} findings at High or Critical severity${list}; the policy allows ${p.maxHigh}.`);
  }

  if (!p.requireFreshState) add("requireFreshState", "off", "Fresh app state not required.");
  else if (!sessions.length) add("requireFreshState", "fail", "No plans were captured.");
  else {
    const problems = sessions.flatMap((s) => {
      const label = planInfo(s.plan).label;
      if (s.freshState === true) return [];
      if (s.freshState === false) return [`${label} ran on the app's kept data (no approved fresh reset)`];
      return [`${label}: fresh state NOT ESTABLISHED (not recorded in this audit)`];
    });
    if (problems.length) add("requireFreshState", "fail", `${problems.join("; ")}.`);
    else add("requireFreshState", "pass", `All ${sessions.length} plans started from an approved fresh reset.`);
  }

  if (!p.requirePlans.length) add("requirePlans", "off", "No plans required.");
  else {
    const problems = [];
    for (const plan of p.requirePlans) {
      const runs = sessions.filter((s) => s.plan === plan);
      const label = PLAN[plan].label;
      if (!runs.length) { problems.push(`${label} was not captured`); continue; }
      const expected = PLAN[plan].expects;
      if (expected && !runs.some((s) => choiceOf(s) === expected)) {
        const word = expected === "ACCEPT" ? "Accept" : "Reject";
        problems.push(runs.some(screenSeen)
          ? `${label}: a consent screen appeared but no ${word} choice was recorded, so behaviour after it was not observed`
          : `${label}: no consent screen appeared, so no ${word} choice was recorded and behaviour after it was not observed`);
      }
    }
    if (problems.length) add("requirePlans", "fail", `${problems.join("; ")}.`);
    else add("requirePlans", "pass", `Captured: ${p.requirePlans.map((k) => PLAN[k].label).join(", ")}.`);
  }

  if (a.sample) add("sample", "warn", "This is sample data, not a live audit (console note; traceveil-ci prints a SYNTHETIC SAMPLE warning).");

  const verdict = rules.some((r) => r.status === "fail") ? "fail" : rules.some((r) => r.status === "warn") ? "warn" : "pass";
  return { verdict, rules };
}

/** policy.json exactly as traceveil-ci reads it: known keys only; "_"-prefixed keys are comments the CLI ignores. */
export const policyJson = (policy) => {
  const p = normalisePolicy(policy);
  const out = {
    _comment: `TraceVeil CI release gate (${POLICY_SCHEMA}), written by TraceVeil Console. Keys and defaults: docs/CI_MODE.md.`,
    failOn: p.failOn,
    minScore: p.minScore,
    maxHigh: p.maxHigh,
    requireFreshState: p.requireFreshState,
    allowInconclusive: p.allowInconclusive,
    allowNotEstablished: p.allowNotEstablished,
    requirePlans: p.requirePlans,
  };
  return JSON.stringify(out, null, 2) + "\n";
};

/** Real traceveil-ci usage (cli/src/main/kotlin/com/dpdpxray/cli/Main.kt). */
export const cliCommand = (file = "traceveil-policy.json") => `# Build the tool once from the TraceVeil repository (Java 17):
./gradlew :cli:installDist          # cli/build/install/traceveil-ci/bin/traceveil-ci(.bat)

# Gate a release on the audit exported from the phone (Report > "Export audit data (JSON)"):
traceveil-ci check privacy/traceveil/release-1.4.0.json --policy ${file}

# Same check, SARIF for GitHub code scanning (the text report still goes to the log):
traceveil-ci check privacy/traceveil/release-1.4.0.json --policy ${file} --format sarif --out traceveil.sarif

# Release-to-release regression check:
traceveil-ci diff privacy/traceveil/release-1.3.0.json privacy/traceveil/release-1.4.0.json

# Exit codes: 0 pass (diff: no regression), 1 policy violation (diff: regression), 2 usage or unreadable input.`;

/** The repository's example workflow, docs/ci/traceveil-gate.yml, verbatim (read at build time). */
export const githubActions = () => gateWorkflow.replace(/\r\n/g, "\n");

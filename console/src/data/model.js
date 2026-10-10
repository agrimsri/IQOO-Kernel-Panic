// Names, labels and catalogues shared by every page. Enum names match core/src/main/kotlin/com/dpdpxray/core/model/Model.kt.
// The tracker catalogue, fix templates and DPDP passages are read at build time from the TraceVeil core resources.
import trackers from "@core-resources/trackers/trackers.json";
import fixTemplates from "@core-resources/fixes/fix_templates.json";
import passages from "@core-resources/dpdp/passages.json";
import lensData from "@core-resources/law/profiles.json";
import lensPassages from "@core-resources/law/passages.json";

export const AUDIT_SCHEMA = "traceveil.audit.v1";
export const QUARANTINE_SCHEMA = "traceveil.quarantine.v1";
export const BACKUP_SCHEMA = "traceveil.console-backup.v1";
/** The phone's "Back up my checks" file: { schema, createdAt, appVersion, audits: [traceveil.audit.v1 ...], sha256 }. */
export const PHONE_BACKUP_SCHEMA = "traceveil.backup.v1";
export const POLICY_SCHEMA = "traceveil.policy.v1";

export const PLAN = {
  SILENT: { code: "C", label: "Plan C · No interaction", short: "No interaction", order: 0, expects: null },
  REJECT: { code: "B", label: "Plan B · Reject", short: "Reject", order: 1, expects: "REJECT" },
  ACCEPT: { code: "A", label: "Plan A · Accept", short: "Accept", order: 2, expects: "ACCEPT" },
};
export const planInfo = (p) => PLAN[p] || { code: "?", label: String(p), short: String(p), order: 9, expects: null };

export const SEVERITY = {
  CRITICAL: { label: "Critical", weight: 30, rank: 0, color: "var(--sev-critical)", tone: "rose" },
  HIGH: { label: "High", weight: 15, rank: 1, color: "var(--sev-high)", tone: "amber" },
  MEDIUM: { label: "Medium", weight: 8, rank: 2, color: "var(--sev-medium)", tone: "amber" },
  LOW: { label: "Low", weight: 3, rank: 3, color: "var(--sev-low)", tone: "cyan" },
  INFO: { label: "Info", weight: 0, rank: 4, color: "var(--sev-info)", tone: "muted" },
};
export const sevInfo = (s) => SEVERITY[s] || { label: String(s || "Unknown"), weight: 0, rank: 5, color: "var(--sev-info)", tone: "muted" };

export const BAND = {
  READY: { label: "Ready", short: "Ready", color: "var(--clear)", tone: "green" },
  NEEDS_WORK: { label: "Needs work", short: "Needs work", color: "var(--sev-medium)", tone: "amber" },
  AT_RISK: { label: "At risk", short: "At risk", color: "var(--sev-critical)", tone: "rose" },
  INCONCLUSIVE: { label: "Inconclusive", short: "Inconclusive", color: "var(--lav)", tone: "muted" },
};
export const bandInfo = (b) => BAND[b] || { label: String(b || "Unknown"), short: String(b || "Unknown"), color: "var(--lav)", tone: "muted" };

export const CHECKS = {
  C1: "Tracker hosts looked up before consent",
  C2: "Tracker hosts looked up after the user refused",
  C3: "Pre-ticked consent option",
  C4: "No clear way to refuse",
  C5: "Bundled consent for several purposes",
  C6: "Notice lacks itemised data or purpose",
  C7: "No visible way to withdraw consent",
  C8: "Notice offered only in English",
  C9: "Tracking in a child-facing app",
  C10: "Device identifiers likely shared before consent",
  NC: "No consent screen seen during the capture",
  V0: "Low network visibility — result inconclusive",
  U1: "Consent history unknown — app kept its data",
  S1: "Same-company servers contacted (first-party data collection)",
  C11: "Tracker reached through an alias name (CNAME cloaking pattern)",
  C12: "Tracker hosts looked up while the app was in the background",
  C13: "SDK data collection on by default in the app's manifest",
  C14: "Android permission requested before any consent screen or notice",
  C15: "Consent screen shows no privacy-policy link or text",
  V1: "Some lookups may be hidden from TraceVeil",
};
export const CHECK_ORDER = Object.keys(CHECKS);

export const PHASE = {
  BEFORE_CONSENT: { label: "Before the consent choice", short: "Before consent", color: "var(--sev-high)" },
  AFTER_REJECT: { label: "After the user refused", short: "After Reject", color: "var(--sev-critical)" },
  AFTER_ACCEPT: { label: "After the user accepted", short: "After Accept", color: "var(--scan)" },
  NO_CONSENT_SEEN: { label: "No consent screen seen", short: "No consent screen", color: "var(--lav)" },
};

export const CATEGORY_LABEL = {
  ANALYTICS: "Analytics", ADVERTISING: "Advertising", ATTRIBUTION: "Attribution", SOCIAL: "Social", CRASH_REPORTING: "Crash reporting",
  ENGAGEMENT: "Engagement", FIRST_PARTY: "First party", UNKNOWN: "Not in catalogue", CONTENT_DELIVERY: "Content delivery",
  FIRST_PARTY_SAME_COMPANY: "Same company as the app", COMPANY_SERVICE: "Company service (not a tracking endpoint)",
};
export const TRACKING = new Set(["ANALYTICS", "ADVERTISING", "ATTRIBUTION", "SOCIAL", "ENGAGEMENT"]);

/** OBSERVED / INFERRED / NOT ESTABLISHED: the three evidence labels used everywhere. */
export const EVIDENCE = {
  OBSERVED: { label: "Observed", color: "var(--ev-observed)", help: "Seen on the device: captured traffic or the screen's accessibility tree." },
  INFERRED: { label: "Inferred", color: "var(--ev-inferred)", help: "Reasoned from what was seen (vendor documentation, the on-device AI's reading, or a pattern). Not directly observed." },
  NOT_ESTABLISHED: { label: "Not established", color: "var(--ev-unknown)", help: "This evidence cannot tell. Unknown stays unknown, never zero." },
};

// ---------- tracker catalogue (same matching as core TrackerCatalog.classify: host suffixes, then CNAME aliases) ----------
const byDomain = new Map();
for (const t of trackers) for (const d of t.domains || []) byDomain.set(d.toLowerCase(), t);
const byEntity = new Map(trackers.map((t) => [t.entity, t]));
export const CATALOGUE_SIZE = trackers.length;

const norm = (h) => String(h || "").toLowerCase().replace(/\.$/, "");
const suffixes = (host) => { const p = host.split("."); return p.map((_, i) => p.slice(i).join(".")).filter((s) => s.includes(".") || s === host); };
const entryForHost = (host) => { for (const s of suffixes(host)) { const e = byDomain.get(s); if (e) return e; } return null; };

/**
 * Entity, company and category for one lookup. A stored entity wins, as in the app's SessionClassifier; a stored catalogue
 * entity takes the catalogue's current category, so corrections (facebook.com is a company service) reach older audits.
 */
export function classifyHost(hostname, cnameChain = [], storedEntity = null, storedCategory = null) {
  if (storedEntity) {
    const e = byEntity.get(storedEntity);
    const category = e?.category || (storedCategory && storedCategory !== "UNKNOWN" ? storedCategory : "UNKNOWN");
    return { entity: storedEntity, company: e?.company || storedEntity, category, sdkKey: e?.sdkKey || null, adIdAtInit: !!e?.collectsAdIdAtInit, via: null };
  }
  const host = norm(hostname);
  const direct = entryForHost(host);
  if (direct) return info(direct);
  for (const alias of (cnameChain || []).map(norm)) {
    if (alias === host) continue;
    const e = entryForHost(alias);
    if (e && e.category !== "CONTENT_DELIVERY" && e.category !== "COMPANY_SERVICE") return info(e, alias);
  }
  const cat = storedCategory && storedCategory !== "UNKNOWN" ? storedCategory : "UNKNOWN";
  return { entity: null, company: null, category: cat, sdkKey: null, adIdAtInit: false, via: null };
}
const info = (e, via = null) => ({ entity: e.entity, company: e.company || e.entity, category: e.category, sdkKey: e.sdkKey || null, adIdAtInit: !!e.collectsAdIdAtInit, via });
export const companyOfEntity = (entity) => byEntity.get(entity)?.company || entity;

// ---------- fix templates (same selection as core FixCatalog.fixesFor) ----------
const SDK_CHECKS = new Set(["C1", "C2", "C9", "C10", "C13"]);
const fixById = new Map(fixTemplates.map((t) => [t.id, t]));
export function fixesForFinding(finding) {
  const out = [];
  if (SDK_CHECKS.has(finding.checkId)) {
    for (const ent of finding.entities || []) {
      const key = byEntity.get(ent)?.sdkKey;
      const t = key && fixById.get(key);
      if (t && t.appliesTo.includes(finding.checkId) && !out.includes(t)) out.push(t);
    }
  }
  for (const t of fixTemplates) if (t.kind === "ui" && t.appliesTo.includes(finding.checkId) && !out.includes(t)) out.push(t);
  return out;
}

export const fixTemplate = (id) => fixById.get(id) || null;

// ---------- "Which SDK caused this?" (the export's optional sdkAttribution part, written by core SdkAttribution) ----------
// The groups are read as exported; these helpers only word them the way the app does (SdkAttribution companion).
export const SDK_BEFORE_CONSENT = "before consent";
export const SDK_AFTER_REJECT = "after Reject";
export const SDK_LOOKUP_CAVEAT = "A lookup is an attempt to contact a host, not proof that data was sent.";
/** "first seen 3.0 s after launch · before consent", or "" when no host of the group has a timed sighting. */
export function sdkFirstSeenLine(group) {
  const timed = (group.hosts || []).filter((h) => typeof h.firstSeenSeconds === "number");
  if (!timed.length) return "";
  const first = timed.reduce((a, h) => (h.firstSeenSeconds < a.firstSeenSeconds ? h : a));
  return `first seen ${first.firstSeenSeconds.toFixed(1)} s after launch · ${first.firstRelation}`;
}
/** One calm sentence naming the services behind the weighted findings, or null (core SdkAttribution.headline, by service). */
export function sdkHeadline(groups) {
  const weights = new Map();
  for (const g of groups) if (g.known !== false && g.impact > 0) weights.set(g.name, (weights.get(g.name) || 0) + g.impact);
  if (!weights.size) return null;
  const total = [...weights.values()].reduce((a, b) => a + b, 0);
  const names = [...weights.entries()].sort((a, z) => z[1] - a[1]).map(([n]) => n);
  if (names.length > 1 && weights.get(names[0]) > total / 2) return `Most of what this check found involves ${names[0]}.`;
  const shown = names.slice(0, 3);
  const list = names.length === 1 ? shown[0] : names.length <= 3 ? `${shown.slice(0, -1).join(", ")} and ${shown[shown.length - 1]}` : `${shown.join(", ")} and ${names.length - 3} more`;
  return `What this check found involves ${list}.`;
}

// ---------- DPDP passages (context, not a verdict). The penalty schedule is never shown. ----------
const passageById = new Map(passages.filter((p) => p.id !== "schedule").map((p) => [p.id, p]));
export const passageFor = (ref) => passageById.get(ref) || null;
export const refLabel = (ref) => {
  const m = /^s(\d+)\((\d+)\)$/.exec(ref);
  if (m) return `DPDP s.${m[1]}(${m[2]})`;
  const r = /^r(\d+)\((\w)\)$/.exec(ref);
  return r ? `DPDP Rules r.${r[1]}(${r[2]})` : ref;
};

// ---------- legal lenses (mirror of core law/LegalLens.kt; data read from core resources/law) ----------
// The same findings read against one law: REQUIRED, CONTEXT or NOT_APPLICABLE per check, never a verdict.
export const LENS = Object.fromEntries(lensData.profiles.map((p) => [p.id, p]));
export const LENS_ORDER = lensData.profiles.map((p) => p.id);
export const LENS_DEFAULT = "GLOBAL_BASELINE";
const lensKey = (x) => String(x || "").trim().toUpperCase().replace(/[-\s]/g, "_");
/** The lens a bundle names (its top-level legalProfile), else the global baseline, as in traceveil-ci. */
export const lensOf = (bundle) => {
  const k = lensKey(bundle?.legalProfile);
  return LENS[k] ? { id: k, source: "bundle" } : { id: LENS_DEFAULT, source: k ? "unknown" : "default", named: bundle?.legalProfile || null };
};
export const lensMapping = (id, checkId) => (LENS[id]?.checks?.[checkId]) || LENS[id]?.fallback || { relation: "CONTEXT", note: "", refs: [] };
const RELATION_LABEL = { REQUIRED: "Required", CONTEXT: "Context", NOT_APPLICABLE: "Not applicable" };
export const relationLabel = (id, rel) => (LENS[id]?.kind === "GOOD_PRACTICE" && rel === "REQUIRED" ? "Good practice" : RELATION_LABEL[rel] || rel);
const lensPassageById = new Map(lensPassages.map((p) => [p.id, p]));
export const lensPassageFor = (ref) => lensPassageById.get(ref) || null;
export const PASSAGE_KIND = { LAW: "Law", RECITAL: "Recital (guides interpretation)", GUIDANCE: "Regulator guidance, not law", GOOD_PRACTICE: "Good practice, not law" };
// Same table as core LensText: India's legal sentences in rule explanations, dropped under other lenses.
const INDIA_SENTENCES = [
  [" Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), and consent must be given by a clear affirmative action before processing starts.", ""],
  [" DPDP requires a notice (s.5) and valid consent (s.6) before processing, and the Data Fiduciary must be able to prove both (s.6(10)).", ""],
  [" Consent must be free and specific; processing based on a refused consent has no lawful ground.", ""],
  [" DPDP consent needs a clear affirmative action by the user.", ""],
  [" Consent must be free, and withdrawing it must be as easy as giving it.", ""],
  [" Consent must be specific to each specified purpose.", ""],
  [", as Rule 3 requires.", "."],
  [", or complain to the Board.", ", or complain to a regulator."],
  [" DPDP requires an option to access it in English or any Eighth Schedule language.", ""],
  [" DPDP bars tracking, behavioural monitoring and targeted advertising directed at children, and requires verifiable parental consent.", ""],
];
const INDIA_MARKERS = ["DPDP", "Data Fiduciary", "Rule 3", "Eighth Schedule", "the Board"];
export const lensExplanation = (text, id) => {
  if (id === "INDIA") return { text, citesIndia: false };
  const t = INDIA_SENTENCES.reduce((acc, [from, to]) => acc.split(from).join(to), String(text || ""));
  return { text: t, citesIndia: INDIA_MARKERS.some((m) => t.includes(m)) };
};
/** Score under a lens: 100 minus severity weights (inferred half) over findings that are not NOT_APPLICABLE; V0 = inconclusive. */
export function lensScore(findings, id) {
  if (findings.some((f) => f.checkId === "V0")) return null;
  const penalty = findings.filter((f) => lensMapping(id, f.checkId).relation !== "NOT_APPLICABLE")
    .reduce((a, f) => a + sevInfo(f.severity).weight * (f.evidenceType === "INFERRED" ? 0.5 : 1), 0);
  return Math.max(0, Math.round(100 - penalty));
}

export const QUERY_TYPE = { 1: "A", 28: "AAAA", 5: "CNAME", 65: "HTTPS", 64: "SVCB", 12: "PTR", 16: "TXT", 15: "MX", 33: "SRV" };
export const PROTOCOL_LABEL = { TCP_SYN: "TCP SYN", TCP_OTHER: "TCP", UDP: "UDP", ICMP: "ICMP", OTHER: "Other" };
export const STOP_REASON = {
  TIMER: "The planned time ended",
  USER: "Stopped by the tester",
  VPN_REVOKED: "Android or another VPN turned the quarantine off",
  TUNNEL_CLOSED: "The quarantine tunnel closed unexpectedly",
  FAILED: "The run could not continue",
};
export const SETTINGS_TITLE = { ACCESSIBILITY: "Accessibility settings", DISPLAY_OVER_OTHER_APPS: "Display over other apps" };

// ---------- formatting ----------
export const fmtS = (sec) => (sec == null || !Number.isFinite(sec) ? "–" : `${(Math.round(sec * 10) / 10).toFixed(1)} s`);
export const fmtDate = (ms, withTime = true) => {
  if (!ms) return "Unknown date";
  const d = new Date(ms);
  const date = d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
  return withTime ? `${date}, ${d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}` : date;
};
export const fmtDuration = (ms) => {
  if (ms == null || !Number.isFinite(ms)) return "–";
  const s = Math.round(ms / 1000);
  return s >= 60 ? `${Math.floor(s / 60)} min ${s % 60 ? `${s % 60} s` : ""}`.trim() : `${s} s`;
};
export const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

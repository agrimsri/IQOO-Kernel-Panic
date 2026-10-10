// Builds the console's three SAMPLE bundles and checks them against the measured report they come from.
//
//   node scripts/make-samples.mjs          (or: npm run samples)
//
// Sources (all inside this repository or the team's saved reports):
//   1. LeakyShop leaky build, live audit audit-1791590915883 (vivo I2501, Android 16): hosts, plan order, lookup
//      times (0.1 s resolution), consent choices and labels, findings C2 C1 C3 C4 C10 and the 21/100 score are
//      copied from its saved Markdown report. Consent-screen times are DERIVED (choice time minus the agent's
//      2.5 s dwell, plus 1.5 s for "Manage"); plan windows are APPROXIMATE (the measured windows of the same
//      build's later undisturbed audit). Sub-0.1 s ordering inside one report row is arbitrary.
//   2. LeakyShop Fixed build, live audit audit-1791591219563: 100/100, zero findings, one lookup per plan, real
//      Reject and Accept (docs/VERIFICATION.md). The lookup host is the app's own API (leakyshop/Shop.kt); its
//      exact time was not in the saved summary and is set to 0.1 s like the leaky build's launch lookup.
//   3. TEST Challan quarantine: ILLUSTRATIVE, not a recorded phone run. Built from what the riskdemo fixture is
//      coded to do (riskdemo/.../DemoSignals.kt: three reserved names and one TEST-NET-3 connection) plus an SMS
//      permission dialog, shaped exactly like core QuarantineReport (summary and timeline computed the same way).
import { writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "..", "src", "data", "samples");
mkdirSync(out, { recursive: true });

const S = 1_000_000_000;
const ns = (sec) => Math.round(sec * S);
const PLAN_LABEL = { ACCEPT: "Plan A · Accept", REJECT: "Plan B · Reject", SILENT: "Plan C · No interaction" };

// ---------------------------------------------------------------- LeakyShop (leaky build)
const leakyTree = {
  className: "android.widget.FrameLayout", text: null, contentDescription: null, isCheckable: false, isChecked: false, isClickable: false, bounds: null,
  children: [
    node("android.widget.TextView", "We value your privacy"),
    node("android.widget.TextView", "We use your data to personalise offers, measure the app and show relevant ads."),
    node("android.widget.CheckBox", "Share usage data to improve offers", { isCheckable: true, isChecked: true, isClickable: true }),
    node("android.widget.TextView", "Manage", { isClickable: true }),
    node("android.widget.Button", "Accept & Continue", { isClickable: true }),
  ],
};
const fixedTree = {
  className: "android.widget.FrameLayout", text: null, contentDescription: null, isCheckable: false, isChecked: false, isClickable: false, bounds: null,
  children: [
    node("android.widget.TextView", "Your privacy choices"),
    node("android.widget.TextView", "We collect your email, phone number and order history to deliver orders. The choices below are optional."),
    node("android.widget.TextView", "Analytics to improve the app"),
    node("android.widget.Switch", null, { isCheckable: true, isChecked: false, isClickable: true }),
    node("android.widget.TextView", "Personalised offers"),
    node("android.widget.Switch", null, { isCheckable: true, isChecked: false, isClickable: true }),
    node("android.widget.TextView", "You can withdraw consent anytime in Settings › Privacy, or complain to the Data Protection Board."),
    node("android.widget.TextView", "English", { isClickable: true }),
    node("android.widget.TextView", "हिन्दी", { isClickable: true }),
    node("android.widget.TextView", "ಕನ್ನಡ"),
    node("android.widget.Button", "Reject", { isClickable: true }),
    node("android.widget.Button", "Accept", { isClickable: true }),
  ],
};
function node(className, text, extra = {}) {
  return { className, text, contentDescription: null, isCheckable: false, isChecked: false, isClickable: false, bounds: null, children: [], ...extra };
}

// Device-shaped screen review: only the accessibility-tree fields are known (as in DemoScoreRegressionTest).
const screenAudit = (preTicked, rejectOption) => ({
  isConsentScreen: true, preTicked, rejectOption, purposesCount: null, separateChoices: null, itemisedData: null, purposeStated: null,
  withdrawalInfo: null, languageOptions: null, childAudienceSignals: null, darkPatterns: [], summary: "", confidence: 1.0,
  source: "DETERMINISTIC", observedFields: ["preTicked", "rejectOption"], conflicts: [], modelId: null, latencyMs: null,
});

function rawEvent(base, t, host) {
  // Exactly what the capture records: name + type + time. Entity, category and phase are assigned later
  // (by the rules engine on the phone, and by the console here), so they carry their defaults.
  return { tNanos: base + ns(t), hostname: host, queryType: 1, entity: null, category: "UNKNOWN", phase: "NO_CONSENT_SEEN", viaPackage: null, cnameChain: [], answerIps: [], disguisedVia: null, answerStatus: null, rcode: null };
}

function session({ auditId, pkg, label, plan, base, epoch, window, lookups, consent, tree, screen, healthNote }) {
  const netEvents = lookups.map(([t, h]) => rawEvent(base, t, h));
  const consentEvents = consent.map(([t, kind, text]) => ({ tNanos: base + ns(t), kind, nodeText: text ?? null, screenshotPath: null, tree: kind === "SCREEN_APPEARED" ? tree : null }));
  const ts = netEvents.map((e) => e.tNanos);
  return {
    id: `${auditId}-${plan}`, targetPackage: pkg, targetLabel: label, plan, childFacing: false,
    startedAtNanos: base, endedAtNanos: base + ns(window), startedAtEpochMs: epoch,
    netEvents, consentEvents, screenAudit: screen,
    deviceModel: "vivo I2501", androidVersion: "16", privateDnsActive: false, notes: [],
    captureHealth: {
      schemaVersion: 1, captureStartedNanos: base, captureEndedNanos: base + ns(window),
      dnsQueriesParsed: netEvents.length, malformedPackets: null, dnsResponsesRelayed: null, dnsRelayFailures: null, malformedResponses: null,
      eventsRecorded: netEvents.length, eventsDropped: null, eventBufferCapacity: null,
      firstLookupNanos: ts.length ? Math.min(...ts) : null, lastLookupNanos: ts.length ? Math.max(...ts) : null,
      dnsUpstreamPayloadBytesSent: null, dnsUpstreamDatagramsSent: null, dnsUpstreamPayloadBytesReceived: null, dnsUpstreamDatagramsReceived: null,
      notes: [healthNote], hiddenDnsPackets: null, nonDnsPackets: null,
    },
    freshState: null,
  };
}

const HEALTH_NOTE = "Console sample: only counters derivable from the saved report are filled in; the others are null (not recorded in this sample).";
const FA = "app-measurement.com", CR = "firebase-settings.crashlytics.com", FB = "graph.facebook.com", API = "api.leakyshop.example", AF = "launches.appsflyersdk.com";
const ADS = "googleads.g.doubleclick.net", CT = "in1.clevertap-prod.com";

const LEAKY_ID = "audit-1791590915883";
const leakyBaseC = 52_734_118_000_000; // elapsedRealtimeNanos at the start of plan C (any boot-relative value works)
const leakySessions = [
  session({
    auditId: LEAKY_ID, pkg: "com.leakyshop.demo", label: "LeakyShop", plan: "SILENT", base: leakyBaseC, epoch: 1791590847600, window: 25.835,
    lookups: [[0.18, FA], [0.19, CR], [0.20, FB], [0.21, API], [0.22, AF]],
    consent: [[0.92, "SCREEN_APPEARED", "We value your privacy"]], tree: leakyTree, screen: screenAudit(true, "HIDDEN"), healthNote: HEALTH_NOTE,
  }),
  session({
    auditId: LEAKY_ID, pkg: "com.leakyshop.demo", label: "LeakyShop", plan: "REJECT", base: leakyBaseC + ns(27.3), epoch: 1791590874900, window: 19.887,
    lookups: [[0.06, CR], [0.065, API], [0.07, AF], [0.08, FB], [0.09, FA], [4.86, FA], [4.87, FB], [4.88, AF]],
    consent: [[0.82, "SCREEN_APPEARED", "We value your privacy"], [3.32, "SETTINGS", "Manage"], [4.848, "REJECT", "Reject all"]],
    tree: leakyTree, screen: screenAudit(true, "HIDDEN"), healthNote: HEALTH_NOTE,
  }),
  session({
    auditId: LEAKY_ID, pkg: "com.leakyshop.demo", label: "LeakyShop", plan: "ACCEPT", base: leakyBaseC + ns(48.7), epoch: 1791590896300, window: 18.359,
    lookups: [[0.07, API], [0.08, FB], [0.09, CR], [0.10, AF], [0.11, FA], [3.43, CT], [3.44, ADS]],
    consent: [[0.92, "SCREEN_APPEARED", "We value your privacy"], [3.42, "ACCEPT", "Accept & Continue"]],
    tree: leakyTree, screen: screenAudit(true, "HIDDEN"), healthNote: HEALTH_NOTE,
  }),
];

const ENT3 = ["Google Analytics for Firebase", "Meta (Facebook SDK)", "AppsFlyer"];
const ALL = ["SILENT", "REJECT", "ACCEPT"];
const preConsentEvidence = [
  "app-measurement.com (Google Analytics for Firebase) — 0.2 s after launch, the user had not chosen yet [Plan C · No interaction]",
  "graph.facebook.com (Meta (Facebook SDK)) — 0.2 s after launch, the user had not chosen yet [Plan C · No interaction]",
  "launches.appsflyersdk.com (AppsFlyer) — 0.2 s after launch, the user had not chosen yet [Plan C · No interaction]",
  "launches.appsflyersdk.com (AppsFlyer) — 0.1 s after launch, 4.8 s before the consent choice [Plan B · Reject]",
  "graph.facebook.com (Meta (Facebook SDK)) — 0.1 s after launch, 4.8 s before the consent choice [Plan B · Reject]",
  "app-measurement.com (Google Analytics for Firebase) — 0.1 s after launch, 4.8 s before the consent choice [Plan B · Reject]",
  "graph.facebook.com (Meta (Facebook SDK)) — 0.1 s after launch, 3.3 s before the consent choice [Plan A · Accept]",
  "launches.appsflyersdk.com (AppsFlyer) — 0.1 s after launch, 3.3 s before the consent choice [Plan A · Accept]",
  "app-measurement.com (Google Analytics for Firebase) — 0.1 s after launch, 3.3 s before the consent choice [Plan A · Accept]",
];
const leakyFindings = [
  {
    checkId: "C2", severity: "CRITICAL", evidenceType: "OBSERVED", title: "Trackers contacted after the user refused",
    explanation: "The user refused consent, but the app still contacted Google Analytics for Firebase, Meta (Facebook SDK), AppsFlyer. Consent must be free and specific; processing based on a refused consent has no lawful ground.",
    evidence: [
      "app-measurement.com (Google Analytics for Firebase) — 4.9 s after launch, 0.0 s after the user chose reject [Plan B · Reject]",
      "graph.facebook.com (Meta (Facebook SDK)) — 4.9 s after launch, 0.0 s after the user chose reject [Plan B · Reject]",
      "launches.appsflyersdk.com (AppsFlyer) — 4.9 s after launch, 0.0 s after the user chose reject [Plan B · Reject]",
    ],
    plans: ["REJECT"], dpdpRefs: ["s4(1)", "s6(1)"], penaltyBand: "RESIDUAL_50", confidence: 1.0, entities: ENT3,
  },
  {
    checkId: "C1", severity: "HIGH", evidenceType: "OBSERVED", title: "Trackers contacted before consent",
    explanation: "The app contacted Google Analytics for Firebase, Meta (Facebook SDK), AppsFlyer before the user made any consent choice. Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), and consent must be given by a clear affirmative action before processing starts.",
    evidence: preConsentEvidence, plans: ALL, dpdpRefs: ["s4(1)", "s6(1)"], penaltyBand: "RESIDUAL_50", confidence: 1.0, entities: ENT3,
  },
  {
    checkId: "C3", severity: "HIGH", evidenceType: "OBSERVED", title: "Pre-ticked consent option",
    explanation: "A consent option is already switched on before the user acts. DPDP consent needs a clear affirmative action by the user.",
    evidence: ["Consent screen shows a pre-ticked option."], plans: ALL, dpdpRefs: ["s6(1)"], penaltyBand: "RESIDUAL_50", confidence: 1.0, entities: [],
  },
  {
    checkId: "C4", severity: "HIGH", evidenceType: "OBSERVED", title: "No clear way to refuse",
    explanation: "Refusing is harder than agreeing (hidden behind another screen). Consent must be free, and withdrawing it must be as easy as giving it.",
    evidence: ["Reject option: hidden."], plans: ALL, dpdpRefs: ["s6(1)", "s6(4)"], penaltyBand: "RESIDUAL_50", confidence: 1.0, entities: [],
  },
  {
    checkId: "C10", severity: "MEDIUM", evidenceType: "INFERRED", title: "Device identifiers likely shared before consent",
    explanation: "Per vendor documentation, Google Analytics for Firebase, Meta (Facebook SDK), AppsFlyer read a device or advertising identifier when they initialise. They were contacted before consent, so identifiers were likely shared without consent. (Inferred from SDK behaviour; payloads were not inspected.)",
    evidence: preConsentEvidence, plans: ALL, dpdpRefs: ["s6(1)"], penaltyBand: "RESIDUAL_50", confidence: 0.7, entities: ENT3,
  },
];

const WEIGHT = { CRITICAL: 30, HIGH: 15, MEDIUM: 8, LOW: 3, INFO: 0 };
function score(findings) {
  if (findings.some((f) => f.checkId === "V0")) return [null, "INCONCLUSIVE"];
  const penalty = findings.reduce((a, f) => a + WEIGHT[f.severity] * (f.evidenceType === "INFERRED" ? 0.5 : 1), 0);
  const s = Math.max(0, Math.round(100 - penalty));
  return [s, s >= 85 ? "READY" : s >= 60 ? "NEEDS_WORK" : "AT_RISK"];
}

function auditBundle({ id, createdAtMs, pkg, label, sessions, findings, note, sources, exportedAt }) {
  const [sc, band] = score(findings);
  return {
    schema: "traceveil.audit.v1",
    exportedAt,
    app: { package: pkg, label },
    meta: {
      id, targetPackage: pkg, targetLabel: label, createdAtMs, score: sc, band, findingCount: findings.length,
      plans: sessions.map((s) => PLAN_LABEL[s.plan]), sample: true, timingProfile: null,
    },
    sessions,
    result: { findings, score: sc, band },
    networkReadings: [],
    tool: { name: "TraceVeil Console sample", version: "1" },
    sample: { kind: "reconstructed", note, sources },
  };
}

const leaky = auditBundle({
  id: LEAKY_ID, createdAtMs: 1791590915883, pkg: "com.leakyshop.demo", label: "LeakyShop", sessions: leakySessions, findings: leakyFindings,
  exportedAt: "2026-10-10T00:08:40Z",
  note: "Sample reconstructed from the saved report of a live audit of the LeakyShop demo app (vivo I2501, Android 16). Hosts, plan order, lookup times (0.1 s resolution), consent choices, findings and the 21/100 score are as measured. Consent-screen times are derived from the agent's dwell; plan windows and capture counters are approximate. Upload byte readings are not included.",
  sources: ["Saved report live-leaky-1791590915883 (Markdown)", "docs/VERIFICATION.md (audit-1791590915883)"],
});

// ---------------------------------------------------------------- LeakyShop Fixed
const FIXED_ID = "audit-1791591219563";
const fixedBaseC = leakyBaseC + ns(303.7);
const fixedSessions = [
  session({ auditId: FIXED_ID, pkg: "com.leakyshop.demo.fixed", label: "LeakyShop Fixed", plan: "SILENT", base: fixedBaseC, epoch: 1791591151300, window: 25.838,
    lookups: [[0.1, API]], consent: [[0.92, "SCREEN_APPEARED", "Your privacy choices"]], tree: fixedTree, screen: screenAudit(false, "VISIBLE"), healthNote: HEALTH_NOTE }),
  session({ auditId: FIXED_ID, pkg: "com.leakyshop.demo.fixed", label: "LeakyShop Fixed", plan: "REJECT", base: fixedBaseC + ns(27.3), epoch: 1791591178600, window: 18.369,
    lookups: [[0.1, API]], consent: [[0.82, "SCREEN_APPEARED", "Your privacy choices"], [3.32, "REJECT", "Reject"]], tree: fixedTree, screen: screenAudit(false, "VISIBLE"), healthNote: HEALTH_NOTE }),
  session({ auditId: FIXED_ID, pkg: "com.leakyshop.demo.fixed", label: "LeakyShop Fixed", plan: "ACCEPT", base: fixedBaseC + ns(47.2), epoch: 1791591198500, window: 18.361,
    lookups: [[0.1, API]], consent: [[0.92, "SCREEN_APPEARED", "Your privacy choices"], [3.42, "ACCEPT", "Accept"]], tree: fixedTree, screen: screenAudit(false, "VISIBLE"), healthNote: HEALTH_NOTE }),
];
const fixed = auditBundle({
  id: FIXED_ID, createdAtMs: 1791591219563, pkg: "com.leakyshop.demo.fixed", label: "LeakyShop Fixed", sessions: fixedSessions, findings: [],
  exportedAt: "2026-10-10T00:13:45Z",
  note: "Sample reconstructed from a live audit of the LeakyShop fixed build (vivo I2501, Android 16): 100/100, zero findings, one lookup per plan, real Reject and Accept. The lookup time and consent-screen times are set like the leaky build's (not in the saved summary); plan windows and capture counters are approximate. 100 means no finding in the captured evidence, not a certificate.",
  sources: ["docs/VERIFICATION.md (audit-1791591219563)", "leakyshop/src/main/java/com/leakyshop/demo/Shop.kt"],
});

// ---------------------------------------------------------------- TEST Challan quarantine (illustrative)
const Q_START = 61_244_903_000_000;
const RESOLVERS = ["10.111.0.53", "fd00:111::53"];
const qt = (sec) => Q_START + ns(sec);
const domains = [
  { hostname: "c2.traceveil-demo.invalid", queryTypes: [1, 28], resolvers: ["10.111.0.53"], firstNanos: qt(1.62), lastNanos: qt(1.63), count: 2 },
  { hostname: "pay-challan-update.invalid", queryTypes: [1, 28], resolvers: ["10.111.0.53"], firstNanos: qt(1.66), lastNanos: qt(1.67), count: 2 },
  { hostname: "otp-relay.example", queryTypes: [1, 28], resolvers: ["10.111.0.53"], firstNanos: qt(1.70), lastNanos: qt(1.71), count: 2 },
];
const blocked = [
  { protocol: "TCP_SYN", protocolNumber: 6, ipVersion: 4, address: "203.0.113.10", port: 443, scope: "PUBLIC", role: "APP_DESTINATION", firstNanos: qt(1.74), lastNanos: qt(2.76), packets: 2 },
];
const dialogs = [
  { packageName: "com.google.android.permissioncontroller", text: "Allow TEST Challan (TraceVeil demo) to send and view SMS messages?", namesTarget: true, firstNanos: qt(14.2), lastNanos: qt(14.2), timesShown: 1 },
];
const observations = {
  domains, blocked, permissionDialogs: dialogs, settingsScreens: [], targetOnScreenNanos: qt(1.21),
  dnsQueries: 6, blockedPackets: 2, networkControlPackets: 4, unreadablePackets: 0, domainsOverflow: 0, destinationsOverflow: 0, uiEventsOverflow: 0,
};
// Same as core QuarantineSummary.of (no DNS answers in quarantine: every lookup is answered "does not exist").
const appRows = blocked.filter((b) => b.role === "APP_DESTINATION");
const endpoint = (b) => (b.port == null ? b.address : b.ipVersion === 6 ? `[${b.address}]:${b.port}` : `${b.address}:${b.port}`);
const PROTO = { TCP_SYN: "TCP SYN", TCP_OTHER: "TCP", UDP: "UDP", ICMP: "ICMP", OTHER: "Other" };
const summary = {
  domainsAttempted: domains.map((d) => d.hostname),
  ipsAttempted: [...new Set(appRows.map((b) => b.address))],
  uniqueDomains: domains.length,
  uniqueIps: new Set(appRows.map((b) => b.address)).size,
  uniqueDestinations: new Set(appRows.map((b) => `${b.address}|${b.port}`)).size,
  dnsQueries: observations.dnsQueries,
  blockedPackets: observations.blockedPackets,
  addressesWithoutLookup: [...new Set(appRows.filter((b) => ["PUBLIC", "PRIVATE"].includes(b.scope) && b.port !== 53 && b.port !== 853).map((b) => b.address))],
  dnsBypassSignal: true,
  directResolvers: [],
  encryptedDnsDestinations: [],
  phoneResolverDnsAttempts: [],
  permissionDialogs: dialogs.length,
  settingsScreens: [],
  appCameToScreen: true,
  nothingObserved: false,
};
const at = (t) => Math.max(0, Math.floor((t - Q_START) / 1e6));
const times = (n, unit) => (n > 1 ? ` · ${n} ${unit}` : "");
const timeline = [
  [observations.targetOnScreenNanos, { atMs: at(observations.targetOnScreenNanos), kind: "APP_ON_SCREEN", text: "The app's window appeared", evidence: "OBSERVED" }],
  ...domains.map((d) => [d.firstNanos, { atMs: at(d.firstNanos), kind: "LOOKUP", text: `Tried to look up ${d.hostname} (answered: does not exist)${times(d.count, "lookups")}`, evidence: "OBSERVED" }]),
  ...blocked.map((b) => [b.firstNanos, { atMs: at(b.firstNanos), kind: "BLOCKED", text: `Blocked ${PROTO[b.protocol]} to ${endpoint(b)}${times(b.packets, "packets")}`, evidence: "OBSERVED" }]),
  ...dialogs.map((p) => [p.firstNanos, { atMs: at(p.firstNanos), kind: "PERMISSION_DIALOG", text: `Permission dialog: "${p.text}"${times(p.timesShown, "times")}`, evidence: "OBSERVED" }]),
].sort((a, b) => a[0] - b[0]).map((x) => x[1]);
const LIMITS = [
  "Not a malware verdict and not a full sandbox: it records what this app tried to reach during this run only.",
  "Only this app's own internet is cut off. SMS, phone calls, and requests it hands to other apps or system services (for example Android's download manager or push messaging) are neither blocked nor seen.",
  "After quarantine ends the app has its normal internet again and may then send what it could not send now.",
  "Apps that wait longer than this run, need a login, or wait for a remote command may show little or nothing.",
  "Encrypted DNS inside the app (DNS over HTTPS) shows only as blocked connections to an address, not as names.",
  "Every lookup is answered \"does not exist\", so the app may give up early or try other addresses. Attempts are not proof that any data would have been sent.",
  "If the app was already open before the run, it may reuse addresses or connections from before.",
  "Packet counts include Android's automatic retries; they are not separate decisions by the app.",
  "Permission dialogs and settings screens are recognised from Android's own screens and English titles; other languages or some phone brands' screens may be missed. A settings screen opening does not show whether the app or you opened it.",
];
const quarantine = {
  schema: "traceveil.quarantine.v1",
  exportedAt: "2026-10-10T00:52:10Z",
  report: {
    schemaVersion: 1, id: "quarantine-1791593460000", targetPackage: "com.traceveil.riskdemo", targetLabel: "TEST Challan (TraceVeil demo)",
    startedAtEpochMs: 1791593460000, startedAtNanos: Q_START, endedAtNanos: Q_START + 60_000_000_000, plannedDurationMs: 60_000,
    stopReason: "TIMER", resolverAddresses: RESOLVERS, observations, summary, timeline, limits: LIMITS, notes: [],
    deviceModel: null, androidVersion: null, privateDnsActive: false,
  },
  tool: { name: "TraceVeil Console sample", version: "1" },
  sample: {
    kind: "illustrative",
    note: "Illustrative sample, not a recorded phone run. It shows what a quarantine run of the TEST Challan fixture is expected to record: the app is coded to look up three reserved names and connect to a documentation-only address (203.0.113.10:443), and its button opens Android's SMS permission dialog. Nothing in the fixture can reach a real server.",
    sources: ["riskdemo/src/main/java/com/traceveil/riskdemo/DemoSignals.kt", "docs/DEMO_RISK_APP.md", "core QuarantineReport / QuarantineSummary / QuarantineTimeline"],
  },
};

// ---------------------------------------------------------------- checks against the measured report
const assert = (cond, msg) => { if (!cond) { console.error("FAIL:", msg); process.exit(1); } };
const f1 = (x) => (Math.round(x * 10) / 10).toFixed(1);
assert(leaky.result.score === 21 && leaky.result.band === "AT_RISK", `leaky score ${leaky.result.score}`);
assert(fixed.result.score === 100 && fixed.result.band === "READY", `fixed score ${fixed.result.score}`);
assert(leakySessions.map((s) => s.netEvents.length).join("/") === "5/8/7", "leaky lookups per plan must be 5/8/7");
assert(fixedSessions.every((s) => s.netEvents.length === 1), "fixed: one lookup per plan");
// Report rows: every lookup time rounds to the report's 0.1 s value, and choice times match.
const reportRows = {
  SILENT: [["0.2", FA], ["0.2", CR], ["0.2", FB], ["0.2", API], ["0.2", AF]],
  REJECT: [["0.1", CR], ["0.1", API], ["0.1", AF], ["0.1", FB], ["0.1", FA], ["4.9", FA], ["4.9", FB], ["4.9", AF]],
  ACCEPT: [["0.1", API], ["0.1", FB], ["0.1", CR], ["0.1", AF], ["0.1", FA], ["3.4", CT], ["3.4", ADS]],
};
for (const s of leakySessions) {
  const got = s.netEvents.map((e) => [f1((e.tNanos - s.startedAtNanos) / S), e.hostname]);
  assert(JSON.stringify(got) === JSON.stringify(reportRows[s.plan]), `${s.plan} rows differ: ${JSON.stringify(got)}`);
}
const choice = (s) => s.consentEvents.find((c) => c.kind === "ACCEPT" || c.kind === "REJECT");
assert(f1((choice(leakySessions[1]).tNanos - leakySessions[1].startedAtNanos) / S) === "4.8", "reject at 4.8 s");
assert(f1((choice(leakySessions[2]).tNanos - leakySessions[2].startedAtNanos) / S) === "3.4", "accept at 3.4 s");
// Evidence lines: "x s after launch, y s before/after the choice" must follow from the event times.
for (const f of leakyFindings) for (const line of f.evidence) {
  const m = line.match(/^(\S+) .*— ([\d.]+) s after launch, (?:([\d.]+) s (before the consent choice|after the user chose \w+)|the user had not chosen yet) \[(.+)\]$/);
  if (!m) continue;
  const plan = Object.keys(PLAN_LABEL).find((k) => PLAN_LABEL[k] === m[5]);
  const s = leakySessions.find((x) => x.plan === plan);
  const ev = s.netEvents.filter((e) => e.hostname === m[1]).find((e) => f1((e.tNanos - s.startedAtNanos) / S) === m[2]);
  assert(ev, `no event for evidence: ${line}`);
  if (m[3]) {
    const c = choice(s);
    const d = m[4].startsWith("before") ? (c.tNanos - ev.tNanos) / S : (ev.tNanos - c.tNanos) / S;
    assert(f1(d) === m[3], `relative time differs for: ${line} (${f1(d)})`);
  }
}

const write = (name, obj) => writeFileSync(join(out, name), JSON.stringify(obj, null, 2) + "\n", "utf8");
write("leakyshop-leaky.audit.json", leaky);
write("leakyshop-fixed.audit.json", fixed);
write("test-challan.quarantine.json", quarantine);
console.log("Samples written to src/data/samples and checked: leaky 21/AT_RISK (5/8/7 lookups), fixed 100/READY, quarantine with 3 names + 1 address + 1 dialog.");

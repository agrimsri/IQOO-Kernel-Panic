// Turns stored export bundles into what the pages show. Pure functions, memoised per record.
// Phases follow core PhaseAssigner; host classification follows core TrackerCatalog (see model.js).
import { planInfo, sevInfo, classifyHost, TRACKING, CHECKS, QUERY_TYPE, PROTOCOL_LABEL, companyOfEntity, lensOf } from "./model";

const S = 1e9;
const KEPT_DATA_NOTES = ["App data was not cleared", "Could not clear app data"];
const memo = new WeakMap();
const cached = (rec, fn) => { if (!memo.has(rec)) memo.set(rec, fn(rec)); return memo.get(rec); };

// ---------------------------------------------------------------- audits
export const auditView = (rec) => cached(rec, buildAuditView);

function buildAuditView(rec) {
  const b = rec.bundle;
  const sessions = [...(b.sessions || [])]
    .sort((x, y) => planInfo(x.plan).order - planInfo(y.plan).order || (x.startedAtEpochMs || 0) - (y.startedAtEpochMs || 0))
    .map(sessionView);
  const findings = [...(b.result.findings || [])].map((f) => ({
    ...f,
    title: f.title || CHECKS[f.checkId] || f.checkId,
    evidence: Array.isArray(f.evidence) ? f.evidence : [],
    plans: Array.isArray(f.plans) ? f.plans : [],
    dpdpRefs: Array.isArray(f.dpdpRefs) ? f.dpdpRefs : [],
    entities: Array.isArray(f.entities) ? f.entities : [],
    confidence: typeof f.confidence === "number" ? f.confidence : 1,
  })).sort((a, z) => sevInfo(a.severity).rank - sevInfo(z.severity).rank);
  const first = sessions[0] || {};
  const createdAtMs = b.meta?.createdAtMs || Math.max(...sessions.map((s) => s.epochMs || 0)) || (b.exportedAt ? Date.parse(b.exportedAt) : 0);

  // hosts contacted across all plans
  const hostMap = new Map();
  for (const s of sessions) for (const e of s.events) {
    const h = hostMap.get(e.host) || { host: e.host, entity: e.entity, company: e.company, category: e.category, tracking: e.tracking, count: 0, plans: new Set(), phases: {}, firstT: Infinity };
    h.count++; h.plans.add(s.plan); h.phases[e.phase] = (h.phases[e.phase] || 0) + 1; h.firstT = Math.min(h.firstT, e.t);
    hostMap.set(e.host, h);
  }
  const hosts = [...hostMap.values()].sort((a, z) => Number(z.tracking) - Number(a.tracking) || a.firstT - z.firstT);
  const gaps = sessions.filter((s) => !s.completed).map((s) => `${s.planLabel}: the planned ${s.expects === "ACCEPT" ? "Accept" : "Reject"} was not recorded.`);
  const sevCounts = {};
  for (const f of findings) sevCounts[f.severity] = (sevCounts[f.severity] || 0) + 1;

  return {
    id: rec.id, rec,
    pkg: b.app?.package || b.meta?.targetPackage || first.pkg,
    label: b.app?.label || b.meta?.targetLabel || first.label || b.app?.package,
    createdAtMs,
    score: typeof b.result.score === "number" ? b.result.score : null,
    band: b.result.band || (b.result.score == null ? "INCONCLUSIVE" : null),
    findings, sevCounts,
    sessions, hosts,
    lookups: sessions.reduce((a, s) => a + s.events.length, 0),
    trackerLookups: sessions.reduce((a, s) => a + s.events.filter((e) => e.tracking).length, 0),
    device: first.device || null, android: first.android || null,
    privateDns: first.privateDns,
    timingProfile: b.meta?.timingProfile ?? null,
    sample: rec.sample, sampleInfo: b.sample || null,
    tool: b.tool || null, exportedAt: b.exportedAt || null,
    networkReadings: Array.isArray(b.networkReadings) ? b.networkReadings : [],
    gaps,
    plansKey: sessions.map((s) => s.plan).sort().join(","),
    lens: lensOf(b),
  };
}

function sessionView(s) {
  const start = Number(s.startedAtNanos) || 0;
  const rel = (t) => Math.max(0, (Number(t) - start) / S);
  const consent = [...(s.consentEvents || [])].sort((a, z) => a.tNanos - z.tNanos).map((c) => ({ ...c, t: rel(c.tNanos) }));
  const choice = consent.find((c) => c.kind === "ACCEPT" || c.kind === "REJECT") || null;
  const screenSeen = consent.some((c) => c.kind === "SCREEN_APPEARED" || c.kind === "MANUAL_MARK") || !!choice;
  const events = [...(s.netEvents || [])].sort((a, z) => a.tNanos - z.tNanos).map((e) => {
    const t = rel(e.tNanos);
    const phase = choice && e.tNanos < choice.tNanos ? "BEFORE_CONSENT"
      : choice ? (choice.kind === "ACCEPT" ? "AFTER_ACCEPT" : "AFTER_REJECT")
      : screenSeen ? "BEFORE_CONSENT" : "NO_CONSENT_SEEN";
    const c = classifyHost(e.hostname, e.cnameChain, e.entity, e.category);
    const relation = !choice ? (phase === "NO_CONSENT_SEEN" ? "no consent screen was seen" : "the user had not chosen yet")
      : e.tNanos < choice.tNanos ? `${((choice.tNanos - e.tNanos) / S).toFixed(1)} s before the consent choice`
      : `${((e.tNanos - choice.tNanos) / S).toFixed(1)} s after the user chose ${choice.kind.toLowerCase()}`;
    return {
      t, host: e.hostname, queryType: QUERY_TYPE[e.queryType] || (e.queryType != null ? `type ${e.queryType}` : "–"),
      entity: c.entity, company: c.company, category: c.category, tracking: TRACKING.has(c.category), via: c.via || e.disguisedVia || null,
      viaPackage: e.viaPackage || null, answerIps: e.answerIps || [], phase, relation,
    };
  });
  const p = planInfo(s.plan);
  const ended = Number(s.endedAtNanos) > start ? rel(s.endedAtNanos) : null;
  const kept = s.freshState === false || (s.freshState == null && (s.notes || []).some((n) => KEPT_DATA_NOTES.some((k) => n.toLowerCase().includes(k.toLowerCase()))));
  const completed = p.expects ? choice?.kind === p.expects : true;
  return {
    id: s.id, plan: s.plan, planLabel: p.label, code: p.code, short: p.short, expects: p.expects,
    pkg: s.targetPackage, label: s.targetLabel, epochMs: s.startedAtEpochMs || 0,
    duration: Math.max(ended ?? 0, ...events.map((e) => e.t), ...consent.map((c) => c.t), 1),
    windowS: ended,
    events, consent, choice, screenSeen, completed,
    unexpectedChoice: !p.expects && !!choice,
    freshState: s.freshState ?? null, appDataKept: kept,
    health: s.captureHealth || null,
    device: s.deviceModel || null, android: s.androidVersion || null, privateDns: s.privateDnsActive ?? null,
    notes: s.notes || [], screenAudit: s.screenAudit || null,
    screenTree: consent.find((c) => c.tree)?.tree || null,
  };
}

/** Flattens an accessibility tree into visible rows (text, checkbox/switch state, clickable). */
export function flattenTree(node, out = []) {
  if (!node) return out;
  const label = [node.text, node.contentDescription].filter(Boolean).join(" ").trim();
  if (label || node.isCheckable) out.push({ label, checkable: !!node.isCheckable, checked: !!node.isChecked, clickable: !!node.isClickable, cls: (node.className || "").split(".").pop() });
  for (const c of node.children || []) flattenTree(c, out);
  return out;
}

// ---------------------------------------------------------------- quarantine
export const quarantineView = (rec) => cached(rec, buildQuarantineView);

function buildQuarantineView(rec) {
  const r = rec.bundle.report;
  const o = r.observations || {};
  const start = Number(r.startedAtNanos) || 0;
  const ms = (t) => Math.max(0, Math.round((Number(t) - start) / 1e6));
  const resolvers = new Set(r.resolverAddresses || []);
  const summary = r.summary || computeSummary(o, resolvers);
  const hardcoded = new Set(summary.addressesWithoutLookup || []);
  const endpoint = (b) => (b.port == null ? b.address : b.ipVersion === 6 ? `[${b.address}]:${b.port}` : `${b.address}:${b.port}`);
  const domains = (o.domains || []).map((d) => ({
    host: d.hostname, count: d.count, firstMs: ms(d.firstNanos), lastMs: ms(d.lastNanos),
    types: (d.queryTypes || []).map((q) => QUERY_TYPE[q] || `type ${q}`),
    resolvers: (d.resolvers || []).map((x) => ({ addr: x, own: !resolvers.has(x) })),
  })).sort((a, z) => a.firstMs - z.firstMs);
  const blocked = (o.blocked || []).map((b) => ({
    endpoint: endpoint(b), address: b.address, port: b.port, protocol: PROTOCOL_LABEL[b.protocol] || b.protocol, packets: b.packets,
    scope: b.scope, role: b.role, firstMs: ms(b.firstNanos), lastMs: ms(b.lastNanos),
    hardcoded: b.role === "APP_DESTINATION" && hardcoded.has(b.address) && b.port !== 53 && b.port !== 853,
    dns: b.port === 53 || b.port === 853,
  })).sort((a, z) => a.firstMs - z.firstMs);
  const dialogs = (o.permissionDialogs || []).map((p) => ({ ...p, firstMs: ms(p.firstNanos) }));
  const settings = (o.settingsScreens || []).map((s) => ({ ...s, firstMs: ms(s.firstNanos) }));
  const timeline = (r.timeline && r.timeline.length ? r.timeline : buildTimeline(o, start, endpoint)).map((t) => ({ ...t }));
  const observedMs = Number(r.endedAtNanos) > start ? (Number(r.endedAtNanos) - start) / 1e6 : null;
  return {
    id: rec.id, rec, pkg: r.targetPackage, label: r.targetLabel || r.targetPackage, startedAtMs: r.startedAtEpochMs || 0,
    observedMs, plannedMs: r.plannedDurationMs ?? null, stopReason: r.stopReason, summary, domains, blocked, dialogs, settings, timeline,
    limits: r.limits || [], notes: r.notes || [], device: r.deviceModel || null, android: r.androidVersion || null, privateDns: r.privateDnsActive ?? null,
    appOnScreenMs: o.targetOnScreenNanos != null ? ms(o.targetOnScreenNanos) : null,
    overflow: (o.domainsOverflow || 0) + (o.destinationsOverflow || 0) + (o.uiEventsOverflow || 0),
    unreadable: o.unreadablePackets || 0, controlPackets: o.networkControlPackets || 0,
    sample: rec.sample, sampleInfo: rec.bundle.sample || null, raw: !!rec.bundle.rawReport,
  };
}

// Same rules as core QuarantineSummary.of, used only when an export carries no summary.
function computeSummary(o, resolvers) {
  const rows = (o.blocked || []).filter((b) => b.role === "APP_DESTINATION");
  const server = (b) => b.scope === "PUBLIC" || b.scope === "PRIVATE";
  const dnsT = (b) => b.port === 53 || b.port === 853;
  const label = (b) => `${b.address}:${b.port} (${PROTOCOL_LABEL[b.protocol] || b.protocol})`;
  const without = [...new Set(rows.filter((b) => server(b) && !dnsT(b)).map((b) => b.address))];
  return {
    domainsAttempted: (o.domains || []).map((d) => d.hostname),
    ipsAttempted: [...new Set(rows.map((b) => b.address))],
    uniqueDomains: (o.domains || []).length,
    uniqueIps: new Set(rows.map((b) => b.address)).size,
    uniqueDestinations: new Set(rows.map((b) => `${b.address}|${b.port}`)).size,
    dnsQueries: o.dnsQueries || 0, blockedPackets: o.blockedPackets || 0,
    addressesWithoutLookup: without, dnsBypassSignal: without.length > 0,
    directResolvers: [...new Set((o.domains || []).flatMap((d) => d.resolvers || []).filter((x) => !resolvers.has(x)))],
    encryptedDnsDestinations: rows.filter((b) => b.port === 853 || (b.port === 53 && b.protocol !== "UDP")).map(label),
    phoneResolverDnsAttempts: (o.blocked || []).filter((b) => b.role === "PHONE_RESOLVER").map(label),
    permissionDialogs: (o.permissionDialogs || []).length,
    settingsScreens: (o.settingsScreens || []).map((s) => s.kind),
    appCameToScreen: o.targetOnScreenNanos != null,
    nothingObserved: !o.dnsQueries && !(o.blocked || []).length && !(o.permissionDialogs || []).length && !(o.settingsScreens || []).length,
  };
}

function buildTimeline(o, start, endpoint) {
  const at = (t) => Math.max(0, Math.floor((t - start) / 1e6));
  const times = (n, unit) => (n > 1 ? ` · ${n} ${unit}` : "");
  const e = [];
  if (o.targetOnScreenNanos != null) e.push([o.targetOnScreenNanos, { atMs: at(o.targetOnScreenNanos), kind: "APP_ON_SCREEN", text: "The app's window appeared", evidence: "OBSERVED" }]);
  for (const d of o.domains || []) e.push([d.firstNanos, { atMs: at(d.firstNanos), kind: "LOOKUP", text: `Tried to look up ${d.hostname} (answered: does not exist)${times(d.count, "lookups")}`, evidence: "OBSERVED" }]);
  for (const b of o.blocked || []) e.push([b.firstNanos, { atMs: at(b.firstNanos), kind: "BLOCKED", text: `Blocked ${PROTOCOL_LABEL[b.protocol] || b.protocol} to ${endpoint(b)}${b.role === "PHONE_RESOLVER" ? " (the phone's own resolver)" : ""}${times(b.packets, "packets")}`, evidence: "OBSERVED" }]);
  for (const p of o.permissionDialogs || []) e.push([p.firstNanos, { atMs: at(p.firstNanos), kind: "PERMISSION_DIALOG", text: `Permission dialog${p.namesTarget ? "" : " (does not name this app)"}: "${p.text}"${times(p.timesShown, "times")}`, evidence: "OBSERVED" }]);
  for (const s of o.settingsScreens || []) e.push([s.firstNanos, { atMs: at(s.firstNanos), kind: "SETTINGS_SCREEN", text: `${s.title || s.kind} screen opened${times(s.timesShown, "times")}`, evidence: "OBSERVED" }]);
  return e.sort((a, z) => a[0] - z[0]).map((x) => x[1]);
}

// ---------------------------------------------------------------- portfolio
/** Latest audit per package (newest first). */
export function latestByPackage(audits) {
  const m = new Map();
  for (const a of audits) { const cur = m.get(a.pkg); if (!cur || a.createdAtMs > cur.createdAtMs) m.set(a.pkg, a); }
  return [...m.values()].sort((a, z) => z.createdAtMs - a.createdAtMs);
}

export function median(nums) {
  const v = nums.filter((x) => typeof x === "number").sort((a, z) => a - z);
  if (!v.length) return null;
  const mid = Math.floor(v.length / 2);
  return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
}

/** Findings by check across the latest audit of each app: apps affected and runs (plans) affected. */
export function findingsByCheck(latest) {
  const m = new Map();
  for (const a of latest) for (const f of a.findings) {
    const r = m.get(f.checkId) || { checkId: f.checkId, title: CHECKS[f.checkId] || f.title, severity: f.severity, apps: new Set(), runs: 0, evidence: new Set() };
    r.apps.add(a.label); r.runs += Math.max(1, f.plans.length); if (f.evidenceType) r.evidence.add(f.evidenceType);
    if (sevInfo(f.severity).rank < sevInfo(r.severity).rank) r.severity = f.severity;
    m.set(f.checkId, r);
  }
  return [...m.values()].sort((a, z) => sevInfo(a.severity).rank - sevInfo(z.severity).rank || z.runs - a.runs);
}

/** Tracking-category lookups per company across the latest audits, split by consent phase. */
export function trackersByCompany(latest) {
  const m = new Map();
  for (const a of latest) for (const s of a.sessions) for (const e of s.events) {
    if (!e.tracking) continue;
    const company = e.company || companyOfEntity(e.entity) || e.host;
    const r = m.get(company) || { company, total: 0, phases: {}, entities: new Set(), apps: new Set() };
    r.total++; r.phases[e.phase] = (r.phases[e.phase] || 0) + 1; if (e.entity) r.entities.add(e.entity); r.apps.add(a.label);
    m.set(company, r);
  }
  return [...m.values()].sort((a, z) => z.total - a.total);
}

// ---------------------------------------------------------------- compare
export function compareAudits(base, cand) {
  const key = (f) => f.checkId;
  const b = new Map(base.findings.map((f) => [key(f), f]));
  const c = new Map(cand.findings.map((f) => [key(f), f]));
  const resolved = [...b.values()].filter((f) => !c.has(key(f)));
  const added = [...c.values()].filter((f) => !b.has(key(f)));
  const unchanged = [...c.values()].filter((f) => b.has(key(f))).map((f) => ({ ...f, before: b.get(key(f)) }));
  const caveats = [];
  const samePkg = base.pkg === cand.pkg;
  if (!samePkg) caveats.push({ tone: "amber", text: `Different packages: ${base.pkg} and ${cand.pkg}. This compares two separate apps (for example two build flavours), not a before-and-after of one app.` });
  if (base.plansKey !== cand.plansKey) caveats.push({ tone: "amber", text: "The two audits ran different plans, so their scores are not like for like." });
  if (base.timingProfile == null || cand.timingProfile == null) caveats.push({ tone: "muted", text: "Capture timing profile is not recorded for at least one audit, so a score change cannot fully establish an improvement." });
  else if (base.timingProfile !== cand.timingProfile) caveats.push({ tone: "amber", text: "Capture timing profiles differ (normal vs fast preview); score changes are not comparable." });
  if (base.gaps.length || cand.gaps.length) caveats.push({ tone: "amber", text: "A planned consent choice was not recorded in at least one audit; repeat the same plans before comparing." });
  if (base.score == null || cand.score == null) caveats.push({ tone: "amber", text: "An inconclusive score cannot establish a score change." });
  if (base.sample !== cand.sample) caveats.push({ tone: "muted", text: "One audit is a sample and the other is not." });
  if (cand.createdAtMs < base.createdAtMs) caveats.push({ tone: "muted", text: "The candidate audit is older than the baseline." });
  const hostsB = new Set(base.hosts.map((h) => h.host));
  const hostsC = new Set(cand.hosts.map((h) => h.host));
  return {
    samePkg, resolved, added, unchanged, caveats,
    delta: base.score != null && cand.score != null ? cand.score - base.score : null,
    hostsOnlyBase: base.hosts.filter((h) => !hostsC.has(h.host)),
    hostsOnlyCand: cand.hosts.filter((h) => !hostsB.has(h.host)),
    hostsBoth: cand.hosts.filter((h) => hostsB.has(h.host)),
  };
}

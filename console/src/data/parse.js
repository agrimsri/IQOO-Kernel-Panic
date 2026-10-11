// Reads TraceVeil export files. Everything happens in this browser; nothing is uploaded.
//   traceveil.audit.v1       { schema, exportedAt, app{package,label}, meta, sessions[], result{findings[],score,band}, networkReadings, tool }
//   traceveil.quarantine.v1  { schema, exportedAt, report{...core QuarantineReport...} }
//   traceveil.console-backup.v1 (this console's own "Download all data" file)
//   traceveil.backup.v1      the phone's "Back up my checks" file: each item of "audits" is a traceveil.audit.v1 bundle.
//                            Its sha256 is checked by the phone on restore, not here; each audit gets the same checks as
//                            a single export.
//   traceveil.signature.v1   the phone's hardware signature next to an export (<file>.sig.json). It is paired with the
//                            export imported in the same batch and only noted as present: the console never verifies
//                            it (that needs the exact file bytes and the attestation chain; use traceveil-ci verify).
// A bare QuarantineReport (the phone's saved report JSON) is accepted too. Unknown fields are kept and ignored.
import { AUDIT_SCHEMA, QUARANTINE_SCHEMA, BACKUP_SCHEMA, PHONE_BACKUP_SCHEMA, SIGNATURE_SCHEMA, PLAN } from "./model";

export class ImportError extends Error {}

const isObj = (x) => x && typeof x === "object" && !Array.isArray(x);
const arr = (x) => (Array.isArray(x) ? x : []);

/**
 * Returns a list of records ready to store: { id, kind: "audit"|"quarantine", sample, importedAt, fileName, bundle }. A
 * signature file gives one { kind: "signature", fileName, signedFile } item, which readFiles pairs with its export.
 */
export function parseExportText(text, fileName = "file") {
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new ImportError(`${fileName}: not valid JSON.`);
  }
  if (!isObj(data)) throw new ImportError(`${fileName}: expected a JSON object.`);
  const importedAt = Date.now();
  const schema = typeof data.schema === "string" ? data.schema : null;

  if (schema === BACKUP_SCHEMA) {
    return [...arr(data.audits).map((b) => auditRecord(b, fileName, importedAt)), ...arr(data.quarantine).map((b) => quarantineRecord(b, fileName, importedAt))];
  }
  if (schema === PHONE_BACKUP_SCHEMA) return arr(data.audits).map((b) => auditRecord(b, fileName, importedAt));
  if (schema === AUDIT_SCHEMA) return [auditRecord(data, fileName, importedAt)];
  if (schema === QUARANTINE_SCHEMA) return [quarantineRecord(data, fileName, importedAt)];
  if (schema === SIGNATURE_SCHEMA) {
    return [{ kind: "signature", fileName, signedFile: typeof data.signedFile === "string" ? data.signedFile : null, appVersion: data.appVersion ?? null, createdAt: data.createdAt ?? null }];
  }
  if (!schema && isObj(data.observations) && typeof data.targetPackage === "string") {
    return [quarantineRecord({ schema: QUARANTINE_SCHEMA, exportedAt: null, report: data, rawReport: true }, fileName, importedAt)];
  }
  if (schema && schema.startsWith("traceveil.")) {
    throw new ImportError(`${fileName}: schema "${schema}" is not supported by this console version (it reads ${AUDIT_SCHEMA}, ${QUARANTINE_SCHEMA} and ${PHONE_BACKUP_SCHEMA}).`);
  }
  throw new ImportError(`${fileName}: not a TraceVeil export. Export an audit or quarantine report from the TraceVeil app and drop that file here.`);
}

function auditRecord(b, fileName, importedAt) {
  if (!isObj(b) || b.schema !== AUDIT_SCHEMA) throw new ImportError(`${fileName}: an audit inside is not ${AUDIT_SCHEMA}.`);
  const sessions = arr(b.sessions);
  if (!sessions.length) throw new ImportError(`${fileName}: the audit has no plan sessions.`);
  for (const s of sessions) {
    if (!isObj(s) || typeof s.id !== "string") throw new ImportError(`${fileName}: a session is missing its id.`);
    if (!PLAN[s.plan]) throw new ImportError(`${fileName}: session ${s.id} has an unknown plan "${s.plan}".`);
    if (s.netEvents != null && !Array.isArray(s.netEvents)) throw new ImportError(`${fileName}: session ${s.id} has malformed netEvents.`);
  }
  if (!isObj(b.result) || !Array.isArray(b.result.findings)) {
    throw new ImportError(`${fileName}: the audit has no result (findings, score, band). Re-export it from TraceVeil.`);
  }
  for (const f of b.result.findings) {
    if (!isObj(f) || typeof f.checkId !== "string") throw new ImportError(`${fileName}: a finding is missing its checkId.`);
  }
  if (b.result.score != null && (typeof b.result.score !== "number" || b.result.score < 0 || b.result.score > 100)) {
    throw new ImportError(`${fileName}: the score must be a number from 0 to 100, or null when inconclusive.`);
  }
  const pkg = b.app?.package || b.meta?.targetPackage || sessions[0].targetPackage;
  if (!pkg) throw new ImportError(`${fileName}: the audit does not name the app package.`);
  const id = String(b.meta?.id || `${pkg}-${sessions[0].startedAtEpochMs || sessions[0].id}`);
  return { id, kind: "audit", sample: !!(b.sample || b.meta?.sample), importedAt, fileName, bundle: b };
}

function quarantineRecord(b, fileName, importedAt) {
  const r = b?.report;
  if (!isObj(r)) throw new ImportError(`${fileName}: the quarantine export has no report.`);
  if (typeof r.id !== "string" || typeof r.targetPackage !== "string") throw new ImportError(`${fileName}: the quarantine report is missing its id or package.`);
  if (!isObj(r.observations)) throw new ImportError(`${fileName}: the quarantine report has no observations.`);
  if (r.schemaVersion != null && r.schemaVersion !== 1) {
    throw new ImportError(`${fileName}: quarantine report schemaVersion ${r.schemaVersion} is newer than this console understands (1).`);
  }
  return { id: r.id, kind: "quarantine", sample: !!b.sample, importedAt, fileName, bundle: b };
}

/** Names of the export a signature file belongs to: "<x>.json.sig.json" -> "<x>.json", "<x>.sig.json" -> "<x>.json". */
export function signedFileNames(sigFileName, signedFile = null) {
  const names = [];
  if (sigFileName.endsWith(".sig.json")) {
    const base = sigFileName.slice(0, -".sig.json".length);
    names.push(base, `${base}.json`);
  }
  if (signedFile) names.push(signedFile);
  return names;
}

/**
 * Attaches each signature item to the one audit export of the same batch it names (by file name, then by its signedFile
 * field). The record keeps only that a signature file was present; an unpaired signature file is reported, not stored.
 */
export function pairSignatures(items) {
  const records = items.filter((r) => r.kind !== "signature");
  const errors = [];
  for (const sig of items.filter((r) => r.kind === "signature")) {
    const names = signedFileNames(sig.fileName, sig.signedFile);
    const target = names.map((n) => records.filter((r) => r.fileName === n)).find((list) => list.length > 0) || [];
    if (target.length === 1 && target[0].kind === "audit") {
      target[0].signatureFile = { fileName: sig.fileName, signedFile: sig.signedFile, appVersion: sig.appVersion, createdAt: sig.createdAt };
    } else if (target.length) {
      errors.push(`${sig.fileName}: a TraceVeil signature file for ${target[0].fileName}, which is not a single audit export; check it with traceveil-ci verify.`);
    } else {
      errors.push(`${sig.fileName}: a TraceVeil signature file (${SIGNATURE_SCHEMA}). Import it together with the export it signs (${names[0] || sig.signedFile || "the signed file"}), then verify it with traceveil-ci verify.`);
    }
  }
  return { records, errors };
}

/** Reads dropped or picked files. Returns { records, errors }. */
export async function readFiles(fileList) {
  const items = [];
  const errors = [];
  for (const file of Array.from(fileList || [])) {
    if (file.size > 25 * 1024 * 1024) { errors.push(`${file.name}: larger than 25 MB, skipped.`); continue; }
    try {
      const text = await file.text();
      items.push(...parseExportText(text, file.name));
    } catch (e) {
      errors.push(e instanceof ImportError ? e.message : `${file.name}: ${e?.message || "could not be read"}`);
    }
  }
  const paired = pairSignatures(items);
  return { records: paired.records, errors: [...errors, ...paired.errors] };
}

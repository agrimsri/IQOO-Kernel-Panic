import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import * as store from "./store";
import { readFiles, parseExportText } from "./parse";
import { auditView, quarantineView } from "./derive";
import { DEFAULT_POLICY, normalisePolicy } from "./policy";
import { BACKUP_SCHEMA } from "./model";
import leakySample from "./samples/leakyshop-leaky.audit.json";
import fixedSample from "./samples/leakyshop-fixed.audit.json";
import challanSample from "./samples/test-challan.quarantine.json";

const Ctx = createContext(null);
export const useData = () => useContext(Ctx);

const SAMPLE_FILES = [
  ["leakyshop-leaky.audit.json", leakySample],
  ["leakyshop-fixed.audit.json", fixedSample],
  ["test-challan.quarantine.json", challanSample],
];
export const sampleRecords = () => SAMPLE_FILES.flatMap(([name, obj]) => parseExportText(JSON.stringify(obj), `sample/${name}`));

export function DataProvider({ children }) {
  const [state, setState] = useState({ ready: false, mode: null, audits: [], quarantine: [], policy: DEFAULT_POLICY, firstVisit: false, tourSeen: true });

  const reload = useCallback(async (patch = {}) => {
    const [audits, quarantine] = await Promise.all([store.getAll("audits"), store.getAll("quarantine")]);
    setState((s) => ({ ...s, ...patch, audits, quarantine }));
  }, []);

  useEffect(() => {
    (async () => {
      const mode = await store.storageMode();
      let initialised = await store.getKV("initialised", false);
      const firstVisit = !initialised;
      if (!initialised) {
        // First visit: load the labelled samples so the console is not empty. They can be removed in Settings.
        const recs = sampleRecords();
        await store.putMany("audits", recs.filter((r) => r.kind === "audit"));
        await store.putMany("quarantine", recs.filter((r) => r.kind === "quarantine"));
        await store.setKV("initialised", true);
        initialised = true;
      }
      const policy = normalisePolicy(await store.getKV("policy", DEFAULT_POLICY));
      const tourSeen = !!(await store.getKV("tourSeen", false));
      await reload({ ready: true, mode, policy, firstVisit, tourSeen });
    })();
  }, [reload]);

  const putRecords = useCallback(async (records) => {
    const existing = new Set([...state.audits.map((r) => `audit:${r.id}`), ...state.quarantine.map((r) => `quarantine:${r.id}`)]);
    const replaced = records.filter((r) => existing.has(`${r.kind}:${r.id}`)).length;
    await store.putMany("audits", records.filter((r) => r.kind === "audit"));
    await store.putMany("quarantine", records.filter((r) => r.kind === "quarantine"));
    await reload();
    return { added: records.length - replaced, replaced };
  }, [state.audits, state.quarantine, reload]);

  const importFiles = useCallback(async (files) => {
    const { records, errors } = await readFiles(files);
    const res = records.length ? await putRecords(records) : { added: 0, replaced: 0 };
    return { ...res, errors, records };
  }, [putRecords]);

  const loadSamples = useCallback(() => putRecords(sampleRecords()), [putRecords]);

  const removeSamples = useCallback(async () => {
    await store.remove("audits", state.audits.filter((r) => r.sample).map((r) => r.id));
    await store.remove("quarantine", state.quarantine.filter((r) => r.sample).map((r) => r.id));
    await reload();
  }, [state.audits, state.quarantine, reload]);

  const deleteRecord = useCallback(async (kind, id) => {
    await store.remove(kind === "audit" ? "audits" : "quarantine", [id]);
    await reload();
  }, [reload]);

  const clearEverything = useCallback(async () => {
    await store.clearAll();
    await store.setKV("initialised", true); // do not bring the samples back on the next visit
    await store.setKV("tourSeen", true);
    await reload({ policy: DEFAULT_POLICY });
  }, [reload]);

  const setPolicy = useCallback(async (p) => {
    const policy = normalisePolicy(p);
    setState((s) => ({ ...s, policy }));
    await store.setKV("policy", policy);
  }, []);

  const markTourSeen = useCallback(async () => {
    setState((s) => ({ ...s, tourSeen: true }));
    await store.setKV("tourSeen", true);
  }, []);

  const backupText = useCallback(() => JSON.stringify({
    schema: BACKUP_SCHEMA,
    exportedAt: new Date().toISOString(),
    audits: state.audits.map((r) => r.bundle),
    quarantine: state.quarantine.map((r) => r.bundle),
  }, null, 2), [state.audits, state.quarantine]);

  const value = useMemo(() => {
    const audits = state.audits.map(auditView).sort((a, z) => z.createdAtMs - a.createdAtMs);
    const quarantines = state.quarantine.map(quarantineView).sort((a, z) => z.startedAtMs - a.startedAtMs);
    const hasSamples = state.audits.some((r) => r.sample) || state.quarantine.some((r) => r.sample);
    return {
      ...state, audits, quarantines, hasSamples,
      importFiles, loadSamples, removeSamples, deleteRecord, clearEverything, setPolicy, markTourSeen, backupText,
    };
  }, [state, importFiles, loadSamples, removeSamples, deleteRecord, clearEverything, setPolicy, markTourSeen, backupText]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

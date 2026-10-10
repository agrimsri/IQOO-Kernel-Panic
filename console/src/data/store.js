// Local storage for imported exports: IndexedDB in this browser. Nothing is ever sent anywhere.
// If the browser blocks IndexedDB (private mode, strict settings), the console keeps data in memory for this tab
// and says so on screen.
export const DB_NAME = "traceveil-console";
const DB_VERSION = 1;
const STORES = ["audits", "quarantine", "kv"];

let dbp = null;
let memory = null; // fallback: { audits: Map, quarantine: Map, kv: Map }

function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    try {
      if (typeof indexedDB === "undefined") throw new Error("IndexedDB is not available");
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("audits")) db.createObjectStore("audits", { keyPath: "id" });
        if (!db.objectStoreNames.contains("quarantine")) db.createObjectStore("quarantine", { keyPath: "id" });
        if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv", { keyPath: "key" });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error("IndexedDB open failed"));
      req.onblocked = () => reject(new Error("IndexedDB is blocked by another tab"));
    } catch (e) {
      reject(e);
    }
  }).catch((e) => {
    memory = { audits: new Map(), quarantine: new Map(), kv: new Map() };
    console.warn("TraceVeil Console: using in-memory storage", e);
    return null;
  });
  return dbp;
}

export async function storageMode() {
  const db = await open();
  return db ? "indexeddb" : "memory";
}

const tx = (db, store, mode, fn) => new Promise((resolve, reject) => {
  const t = db.transaction(store, mode);
  const s = t.objectStore(store);
  const out = fn(s);
  t.oncomplete = () => resolve(typeof IDBRequest !== "undefined" && out instanceof IDBRequest ? out.result : undefined);
  t.onerror = () => reject(t.error);
  t.onabort = () => reject(t.error || new Error("transaction aborted"));
});

export async function getAll(store) {
  const db = await open();
  if (!db) return [...memory[store].values()];
  return tx(db, store, "readonly", (s) => s.getAll());
}

export async function putMany(store, records) {
  const db = await open();
  if (!db) { records.forEach((r) => memory[store].set(r.id ?? r.key, r)); return; }
  await tx(db, store, "readwrite", (s) => { records.forEach((r) => s.put(r)); });
}

export async function remove(store, ids) {
  const db = await open();
  if (!db) { ids.forEach((id) => memory[store].delete(id)); return; }
  await tx(db, store, "readwrite", (s) => { ids.forEach((id) => s.delete(id)); });
}

export async function clearAll() {
  const db = await open();
  if (!db) { STORES.forEach((k) => memory[k].clear()); return; }
  for (const store of STORES) await tx(db, store, "readwrite", (s) => { s.clear(); });
}

export async function getKV(key, fallback = null) {
  const db = await open();
  if (!db) return memory.kv.has(key) ? memory.kv.get(key).value : fallback;
  const row = await tx(db, "kv", "readonly", (s) => s.get(key));
  return row ? row.value : fallback;
}

export async function setKV(key, value) {
  const db = await open();
  if (!db) { memory.kv.set(key, { key, value }); return; }
  await tx(db, "kv", "readwrite", (s) => { s.put({ key, value }); });
}

export async function estimate() {
  try {
    if (!navigator.storage?.estimate) return null;
    const e = await navigator.storage.estimate();
    const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : null;
    return { usage: e.usage ?? null, quota: e.quota ?? null, persisted };
  } catch {
    return null;
  }
}

export async function requestPersist() {
  try { return navigator.storage?.persist ? await navigator.storage.persist() : false; } catch { return false; }
}

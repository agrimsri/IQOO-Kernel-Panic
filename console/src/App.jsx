import { useEffect, useRef, useState } from "react";
import { LayoutDashboard, ScrollText, GitCompareArrows, ShieldBan, ShieldCheck, Gem, Settings as Cog, PlayCircle, Upload, Lock, Menu, HardDrive, FileUp } from "lucide-react";
import { useRoute, navigate } from "./lib/router";
import AppShell from "./shell/AppShell";
import Tour from "./tour/Tour";
import { configureTour, start, useTour } from "./tour/engine";
import { Toasts, toast, Sheet } from "./ui";
import { SampleBadge } from "./ui/tv";
import { DataProvider, useData } from "./data/DataContext";
import { AUDIT_SCHEMA, QUARANTINE_SCHEMA } from "./data/model";
import logo from "./assets/logo-96.png";
import Portfolio from "./pages/Portfolio";
import Audits from "./pages/Audits";
import AuditDetail from "./pages/AuditDetail";
import Compare from "./pages/Compare";
import Quarantine from "./pages/Quarantine";
import Policy from "./pages/Policy";
import Plans from "./pages/Plans";
import Settings from "./pages/Settings";

const NAV = [
  { group: "Workspace", items: [
    { to: "/", label: "Portfolio", icon: LayoutDashboard, exact: true },
    { to: "/audits", label: "Audits", icon: ScrollText },
    { to: "/compare", label: "Compare", icon: GitCompareArrows },
    { to: "/quarantine", label: "Quarantine", icon: ShieldBan, short: "Quarantine" },
  ] },
  { group: "Release", items: [{ to: "/policy", label: "Release policy", icon: ShieldCheck, short: "Policy" }] },
  { group: "Account", items: [
    { to: "/plans", label: "Plans", icon: Gem },
    { to: "/settings", label: "Settings & privacy", icon: Cog, short: "Settings" },
  ] },
];
const MOBILE_TABS = [NAV[0].items[0], NAV[0].items[1], NAV[0].items[3], NAV[1].items[0], NAV[2].items[1]];
const TITLES = [
  ["/audits", "Audits", "Consent X-ray results"],
  ["/compare", "Compare", "Two audits side by side"],
  ["/quarantine", "Quarantine", "Network-blocked behaviour runs"],
  ["/policy", "Release policy", "CI gate rules"],
  ["/plans", "Plans", "Free, Pro team and Enterprise"],
  ["/settings", "Settings & privacy", "Storage, data and theme"],
  ["/", "Portfolio", "All apps at a glance"],
];

export default function App() {
  return (
    <DataProvider>
      <Console />
    </DataProvider>
  );
}

function Console() {
  const route = useRoute();
  const data = useData();
  const tour = useTour();
  const [dragging, setDragging] = useState(false);
  const [menu, setMenu] = useState(false);
  const fileInput = useRef(null);
  const depth = useRef(0);

  // whole-window drag and drop import
  useEffect(() => {
    const hasFiles = (e) => Array.from(e.dataTransfer?.types || []).includes("Files");
    const enter = (e) => { if (!hasFiles(e)) return; e.preventDefault(); depth.current++; setDragging(true); };
    const over = (e) => { if (hasFiles(e)) e.preventDefault(); };
    const leave = (e) => { if (!hasFiles(e)) return; depth.current = Math.max(0, depth.current - 1); if (!depth.current) setDragging(false); };
    const drop = (e) => { if (!hasFiles(e)) return; e.preventDefault(); depth.current = 0; setDragging(false); runImport(e.dataTransfer.files); };
    window.addEventListener("dragenter", enter); window.addEventListener("dragover", over);
    window.addEventListener("dragleave", leave); window.addEventListener("drop", drop);
    return () => { window.removeEventListener("dragenter", enter); window.removeEventListener("dragover", over); window.removeEventListener("dragleave", leave); window.removeEventListener("drop", drop); };
  });

  const runImport = async (files) => {
    if (!files?.length) return;
    const res = await data.importFiles(files);
    res.errors.forEach((e) => toast(e, "warn"));
    if (res.records.length) {
      toast(`Imported ${res.records.length} item${res.records.length === 1 ? "" : "s"}${res.replaced ? ` (${res.replaced} replaced)` : ""}. Stored in this browser only.`, "ok");
      const first = res.records[0];
      navigate(first.kind === "audit" ? `/audits/${encodeURIComponent(first.id)}` : `/quarantine/${encodeURIComponent(first.id)}`);
    }
  };
  const pickFiles = () => fileInput.current?.click();

  // guided tour: built from whatever data is loaded, auto-started once on the first visit
  useEffect(() => {
    if (!data.ready) return;
    const worst = [...data.audits].sort((a, z) => (a.score ?? 101) - (z.score ?? 101))[0];
    const second = data.audits.find((a) => a !== worst);
    const q = data.quarantines[0];
    const steps = [
      { id: "kpis", route: "/", target: "kpis", title: "Every app, one glance", body: "Apps checked, audits, open critical findings, the median score and quarantine runs. Everything is computed in this browser from the exports you import." },
      { id: "import", route: "/", target: "import", title: "Drop exports anywhere", body: `Drag TraceVeil JSON exports (${AUDIT_SCHEMA} or ${QUARANTINE_SCHEMA}) onto the window. Files are read locally and kept in IndexedDB; nothing is uploaded.` },
      { id: "attention", route: "/", target: "attention", title: "What needs attention", body: "Critical findings, inferred quarantine signals and incomplete runs float to the top." },
    ];
    if (worst) {
      steps.push(
        { id: "coverage", route: `/audits/${encodeURIComponent(worst.id)}`, target: "coverage", title: "Coverage comes first", body: "Before any finding: which plans ran, whether the consent screen appeared and whether the planned Accept or Reject was really tapped." },
        { id: "lanes", route: `/audits/${encodeURIComponent(worst.id)}`, target: "lanes", title: "Consent timeline", body: "One lane per plan. Dots are DNS lookups; the vertical marks are the consent screen and the exact recorded tap. Red diamonds are tracker hosts looked up after the user refused (catalogue matches, not proof that data was sent)." },
        { id: "finding", route: `/audits/${encodeURIComponent(worst.id)}`, target: "finding-0", title: "Findings with evidence labels", body: "Each finding says whether it was observed or inferred, shows its first evidence lines and the fix, with a copy button." },
      );
    }
    if (worst && second) steps.push({ id: "compare", route: `/compare?a=${encodeURIComponent(worst.id)}&b=${encodeURIComponent(second.id)}`, target: "compare-delta", title: "Compare two audits", body: "Resolved, new and unchanged findings with the score change. Different packages are labelled as such, never passed off as a before-and-after." });
    if (q) steps.push({ id: "quarantine", route: `/quarantine/${encodeURIComponent(q.id)}`, target: "q-signals", title: "Quarantine behaviour", body: "What an app tried to reach while its internet was cut off. A hard-coded address is an inferred signal, not proof, and never a malware verdict." });
    steps.push(
      { id: "policy", route: "/policy", target: "policy-preview", title: "Release policy", body: "Turn findings into a CI gate and preview which loaded audits would pass, then download policy.json." },
      { id: "privacy", route: "/settings", target: "storage", title: "Your data stays here", body: "Stored in this browser's IndexedDB. Clear it any time. Not legal advice and not a malware verdict." },
    );
    configureTour({ steps });
    const noTour = route.query.shot != null || route.query.notour != null;
    if (!data.tourSeen && data.firstVisit && !noTour) {
      data.markTourSeen();
      setTimeout(() => start(0), 900);
    }
  }, [data.ready, data.audits.length, data.quarantines.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const p = route.path;
  let page;
  if (!data.ready) page = <div className="skeleton" style={{ height: 320 }} />;
  else if (p === "/") page = <Portfolio onImport={pickFiles} />;
  else if (p === "/audits") page = <Audits onImport={pickFiles} />;
  else if (p.startsWith("/audits/")) page = <AuditDetail id={decodeURIComponent(route.parts[1] || "")} />;
  else if (p === "/compare") page = <Compare query={route.query} />;
  else if (p === "/quarantine" || p.startsWith("/quarantine/")) page = <Quarantine id={route.parts[1] ? decodeURIComponent(route.parts[1]) : null} onImport={pickFiles} />;
  else if (p === "/policy") page = <Policy />;
  else if (p === "/plans") page = <Plans />;
  else if (p === "/settings") page = <Settings onImport={pickFiles} />;
  else page = <Portfolio onImport={pickFiles} />;

  const counts = data.audits.reduce((n, a) => n + a.findings.filter((f) => f.severity === "CRITICAL").length, 0);
  const nav = NAV.map((g) => ({ ...g, items: g.items.map((it) => (it.to === "/audits" && counts ? { ...it, badge: counts } : it)) }));

  return (
    <>
      <AppShell
        path={p}
        brand={{ name: "TraceVeil", sub: "Console", logo, href: "#/" }}
        nav={nav}
        titles={TITLES}
        mobileTabs={MOBILE_TABS}
        topRight={(
          <>
            {data.hasSamples && <span className="desktop-only"><SampleBadge label="Sample data loaded" /></span>}
            <span className={`local-pill ${data.mode === "memory" ? "mem" : ""}`} title={data.mode === "memory" ? "This browser blocked IndexedDB: data lives only in this tab." : "Stored in this browser (IndexedDB). Nothing is uploaded."}>
              {data.mode === "memory" ? <HardDrive size={13} /> : <Lock size={13} />}<span className="txt">{data.mode === "memory" ? "This tab only" : "Local only"}</span>
            </span>
            <button className="btn sm ghost desktop-only" onClick={() => start(0)} disabled={tour.active}><PlayCircle size={15} />Tour</button>
            <button className="btn sm primary" onClick={pickFiles} data-tour="import-btn"><Upload size={15} /><span className="desktop-only">Import</span></button>
            <button className="btn icon sm mobile-only" onClick={() => setMenu(true)} aria-label="Menu"><Menu size={17} /></button>
          </>
        )}
        footer={(
          <div className="side-note">
            <div className="row"><Lock size={14} color="var(--clear)" /><b style={{ color: "var(--text)" }}>Local-first</b></div>
            <span>Exports are read and stored in this browser{data.mode === "memory" ? " tab (IndexedDB is blocked)" : " (IndexedDB)"}. This page makes no network requests.</span>
          </div>
        )}
      >
        <div key={p}>{page}</div>
      </AppShell>
      <input ref={fileInput} type="file" accept=".json,application/json" multiple hidden onChange={(e) => { runImport(e.target.files); e.target.value = ""; }} />
      {dragging && (
        <div className="drop-overlay">
          <div className="drop-box">
            <FileUp size={34} />
            <h2>Drop TraceVeil exports</h2>
            <p className="text-2 small">{AUDIT_SCHEMA} and {QUARANTINE_SCHEMA} files. They are read in this browser and stored locally; nothing is uploaded.</p>
          </div>
        </div>
      )}
      <Sheet open={menu} onClose={() => setMenu(false)} title="TraceVeil Console">
        <div className="list">
          {NAV.flatMap((g) => g.items).map((it) => (
            <a key={it.to} className="li" href={`#${it.to}`} onClick={() => setMenu(false)}>
              <it.icon size={18} /><span className="grow t">{it.label}</span>
            </a>
          ))}
        </div>
        <button className="btn block" style={{ marginTop: 14 }} onClick={() => { setMenu(false); start(0); }}><PlayCircle size={16} />Start the guided tour</button>
      </Sheet>
      <Tour />
      <Toasts />
    </>
  );
}

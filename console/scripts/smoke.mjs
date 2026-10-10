// End-to-end smoke test of the single-file build from file:// with the local Chrome (no server, no network).
//   node scripts/smoke.mjs        (or: npm run smoke)
// Checks: first visit loads the labelled samples and starts the tour; importing a valid audit, a bare quarantine
// report and a broken file; importing the traceveil-ci sample bundles from ../docs/samples (the same files the CLI
// tests gate); same-package compare; the policy preview with the CLI's defaults and rules; deleting all data stays
// deleted on reload; no console errors and no network requests.
import puppeteer from "puppeteer-core";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const url = pathToFileURL(join(root, "dist-single", "index.html")).href;
const chrome = process.env.CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const tmp = mkdtempSync(join(tmpdir(), "tv-console-smoke-"));
const shotDir = process.argv[2] ? resolve(process.argv[2]) : null;

// fixtures: a later audit of the same package (copied from the sample, new id) and a bare quarantine report
const leaky = JSON.parse(readFileSync(join(root, "src/data/samples/leakyshop-leaky.audit.json"), "utf8"));
const later = structuredClone(leaky);
delete later.sample; later.meta.sample = false; later.meta.id = "audit-smoke-later"; later.meta.createdAtMs += 3_600_000;
later.legalProfile = "US_CHILDREN"; // the exporting phone's legal lens (core LegalProfile name)
later.sessions.forEach((s) => { s.id = s.id.replace("audit-1791590915883", "audit-smoke-later"); s.startedAtEpochMs += 3_600_000; });
const q = JSON.parse(readFileSync(join(root, "src/data/samples/test-challan.quarantine.json"), "utf8")).report;
q.id = "quarantine-smoke-bare"; delete q.summary; delete q.timeline; // the console must compute both
const files = {
  later: join(tmp, "later.traceveil.json"), bare: join(tmp, "bare-report.json"), broken: join(tmp, "broken.json"), other: join(tmp, "other.json"),
};
writeFileSync(files.later, JSON.stringify(later));
writeFileSync(files.bare, JSON.stringify(q));
writeFileSync(files.broken, "{ not json");
writeFileSync(files.other, JSON.stringify({ hello: "world" }));
// the CLI's synthetic sample bundles, exactly as committed (traceveil.audit.v1 x3, traceveil.quarantine.v1 x1)
const cliSamplesDir = join(root, "..", "docs", "samples");
const cliSamples = ["leakyshop-leaky_sample.json", "leakyshop-fixed_sample.json", "instagram-kept-data_sample.json", "riskdemo-quarantine_sample.json"]
  .map((f) => join(cliSamplesDir, f));

let failures = 0;
const check = (ok, msg) => { console.log(`${ok ? "PASS" : "FAIL"}  ${msg}`); if (!ok) failures++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ["--allow-file-access-from-files"] });
const ctx = await browser.createBrowserContext();
const page = await ctx.newPage();
await page.setViewport({ width: 1440, height: 900 });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
page.on("request", (r) => { if (!/^(file|data|blob):/.test(r.url())) errors.push(`network request: ${r.url()}`); });
page.on("dialog", (d) => d.accept());
const text = () => page.evaluate(() => document.body.innerText);
const go = async (hash) => { await page.evaluate((h) => { location.hash = h; }, hash); await sleep(700); };

// 1. first visit
await page.goto(`${url}#/`, { waitUntil: "load" });
await page.waitForSelector("[data-tour=kpis]");
await sleep(1800);
check(await page.$(".tour-card") !== null, "guided tour starts on the first visit");
if (shotDir) await page.screenshot({ path: join(shotDir, "tour.png") });
await page.keyboard.press("Escape");
await sleep(400);
check(await page.$(".tour-card") === null, "Escape closes the tour");
let t = await text();
check(t.includes("SAMPLE DATA LOADED") || t.toLowerCase().includes("sample data loaded"), "samples are loaded and labelled");
check(/Consent audits\s*2/.test(t), "portfolio counts 2 sample audits");

// 2. imports
const input = await page.$("input[type=file]");
await input.uploadFile(files.later, files.bare, files.broken, files.other);
await sleep(1500);
t = await text();
check(t.includes("broken.json: not valid JSON"), "a broken file is rejected with a reason");
check(t.includes("other.json: not a TraceVeil export"), "a non-TraceVeil JSON file is rejected with a reason");
await go("/audits");
t = await text();
check(t.includes("3 audits"), "the valid audit export was imported (3 audits)");
// 2b. the traceveil-ci sample bundles from docs/samples
const input2 = await page.$("input[type=file]");
await input2.uploadFile(...cliSamples);
await sleep(1500);
t = await text();
check(!/_sample\.json: /.test(t), "the docs/samples bundles import without errors");
await go("/audits");
t = await text();
check(t.includes("6 audits"), "the three traceveil-ci sample audits were imported (6 audits)");
await go("/audits/sample-leakyshop-leaky");
t = await text();
check(t.includes("LeakyShop (sample)") && /\b21\b/.test(t), "the CLI leaky sample opens with its score of 21");
await go("/audits/sample-instagram-kept-data");
t = await text();
check(t.includes("Instagram (synthetic sample)"), "the CLI kept-data sample opens");
await go("/quarantine/quarantine-sample");
t = await text();
check(t.includes("panel.example.com") && t.includes("203.0.113.9"), "the CLI quarantine sample (traceveil.quarantine.v1) opens with its lookup and blocked address");
await go("/quarantine/quarantine-smoke-bare");
t = await text();
check(t.includes("Possible hard-coded address") && t.includes("203.0.113.10:443"), "a bare quarantine report gets its summary and hard-coded address signal computed");
check(t.includes("Tried to look up c2.traceveil-demo.invalid"), "a bare quarantine report gets its timeline computed");

// 3. compare picks two audits of the same package
await go("/compare");
t = await text();
check(t.includes("SAME PACKAGE") || t.includes("Same package"), "compare defaults to two audits of the same package");
check(/Score change\s*0/i.test(t) || t.includes("\n0\n"), "same findings give a score change of 0");

// 4. policy preview: the traceveil-ci defaults and rules (cli Policy.kt / Gate.kt)
await go("/policy");
t = await text();
check(t.includes("C2 Tracker hosts looked up after the user refused (Critical, OBSERVED evidence)"), "the default policy fails LeakyShop on C2");
check(t.includes("C9 NOT ESTABLISHED: the app was not declared child-facing"), "a failOn check that could not be tested fails by default (C9 NOT ESTABLISHED)");
check(t.includes("fresh state NOT ESTABLISHED (not recorded in this audit)"), "requireFreshState is on by default and fails a fresh state that was not recorded");
check(t.includes("All 3 plans started from an approved fresh reset."), "the CLI fixed sample's recorded fresh resets pass requireFreshState");
check(t.includes("Plan A · Accept was not captured; Plan B · Reject was not captured."), "requirePlans fails the kept-data sample like the CLI");
check(/4 findings at High or Critical/.test(t) === false && t.includes("maxHigh not set."), "maxHigh is not set by default, like the CLI");
const tl = t.toLowerCase(); // status pills are upper-cased by CSS
check(!tl.includes("cli in development") && tl.includes("cli built") && tl.includes("workflow not yet run on github"), "the CI card states that the CLI is built and the workflow has not run on GitHub");
check(t.includes("cli/build/install/traceveil-ci/bin/traceveil-ci check") && t.includes("--policy \"$TRACEVEIL_POLICY\" --format sarif --out traceveil.sarif"), "the GitHub Actions snippet is the repository's real workflow");
check(t.includes('"allowNotEstablished": false') && t.includes('"requirePlans": ['), "policy.json carries allowNotEstablished and requirePlans");
const fresh = await page.$('button.toggle[aria-label="Require a fresh app state"]');
await fresh.click();
await sleep(300);
t = await text();
check(!t.includes("fresh state NOT ESTABLISHED (not recorded in this audit)") && t.includes("Fresh app state not required."), "switching requireFreshState off removes that failure");
await fresh.click();
const allowNe = await page.$('button.toggle[aria-label="Allow checks that could not be tested"]');
await allowNe.click();
await sleep(300);
t = await text();
check(t.includes("C9 NOT ESTABLISHED") && t.includes("(reported, not failed: allowNotEstablished is on)"), "allowNotEstablished reports NOT ESTABLISHED without failing");
await allowNe.click();

// 5. audit detail renders the lanes and fixes
await go("/audits/audit-1791590915883");
check(await page.$("[data-tour=lanes] .lane-svg") !== null, "consent timeline lanes render");
const diamonds = await page.$$eval("[data-tour=lanes] path", (ps) => ps.filter((p) => (p.getAttribute("fill") || "").includes("sev-critical")).length);
check(diamonds === 3, `three after-Reject tracker marks (found ${diamonds})`);
t = await text();
check(t.includes("Firebase Analytics: start collection only after consent"), "fix snippets are shown for C2");
check(t.includes("Legal lens: Global baseline") && t.includes("the export names no lens"), "an export without a lens is read under the global baseline");
check(t.includes("Global baseline: Good practice") && t.includes("Compare laws") && !t.includes("DPDP-ready"), "findings carry the lens relation and a Compare laws fold");
await go("/audits/audit-smoke-later");
t = await text();
check(t.includes("Legal lens: US children (COPPA)") && t.includes("named in the export") && t.includes("skips C3, C4 (not applicable)"),
  "an export's legalProfile selects its lens and the lens score skips not-applicable checks");
check(t.includes("US children (COPPA): Not applicable"), "a not-applicable finding is labelled, not hidden");

// 6. delete everything, reload: samples do not come back
await go("/settings");
const del = await page.$$("button.btn.danger");
await del[0].click();
await sleep(800);
await page.reload({ waitUntil: "load" });
await sleep(1500);
await go("/audits");
t = await text();
check(t.includes("No audits yet"), "deleted data stays deleted after a reload");

check(errors.length === 0, `no console errors or network requests${errors.length ? `: ${errors.join(" | ")}` : ""}`);
await browser.close();
console.log(failures ? `${failures} check(s) failed` : "All smoke checks passed");
process.exit(failures ? 1 : 0);

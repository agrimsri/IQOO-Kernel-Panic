// Screenshots of the single-file build (dist-single/index.html opened from file://) with the local Chrome.
//   node scripts/screens.mjs [outDir] [--mobile] [--all] [--dpr=1]
// Every shot runs in a fresh browser context, so the console loads its labelled samples like a first visit.
// "?shot" in the route suppresses the first-visit guided tour. Console errors and page errors are printed and
// make the script exit with code 1.
import puppeteer from "puppeteer-core";
import { mkdirSync, existsSync } from "node:fs";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const outDir = resolve(root, args.find((a) => !a.startsWith("--")) || "screens");
const mobile = args.includes("--mobile");
const all = args.includes("--all");
const dpr = Number((args.find((a) => a.startsWith("--dpr=")) || "--dpr=1").slice(6));
const chrome = process.env.CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const page0 = pathToFileURL(join(root, "dist-single", "index.html")).href;
if (!existsSync(join(root, "dist-single", "index.html"))) { console.error("Build first: npm run build:single"); process.exit(1); }
mkdirSync(outDir, { recursive: true });

const LEAKY = "audit-1791590915883", FIXED = "audit-1791591219563", Q = "quarantine-1791593460000";
const shots = [
  ["portfolio", "/", "[data-tour=kpis]"],
  ["audit-detail", `/audits/${LEAKY}`, "[data-tour=lanes]"],
  ["quarantine", `/quarantine/${Q}`, "[data-tour=q-signals]"],
  ["policy", "/policy", "[data-tour=policy-preview]"],
  ["plans", "/plans", ".plan-card"],
  ["audit-timeline", `/audits/${LEAKY}`, "[data-tour=lanes]", "[data-tour=lanes]"],
  ["compare", `/compare?a=${LEAKY}&b=${FIXED}`, "[data-tour=compare-delta]"],
];
if (all) shots.push(
  ["audits", "/audits", ".tbl"],
  ["settings", "/settings", "[data-tour=storage]"],
  ["audit-fixed", `/audits/${FIXED}`, "[data-tour=lanes]"],
  ["audit-detail-findings", `/audits/${LEAKY}`, "[data-tour=finding-0]", "[data-tour=finding-0]"],
);

const viewport = mobile ? { width: 390, height: 844, deviceScaleFactor: dpr, isMobile: true, hasTouch: true } : { width: 1440, height: 900, deviceScaleFactor: dpr };
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ["--allow-file-access-from-files", "--hide-scrollbars"] });
let problems = 0;
for (const [name, route, waitFor, scrollTo] of shots) {
  const ctx = await browser.createBrowserContext();
  const page = await ctx.newPage();
  await page.setViewport(viewport);
  page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") { console.log(`  [${name}] console.${m.type()}: ${m.text()}`); if (m.type() === "error") problems++; } });
  page.on("pageerror", (e) => { console.log(`  [${name}] page error: ${e.stack || e.message}`); problems++; });
  page.on("request", (r) => { const u = r.url(); if (!u.startsWith("file:") && !u.startsWith("data:") && !u.startsWith("blob:")) { console.log(`  [${name}] NETWORK REQUEST: ${u}`); problems++; } });
  const sep = route.includes("?") ? "&" : "?";
  await page.goto(`${page0}#${route}${sep}shot=1`, { waitUntil: "load" });
  await page.waitForSelector(waitFor, { timeout: 15000 });
  if (scrollTo) await page.$eval(scrollTo, (el) => { const card = el.closest(".card, .finding") || el; window.scrollTo(0, card.getBoundingClientRect().top + window.scrollY - 84); });
  await new Promise((r) => setTimeout(r, 1800));
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 0) { console.log(`  [${name}] horizontal overflow: ${overflow}px`); problems++; }
  const file = join(outDir, `${name}${mobile ? "-mobile" : ""}.png`);
  await page.screenshot({ path: file, fullPage: args.includes("--full") });
  console.log(file);
  await ctx.close();
}
await browser.close();
if (problems) { console.log(`${problems} problem(s) found`); process.exit(1); }

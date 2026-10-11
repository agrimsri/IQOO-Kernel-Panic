// Runs TraceVeil's in-page consent probe (core/src/main/resources/web/consent_probe.js) on the synthetic HTML fixtures in
// core/src/test/resources/web/fixtures in a local, headless Chromium, and writes what the probe returned next to each
// fixture as <name>.probe.json. The Kotlin tests (WebProbeFixturesTest) read those files, so the JSON they test is the
// probe's real output on these pages, not hand-written.
//
// Offline: every http(s) request is aborted; only the local fixture files load. Desktop Chromium runs the same Blink
// engine as Android's browser component, but it is not the phone: results on real sites still need the phone check in
// docs/WEB_CHECK.md.
//
// Usage (paths are examples):
//   set PUPPETEER_CORE=D:\path\to\node_modules\puppeteer-core
//   set CHROME_PATH=C:\Program Files\Google\Chrome\Application\chrome.exe
//   node tools/webcheck/probe-fixtures.mjs
import { createRequire } from 'node:module';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..', '..');
const fixtures = join(repo, 'core', 'src', 'test', 'resources', 'web', 'fixtures');
const probe = readFileSync(join(repo, 'core', 'src', 'main', 'resources', 'web', 'consent_probe.js'), 'utf8').trim();

const require = createRequire(import.meta.url);
const puppeteer = require(process.env.PUPPETEER_CORE || 'puppeteer-core');
const chrome = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-first-run', '--disable-extensions'] });
const results = [];
try {
  for (const file of readdirSync(fixtures).filter((f) => f.endsWith('.html')).sort()) {
    const name = file.replace(/\.html$/, '');
    const context = await browser.createBrowserContext();
    const page = await context.newPage();
    await page.setViewport({ width: 412, height: 915, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true });
    await page.setRequestInterception(true);
    page.on('request', (r) => (r.url().startsWith('file:') ? r.continue() : r.abort()));
    await page.goto(pathToFileURL(join(fixtures, file)).href, { waitUntil: 'load' });
    const scan = await page.evaluate(`${probe}('scan', null)`);
    scan.url = `https://fixture.example/${file}`;
    writeFileSync(join(fixtures, `${name}.probe.json`), JSON.stringify(scan, null, 2) + '\n');
    results.push(`${name}: banner=${scan.banner ? `${scan.banner.cmp ?? 'generic'}/${scan.banner.how} controls=${scan.banner.controls.length} boxes=${scan.banner.boxes.length}` : 'none'} frames=${scan.frames.length} ls=${scan.ls.length}`);

    if (name === 'onetrust') {
      // Click "Accept All Cookies" through the probe, then read the page again: the click listener must report it as a
      // script click (trusted=false), which is how TraceVeil tells its own taps from the person's.
      const accept = scan.banner.controls.find((c) => c.role === 'accept' && c.vis);
      const click = await page.evaluate(`${probe}('click', ${accept.i})`);
      const after = await page.evaluate(`${probe}('scan', null)`);
      after.url = `https://fixture.example/${file}`;
      writeFileSync(join(fixtures, 'onetrust-click.probe.json'), JSON.stringify({ click, after }, null, 2) + '\n');
      results.push(`onetrust click: ok=${click.ok} text=${click.text} clicks=${JSON.stringify(after.clicks.map((c) => [c.text, c.trusted]))}`);
    }
    await context.close();
  }
} finally {
  await browser.close();
}
console.log(results.join('\n'));

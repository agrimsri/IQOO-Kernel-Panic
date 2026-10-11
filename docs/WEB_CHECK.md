# Website check

TraceVeil can check a website as well as an Android app. The person types an address; TraceVeil opens the site in
**its own built-in browser** on the phone, runs the same three plans as a Full app audit (don't touch the banner,
Reject, Accept), and scores the result with the same rules and the same score.

Entry points: the Check tab's third view (**Live check · APK Guard · Website**) and Home › More ways to check ›
**A website**. Results open on the normal result screen and are saved in Reports with the address as their name.

## Why it can see more than an app check

An app check sees the checked app's DNS lookups through a local VPN; it never sees inside encrypted connections.
A website check needs no VPN and no interception: TraceVeil owns the browser, so Android hands it every request the
page asks it to make, **with the full address (http and https, path and query string)**, before the request goes out
unchanged. No other app's traffic is read, nothing is decrypted, and no certificate is installed.

## Method

Each plan, in this order: **Silent** (no tap, 25 s), **Reject** and **Accept** (the choice, then 15 s of watching):

1. TraceVeil empties its browser: `CookieManager.removeAllCookies`, `WebStorage.deleteAllData` (local and session
   storage, databases, service workers), the HTTP cache, history, form data and HTTP-auth logins. It then checks that
   no cookie and no storage origin is left. If it cannot confirm that, the check stops and nothing is saved.
   (Android's multi-profile browser API is in androidx.webkit, which this build does not include, so the single
   profile is cleared instead.)
2. It opens the address and records every request the page and its service workers make
   (`shouldInterceptRequest`, which returns null: the request is never changed, blocked or answered by TraceVeil).
3. Every second it runs TraceVeil's in-page probe (`core/src/main/resources/web/consent_probe.js`) in the top page.
   The probe finds the consent banner, reads its text, buttons (with sizes) and checkboxes, lists the page's storage
   keys with value lengths, reads first-party cookie metadata from the Cookie Store API (never values), reports the
   page's own Resource Timing types, and records clicks (a real tap is `isTrusted`; TraceVeil's own taps are not).
4. Reject/Accept: about 2.5 s after the banner appears, TraceVeil taps the plan's choice. For Reject, if no refusal
   is on the first screen, it opens the banner's settings once and looks for a refusal there. With "Let TraceVeil tap
   the banner" off, the person taps instead (up to 45 s), and TraceVeil records the tap.
5. It reads Android's cookie store for every host the page contacted: when the banner appears, just before the tap,
   2 s after it, and at the end (every second while waiting for a person's tap).
6. After the last plan it empties the browser again, then asks the phone's DNS resolver for the addresses of the
   contacted hosts (at most 120, 15 s in all) for the server map.

Consent banners are found by the consent tool's own elements (OneTrust, Cookiebot, Didomi, Quantcast Choice,
TrustArc, Usercentrics including its shadow root, Complianz, CookieYes, Osano) or, for other banners, by consent
wording plus a consent-like button (English and Hindi through `ConsentLexicon`, which gained common website wording
such as "Continue without agreeing" and "Accept necessary cookies" as refusals). A consent tool's frame from another
site (Sourcepoint and similar) is noticed, recorded as a consent screen, but cannot be read or tapped.

## What a website check records

Per plan, on `AuditSession.web` (`core/src/main/kotlin/com/dpdpxray/core/web/WebModel.kt`):

| Part | Fields |
|---|---|
| Requests | full URL (cut at 2048 characters), host, scheme, port, method, type (from the page's Resource Timing when it has one, otherwise inferred from headers and the file extension), main page or not, service worker or not, third-party or not, phase relative to the choice, tracker entity, the documented tracking path that decided it |
| Cookies | name, the hosts it is kept for, site, third-party or not, **value length and the first 12 hex characters of its SHA-256 hash (never the value)**, when it was first read and the reading before it that did not have it, whether it was written again with a new value, whether it was still there at the end, domain and expiry (first-party cookies the page can read only), tracker entity and why (tracker host or vendor-documented name) |
| Storage | localStorage and sessionStorage key names (top page) and value lengths |
| Banner | consent tool, how it was found, text lines, controls (text, role, button or link, shown or not, first or settings layer, size), checkboxes (label, ticked, can be changed) |
| Choice | screen shown, settings opened, Accept or Reject (with the button text), the banner coming back after Reject |

Each request is also a `NetEvent` on the session, so the timeline, the server map, "Which company or tracker?" and the
consent-phase logic work exactly as for apps.

## Checks that apply

Same check ids, severities and `ScoreCalculator` as app audits (`WebRules`, reached through `RulesEngine`):

| Check | Website meaning |
|---|---|
| V0 | Almost no requests seen (page did not load or refused the browser): inconclusive, no score |
| C1 | Tracker hosts (or documented tracking paths) contacted before any consent choice |
| NC | Trackers contacted and no consent banner found in any plan |
| C2 | Tracker hosts contacted after Reject |
| C3–C8 | The app's banner rules on the banner: pre-ticked boxes (enabled checkboxes only; always-on necessary boxes never count), refusal missing or only behind settings, and the wording checks |
| C15 | The banner names no privacy or cookie policy (Info) |
| W1 | Tracking cookies stored before consent (Info: the requests behind them are scored under C1) |
| W2 | Tracking cookies written, or written again, after Reject (Info: scored under C2) |

Not applicable: C9 (no child-facing declaration), C10 (no advertising identifier in a browser), C11 (no DNS replies),
C12 (no background step), C13 (no manifest), C14 (no Android permission dialogs), U1 (every plan starts empty), S1
(no app publisher). The dark-pattern signals (CCPA 2023) read the banner exactly like an app's consent screen
(interface interference by button size and button-versus-link, confirm shaming, forced action, false urgency,
double negatives, nagging if the banner comes back after Reject, and tap cost when Reject took more taps).

A cookie counts as set after the tap only when a cookie-store reading that started at most 1 s before the tap, or
later, did not have it. TraceVeil reads the store immediately before it taps, so in practice the window is the moment
between that reading and the tap.

## Trackers added for the web

Host entries added to `trackers.json`, each with `domainSource`, the vendor page read on 11 October 2026 that shows
the domain in use: Google Tag Manager (googletagmanager.com), LinkedIn Insight Tag (snap.licdn.com, ads.linkedin.com,
p.adsymptotic.com, linkedin.oribi.io, sjs.bizographics.com), Pinterest Tag (ct.pinterest.com), Snap Pixel
(tr.snapchat.com), Criteo, Taboola, Outbrain, Adobe Experience Cloud (demdex.net, omtrdc.net), Microsoft Invest
(Xandr, adnxs.com), The Trade Desk (adsrvr.org), Comscore (scorecardresearch.com), and hotjar.io for Hotjar. Google
Analytics, DoubleClick, connect.facebook.net, Hotjar and Microsoft Clarity were already in the catalogue.

`web/web_trackers.json` adds what a host cannot decide: Meta's `/tr` pixel on facebook.com and Pinterest's `/ct/` tag
on s.pinimg.com, and first-party cookie names documented by the vendors (`_ga`, `_ga_*`, `_gid`, `_gat`, `_gcl_au`,
`_gcl_aw`, `_fbp`, `_fbc`, `_clck`, `_clsk`, `_hjSessionUser_*`, `_hjSession_*`, `_ttp`, `AMP_*`, `mp_*_mixpanel`,
`ajs_anonymous_id`, `ajs_user_id`), each with its source and a short quote.

Not added, because no vendor page could be read to confirm the domain: Microsoft Advertising UET (bat.bing.com),
TikTok (analytics.tiktok.com), X (static.ads-twitter.com), Amazon Ads, Quantcast, Yandex Metrica, Snap's
sc-static.net, Pinterest's `_pin_unauth`. Requests to them show as "Unknown hosts", never as trackers.

## Privacy and network use

* Shown on the start screen before anything opens: "TraceVeil opens this site on your phone to check it; the site
  sees a normal visit. Nothing else is sent anywhere." and "Afterwards it asks this phone's DNS resolver for the
  addresses of the servers the site contacted, for the server map."
* Two new network paths, both listed on the trust screen (`TrustText.networkPaths`) and pinned by
  `NetworkCodeScanTest`: `webcheck/WebCheckBrowser.kt` (the only file that uses Android's browser component) and
  `webcheck/WebHostResolver.kt`. The controls inventory ("What TraceVeil uses") has an information row for it.
* Google Safe Browsing lookups and the browser's usage statistics are switched off in the manifest, so the browser
  sends nothing beyond what the site asks for. Plain http is never opened (TraceVeil uses only encrypted
  connections); an http address is checked as https and the screen says so.
* The browser refuses the page's camera, microphone, location, pop-up windows and dialogs, never opens other apps'
  links (intent:, tel:, market:), and never proceeds past a certificate error.
* Identifiers the page sets belong to TraceVeil's emptied browser, not to your own browser. Cookie values are never
  stored; URLs are stored in full because the full address is the evidence, and they can contain identifiers the
  site generated during the check. Saved checks stay on the phone until you share or export them.

## Limits

* TraceVeil's built-in browser is not your Chrome: it starts empty each plan, allows third-party cookies, and its user
  agent says it is an in-app browser, so some sites behave differently or detect it.
* Many consent tools show a banner only to visitors from some regions (for example the EU or UK). From India a site
  may show no banner at all; that is recorded as "no banner found", not as a pass.
* Banners inside another site's frame cannot be read or tapped, and taps inside them are not seen.
* WebSocket traffic and requests the browser makes for itself are not listed. The in-page probe runs in the top page
  only, and the page's own scripts could interfere with it.
* Cookie expiry is known only for first-party cookies the page can read; cookies scoped below the site's root path are
  not read. A request is an attempt to contact a host: TraceVeil sees the address, not the body or the reply.
* No sign-in unless you sign in during the check; the browser is emptied before each plan and afterwards.
* The developer-notice draft (C2) is worded for an app's DNS lookups and is not offered for website checks.

## Export and CI

A website check exports as a normal `traceveil.audit.v1` bundle with an optional top-level
`"target": {"kind": "web", "url": "https://..."}`, `meta.targetKind = "web"`, `app.package = "web:<host>"`, and a
`web` part on each session. App audits never write `target`, `targetKind` or `web`, so app exports and the CLI samples
keep their exact shape. `traceveil-ci check` re-evaluates website bundles with the same website rules (through
`RulesEngine`) and adds a website caveat line to its output.

## Tests

* `core/src/test/.../web/`: URL validation, site names, request phases, cookie classification and timing, the CMP
  fixtures, the website rules, the Markdown report and the export round trip.
* The CMP fixtures are synthetic HTML pages (`core/src/test/resources/web/fixtures/*.html`). Their `.probe.json`
  files are the probe's real output in headless desktop Chromium, regenerated with
  `node tools/webcheck/probe-fixtures.mjs` (needs `PUPPETEER_CORE` and a local Chrome; all network requests are
  blocked). Desktop Chromium runs the same engine as Android's browser component, but it is not the phone.
* `app/src/test/.../webcheck/WebCheckAppTest.kt`: texts in both languages, the disclosure, counters, the evidence
  card, tap classification, the JSON export, the report scope and the trust facts.
* `cli/src/test/.../WebBundleCheckTest.kt`: `traceveil-ci check` on a website bundle.

## Phone test steps (not yet done on a phone)

Use a phone with this build installed; no VPN or consent-watcher setup is needed for a website check.

1. Check tab › **Website** (or Home › More ways to check › A website). Type `www.gov.uk`, leave "Let TraceVeil tap
   the banner" on, tap **Check this website**. GOV.UK shows its own cookie banner to every visitor
   ("Accept additional cookies" / "Reject additional cookies"), so this exercises the wording-based detection.
   Watch the three runs (about 1 to 2 minutes): the step line, the counters and the page below. Expected: banner
   found in each run; Reject and Accept tapped; on the result, the "Website evidence" card shows per-run requests,
   cookies, storage keys and "Banner: found by its wording".
2. A major news site that uses a known consent tool (for example a site whose banner reads "Cookies Settings / Reject
   All / Accept All Cookies", the OneTrust layout). Check that the result names the tool ("Banner: OneTrust, found by
   its own element"), that the Reject run's choice line names the button, and look at C1, C2, W1 and W2 with their
   evidence lines (full addresses). If no banner appears from India, record that: regional banners are a known limit.
3. An Indian e-commerce site. Many show no consent banner; expect NC with C1 if trackers load, and note the "no banner
   found" lines. Then repeat one site with "Let TraceVeil tap the banner" off and tap Reject yourself; the choice line
   must say "You tapped".
4. On one result: Report › Export audit data (JSON); copy it to a laptop and run `traceveil-ci check <file>`. The
   output must say the result is the same as stored and include the website caveat line.
5. Trust screen: "Website check, only when you start one" and "Server addresses for the map, after a website check"
   appear under TraceVeil's own network use; Permissions & background lists "Website check browser" as information.

Record the build hash, the sites, the date and what each result showed in docs/VERIFICATION.md; do not call a site
safe or compliant from a result.

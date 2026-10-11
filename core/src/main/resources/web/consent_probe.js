(function (mode, arg) {
  // TraceVeil website check: in-page probe. Runs only in TraceVeil's own built-in browser, in the top page, when TraceVeil
  // asks. It reads the consent banner (text, buttons, checkboxes, sizes), the page's own storage key names and value
  // lengths, first-party cookie metadata (never values), the page's Resource Timing types, and clicks on the page.
  // It sends nothing anywhere: the object it returns goes back to TraceVeil on the phone.
  // mode "scan": read the page. mode "click": click control number `arg` from the last scan.
  'use strict';
  var KEY = '__traceveilProbe';
  var MAX_TEXTS = 40, MAX_TEXT = 300, MAX_CONTROLS = 40, MAX_BOXES = 40, MAX_KEYS = 150, MAX_RES = 600;

  function clean(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }
  function cut(s, n) { s = clean(s); return s.length > n ? s.slice(0, n) : s; }
  function labelOf(el) {
    if (!el) return '';
    var t = el.innerText || el.textContent || '';
    if (!clean(t) && el.value != null && el.tagName === 'INPUT') t = el.value;
    if (!clean(t)) t = el.getAttribute('aria-label') || el.getAttribute('title') || '';
    return cut(t, 160);
  }

  var st = window[KEY];
  if (!st) {
    st = { clicks: [], controls: [], resIndex: 0, cs: null };
    try { Object.defineProperty(window, KEY, { value: st, enumerable: false }); } catch (e) { window[KEY] = st; }
    document.addEventListener('click', function (ev) {
      try {
        var path = ev.composedPath ? ev.composedPath() : [ev.target];
        var el = null;
        for (var i = 0; i < path.length && i < 12; i++) {
          var n = path[i];
          if (n && n.nodeType === 1 && n.matches && n.matches('button,a,[role="button"],input[type="button"],input[type="submit"],label')) { el = n; break; }
        }
        if (st.clicks.length < 40) st.clicks.push({ t: Date.now(), text: labelOf(el || ev.target), trusted: !!ev.isTrusted });
      } catch (e) { /* never break the page */ }
    }, true);
    try { if (performance.setResourceTimingBufferSize) performance.setResourceTimingBufferSize(5000); } catch (e) {}
  }

  function visible(el) {
    try {
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return false;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
      var n = el, depth = 0;
      while (n && n.nodeType === 1 && depth < 8) {
        if (parseFloat(getComputedStyle(n).opacity) < 0.05) return false;
        n = n.parentElement; depth++;
      }
      return true;
    } catch (e) { return false; }
  }

  function textLines(root) {
    var out = [], seen = {};
    var raw = '';
    try {
      if (root.innerText != null) raw = root.innerText;
      else { for (var i = 0; i < root.children.length; i++) raw += '\n' + (root.children[i].innerText || root.children[i].textContent || ''); }
    } catch (e) { raw = root.textContent || ''; }
    var parts = String(raw).split(/\n+/);
    for (var j = 0; j < parts.length && out.length < MAX_TEXTS; j++) {
      var t = cut(parts[j], MAX_TEXT);
      if (t && !seen[t]) { seen[t] = 1; out.push(t); }
    }
    return out;
  }

  var CMPS = [
    { name: 'OneTrust', root: '#onetrust-banner-sdk', layer2: '#onetrust-pc-sdk',
      roles: { accept: '#onetrust-accept-btn-handler, #accept-recommended-btn-handler', reject: '#onetrust-reject-all-handler, .ot-pc-refuse-all-handler',
        settings: '#onetrust-pc-btn-handler', save: '.save-preference-btn-handler', close: '.onetrust-close-btn-handler, #close-pc-btn-handler' } },
    { name: 'Cookiebot', root: '#CybotCookiebotDialog',
      roles: { accept: '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll, #CybotCookiebotDialogBodyButtonAccept',
        reject: '#CybotCookiebotDialogBodyButtonDecline',
        settings: '#CybotCookiebotDialogBodyButtonDetails, #CybotCookiebotDialogBodyLevelButtonCustomize, #CybotCookiebotDialogNavDetails',
        save: '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowallSelection' } },
    { name: 'Didomi', root: '#didomi-notice, #didomi-popup',
      roles: { accept: '#didomi-notice-agree-button', reject: '#didomi-notice-disagree-button, .didomi-continue-without-agreeing',
        settings: '#didomi-notice-learn-more-button' } },
    { name: 'Quantcast Choice', root: '#qc-cmp2-container, .qc-cmp2-container', roles: {} },
    { name: 'TrustArc', root: '#truste-consent-track, #truste-consent-content',
      roles: { accept: '#truste-consent-button', reject: '#truste-consent-required', settings: '#truste-show-consent' } },
    { name: 'Usercentrics', shadowHost: '#usercentrics-root, #usercentrics-cmp-ui',
      roles: { accept: '[data-testid="uc-accept-all-button"]', reject: '[data-testid="uc-deny-all-button"]', settings: '[data-testid="uc-more-button"]' } },
    { name: 'Complianz', root: '.cmplz-cookiebanner', roles: { accept: '.cmplz-accept', reject: '.cmplz-deny', settings: '.cmplz-view-preferences' } },
    { name: 'CookieYes', root: '.cky-consent-container', roles: { accept: '.cky-btn-accept', reject: '.cky-btn-reject', settings: '.cky-btn-customize' } },
    { name: 'Osano', root: '.osano-cm-dialog', roles: { accept: '.osano-cm-accept-all, .osano-cm-accept', reject: '.osano-cm-denyAll, .osano-cm-deny', settings: '.osano-cm-manage' } }
  ];

  var FRAME_CMPS = [
    { name: 'Sourcepoint', sel: 'iframe[id^="sp_message_iframe"]' },
    { name: 'TrustArc', sel: 'iframe[src*="trustarc.com"]' },
    { name: 'Quantcast Choice', sel: 'iframe[src*="quantcast"]' },
    { name: 'Consent frame', sel: 'iframe[id*="consent" i], iframe[title*="consent" i], iframe[title*="cookie" i], iframe[src*="consent" i]' }
  ];

  var CONTROL_SEL = 'button, a[href], [role="button"], input[type="button"], input[type="submit"]';
  var BOX_SEL = 'input[type="checkbox"], [role="switch"], [role="checkbox"]';
  var CUE = /cookie|consent|privacy|gdpr|personal data|tracking|partners|कुकी|सहमति|गोपनीयता|निजता|ಕುಕೀ|ಸಮ್ಮತಿ/i;
  var CHOICE = /accept|agree|allow|reject|decline|deny|refuse|manage|settings|preferences|customi[sz]e|necessary|essential|consent|got it|^ok|स्वीकार|अस्वीकार|सहमत|अनुमति/i;

  function all(scope, sel) { try { return Array.prototype.slice.call(scope.querySelectorAll(sel)); } catch (e) { return []; } }
  function first(sel) { try { return document.querySelector(sel); } catch (e) { return null; } }

  function roleFor(el, roles) {
    for (var k in roles) {
      if (!roles.hasOwnProperty(k)) continue;
      try { if (el.matches(roles[k]) || (el.closest && el.closest(roles[k]))) return k; } catch (e) {}
    }
    return null;
  }

  function boxLabel(el, scope) {
    var t = '';
    try {
      if (el.id) { var l = scope.querySelector('label[for="' + el.id.replace(/"/g, '') + '"]'); if (l) t = labelOf(l); }
      if (!t && el.closest) { var c = el.closest('label'); if (c) t = labelOf(c); }
      if (!t) t = el.getAttribute('aria-label') || '';
      if (!t && el.parentElement) t = labelOf(el.parentElement);
    } catch (e) {}
    return cut(t, 120);
  }

  function readBanner(name, how, root, layer2, roles) {
    var controls = [], boxes = [], texts = textLines(root);
    st.controls = [];
    var scopes = [[root, false]];
    if (layer2 && layer2 !== root) scopes.push([layer2, true]);
    for (var s = 0; s < scopes.length; s++) {
      var scope = scopes[s][0], second = scopes[s][1];
      var els = all(scope, CONTROL_SEL);
      for (var i = 0; i < els.length && controls.length < MAX_CONTROLS; i++) {
        var el = els[i];
        var text = labelOf(el);
        if (!text || text.length > 120) continue;
        var r = el.getBoundingClientRect();
        var tag = el.tagName.toLowerCase();
        var isButton = tag === 'button' || tag === 'input' || el.getAttribute('role') === 'button';
        st.controls.push(el);
        controls.push({ i: st.controls.length - 1, text: text, role: roleFor(el, roles || {}), btn: isButton, vis: visible(el), l2: second,
          x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) });
      }
      var bx = all(scope, BOX_SEL);
      for (var j = 0; j < bx.length && boxes.length < MAX_BOXES; j++) {
        var b = bx[j];
        var checked = b.checked === true || b.getAttribute('aria-checked') === 'true';
        var disabled = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
        boxes.push({ label: boxLabel(b, scope), checked: checked, disabled: disabled, vis: visible(b) || (b.parentElement ? visible(b.parentElement) : false), l2: second });
      }
    }
    return { cmp: name, how: how, texts: texts, controls: controls, boxes: boxes };
  }

  function findCmp() {
    for (var i = 0; i < CMPS.length; i++) {
      var c = CMPS[i], root = null;
      if (c.shadowHost) {
        var host = first(c.shadowHost);
        if (host && host.shadowRoot) root = host.shadowRoot;
        if (root) {
          var anyVisible = all(root, CONTROL_SEL).some(visible);
          if (!anyVisible) continue;
          return readBanner(c.name, 'cmp', root, null, c.roles);
        }
        continue;
      }
      root = first(c.root);
      if (root && visible(root)) {
        var layer2 = c.layer2 ? first(c.layer2) : null;
        return readBanner(c.name, 'cmp', root, layer2, c.roles);
      }
    }
    // A preference layer opened on its own (first layer already gone).
    var ot = first('#onetrust-pc-sdk');
    if (ot && visible(ot)) return readBanner('OneTrust', 'cmp', ot, null, CMPS[0].roles);
    return null;
  }

  function findGeneric() {
    var sel = '[id*="cookie" i],[class*="cookie" i],[id*="consent" i],[class*="consent" i],[id*="gdpr" i],[class*="gdpr" i],' +
      '[aria-label*="cookie" i],[aria-label*="consent" i],[role="dialog"],[role="alertdialog"],[aria-modal="true"],dialog[open]';
    var cands = all(document, sel).slice(0, 400);
    var kids = document.body ? document.body.children : [];
    for (var k = 0; k < kids.length && k < 80; k++) {
      try { var p = getComputedStyle(kids[k]).position; if (p === 'fixed' || p === 'sticky') cands.push(kids[k]); } catch (e) {}
    }
    var ok = [];
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el === document.body || el === document.documentElement || ok.indexOf(el) >= 0 || !visible(el)) continue;
      var text = '';
      try { text = el.innerText || ''; } catch (e) {}
      if (!text || text.length > 6000 || !CUE.test(text)) continue;
      var ctrls = all(el, CONTROL_SEL).filter(function (c) { var t = labelOf(c); return t && t.length <= 80 && CHOICE.test(t) && visible(c); });
      if (ctrls.length > 0) ok.push(el);
    }
    // The outermost matching element (the whole banner, not just its button row), the smallest if several.
    var outer = ok.filter(function (e) { return !ok.some(function (o) { return o !== e && o.contains(e); }); });
    var best = null, bestArea = Infinity;
    for (var m = 0; m < outer.length; m++) {
      var rr = outer[m].getBoundingClientRect(), area = rr.width * rr.height;
      if (area < bestArea) { best = outer[m]; bestArea = area; }
    }
    return best ? readBanner(null, 'generic', best, null, {}) : null;
  }

  function findFrames() {
    var out = [];
    for (var i = 0; i < FRAME_CMPS.length; i++) {
      var fs = all(document, FRAME_CMPS[i].sel);
      for (var j = 0; j < fs.length && out.length < 5; j++) {
        if (!visible(fs[j])) continue;
        var host = '';
        try { host = new URL(fs[j].src, location.href).hostname; } catch (e) {}
        out.push({ cmp: FRAME_CMPS[i].name, host: host, id: cut(fs[j].id || '', 80) });
      }
      if (out.length) break;
    }
    return out;
  }

  function keys(storage) {
    var out = [];
    try {
      for (var i = 0; i < storage.length && out.length < MAX_KEYS; i++) {
        var k = storage.key(i), v = storage.getItem(k);
        out.push({ k: String(k).slice(0, 100), n: v == null ? 0 : String(v).length });
      }
    } catch (e) {}
    return out;
  }

  function cookieMeta() {
    try {
      if (window.cookieStore && cookieStore.getAll) {
        cookieStore.getAll().then(function (list) {
          st.cs = list.slice(0, 200).map(function (c) {
            return { name: String(c.name).slice(0, 100), domain: c.domain || null, path: c.path || null, expires: typeof c.expires === 'number' ? c.expires : null };
          });
        }).catch(function () {});
      }
    } catch (e) {}
    return st.cs || [];
  }

  function resources() {
    var res = [];
    try {
      var ents = performance.getEntriesByType('resource');
      var j = st.resIndex;
      for (; j < ents.length && res.length < MAX_RES; j++) res.push([String(ents[j].name).slice(0, 2048), String(ents[j].initiatorType || '')]);
      st.resIndex = j;
    } catch (e) {}
    return res;
  }

  try {
    if (mode === 'click') {
      var el = st.controls[arg];
      if (!el || !el.isConnected) return { v: 1, ok: false, text: null };
      var text = labelOf(el);
      try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
      el.click();
      return { v: 1, ok: true, text: text };
    }
    var banner = findCmp() || findGeneric();
    var clicks = st.clicks.splice(0, st.clicks.length);
    return {
      v: 1,
      url: String(location.href).slice(0, 2048),
      banner: banner,
      frames: banner ? [] : findFrames(),
      vp: { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio || 1 },
      ls: keys(window.localStorage),
      ss: keys(window.sessionStorage),
      fpc: cookieMeta(),
      res: resources(),
      clicks: clicks
    };
  } catch (e) {
    return { v: 1, error: cut(String(e && e.message || e), 200) };
  }
})

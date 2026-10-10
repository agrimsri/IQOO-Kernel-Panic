/* TraceVeil interactive report. Self-contained: no network access, no libraries. Data is read from the page only. */
(function () {
  'use strict';
  var doc = document;
  var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  var PHASES = {
    BEFORE_CONSENT: { label: 'Before consent', color: '#FF8A3D', cls: 'ph-before' },
    AFTER_REJECT: { label: 'After Reject', color: '#FF5A4E', cls: 'ph-reject' },
    AFTER_ACCEPT: { label: 'After Accept', color: '#7FD3E6', cls: 'ph-accept' },
    NO_CONSENT_SEEN: { label: 'No consent screen seen', color: '#F2B544', cls: 'ph-none' }
  };
  var PHASE_ORDER = ['AFTER_REJECT', 'BEFORE_CONSENT', 'NO_CONSENT_SEEN', 'AFTER_ACCEPT'];
  var CAVEAT = 'A DNS lookup is an attempt to contact a host, not proof that personal data was sent.';

  function el(tag, cls, text) {
    var e = doc.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function phaseChip(code, count) {
    var p = PHASES[code] || { label: String(code), cls: '' };
    return el('span', 'ph ' + p.cls, count != null ? count + ' ' + p.label.toLowerCase() : p.label);
  }
  function webUrl(u) { return typeof u === 'string' && /^https?:\/\/\S+$/.test(u); }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  /* ------------------------------------------------------------ timeline tooltips */
  var tip = doc.getElementById('tv-tip');
  var lanesBox = doc.querySelector('.lanes');
  var dots = lanesBox ? lanesBox.querySelectorAll('.dot') : [];
  var pinned = null, hlHost = null;

  function tipRow(label, value) {
    var r = el('div', 't-row');
    r.appendChild(doc.createTextNode(label + ' '));
    r.appendChild(el('b', null, value));
    return r;
  }
  function fillTip(t) {
    var d = t.dataset;
    tip.textContent = '';
    if (d.h != null) {
      tip.appendChild(el('div', 't-host', d.h));
      var own = !d.e && (d.c || '').indexOf('First party') === 0;
      tip.appendChild(tipRow('Entity', d.e ? d.e : own ? 'none (marked as your own domain)' : 'unclassified (not in the tracker catalogue)'));
      tip.appendChild(tipRow('Category', d.c || 'Unknown'));
      tip.appendChild(tipRow('At', d.t + ' s'));
      var pr = el('div', 't-row');
      pr.appendChild(phaseChip(d.p));
      tip.appendChild(pr);
      if (d.a) tip.appendChild(el('div', 't-alias', 'Alias trail: ' + d.a));
      if (d.v) tip.appendChild(tipRow('Looked up via', d.v));
      tip.appendChild(el('div', 't-cav', CAVEAT));
    } else {
      tip.appendChild(el('div', 't-host', d.k || 'Consent event'));
      tip.appendChild(tipRow('At', d.t + ' s'));
      if (d.n) tip.appendChild(tipRow('On-screen text', '“' + d.n + '”'));
    }
  }
  function placeTip(x, y) {
    tip.hidden = false;
    var w = tip.offsetWidth, h = tip.offsetHeight, vw = window.innerWidth, vh = window.innerHeight;
    var left = x + 14, top = y + 14;
    if (left + w > vw - 8) left = Math.max(8, x - w - 14);
    if (top + h > vh - 8) top = Math.max(8, y - h - 14);
    tip.style.left = left + 'px';
    tip.style.top = top + 'px';
  }
  function highlight(host) {
    host = host == null ? null : host;
    if (host === hlHost || !lanesBox) return;
    hlHost = host;
    lanesBox.classList.toggle('hl-on', host !== null);
    for (var i = 0; i < dots.length; i++) dots[i].classList.toggle('hl', host !== null && dots[i].getAttribute('data-h') === host);
  }
  function showFor(t, x, y) { fillTip(t); placeTip(x, y); highlight(t.getAttribute('data-h')); }
  function hideTip() { if (tip) tip.hidden = true; highlight(null); }
  function tipTarget(node) {
    return node && node.closest ? node.closest('.lane-svg [data-h], .lane-svg [data-k]') : null;
  }

  if (lanesBox && tip) {
    lanesBox.addEventListener('pointerover', function (ev) {
      if (pinned) return;
      var t = tipTarget(ev.target);
      if (t) showFor(t, ev.clientX, ev.clientY);
    });
    lanesBox.addEventListener('pointermove', function (ev) {
      if (pinned || tip.hidden) return;
      if (tipTarget(ev.target)) placeTip(ev.clientX, ev.clientY);
    });
    lanesBox.addEventListener('pointerout', function (ev) {
      if (pinned) return;
      var t = tipTarget(ev.target);
      if (t && !(ev.relatedTarget && t.contains(ev.relatedTarget))) hideTip();
    });
    lanesBox.addEventListener('click', function (ev) {
      var t = tipTarget(ev.target);
      if (!t || pinned === t) { pinned = null; hideTip(); return; }
      pinned = t;
      showFor(t, ev.clientX, ev.clientY);
      ev.stopPropagation();
    });
    lanesBox.addEventListener('focusin', function (ev) {
      var t = tipTarget(ev.target);
      if (!t) return;
      var r = t.getBoundingClientRect();
      showFor(t, r.right, r.bottom);
    });
    lanesBox.addEventListener('focusout', function () { if (!pinned) hideTip(); });
    doc.addEventListener('click', function (ev) { if (pinned && !tipTarget(ev.target)) { pinned = null; hideTip(); } });
    doc.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') { pinned = null; hideTip(); } });
    window.addEventListener('scroll', function () { if (!tip.hidden) { pinned = null; hideTip(); } }, { passive: true });
  }

  /* ------------------------------------------------------------ copy buttons */
  function selectNode(node) {
    var range = doc.createRange();
    range.selectNodeContents(node);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }
  function copyText(text, done) {
    function fallback() {
      var ta = el('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '0';
      ta.style.opacity = '0';
      doc.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = doc.execCommand('copy'); } catch (e) { ok = false; }
      doc.body.removeChild(ta);
      done(ok);
    }
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(function () { done(true); }, fallback);
    else fallback();
  }
  doc.addEventListener('click', function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest('button[data-copy]') : null;
    if (!b) return;
    var src = doc.getElementById(b.getAttribute('data-copy'));
    if (!src) return;
    copyText(src.textContent, function (ok) {
      b.textContent = ok ? 'Copied' : 'Selected: press Ctrl+C';
      b.classList.toggle('done', ok);
      if (!ok) selectNode(src);
      setTimeout(function () { b.textContent = 'Copy'; b.classList.remove('done'); }, 1800);
    });
  });

  /* ------------------------------------------------------------ network table filter */
  var q = doc.getElementById('net-q'), planSel = doc.getElementById('net-plan'), trk = doc.getElementById('net-trk');
  var body = doc.getElementById('net-body'), count = doc.getElementById('net-count');
  if (body && q && planSel && trk && count) {
    var rows = Array.prototype.slice.call(body.rows);
    var texts = rows.map(function (r) { return r.textContent.toLowerCase(); });
    var filter = function () {
      var needle = (q.value || '').trim().toLowerCase(), lane = planSel.value, only = trk.checked, shown = 0;
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        var ok = (!lane || r.getAttribute('data-lane') === lane) && (!only || r.getAttribute('data-trk') === '1') &&
          (!needle || texts[i].indexOf(needle) >= 0);
        r.hidden = !ok;
        if (ok) shown++;
      }
      count.textContent = shown === rows.length ? rows.length + ' rows' : shown + ' of ' + rows.length + ' rows';
    };
    q.addEventListener('input', filter);
    planSel.addEventListener('change', filter);
    trk.addEventListener('change', filter);
  }

  /* ------------------------------------------------------------ nav + print */
  var links = Array.prototype.slice.call(doc.querySelectorAll('.topbar nav a'));
  if ('IntersectionObserver' in window && links.length) {
    var byHash = {};
    links.forEach(function (a) { byHash[a.getAttribute('href').slice(1)] = a; });
    var navIo = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        links.forEach(function (a) { a.classList.remove('on'); });
        if (byHash[en.target.id]) byHash[en.target.id].classList.add('on');
      });
    }, { rootMargin: '-40% 0px -55% 0px' });
    Object.keys(byHash).forEach(function (id) { var s = doc.getElementById(id); if (s) navIo.observe(s); });
  }
  var reopened = [];
  window.addEventListener('beforeprint', function () {
    reopened = Array.prototype.filter.call(doc.querySelectorAll('details:not([open])'), function (d) { d.open = true; return true; });
  });
  window.addEventListener('afterprint', function () { reopened.forEach(function (d) { d.open = false; }); reopened = []; });

  /* ------------------------------------------------------------ evidence constellation */
  var graphEl = doc.getElementById('tv-graph'), canvas = doc.getElementById('cst');
  if (graphEl && canvas && canvas.getContext) {
    var data = null;
    try { data = JSON.parse(graphEl.textContent); } catch (e) { data = null; }
    if (data && data.app && data.nodes && data.edges) constellation(data, canvas);
  }

  function constellation(data, canvas) {
    var ctx = canvas.getContext('2d');
    var nodes = [{ id: 'app', k: 'a', label: data.app.label, pkg: data.app.pkg, n: data.app.lookups }];
    data.nodes.forEach(function (n) { if (n && (n.k === 'h' || n.k === 'e')) nodes.push(n); });
    var byId = {};
    nodes.forEach(function (n, i) { n.i = i; n.adj = []; byId[n.id] = n; });
    var edges = [], groups = {};
    data.edges.forEach(function (e) {
      var s = byId[e.s], t = byId[e.t];
      if (!s || !t) return;
      var edge = { s: s, t: t, ph: PHASES[e.ph] ? e.ph : null, n: e.n || 1, alias: !!e.alias };
      edges.push(edge);
      var key = s.id + '>' + t.id;
      (groups[key] = groups[key] || []).push(edge);
      if (s.adj.indexOf(t) < 0) { s.adj.push(t); t.adj.push(s); }
    });
    var springs = Object.keys(groups).map(function (key) {
      var g = groups[key];
      g.forEach(function (e, i) { e.off = i - (g.length - 1) / 2; });
      return { s: g[0].s, t: g[0].t, len: g[0].t.k === 'e' ? 0.34 : 1 };
    });
    var hostCount = nodes.filter(function (n) { return n.k === 'h'; }).length;
    var entCount = nodes.filter(function (n) { return n.k === 'e'; }).length;

    function mulberry(a) {
      return function () {
        a |= 0; a = a + 0x6D2B79F5 | 0;
        var t = Math.imul(a ^ a >>> 15, 1 | a);
        t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
        return ((t ^ t >>> 14) >>> 0) / 4294967296;
      };
    }
    function seed() {
      var h = 2166136261;
      nodes.forEach(function (n) {
        var s = String(n.label);
        for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
      });
      return h >>> 0;
    }

    /* Deterministic 3D force layout, computed once before the first frame (no settling animation). */
    (function layout() {
      var rnd = mulberry(seed()), N = nodes.length, i, j;
      nodes.forEach(function (n) {
        if (n.k === 'a') { n.x = n.y = n.z = 0; return; }
        var u = rnd() * 2 - 1, th = rnd() * Math.PI * 2, s = Math.sqrt(1 - u * u), r = n.k === 'e' ? 1.35 : 1;
        n.x = r * s * Math.cos(th); n.y = r * u * 0.45; n.z = r * s * Math.sin(th);
      });
      springs.forEach(function (sp) {
        if (sp.t.k !== 'e') return;
        var h = sp.s, e = sp.t;
        h.x = e.x * 0.8 + (rnd() - 0.5) * 0.3; h.y = e.y * 0.8 + (rnd() - 0.5) * 0.3; h.z = e.z * 0.8 + (rnd() - 0.5) * 0.3;
      });
      nodes.forEach(function (n) { n.vx = n.vy = n.vz = 0; });
      var ticks = N > 220 ? 140 : 280;
      for (var it = 0; it < ticks; it++) {
        var cool = 1 - it / ticks;
        for (i = 0; i < N; i++) {
          var p = nodes[i];
          for (j = i + 1; j < N; j++) {
            var o = nodes[j], dx = p.x - o.x, dy = p.y - o.y, dz = p.z - o.z, d2 = dx * dx + dy * dy + dz * dz + 0.0025;
            var f = 0.004 / (d2 * Math.sqrt(d2));
            dx *= f; dy *= f; dz *= f;
            p.vx += dx; p.vy += dy; p.vz += dz; o.vx -= dx; o.vy -= dy; o.vz -= dz;
          }
        }
        springs.forEach(function (sp) {
          var s = sp.s, t = sp.t, dx = t.x - s.x, dy = t.y - s.y, dz = t.z - s.z;
          var d = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1e-6, f = 0.08 * (d - sp.len) / d;
          dx *= f; dy *= f; dz *= f;
          s.vx += dx; s.vy += dy; s.vz += dz; t.vx -= dx; t.vy -= dy; t.vz -= dz;
        });
        var cap = 0.08 * cool + 0.004;
        nodes.forEach(function (n) {
          if (n.k === 'a') { n.x = n.y = n.z = n.vx = n.vy = n.vz = 0; return; }
          n.vx -= n.x * 0.01; n.vy -= n.y * 0.03; n.vz -= n.z * 0.01;
          var sp = Math.sqrt(n.vx * n.vx + n.vy * n.vy + n.vz * n.vz);
          if (sp > cap) { n.vx *= cap / sp; n.vy *= cap / sp; n.vz *= cap / sp; }
          n.x += n.vx; n.y += n.vy; n.z += n.vz;
          n.vx *= 0.6; n.vy *= 0.6; n.vz *= 0.6;
        });
      }
      var maxR = 0;
      nodes.forEach(function (n) { maxR = Math.max(maxR, Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z)); });
      if (maxR > 0) nodes.forEach(function (n) { n.x /= maxR; n.y /= maxR; n.z /= maxR; });
    })();

    var HOME = { yaw: 0.55, pitch: 0.5, zoom: 1 };
    var view = { yaw: HOME.yaw, pitch: HOME.pitch, zoom: HOME.zoom };
    var W = 0, H = 0, cx = 0, dpr = 1, spin = !reduceMotion, hover = null, focusNode = null, visible = true;
    var pausedUntil = 0, raf = 0, last = 0;
    var spinBtn = doc.getElementById('cst-spin'), pickSel = doc.getElementById('cst-pick'), info = doc.getElementById('cst-info');
    var infoDefault = info ? Array.prototype.map.call(info.childNodes, function (c) { return c.cloneNode(true); }) : [];
    if (spinBtn) spinBtn.setAttribute('aria-pressed', String(spin));

    function radius(n) {
      var base = n.k === 'a' ? 15 : n.k === 'e' ? Math.min(20, 6.5 + 2.2 * Math.sqrt(n.n)) : Math.min(16, 3.2 + 1.9 * Math.sqrt(n.n));
      return base * n.ss * Math.sqrt(view.zoom);
    }
    function depth(z) { return clamp(0.66 - z * 0.42, 0.26, 1); }
    function rgba(hex, a) {
      var v = parseInt(hex.slice(1), 16);
      return 'rgba(' + (v >> 16 & 255) + ',' + (v >> 8 & 255) + ',' + (v & 255) + ',' + a + ')';
    }
    function project() {
      var cy = Math.cos(view.yaw), sy = Math.sin(view.yaw), cp = Math.cos(view.pitch), sp = Math.sin(view.pitch);
      var R = Math.min(W * 0.46, H * 0.42) * view.zoom, D = 3.2;
      // On wide stages the legend sits top-left, so the scene shifts right to keep clear of it.
      cx = W / 2 + (W > 640 ? Math.min(90, W * 0.08) : 0);
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i], x = n.x * cy + n.z * sy, z = -n.x * sy + n.z * cy, y = n.y * cp - z * sp;
        z = n.y * sp + z * cp;
        var s = D / (D + z);
        n.sx = cx + x * s * R; n.sy = H / 2 + y * s * R; n.sz = z; n.ss = s;
      }
      return { cy: cy, sy: sy, cp: cp, sp: sp, R: R, D: D };
    }
    function lit() {
      var f = focusNode;
      if (!f || f.k === 'a') return null;
      var on = {}, ed = [];
      on[f.id] = true; on.app = true;
      if (f.k === 'h') {
        f.adj.forEach(function (m) { on[m.id] = true; });
        edges.forEach(function (e) { if (e.s === f || e.t === f) ed.push(e); });
      } else {
        f.adj.forEach(function (m) { on[m.id] = true; });
        edges.forEach(function (e) { if (e.s === f || e.t === f || (e.s.k === 'a' && f.adj.indexOf(e.t) >= 0)) ed.push(e); });
      }
      return { nodes: on, edges: ed };
    }
    function hexagon(x, y, r) {
      ctx.beginPath();
      for (var k = 0; k < 6; k++) {
        var a = Math.PI / 3 * k;
        if (k === 0) ctx.moveTo(x + r * Math.cos(a), y + r * Math.sin(a));
        else ctx.lineTo(x + r * Math.cos(a), y + r * Math.sin(a));
      }
      ctx.closePath();
    }
    function trunc(s, max) { s = String(s); return s.length > max ? s.slice(0, max - 1) + '…' : s; }

    function draw() {
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      if (W < 10 || H < 10) return;
      var pr = project(), L = lit(), i;

      // Equatorial guide ring (a depth cue, not data).
      ctx.globalAlpha = 0.5;
      ctx.strokeStyle = 'rgba(127,211,230,0.09)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (i = 0; i <= 72; i++) {
        var a = i / 72 * Math.PI * 2, gx = Math.cos(a) * 0.92, gz = Math.sin(a) * 0.92;
        var x = gx * pr.cy + gz * pr.sy, z = -gx * pr.sy + gz * pr.cy, y = -z * pr.sp;
        z = z * pr.cp;
        var s = pr.D / (pr.D + z), px = cx + x * s * pr.R, py = H / 2 + y * s * pr.R;
        if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
      }
      ctx.stroke();

      edges.sort(function (a, b) { return (b.s.sz + b.t.sz) - (a.s.sz + a.t.sz); });
      for (i = 0; i < edges.length; i++) {
        var e = edges[i], s1 = e.s, t1 = e.t;
        var on = !L || L.edges.indexOf(e) >= 0;
        var alpha = depth((s1.sz + t1.sz) / 2) * (L ? (on ? 1 : 0.06) : 0.85);
        var dx = t1.sx - s1.sx, dy = t1.sy - s1.sy, len = Math.sqrt(dx * dx + dy * dy) || 1;
        var ox = -dy / len * e.off * 3.4, oy = dx / len * e.off * 3.4;
        ctx.globalAlpha = alpha;
        if (e.ph) {
          var col = PHASES[e.ph].color, g = ctx.createLinearGradient(s1.sx, s1.sy, t1.sx, t1.sy);
          g.addColorStop(0, rgba(col, 0.12)); g.addColorStop(0.55, rgba(col, 0.7)); g.addColorStop(1, rgba(col, 1));
          ctx.strokeStyle = g;
          ctx.lineWidth = Math.min(4.2, 1 + Math.log(1 + e.n) * 0.8) * (s1.ss + t1.ss) / 2;
          ctx.setLineDash([]);
        } else {
          ctx.strokeStyle = e.alias ? '#FAA275' : 'rgba(147,174,191,0.75)';
          ctx.lineWidth = 1.2 * (s1.ss + t1.ss) / 2;
          ctx.setLineDash(e.alias ? [4, 3] : []);
        }
        ctx.beginPath();
        ctx.moveTo(s1.sx + ox, s1.sy + oy);
        ctx.lineTo(t1.sx + ox, t1.sy + oy);
        ctx.stroke();
      }
      ctx.setLineDash([]);

      var order = nodes.slice().sort(function (a, b) { return b.sz - a.sz; });
      for (i = 0; i < order.length; i++) {
        var n = order[i], r = radius(n);
        ctx.globalAlpha = depth(n.sz) * (L ? (L.nodes[n.id] ? 1 : 0.12) : 1);
        if (n.k === 'a') {
          var glow = ctx.createRadialGradient(n.sx, n.sy, r * 0.6, n.sx, n.sy, r * 3.2);
          glow.addColorStop(0, 'rgba(255,140,97,0.38)'); glow.addColorStop(1, 'rgba(255,140,97,0)');
          ctx.fillStyle = glow;
          ctx.beginPath(); ctx.arc(n.sx, n.sy, r * 3.2, 0, Math.PI * 2); ctx.fill();
          var core = ctx.createRadialGradient(n.sx - r * 0.35, n.sy - r * 0.35, r * 0.1, n.sx, n.sy, r);
          core.addColorStop(0, '#FAA275'); core.addColorStop(0.55, '#FF8C61'); core.addColorStop(1, '#CE6A85');
          ctx.fillStyle = core;
          ctx.beginPath(); ctx.arc(n.sx, n.sy, r, 0, Math.PI * 2); ctx.fill();
          ctx.strokeStyle = 'rgba(220,235,242,0.7)'; ctx.lineWidth = 1.5; ctx.stroke();
        } else if (n.k === 'e') {
          hexagon(n.sx, n.sy, r);
          ctx.fillStyle = '#172C3C'; ctx.fill();
          ctx.strokeStyle = n.trk ? '#7FD3E6' : '#93AEBF'; ctx.lineWidth = 2; ctx.stroke();
          ctx.fillStyle = n.trk ? '#7FD3E6' : '#93AEBF';
          ctx.beginPath(); ctx.arc(n.sx, n.sy, Math.max(1.5, r * 0.28), 0, Math.PI * 2); ctx.fill();
        } else if (n.entity || n.fp) {
          ctx.fillStyle = n.trk ? '#DCEBF2' : '#93AEBF';
          ctx.beginPath(); ctx.arc(n.sx, n.sy, r, 0, Math.PI * 2); ctx.fill();
          ctx.strokeStyle = '#10202E'; ctx.lineWidth = 1.5; ctx.stroke();
        } else {
          ctx.fillStyle = '#10202E';
          ctx.beginPath(); ctx.arc(n.sx, n.sy, r, 0, Math.PI * 2); ctx.fill();
          ctx.setLineDash([2.5, 2]); ctx.strokeStyle = '#93AEBF'; ctx.lineWidth = 1.4; ctx.stroke(); ctx.setLineDash([]);
        }
        if (n === hover || n === focusNode) {
          ctx.globalAlpha = 1;
          ctx.strokeStyle = '#7FD3E6'; ctx.lineWidth = 2;
          ctx.beginPath(); ctx.arc(n.sx, n.sy, r + 5, 0, Math.PI * 2); ctx.stroke();
        }
      }
      labels(L);
      if (hostCount === 0) {
        ctx.globalAlpha = 1;
        ctx.fillStyle = '#93AEBF'; ctx.font = '13px system-ui, sans-serif'; ctx.textAlign = 'center';
        ctx.fillText('No DNS lookups were recorded in this audit.', cx, H / 2 + 48);
        ctx.textAlign = 'left';
      }
      ctx.globalAlpha = 1;
    }

    function labels(L) {
      var placed = [];
      var small = hostCount <= 12;
      var topHosts = nodes.filter(function (n) { return n.k === 'h'; }).sort(function (a, b) { return b.n - a.n; }).slice(0, 5);
      var cands = nodes.filter(function (n) {
        if (n.k === 'a' || n === hover || n === focusNode) return true;
        if (L) return !!L.nodes[n.id];
        return n.k === 'e' || small || topHosts.indexOf(n) >= 0;
      });
      function rank(n) { return n.k === 'a' ? 0 : n === focusNode ? 1 : n === hover ? 2 : n.k === 'e' ? 3 : 4; }
      cands.sort(function (a, b) { return rank(a) - rank(b) || b.n - a.n; });
      cands.forEach(function (n) {
        var forced = rank(n) <= 2, r = radius(n), text, x, y;
        if (n.k === 'a') { ctx.font = '700 13px system-ui, -apple-system, "Segoe UI", sans-serif'; text = trunc(n.label, 40); }
        else if (n.k === 'e') { ctx.font = '600 12px system-ui, -apple-system, "Segoe UI", sans-serif'; text = trunc(n.label, 34); }
        else { ctx.font = '11px ui-monospace, "Cascadia Code", Consolas, monospace'; text = trunc(n.label, 36); }
        var w = ctx.measureText(text).width;
        if (n.k === 'a') { x = n.sx - w / 2; y = n.sy + r + 18; } else { x = n.sx + r + 6; y = n.sy + 4; }
        if (x + w > W - 6) x = n.sx - r - 6 - w;
        var box = { x: x - 2, y: y - 12, w: w + 4, h: 16 };
        if (!forced && placed.some(function (b) { return box.x < b.x + b.w && b.x < box.x + box.w && box.y < b.y + b.h && b.y < box.y + box.h; })) return;
        placed.push(box);
        ctx.globalAlpha = forced ? 1 : depth(n.sz);
        ctx.lineJoin = 'round'; ctx.lineWidth = 4; ctx.strokeStyle = 'rgba(11,24,35,0.92)';
        ctx.strokeText(text, x, y);
        ctx.fillStyle = n.k === 'h' ? '#93AEBF' : '#DCEBF2';
        if (n === focusNode || n === hover) ctx.fillStyle = '#FFFFFF';
        ctx.fillText(text, x, y);
      });
    }

    function frame(ts) {
      raf = 0;
      ts = ts || 0;
      var spinning = spin && visible && !doc.hidden && ts >= pausedUntil && !drag && !pinch;
      if (spinning && last) view.yaw += Math.min(ts - last, 50) * 0.00012;
      last = ts;
      draw();
      if (spin && visible && !doc.hidden) request();
    }
    function request() { if (!raf) raf = window.requestAnimationFrame(frame); }
    function resize() {
      var r = canvas.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = r.width; H = r.height;
      canvas.width = Math.max(1, Math.round(W * dpr));
      canvas.height = Math.max(1, Math.round(H * dpr));
      request();
    }
    function setZoom(z) { view.zoom = clamp(z, 0.45, 3.2); request(); }
    function pause() { pausedUntil = (window.performance ? performance.now() : 0) + 3500; }
    function local(ev) { var r = canvas.getBoundingClientRect(); return { x: ev.clientX - r.left, y: ev.clientY - r.top }; }
    function pick(x, y) {
      var best = null, bd = Infinity;
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i], r = radius(n) + 6, dx = n.sx - x, dy = n.sy - y, d = dx * dx + dy * dy;
        if (d < r * r && d < bd) { best = n; bd = d; }
      }
      return best;
    }

    var pointers = {}, drag = null, pinch = null, active = false;
    function dist(a, b) { return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)) || 1; }
    canvas.addEventListener('pointerdown', function (ev) {
      if (canvas.setPointerCapture) { try { canvas.setPointerCapture(ev.pointerId); } catch (e) { /* ignore */ } }
      pointers[ev.pointerId] = { x: ev.clientX, y: ev.clientY };
      var ids = Object.keys(pointers);
      if (ids.length === 1) drag = { x: ev.clientX, y: ev.clientY, moved: 0 };
      else if (ids.length === 2) { pinch = { d: dist(pointers[ids[0]], pointers[ids[1]]), zoom: view.zoom }; drag = null; }
      active = true;
      pause();
    });
    canvas.addEventListener('pointermove', function (ev) {
      var p = pointers[ev.pointerId];
      if (p) {
        p.x = ev.clientX; p.y = ev.clientY;
        var ids = Object.keys(pointers);
        if (pinch && ids.length >= 2) { setZoom(pinch.zoom * dist(pointers[ids[0]], pointers[ids[1]]) / pinch.d); pause(); return; }
        if (drag) {
          var dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
          drag.x = ev.clientX; drag.y = ev.clientY; drag.moved += Math.abs(dx) + Math.abs(dy);
          if (drag.moved > 4) {
            view.yaw += dx * 0.008;
            view.pitch = clamp(view.pitch + dy * 0.008, -1.35, 1.35);
            canvas.classList.add('drag');
            pause();
            request();
          }
          return;
        }
      }
      if (ev.pointerType === 'mouse') {
        var m = local(ev), n = pick(m.x, m.y);
        if (n !== hover) { hover = n; canvas.classList.toggle('pointing', !!n); request(); }
      }
    });
    function end(ev) {
      var tap = drag && drag.moved <= 4 && ev.type === 'pointerup';
      delete pointers[ev.pointerId];
      canvas.classList.remove('drag');
      if (Object.keys(pointers).length < 2) pinch = null;
      if (tap) { var m = local(ev); setFocus(pick(m.x, m.y)); }
      drag = null;
      request();
    }
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('pointerleave', function () { active = false; if (hover) { hover = null; canvas.classList.remove('pointing'); request(); } });
    canvas.addEventListener('wheel', function (ev) {
      if (!active && !ev.ctrlKey) return;
      ev.preventDefault();
      setZoom(view.zoom * Math.exp(-ev.deltaY * 0.0015));
      pause();
    }, { passive: false });
    canvas.addEventListener('keydown', function (ev) {
      var k = ev.key, used = true;
      if (k === 'ArrowLeft') view.yaw -= 0.12;
      else if (k === 'ArrowRight') view.yaw += 0.12;
      else if (k === 'ArrowUp') view.pitch = clamp(view.pitch - 0.12, -1.35, 1.35);
      else if (k === 'ArrowDown') view.pitch = clamp(view.pitch + 0.12, -1.35, 1.35);
      else if (k === '+' || k === '=') setZoom(view.zoom * 1.2);
      else if (k === '-' || k === '_') setZoom(view.zoom / 1.2);
      else if (k === 'Escape') setFocus(null);
      else used = false;
      if (used) { ev.preventDefault(); pause(); request(); }
    });

    function bind(id, fn) { var b = doc.getElementById(id); if (b) b.addEventListener('click', fn); }
    bind('cst-zin', function () { setZoom(view.zoom * 1.25); pause(); });
    bind('cst-zout', function () { setZoom(view.zoom / 1.25); pause(); });
    bind('cst-reset', function () { view.yaw = HOME.yaw; view.pitch = HOME.pitch; view.zoom = HOME.zoom; setFocus(null); request(); });
    bind('cst-spin', function () {
      spin = !spin;
      spinBtn.setAttribute('aria-pressed', String(spin));
      pausedUntil = 0; last = 0;
      request();
    });

    function kindRank(n) { return n.k === 'a' ? 0 : n.k === 'e' ? 1 : 2; }
    if (pickSel) {
      nodes.slice().sort(function (a, b) { return kindRank(a) - kindRank(b) || b.n - a.n || (a.label < b.label ? -1 : 1); }).forEach(function (n) {
        var kind = n.k === 'a' ? 'App' : n.k === 'e' ? 'Entity' : 'Host';
        var o = el('option', null, kind + ' · ' + trunc(n.label, 48) + ' (' + n.n + ')');
        o.value = n.id;
        pickSel.appendChild(o);
      });
      pickSel.addEventListener('change', function () { setFocus(byId[pickSel.value] || null); });
    }

    function setFocus(n) {
      focusNode = n || null;
      if (pickSel) pickSel.value = focusNode ? focusNode.id : '';
      renderInfo(focusNode);
      request();
    }
    function para(cls, text) { return el('p', cls, text); }
    function renderInfo(n) {
      if (!info) return;
      info.textContent = '';
      if (!n) { infoDefault.forEach(function (c) { info.appendChild(c.cloneNode(true)); }); return; }
      if (n.k === 'a') {
        info.appendChild(para('eyebrow', 'Audited app'));
        info.appendChild(para('ci-title', n.label));
        info.appendChild(para('ci-host', n.pkg));
        info.appendChild(para(null, n.n + (n.n === 1 ? ' lookup' : ' lookups') + ' to ' + hostCount + (hostCount === 1 ? ' host' : ' hosts') +
          '; ' + entCount + (entCount === 1 ? ' catalogued entity.' : ' catalogued entities.')));
        return;
      }
      if (n.k === 'h') {
        info.appendChild(para('eyebrow', 'Contacted host'));
        info.appendChild(para('ci-host', n.label));
        info.appendChild(para(null, n.n + (n.n === 1 ? ' lookup' : ' lookups') + ' · ' + n.cat));
        info.appendChild(para(null, n.entity ? 'Entity: ' + n.entity : n.fp ? 'Marked as your own domain (first party) in TraceVeil settings.' :
          'Entity: unclassified. This host is not in the tracker catalogue; that does not make it harmless.'));
        var ul = el('ul');
        PHASE_ORDER.forEach(function (p) {
          if (n.ph && n.ph[p]) { var li = el('li'); li.appendChild(phaseChip(p, n.ph[p])); ul.appendChild(li); }
        });
        info.appendChild(el('h4', null, 'Lookups by phase'));
        info.appendChild(ul);
        if (n.plans && n.plans.length) info.appendChild(para('dim small', 'Sessions: ' + n.plans.join(', ')));
        if (n.alias) n.alias.forEach(function (a) { info.appendChild(para('ci-alias', 'Alias trail: ' + a)); });
        info.appendChild(para('dim small', CAVEAT));
        return;
      }
      info.appendChild(para('eyebrow', n.trk ? 'Tracker entity' : 'Catalogued entity'));
      info.appendChild(para('ci-title', n.label));
      info.appendChild(para(null, n.cat + ' · ' + n.n + (n.n === 1 ? ' lookup' : ' lookups') + ' across ' + n.adj.length + (n.adj.length === 1 ? ' host' : ' hosts')));
      var hosts = el('ul');
      n.adj.slice().sort(function (a, b) { return b.n - a.n; }).forEach(function (h) { hosts.appendChild(el('li', 'mono small', h.label + ' (' + h.n + ')')); });
      info.appendChild(hosts);
      var hq = el('p', 'small');
      if (n.hq) {
        hq.appendChild(doc.createTextNode('Company headquarters (cited): ' + n.hq + '. This is not where data is stored or processed. '));
        if (webUrl(n.hqSrc)) {
          var a = el('a', null, 'Source');
          a.href = n.hqSrc; a.target = '_blank'; a.rel = 'noopener noreferrer';
          hq.appendChild(a);
        }
      } else {
        hq.appendChild(el('span', 'chip ev-unknown', 'Headquarters not established'));
      }
      info.appendChild(hq);
    }

    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) { visible = entries[0].isIntersecting; if (visible) request(); }).observe(canvas);
    }
    doc.addEventListener('visibilitychange', function () { if (!doc.hidden) { last = 0; request(); } });
    if (window.ResizeObserver) new ResizeObserver(resize).observe(canvas);
    else window.addEventListener('resize', resize);
    resize();
  }
})();

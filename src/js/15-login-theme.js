  /* ---------- 20. page colour for the system bars (92/145), remote CSS (73) ---------- */
  /* sign-in (343): tell Java the moment the page is signed in / the account changes, so the
   * cookies are written to disk right away instead of only when the app goes to the background */
  var loginKey = null, lastLoginCheck = 0;
  var checkLogin = guard('login', function () {
    var now = Date.now();
    if (now - lastLoginCheck < 3000) return;
    lastLoginCheck = now;
    if (!window.ytcfg || typeof ytcfg.get !== 'function') return;
    var li = ytcfg.get('LOGGED_IN');
    if (li !== true && li !== false) return;
    var key = li ? 'in|' + (ytcfg.get('DATASYNC_ID') || ytcfg.get('DELEGATED_SESSION_ID') || '') : 'out';
    if (loginKey !== null && key !== loginKey) call('loginChanged', !!li);
    if (loginKey !== key) call('loginState', !!li);   // (346) Java hides "watch later" while signed out
    loginKey = key;
    accountCheck(li ? String(ytcfg.get('DATASYNC_ID') || '') : '');
  });
  /* another account than last time (345): the home feed may still be the old account's cached copy,
   * so it is refreshed once. Only on home, never while playing, at most once a minute. */
  var ACCT_KEY = 'ytl_acct';
  function accountCheck(id) {
    if (!id) return;
    var prev = null;
    try { prev = localStorage.getItem(ACCT_KEY); if (prev !== id) localStorage.setItem(ACCT_KEY, id); } catch (e) { return; }
    if (!prev || prev === id || pageType() !== 'home' || wasRefresh || playingNow()) return;
    var last = 0;
    try { last = +(sessionStorage.getItem('ytl_acct_rl') || 0); sessionStorage.setItem('ytl_acct_rl', String(Date.now())); } catch (e) {}
    if (Date.now() - last < 60000) return;
    log('login', 'another account: home feed refreshed');
    refresh(true);
  }
  /* first time home is opened signed out (347): one bar that says what signing in brings */
  function loginHint() {
    if (pageType() !== 'home' || !loggedOut()) return;
    try { if (localStorage.getItem('ytl_login_hint')) return; localStorage.setItem('ytl_login_hint', '1'); } catch (e) { return; }
    call('loginHint');
  }
  var lastTheme = '', lastThemeCheck = 0;
  function rgbDark(c) {
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(c || '');
    if (!m || (m[4] !== undefined && +m[4] < 0.5)) return null;
    return (0.299 * m[1] + 0.587 * m[2] + 0.114 * m[3]) < 128;
  }
  /* Dark or light before anything is painted (v1.15): YouTube's PREF cookie (f6 bit 0x400 dark,
   * 0x80000 light), else the system setting; the app asks for the dark theme on first run. Only a
   * hint so pure black is there from the first frame; reportTheme decides from the page itself. */
  function darkHint() {
    try {
      var m = /(?:^|;\s*)PREF=([^;]*)/.exec(document.cookie || '');
      var f = m && /(?:^|&)f6=([0-9a-fA-F]+)/.exec(decodeURIComponent(m[1]));
      if (f) {
        var v = parseInt(f[1], 16);
        if (v & 0x80000) return false;
        if (v & 0x400) return true;
      }
      if (window.matchMedia) return !window.matchMedia('(prefers-color-scheme: light)').matches;
    } catch (e) {}
    return true;
  }
  function setDark(on) {
    var h = document.documentElement;
    if (!h) return;
    if (on) { if (!h.hasAttribute('data-ytl-dark')) h.setAttribute('data-ytl-dark', '1'); }
    else if (h.hasAttribute('data-ytl-dark')) h.removeAttribute('data-ytl-dark');
  }
  /* ---------- pure black (91, rebuilt in v1.15) ----------
   * m.youtube paints its surfaces through colour variables whose names are hashed per build (e.g.
   * --t3e41d7b17b187f69:#0f0f0f), and a few bars with fixed colours. Each stylesheet is read once (only
   * :root / html rules for variables, every rule's background-color for the fixed ones); whatever holds a
   * dark surface colour is set to #000 under html[data-ytl-amoled][data-ytl-dark]. Idle time, once per
   * sheet, a few ms; nothing runs with AMOLED off. */
  var AMOLED_DARK = { '#000': 1, '#030303': 1, '#0f0f0f': 1, '#121212': 1, '#181818': 1, '#1f1f1f': 1, '#212121': 1, '#222': 1, '#222222': 1, '#272727': 1, '#282828': 1 };
  var amoledVarSeen = {}, amoledVars = [], amoledCand = [], amoledCandSeen = {}, amoledSelSeen = {}, amoledSels = [], amoledSheetCount = -1;
  function hex2(n) { n = (+n).toString(16); return n.length < 2 ? '0' + n : n; }
  function normColor(v) {
    v = String(v || '').trim().toLowerCase();
    var m = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*(1|1\.0+))?\s*\)$/.exec(v);
    if (m) return '#' + hex2(m[1]) + hex2(m[2]) + hex2(m[3]);
    return v;
  }
  function isDarkSurface(v) { var c = normColor(v); return c !== '#000' && c !== '#000000' && !!AMOLED_DARK[c]; }
  var ROOT_SEL_RE = /^(?::root|html)(?:[:.\[][^\s,>+~]*)?$/i;
  function scanRule(r, depth) {
    if (r.cssRules && depth < 2 && !r.selectorText) {   // @media / @supports: one level down
      for (var i = 0; i < r.cssRules.length; i++) scanRule(r.cssRules[i], depth + 1);
      return;
    }
    var sel = r.selectorText, st = r.style;
    if (!sel || !st) return;
    if (ROOT_SEL_RE.test(sel)) {
      // a candidate only: the same name may be dark text in a light block; the computed value decides
      for (var j = 0; j < st.length; j++) {
        var n = st[j];
        if (n.charCodeAt(0) !== 45 || n.charCodeAt(1) !== 45 || amoledCandSeen[n]) continue;
        if (isDarkSurface(st.getPropertyValue(n))) { amoledCandSeen[n] = 1; amoledCand.push(n); }
      }
      return;
    }
    if (amoledSels.length < 300 && !amoledSelSeen[sel] && isDarkSurface(st.getPropertyValue('background-color')) && sel.indexOf('ytl') < 0) {
      amoledSelSeen[sel] = 1;
      amoledSels.push(sel);
    }
  }
  var amoledScan = guard('amoled-scan', function () {
    if (!cfg.amoled || !document.styleSheets) return;
    var ss = document.styleSheets;
    if (ss.length === amoledSheetCount) return;
    var before = amoledVars.length + amoledSels.length, unread = false;
    for (var i = 0; i < ss.length; i++) {
      var sh = ss[i], rules = null;
      if (sh.__ytlScanned) continue;
      var own = sh.ownerNode && sh.ownerNode.id;
      if (own && own.indexOf('ytl-') === 0) { sh.__ytlScanned = true; continue; }
      try { rules = sh.cssRules; } catch (e) { sh.__ytlScanned = true; continue; }   // another origin: nothing to read
      if (!rules) { unread = true; continue; }                                         // still loading: next pass
      sh.__ytlScanned = true;
      for (var k = 0; k < rules.length; k++) scanRule(rules[k], 0);
    }
    amoledSheetCount = unread ? -1 : ss.length;
    // the value that is really in effect on <html> (before our override reaches that name)
    if (amoledCand.length) {
      var cs = getComputedStyle(document.documentElement), rest = [];
      for (var c = 0; c < amoledCand.length; c++) {
        var name = amoledCand[c];
        if (amoledVarSeen[name]) continue;
        var val = cs.getPropertyValue(name);
        if (!val || isDarkSurface(val)) { amoledVarSeen[name] = 1; amoledVars.push(name); } else rest.push(name);
      }
      amoledCand = rest;
    }
    if (amoledVars.length + amoledSels.length !== before) writeAmoledCss();
  });
  function writeAmoledCss() {
    var P = 'html[data-ytl-amoled][data-ytl-dark]';
    var out = [];
    if (amoledVars.length) out.push(P + '{' + amoledVars.map(function (n) { return n + ':#000!important'; }).join(';') + '}');
    // one rule per selector: one that this engine can't parse only drops itself
    for (var i = 0; i < amoledSels.length; i++) {
      var parts = amoledSels[i].split(',').map(function (x) { x = x.trim(); return /^(?::root|html)\b/i.test(x) ? '' : P + ' ' + x; })
        .filter(function (x) { return x; });
      if (parts.length) out.push(parts.join(',') + '{background-color:#000!important}');
    }
    var el = document.getElementById('ytl-css-amoled');
    if (!el) {
      el = document.createElement('style');
      el.id = 'ytl-css-amoled';
      (document.head || document.documentElement).appendChild(el);
    }
    el.textContent = out.join('\n');
  }
  /* light / dark (257): YouTube marks dark mode with html[dark]; an attribute observer reports
   * changes, and getComputedStyle is only read when the attribute says nothing (no timer polling) */
  var themeKnown = false, themeMo = null, darkFromPage = false;
  function watchThemeAttr() {
    if (themeMo || !window.MutationObserver || !document.documentElement) return;
    themeMo = new MutationObserver(function () {
      reportTheme(true);
      setTimeout(function () { reportTheme(true); }, 300);   // the page may repaint the body a moment later
    });
    themeMo.observe(document.documentElement, { attributes: true, attributeFilter: ['dark'] });
  }
  var reportTheme = guard('theme', function (force) {
    watchThemeAttr();
    if (!force && themeKnown) return;   // known: only the attribute observer reports again
    var now = Date.now();
    if (!force && now - lastThemeCheck < 1500) return;
    lastThemeCheck = now;
    var h = document.documentElement, dark = null;
    if (h && h.hasAttribute('dark')) dark = true;
    // while our pure black paints the backgrounds they say nothing about the theme: the text colour does
    var forced = !!(h && h.hasAttribute('data-ytl-amoled') && h.hasAttribute('data-ytl-dark'));
    if (dark === null && !forced && document.body) dark = rgbDark(getComputedStyle(document.body).backgroundColor);
    if (dark === null && !forced && h) dark = rgbDark(getComputedStyle(h).backgroundColor);
    if (dark === null && h) {
      // plain black is the browser default before YouTube's CSS is in: no answer yet (its light text is #0f0f0f)
      var col = getComputedStyle(h).color || '';
      var tx = /^rgba?\(0,\s*0,\s*0(?:,\s*1)?\)$/.test(col) ? null : rgbDark(col);
      if (tx !== null) dark = !tx;
    }
    if (dark === null && forced && darkFromPage) dark = true;   // decided before; only our black hides it now
    if (dark === null) return;   // unknown: keep the app's dark default
    themeKnown = true;
    darkFromPage = true;
    setDark(dark);
    if (dark && cfg.amoled) amoledScan();
    var t = dark ? (cfg.amoled ? '#000000' : '#0f0f0f') : '#ffffff';
    if (t !== lastTheme) { lastTheme = t; call('pageTheme', t); }
  });
  var BROAD_RE = /^(?:\*|html|body|head|video|div|span|a|img|button|ytm-app|#movie_player|\.html5-video-player|ytm-watch|ytm-browse)$/i;
  function remoteSelectors() {
    var ok = [];
    for (var i = 0; i < remote.hide.length; i++) {
      var sel = remote.hide[i].trim();
      if (!sel || /[{}<>;@\\]/.test(sel) || BROAD_RE.test(sel) || !/[-.\[#]/.test(sel)) continue;
      try { document.querySelector(sel); ok.push(sel); } catch (e) {}
    }
    return ok;
  }
  var addRemoteCss = guard('remote-css', function () {
    if (!remote.hide.length || document.getElementById('ytl-css-remote')) return;
    var ok = remoteSelectors();
    if (!ok.length) return;
    var st = document.createElement('style');
    st.id = 'ytl-css-remote';
    st.textContent = ok.map(function (x) { return x + '{display:none!important}'; }).join('\n');
    (document.head || document.documentElement).appendChild(st);
  });


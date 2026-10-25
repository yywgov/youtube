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
  /* light / dark (257): YouTube marks dark mode with html[dark]; an attribute observer reports
   * changes, and getComputedStyle is only read when the attribute says nothing (no timer polling) */
  var themeKnown = false, themeMo = null;
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
    var dark = null;
    if (document.documentElement && document.documentElement.hasAttribute('dark')) dark = true;
    if (dark === null && document.body) dark = rgbDark(getComputedStyle(document.body).backgroundColor);
    if (dark === null && document.documentElement) dark = rgbDark(getComputedStyle(document.documentElement).backgroundColor);
    if (dark === null) return;   // unknown: keep the app's dark default
    themeKnown = true;
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


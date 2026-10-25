  /* ---------- 14. one throttled observer for the few DOM tasks left ----------
   * Watches the content root (ytm-app) and the top level of <body> for popups only (42).
   * Mutations only set a flag; the work runs at most every ~200 ms, in idle time, never reads
   * layout, and stops completely while the screen is off / app in background (54). */
  var FEEDBACK_RE = textRe('feedback');
  function textOf(el) { return (el.textContent || '').replace(/\s+/g, ' ').trim(); }
  function hideByText(sel, re) {
    var l = document.querySelectorAll(sel);
    for (var i = 0; i < l.length; i++) {
      var el = l[i], t = textOf(el);
      var hit = t.length > 0 && t.length < 40 && re.test(t);
      if (hit) { if (!el.hasAttribute('data-ytl-hide')) el.setAttribute('data-ytl-hide', ''); }
      else if (el.hasAttribute('data-ytl-hide')) el.removeAttribute('data-ytl-hide'); // recycled node
    }
  }
  var hideMenus = guard('menus', function () { hideByText(RULES.sel.menu, FEEDBACK_RE); });
  /** Screen-reader name of the gear (374): the app's display name, and the update dot said in words. */
  function gearLabel() { return (cfg.appName || '一维视频') + ' 设置' + (cfg.updateDot ? '，有新版本' : ''); }
  var ensureGear = guard('gear', function () {
    if (document.getElementById('ytl-gear')) return;
    var logo = document.querySelector('ytm-home-logo');
    if (!logo || !logo.parentNode) return;
    var b = document.createElement('button');
    b.id = 'ytl-gear';
    b.setAttribute('aria-label', gearLabel());
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('width', '22'); svg.setAttribute('height', '22');
    var path = document.createElementNS(NS, 'path');
    path.setAttribute('d', 'M19.14 12.94a7.07 7.07 0 000-1.88l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.6-.22l-2.39.96a7 7 0 00-1.63-.94l-.36-2.54A.5.5 0 0013.9 2.4h-3.84a.5.5 0 00-.49.42l-.36 2.54a7 7 0 00-1.63.94l-2.39-.96a.5.5 0 00-.6.22L2.67 8.88a.5.5 0 00.12.64l2.03 1.58a7.07 7.07 0 000 1.88l-2.03 1.58a.5.5 0 00-.12.64l1.92 3.32a.5.5 0 00.6.22l2.39-.96c.5.38 1.05.7 1.63.94l.36 2.54a.5.5 0 00.49.42h3.84a.5.5 0 00.49-.42l.36-2.54a7 7 0 001.63-.94l2.39.96a.5.5 0 00.6-.22l1.92-3.32a.5.5 0 00-.12-.64zM12 15.5A3.5 3.5 0 1115.5 12 3.5 3.5 0 0112 15.5z');
    svg.appendChild(path);
    b.appendChild(svg);
    b.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); call('openSettings'); }, true);
    logo.parentNode.insertBefore(b, logo.nextSibling);
  });
  // moon button: native playback tools sheet (sleep, black screen, touch lock, speed, loop)
  var ensureTools = guard('tools', function () {
    if (document.getElementById('ytl-tools')) return;
    var gear = document.getElementById('ytl-gear');
    if (!gear || !gear.parentNode || !B || typeof B.openTools !== 'function') return;
    var b = document.createElement('button');
    b.id = 'ytl-tools';
    b.setAttribute('aria-label', '播放工具与睡眠定时');
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('width', '22'); svg.setAttribute('height', '22');
    var path = document.createElementNS(NS, 'path');
    path.setAttribute('d', 'M9.27 4.49A9 9 0 1019.51 14.73 7.75 7.75 0 019.27 4.49z');
    svg.appendChild(path);
    b.appendChild(svg);
    b.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); call('openTools'); }, true);
    gear.parentNode.insertBefore(b, gear.nextSibling);
  });
  /** The moon button shows with a loaded video (also in the mini player) or a running timer (363). */
  function updateToolsBtn() {
    var b = document.getElementById('ytl-tools');
    if (!b) return;
    var want = !!(curId || isWatch() || sleeping || badgeText);
    if (want === !b.hasAttribute('data-ytl-off')) return;
    if (want) b.removeAttribute('data-ytl-off'); else b.setAttribute('data-ytl-off', '');
  }
  // autoplay on by default; off while a "what plays next" rule or a loop decides (21/22/152)
  var forceAutonav = guard('autonav', function () {
    var want = wantAutonav();
    var key = curId + '|' + want;
    if (autonavFor === key) return;
    var tg = document.querySelector(RULES.sel.autonav);
    if (tg) {
      autonavFor = key;
      var pressed = tg.getAttribute('aria-pressed');
      if ((want && pressed === 'false') || (!want && pressed === 'true')) tg.click();
    }
  });
  /* incremental pass (256): mutations collect the cards and subtrees they touched; the list tasks
   * (filters, watched, thumbnails) then only look at those. A full pass still runs on navigation,
   * after a settings change, every 5 s at most, and always when the smoothness switch is off. */
  var dirtyItems = [], dirtyRoots = [], fullNeeded = true, lastFull = 0, lastRouteKey = '';
  var FULL_EVERY = 5000, DIRTY_MAX = 300;
  function noteDirtyItem(it) {
    if (!it || it.__ytlQ) return;
    it.__ytlQ = true;
    dirtyItems.push(it);
  }
  function collectDirty(recs) {
    if (fullNeeded) return;
    for (var i = 0; i < recs.length && !fullNeeded; i++) {
      var rec = recs[i], t = rec.target;
      if (t && t.nodeType === 1 && t.closest) noteDirtyItem(t.closest(RULES.sel.feedItem));
      var add = rec.addedNodes;
      for (var j = 0; j < add.length; j++) {
        var n = add[j];
        if (n.nodeType !== 1) continue;
        if (dirtyRoots.length < DIRTY_MAX) dirtyRoots.push(n);
        if (n.matches && n.matches(RULES.sel.feedItem)) noteDirtyItem(n);
        else if (n.querySelectorAll) {
          var inner = n.querySelectorAll(RULES.sel.feedItem);
          for (var k = 0; k < inner.length; k++) noteDirtyItem(inner[k]);
        }
      }
      if (dirtyItems.length > DIRTY_MAX || dirtyRoots.length >= DIRTY_MAX) fullNeeded = true;
    }
  }
  function takeDirty() {
    var items = dirtyItems, roots = dirtyRoots;
    for (var i = 0; i < items.length; i++) items[i].__ytlQ = false;
    dirtyItems = []; dirtyRoots = [];
    return { items: items, roots: roots };
  }
  function domPass(forceFull) {
    var pt = pageType();
    var routeKey = location.pathname + location.search;
    var now = Date.now();
    var full = forceFull === true || fullNeeded || !cfg.perfCss || routeKey !== lastRouteKey || now - lastFull > FULL_EVERY;
    var dirty = takeDirty();
    if (full) { fullNeeded = false; lastFull = now; lastRouteKey = routeKey; }
    try { addCss(); applyPerfAttr(); } catch (e) { report('css', e); }
    try { watchPlayer(); if (player()) skipAd(); } catch (e) { report('player', e); }
    hideMenus();
    try { hideByText(RULES.sel.shortsText, /^shorts$/i); } catch (e) { report('shorts', e); }
    if (pt === 'home' || seenCount || (pt === 'watch' && cfg.hideWatchedRelated)) markWatched(full ? undefined : dirty.items);
    ensureGear();
    ensureTools();
    updateToolsBtn();
    if (pt === 'watch' || badgeText) updateBadge();
    if (pt === 'watch') {
      forceAutonav();
      try { checkPlayerError(); } catch (e) { report('player-error', e); }
    }
    if (cfg.perfCss) { try { trimThumbs(full ? undefined : dirty.roots); } catch (e) { report('thumbs', e); } }
    addRemoteCss();
    filterCards(full ? undefined : dirty.items);
    if (pt === 'home') { countNew(); if (full) checkEmptyFeed(); maybeLeakScan(); }   // (326: full passes only, ≤ 5 s late)
    else if (document.getElementById('ytl-empty')) checkEmptyFeed();
    if (pt === 'search') { markSearchExtras(); ensureChips(); }
    else if (document.getElementById('ytl-chips')) ensureChips();
    searchFocus();
    reportTheme(false);
    checkLogin();
    loginHint();
    try { checkObserverRoot(); } catch (e) { report('observer-root', e); }
  }
  var passQueued = false, menuQueued = false, dirty = false;
  var idle = window.requestIdleCallback
    ? function (f) { window.requestIdleCallback(f, { timeout: 300 }); }
    : function (f) { setTimeout(f, 16); };
  function queuePass() {
    if (bg) { dirty = true; return; }
    if (passQueued) return;
    passQueued = true;
    setTimeout(function () { idle(function () { passQueued = false; if (bg) { dirty = true; return; } domPass(); }); }, 200);
  }
  // menus / bottom sheets: hide feedback items in the next frame so they never flash
  var MENU_NODE_RE = /SHEET|MENU|DIALOG|POPUP|LIST-ITEM/;
  function queueMenus() {
    if (menuQueued) return;
    menuQueued = true;
    requestAnimationFrame(function () { menuQueued = false; hideMenus(); });
  }
  var mo = null, moRoot = null;
  function contentRoot() { return document.querySelector(RULES.sel.contentRoot) || document.body || document.documentElement; }
  /** Watch page (264): a batch that only touched comments / description panels needs no DOM pass. */
  function quietBatch(recs) {
    if (pageType() !== 'watch') return false;
    for (var i = 0; i < recs.length; i++) {
      var t = recs[i].target;
      if (!t || t.nodeType !== 1 || !t.closest || !t.closest(RULES.sel.quietZone)) return false;
    }
    return recs.length > 0;
  }
  var quietSkipped = 0;
  var onMutations = guard('observer', function (recs) {
    if (quietBatch(recs)) quietSkipped++;
    else { collectDirty(recs); queuePass(); }
    var body = document.body;
    for (var i = 0; i < recs.length; i++) {
      var rec = recs[i], add = rec.addedNodes;
      for (var j = 0; j < add.length; j++) {
        var n = add[j];
        if (n.nodeType !== 1) continue;
        // a new top-level popup container: watch inside it too
        if (rec.target === body && moRoot !== body && n !== moRoot && mo) mo.observe(n, { childList: true, subtree: true });
        if (!menuQueued && MENU_NODE_RE.test(n.nodeName)) queueMenus();
      }
    }
  });
  function connectObserver() {
    if (mo || bg || !document.documentElement) return;
    var root = contentRoot();
    mo = new MutationObserver(onMutations);
    moRoot = root;
    mo.observe(root, { childList: true, subtree: true });
    var body = document.body;
    if (body && root !== body && root !== document.documentElement) {
      mo.observe(body, { childList: true });
      for (var c = body.firstElementChild; c; c = c.nextElementSibling) {
        if (c !== root && !c.contains(root) && c.tagName !== 'SCRIPT') mo.observe(c, { childList: true, subtree: true });
      }
    }
  }
  function disconnectObserver() {
    if (mo) { mo.disconnect(); mo = null; moRoot = null; }
  }
  function checkObserverRoot() {
    if (!mo) return;
    var want = contentRoot();
    if (moRoot !== want || !moRoot.isConnected) { disconnectObserver(); connectObserver(); }
  }
  function setBg(b) {
    bg = b;
    applyQuality();
    if (bg) {
      disconnectObserver();
      hideSbPrompt();
    } else if (started) {
      connectObserver();
      dirty = false;
      fullNeeded = true;   // nothing was collected while the observer was off (256)
      queuePass();
    }
  }
  var started = false;
  var startObserver = guard('start', function () {
    started = true;
    domPass();          // one pass even in the background (fresh reload while screen off)
    connectObserver();  // no-op while in background
    selfCheck(false);
  });


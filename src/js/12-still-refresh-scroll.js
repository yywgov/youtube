  /* ---------- 11. "Video paused. Continue watching?" ---------- */
  var STILL_RE = textRe('still');
  var STILL_YES_RE = textRe('stillYes');
  var STILL_NO_RE = textRe('stillNo');
  var dismissStillWatching = guard('still', function (atEnd) {
    if (userPaused) return;
    var dlg = document.querySelector(RULES.sel.stillDialog);
    if (!dlg) {
      var list = document.querySelectorAll(RULES.sel.anyDialog);
      for (var i = 0; i < list.length && !dlg; i++) if (STILL_RE.test(list[i].textContent || '')) dlg = list[i];
    }
    if (!dlg) return;
    var btns = dlg.querySelectorAll('button, [role="button"]');
    if (!btns.length) return;
    // the "yes / continue" button by its text first (248); the last button only as a fallback
    var pickBtn = null, how = 'last';
    for (var b = 0; b < btns.length && !pickBtn; b++) {
      var label = (btns[b].getAttribute('aria-label') || '') + ' ' + textOf(btns[b]);
      if (STILL_YES_RE.test(label.trim())) { pickBtn = btns[b]; how = 'text'; }
    }
    if (!pickBtn) {
      for (var c = btns.length - 1; c >= 0 && !pickBtn; c--) {
        var l2 = (btns[c].getAttribute('aria-label') || '') + ' ' + textOf(btns[c]);
        if (!STILL_NO_RE.test(l2.trim())) pickBtn = btns[c];
      }
    }
    if (!pickBtn) return;
    log('still', 'dismissed by ' + how);
    pickBtn.click();
    // at the end of a video (446) the click itself lets YouTube move on; play() would restart the old one
    if (atEnd === true) return;
    setTimeout(function () { var v = video(); if (v && v.paused && !userPaused) play(); }, 300);
  });

  /* ---------- 12. user input, Shorts links, home-tab refresh ---------- */
  // Pull-to-refresh / tapping Home: throw away YouTube's client-side copies of the feed,
  // then Java reloads with the HTTP cache bypassed (LOAD_NO_CACHE + cache-busting param).
  // Cookies and localStorage are never touched (66): that is the sign-in and the settings.
  var refreshing = false;
  /** A video is playing in the mini player (or anywhere) right now. */
  function playingNow() {
    var v = video();
    return !!(v && !v.paused && !v.ended && !adNow());
  }
  function offline() {
    if (navigator.onLine === false) return true;
    try { if (B && typeof B.isOnline === 'function' && B.isOnline() === false) return true; } catch (e) {}
    return false;
  }
  var LIGHT_LAST = 'ytl_light_last', LIGHT_ZERO = 'ytl_light_zero';
  function lightZeros() { try { return Number(localStorage.getItem(LIGHT_ZERO)) || 0; } catch (e) { return 0; } }
  /** After a refresh (447): count light refreshes in a row that brought no new video. */
  function noteRefreshResult(n) {
    try {
      var wasLight = localStorage.getItem(LIGHT_LAST) === '1';
      localStorage.setItem(LIGHT_ZERO, String(wasLight && n === 0 ? lightZeros() + 1 : 0));
    } catch (e) {}
  }
  /** Returns false when it decided not to reload (offline / asking first). */
  function refresh(force) {
    if (refreshing) return true;
    // offline (318): keep what is on screen, clear nothing
    if (offline()) { call('toast', S('offline', '当前离线，已保留列表')); return false; }
    // something is playing (317): reloading would stop it, so ask first (Java shows the snack bar)
    if (force !== true && playingNow() && B && typeof B.confirmRefresh === 'function') { call('confirmRefresh'); return false; }
    refreshing = true;
    var done = false;
    var go = function () { if (done) return; done = true; refreshing = false; call('hardReload'); };
    var light = !!cfg.lightRefresh;   // (316, experimental): service worker and static caches stay
    // two light refreshes in a row brought nothing new (447): this one clears everything
    if (light && lightZeros() >= 2) { light = false; log('refresh', 'light refresh found nothing twice: full refresh'); }
    try { localStorage.setItem(LIGHT_LAST, light ? '1' : '0'); } catch (e) {}
    try {
      if (!light) sessionStorage.clear();
      else {
        // YouTube's own session keys go, this script's retry / state keys stay
        var sk = [];
        for (var si = 0; si < sessionStorage.length; si++) { var kk = sessionStorage.key(si); if (kk && kk.indexOf('ytl_') !== 0) sk.push(kk); }
        sk.forEach(function (k) { sessionStorage.removeItem(k); });
      }
    } catch (e) {}
    var jobs = [];
    try {
      if (window.indexedDB && indexedDB.databases) {
        jobs.push(indexedDB.databases().then(function (dbs) {
          (dbs || []).forEach(function (d) {
            if (d && d.name && /response-store/i.test(d.name)) { try { indexedDB.deleteDatabase(d.name); } catch (e) {} }
          });
        }));
      }
    } catch (e) {}
    try {
      if (!light && navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
        jobs.push(navigator.serviceWorker.getRegistrations().then(function (rs) {
          return Promise.all(rs.map(function (r) { return r.unregister(); }));
        }));
      }
    } catch (e) {}
    try {
      if (!light && window.caches && caches.keys) {
        jobs.push(caches.keys().then(function (ks) {
          return Promise.all(ks.map(function (k) { return caches.delete(k); }));
        }));
      }
    } catch (e) {}
    Promise.all(jobs).then(go, go);
    setTimeout(go, 800);
    return true;
  }
  window.ytl.refresh = refresh;

  document.addEventListener('touchstart', function () { lastInput = Date.now(); }, { capture: true, passive: true });
  on('click', 'click', function (e) {
    // the click that ends a long press opens the quick menu, not the video (123)
    if (Date.now() - lp.firedAt < 900) { e.preventDefault(); e.stopImmediatePropagation(); return; }
    lastInput = Date.now();
    var t = e.target;
    if (!t || !t.closest) return;
    var a = t.closest('a[href]');
    if (a) {
      var target = '';
      try { var au = new URL(a.href, location.href); if (/(^|\.)youtube\.com$/.test(au.hostname)) target = shortsTarget(au.pathname); } catch (x) {}
      if (target) {
        e.preventDefault();
        e.stopImmediatePropagation();
        location.assign(target);
        return;
      }
    }
    var v = video();
    if (v && t.closest(RULES.sel.playPause)) {
      // user toggled playback: remember intent
      userPaused = !v.paused;
    }
    if (pageType() !== 'home') return;
    var pivot = t.closest('ytm-pivot-bar-item-renderer');
    var isHome = pivot && pivot.parentElement && pivot.parentElement.querySelector('ytm-pivot-bar-item-renderer') === pivot;
    if (isHome || t.closest('ytm-home-logo')) {
      e.preventDefault();
      e.stopPropagation();
      // scrolled down: just back to the top (319); already at the top: refresh
      if ((window.scrollY || document.documentElement.scrollTop || 0) > 0) {
        try { window.scrollTo({ top: 0, behavior: 'smooth' }); } catch (x) { window.scrollTo(0, 0); }
        return;
      }
      refresh();
    }
  });

  // SPA navigation into Shorts (or a channel's Shorts tab): leave immediately.
  function checkRoute() {
    var t = shortsTarget(location.pathname);
    if (t) location.replace(t);
  }
  try {
    ['pushState', 'replaceState'].forEach(function (k) {
      var orig = history[k];
      history[k] = function () {
        if (k === 'pushState') { try { rememberScroll(); } catch (e) {} }
        var r = orig.apply(this, arguments);
        try { checkRoute(); } catch (e) {}
        if (!bg) queuePass();
        return r;
      };
    });
    window.addEventListener('popstate', guard('route', function () { checkRoute(); restoreScroll(); }));
  } catch (e) { report('history', e); }

  /* ---------- 13. scroll position for pull-to-refresh ---------- */
  var atTop = true, scrollQueued = false;
  function reportScroll() {
    scrollQueued = false;
    var top = (window.scrollY || document.documentElement.scrollTop || 0) <= 0;
    if (top !== atTop) { atTop = top; call('setAtTop', top); }
  }
  window.addEventListener('scroll', function () {
    if (!scrollQueued) { scrollQueued = true; requestAnimationFrame(reportScroll); }
  }, { passive: true });



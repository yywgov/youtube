  /* ---------- 17. back to a list keeps the scroll position (119) ---------- */
  var scrollMem = {}, restoreTimer = 0;
  function feedPage(pt) { return pt === 'feed' || pt === 'subs' || pt === 'history' || pt === 'library'; }
  function listPage(pt) { return pt === 'home' || feedPage(pt) || pt === 'playlist' || pt === 'search' || pt === 'channel'; }
  function urlKey() { return location.pathname + location.search; }
  var SCROLL_MEM_MAX = 20;   // last 20 lists only (266)
  function rememberScroll() {
    if (!cfg.keepScroll || !listPage(pageType())) return;
    var y = window.scrollY || 0, k = urlKey();
    delete scrollMem[k];             // re-added at the end: the oldest list goes first
    if (y > 200) scrollMem[k] = y;
    var keys = Object.keys(scrollMem);
    for (var i = 0; i < keys.length - SCROLL_MEM_MAX; i++) delete scrollMem[keys[i]];
  }
  var restoreRo = null;
  /* back to a list (119/266): scroll as soon as the page is tall enough. A ResizeObserver on the page
   * reacts the moment it grows; a slower poll stays as the fallback (a page whose root box never resizes). */
  var restoreScroll = guard('scroll-restore', function () {
    clearTimeout(restoreTimer);
    if (restoreRo) { restoreRo.disconnect(); restoreRo = null; }
    var y = scrollMem[urlKey()];
    if (!cfg.keepScroll || !y || !listPage(pageType())) return;
    var start = Date.now(), t0 = lastInput, done = false;
    function stop() {
      done = true;
      clearTimeout(restoreTimer);
      if (restoreRo) { restoreRo.disconnect(); restoreRo = null; }
    }
    function tryIt() {
      if (done) return true;
      if (lastInput !== t0 || (window.scrollY || 0) > 100) { stop(); return true; }   // touched / YouTube did it
      var h = document.documentElement.scrollHeight || 0;
      if (h >= y + (window.innerHeight || 0) * 0.5) { window.scrollTo(0, y); stop(); return true; }
      if (Date.now() - start >= 3000) { stop(); return true; }
      return false;
    }
    if (tryIt()) return;
    if (window.ResizeObserver) {
      try {
        restoreRo = new ResizeObserver(guard('scroll-ro', function () { tryIt(); }));
        restoreRo.observe(document.documentElement);
        if (document.body) restoreRo.observe(document.body);
      } catch (e) { restoreRo = null; }
    }
    (function poll() { if (!tryIt()) restoreTimer = setTimeout(guard('scroll-try', poll), restoreRo ? 300 : 120); })();
  });

  /* ---------- 18. long-press a video card: quick menu (123) ---------- */
  var lp = { timer: 0, x: 0, y: 0, item: null, firedAt: 0, press: 0, pressed: null };
  var menuItem = null;
  function unpress() {
    clearTimeout(lp.press);
    if (lp.pressed) { lp.pressed.removeAttribute('data-ytl-press'); lp.pressed = null; }
  }
  function lpCancel() { clearTimeout(lp.timer); lp.item = null; unpress(); }
  document.addEventListener('touchstart', function (e) {
    if (!cfg.longPress || !e.touches || e.touches.length !== 1) { lpCancel(); return; }
    var t = e.target;
    var it = t && t.closest && t.closest(RULES.sel.feedItem);
    if (!it || t.closest('button, input, textarea, ytm-menu-renderer')) { lpCancel(); return; }
    lp.item = it; lp.x = e.touches[0].clientX; lp.y = e.touches[0].clientY;
    clearTimeout(lp.timer);
    unpress();
    lp.timer = setTimeout(guard('longpress', fireLongPress), 520);
    // held for 150 ms: the card shows it is being pressed (368)
    lp.press = setTimeout(function () { if (lp.item === it && it.isConnected) { it.setAttribute('data-ytl-press', ''); lp.pressed = it; } }, 150);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!lp.item || !e.touches || !e.touches.length) return;
    if (Math.abs(e.touches[0].clientX - lp.x) > 10 || Math.abs(e.touches[0].clientY - lp.y) > 10) lpCancel();
  }, { capture: true, passive: true });
  document.addEventListener('touchend', lpCancel, { capture: true, passive: true });
  document.addEventListener('touchcancel', lpCancel, { capture: true, passive: true });
  function fireLongPress() {
    var it = lp.item;
    lp.item = null;
    unpress();
    if (!it || !it.isConnected) return;
    var info = cardInfo(it);
    if (!info.id) return;
    lp.firedAt = Date.now();
    menuItem = it;
    // channel link (322) so a block survives renames; watch-later list (331) gets fitting menu rows
    call('cardMenu', JSON.stringify({ id: info.id, title: info.title.slice(0, 120), channel: info.channel.slice(0, 80),
      key: (cardChannelKeys(it)[0] || '').slice(0, 60), wl: pageType() === 'playlist' && /[?&]list=WL(?:&|$)/.test(location.search) }));
  }
  document.addEventListener('contextmenu', function (e) { if (Date.now() - lp.firedAt < 1500) e.preventDefault(); }, true);
  function openCardMenu() {
    var b = menuItem && menuItem.isConnected && menuItem.querySelector(RULES.sel.cardMenuBtn);
    if (b) b.click();
  }

  /* ---------- 19. watch later without the hidden action row (100/123) ----------
   * Same request YouTube's own "Save to Watch later" sends; signed-in only. */
  function cookieVal(name) {
    var m = new RegExp('(?:^|; )' + name.replace(/[.$?*|{}()\[\]\\\/+^]/g, '\\$&') + '=([^;]*)').exec(document.cookie || '');
    return m ? decodeURIComponent(m[1]) : '';
  }
  function sha1Hex(str) {
    return crypto.subtle.digest('SHA-1', new TextEncoder().encode(str)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    });
  }
  function watchLater(id) {
    id = id || videoId();
    if (!/^[\w-]{6,20}$/.test(id || '')) return Promise.resolve(false);
    var yc = window.ytcfg && typeof ytcfg.get === 'function' ? ytcfg : null;
    var sap = cookieVal('SAPISID') || cookieVal('__Secure-3PAPISID');
    if (!yc || !sap || !window.crypto || !crypto.subtle || !yc.get('INNERTUBE_CONTEXT')) { call('toast', S('wlLogin', '登录 YouTube 后才能加入稍后观看')); return Promise.resolve(false); }
    var ts = Math.floor(Date.now() / 1000), origin = location.origin;
    return sha1Hex(ts + ' ' + sap + ' ' + origin).then(function (h) {
      return fetch('/youtubei/v1/browse/edit_playlist?prettyPrint=false', {
        method: 'POST', credentials: 'include',
        headers: {
          'Content-Type': 'application/json', 'Authorization': 'SAPISIDHASH ' + ts + '_' + h, 'X-Origin': origin,
          'X-Goog-AuthUser': String(yc.get('SESSION_INDEX') || 0),
          'X-Youtube-Client-Name': String(yc.get('INNERTUBE_CONTEXT_CLIENT_NAME') || 2),
          'X-Youtube-Client-Version': String(yc.get('INNERTUBE_CLIENT_VERSION') || '')
        },
        body: JSON.stringify({ context: yc.get('INNERTUBE_CONTEXT'), playlistId: 'WL', actions: [{ action: 'ACTION_ADD_VIDEO', addedVideoId: id }] })
      });
    }).then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
      .then(function (j) {
        var ok = !!(j && (j.status === 'STATUS_SUCCEEDED' || j.playlistEditResults));
        call(ok ? 'toast' : 'toastFail', ok ? S('wlOk', '已加入稍后观看') : S('wlFail', '加入稍后观看失败'));
        return ok;
      }, function (e) { log('watch-later', String(e && e.message || e)); call('toastFail', S('wlFail', '加入稍后观看失败')); return false; });
  }


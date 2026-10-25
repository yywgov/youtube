  /* ---------- 8. CSS (41): one stylesheet, injected once ---------- */
  function addCss() {
    if (document.getElementById('ytl-css')) return;
    var s = document.createElement('style');
    s.id = 'ytl-css';
    s.textContent = CSS;
    (document.head || document.documentElement).appendChild(s);
  }
  function setAttr(name, val) {
    var h = document.documentElement;
    if (!h) return;
    if (val) { if (h.getAttribute(name) !== val) h.setAttribute(name, val); }
    else if (h.hasAttribute(name)) h.removeAttribute(name);
  }
  var SUB_SIZES = { s: 1, l: 1, xl: 1 }, SUB_BGS = { half: 1, none: 1 };
  function applyPerfAttr() {
    var h = document.documentElement;
    if (!h) return;
    if (cfg.perfCss) { if (!h.hasAttribute('data-ytl-perf')) h.setAttribute('data-ytl-perf', ''); }
    else if (h.hasAttribute('data-ytl-perf')) h.removeAttribute('data-ytl-perf');
    setAttr('data-ytl-noendwall', cfg.noEndWall ? '1' : '');
    setAttr('data-ytl-subsize', SUB_SIZES[cfg.subSize] ? cfg.subSize : '');
    setAttr('data-ytl-subbg', SUB_BGS[cfg.subBg] ? cfg.subBg : '');
    setAttr('data-ytl-page', pageType());
    setAttr('data-ytl-amoled', cfg.amoled ? '1' : '');
    setAttr('data-ytl-compact', cfg.compact ? '1' : '');
    setAttr('data-ytl-cards', cfg.cardStyle ? '1' : '');
    setAttr('data-ytl-dimthumb', cfg.dimThumbs ? '1' : '');
    setAttr('data-ytl-nosearchx', cfg.hideSearchExtras ? '1' : '');
    setAttr('data-ytl-subsvideos', cfg.subsOnlyVideos ? '1' : '');
    setAttr('data-ytl-noshelves', cfg.hideShelves ? '1' : '');
    setAttr('data-ytl-lowpower', cfg.powerSave ? '1' : '');
    setAttr('data-ytl-update', cfg.updateDot ? '1' : '');
    setAttr('data-ytl-lowmotion', cfg.lowMotion ? '1' : '');
    setAttr('data-ytl-bigfont', cfg.fontLarge ? '1' : '');
    setAttr('data-ytl-nomix', cfg.hideSearchMix ? '1' : '');            // (333)
    setAttr('data-ytl-sleepmode', sleepMode ? '1' : '');                // (340)
    setAttr('data-ytl-landscape', cfg.landscape ? '1' : '');            // (387)
    var gear = document.getElementById('ytl-gear');
    if (gear && gear.getAttribute('aria-label') !== gearLabel()) gear.setAttribute('aria-label', gearLabel());
    var acc = /^#[0-9a-fA-F]{6}$/.test(cfg.accent || '') ? cfg.accent : '';
    setAttr('data-ytl-accent', acc ? '1' : '');
    if (acc && h.style.getPropertyValue('--ytl-accent-pick') !== acc) h.style.setProperty('--ytl-accent-pick', acc);
  }
  function applyCfg() {
    fullNeeded = true;   // settings changed: the next pass looks at every card (256)
    if (!cfg.sponsorBlock) { segments = []; hideSbPrompt(); }
    applyQuality();
    applyPerfAttr();
    if (document.documentElement) markWatched();
    autonavFor = '';
    if (started) {   // the helpers below are defined further down; first run is at start-up
      updateBadge();
      reportTheme(true);   // amoled may have changed the colour the bars should get
      if (isWatch()) forceAutonav();
      if (isWatch() && (cfg.normalize || audioFx.ctx)) applyNormalize(video());
    }
  }

  /* ---------- 8b. home feed: hide videos already watched (>= 80 %) ----------
   * Reads only the inline width of YouTube's red progress bar (no layout), runs inside
   * the throttled DOM pass, home page only. Partly watched videos stay so they can be resumed. */
  var SEEN_MIN = 80;   // default; the setting (62) can make it 50 or 95
  function seenMin() { var n = Number(cfg.seenMin); return n === 50 || n === 95 ? n : SEEN_MIN; }
  var seenCount = 0;
  function watchedPct(el) {
    var w = (el.style && el.style.width) || '';
    var m = /^([\d.]+)%$/.exec(w.trim());
    return m ? parseFloat(m[1]) : -1;
  }
  /** Where watched videos are hidden: home, and the related list under the player when chosen (327). */
  function watchedHere() {
    if (!cfg.hideWatched || filtersPaused) return false;
    var pt = pageType();
    return pt === 'home' || (pt === 'watch' && !!cfg.hideWatchedRelated);
  }
  var markWatched = guard('watched', function (items) {
    if (items) {   // incremental pass (256): only the cards that changed
      var homeOn = watchedHere();
      for (var x = 0; x < items.length; x++) {
        var it = items[x];
        if (!it.isConnected) continue;
        var w = homeOn && itemWatched(it);
        if (w) { if (!it.hasAttribute('data-ytl-seen')) it.setAttribute('data-ytl-seen', ''); seenCount = Math.max(seenCount, 1); }
        else if (it.hasAttribute('data-ytl-seen')) it.removeAttribute('data-ytl-seen');
      }
      return;
    }
    var hits = [], hitSet = new Set();   // (449) set lookups instead of indexOf on long feeds
    if (watchedHere()) {
      var bars = document.querySelectorAll(RULES.sel.watchedBar), min = seenMin();
      for (var i = 0; i < bars.length; i++) {
        if (watchedPct(bars[i]) < min) continue;
        var item = bars[i].closest(RULES.sel.feedItem);
        if (item && !hitSet.has(item)) { hitSet.add(item); hits.push(item); }
      }
    }
    if (seenCount || hits.length) {
      var old = document.querySelectorAll('[data-ytl-seen]');
      for (var j = 0; j < old.length; j++) if (!hitSet.has(old[j])) old[j].removeAttribute('data-ytl-seen');
    }
    for (var k = 0; k < hits.length; k++) if (!hits[k].hasAttribute('data-ytl-seen')) hits[k].setAttribute('data-ytl-seen', '');
    seenCount = hits.length;
  });

  try { addCss(); applyCfg(); } catch (e) { report('css-init', e); }

  /* thumbnails (47): never the maxres image in lists, hqdefault is what the card shows anyway */
  function trimThumbs(roots) {
    var l = [];
    if (roots) {
      for (var r = 0; r < roots.length; r++) {
        var n = roots[r];
        if (!n.isConnected) continue;
        if (n.matches && n.matches(RULES.sel.maxresImg)) l.push(n);
        else if (n.querySelectorAll) l = l.concat(Array.prototype.slice.call(n.querySelectorAll(RULES.sel.maxresImg)));
      }
    } else l = document.querySelectorAll(RULES.sel.maxresImg);
    for (var i = 0; i < l.length; i++) {
      var s = l[i].getAttribute('src') || '';
      if (/^https:\/\/i\d?\.ytimg\.com\/vi(?:_webp)?\//.test(s)) l[i].setAttribute('src', s.replace('/maxresdefault', '/hqdefault'));
    }
    asyncDecode(roots);
  }
  /* list pictures decode off the frame (270): decoding=async on images that have no say yet; loading is left to YouTube */
  function asyncDecode(roots) {
    var imgs = [];
    if (roots) {
      for (var r = 0; r < roots.length; r++) {
        var n = roots[r];
        if (!n.isConnected) continue;
        if (n.tagName === 'IMG') { if (!n.hasAttribute('decoding')) imgs.push(n); }
        else if (n.querySelectorAll) imgs = imgs.concat(Array.prototype.slice.call(n.querySelectorAll(RULES.sel.listImg)));
      }
    } else {
      var root = document.querySelector(RULES.sel.contentRoot);
      if (root) imgs = root.querySelectorAll(RULES.sel.listImg);
    }
    for (var i = 0; i < imgs.length; i++) imgs[i].setAttribute('decoding', 'async');
  }

  function trimCaches() {
    // memory pressure (52): drop only our own bookkeeping, never anything playback needs
    lenIds.forEach(function (id) { if (id !== videoId()) delete lenById[id]; });
    lenIds = lenIds.filter(function (id) { return !!lenById[id]; });
    adLogged = {};
    unknownSeen = {};
  }

  /* ---------- 9. share links without tracking (68) ---------- */
  var TRACK_PARAM_RE = /^(?:si|pp|feature|utm_[a-z_]+|fbclid|gclid)$/i;
  var YT_URL_RE = /https?:\/\/(?:[\w-]+\.)?(?:youtube\.com|youtu\.be)\/[^\s"'<>]*/g;
  function cleanUrl(u) {
    try {
      var x = new URL(u);
      var del = [];
      x.searchParams.forEach(function (v, k) { if (TRACK_PARAM_RE.test(k)) del.push(k); });
      if (!del.length) return u;
      del.forEach(function (k) { x.searchParams.delete(k); });
      return x.toString();
    } catch (e) { return u; }
  }
  function cleanText(t) {
    return (typeof t === 'string' && t.indexOf('youtu') >= 0) ? t.replace(YT_URL_RE, cleanUrl) : t;
  }
  try {
    var clip = navigator.clipboard;
    if (clip && clip.writeText) {
      var _wt = clip.writeText;
      clip.writeText = function (t) { return _wt.call(clip, cleanText(t)); };
    }
    if (navigator.share) {
      var _share = navigator.share;
      navigator.share = function (d) {
        try {
          if (d && typeof d === 'object') {
            d = Object.assign({}, d);
            if (d.url) d.url = cleanText(d.url);
            if (d.text) d.text = cleanText(d.text);
          }
        } catch (e) {}
        return _share.call(navigator, d);
      };
    }
  } catch (e) { report('clipboard', e); }
  document.addEventListener('copy', guard('copy', function (e) {
    var sel = '';
    var ae = document.activeElement;
    if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA') && typeof ae.selectionStart === 'number') {
      sel = String(ae.value || '').substring(ae.selectionStart, ae.selectionEnd);
    }
    if (!sel && window.getSelection) sel = String(window.getSelection() || '');
    if (!sel || !e.clipboardData) return;
    var c = cleanText(sel);
    if (c === sel) return;
    e.clipboardData.setData('text/plain', c);
    e.preventDefault();
  }), true);



/*
 * YT Lite injected script (bundled in the APK, never loaded from a CDN).
 * Based on ideas from YTPro by Prateek Chaubey (MIT) – rewritten lean:
 *  - no setInterval polling, one throttled MutationObserver on the content root only
 *  - injected once per document (document-start script, guarded)
 *  - one stylesheet built from one rule table does the hiding work, JS only does what CSS can't
 *  - every feature runs inside its own try/catch (guard) and reports to the local diag log
 */
(function () {
  'use strict';
  if (window.__ytl) return;
  if (window.top !== window) return;
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;

  /* Shorts are gone from this app: /shorts/ID always opens as a normal video. */
  var SHORTS_RE = /^\/shorts\/([A-Za-z0-9_-]{6,})/;
  var CH_SHORTS_RE = /^(\/(?:@[^\/]+|channel\/[^\/]+|c\/[^\/]+|user\/[^\/]+))\/shorts\/?$/;
  function shortsTarget(path) {
    var m = SHORTS_RE.exec(path || '');
    if (m) return '/watch?v=' + m[1];
    m = CH_SHORTS_RE.exec(path || '');
    if (m) return m[1] + '/videos';
    return '';
  }
  var early = shortsTarget(location.pathname);
  if (early) { location.replace(early); return; }

  window.__ytl = true;

  // cache-busting param added by a pull-to-refresh: drop it before YouTube reads the URL
  if (/[?&]ytlr=/.test(location.search)) {
    try {
      var u0 = new URL(location.href);
      u0.searchParams.delete('ytlr');
      history.replaceState(history.state, '', u0.pathname + u0.search + u0.hash);
    } catch (e) {}
  }

  var B = window.YTL || null; // Java bridge
  var cfg = { sponsorBlock: true, lowQualityBg: true, hideWatched: true, perfCss: true };
  try { if (B) cfg = Object.assign(cfg, JSON.parse(B.getConfig())); } catch (e) {}

  var bg = false;            // app in background / screen off (told by Java)
  var userPaused = false;    // pause requested by user / timer / noisy
  var lastInput = 0;         // last touch time
  var stopAtEnd = false;     // sleep timer "end of current video"
  var savedVolume = -1;
  var segments = [];         // SponsorBlock [[start,end,category],...]
  var segSkipped = {};       // segment index -> true once undone (never skip it again for this video)
  var segFor = '';
  var curId = '';
  var lastSkip = 0;
  var lastLact = 0;
  var stalled = false;
  var endTimer = 0;
  var autonavFor = '';
  var adMuted = false;
  var adRate = 0;
  // state Java still holds when this document is (re)loaded: sleep timer mode, background
  if (cfg.stopAtEnd) stopAtEnd = true;
  if (cfg.bg) bg = true;
  delete cfg.stopAtEnd; delete cfg.bg;

  /* ---------- 0. the rule table: every selector the script uses lives here ---------- */
  var RULES = {
    // display:none groups (one stylesheet). Selectors with :has() go in their own rule so an
    // engine without :has() support only drops those, never the plain ones.
    hide: {
      ads: [
        'ad-slot-renderer', 'ytm-ad-slot-renderer', 'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
        'ytm-promoted-video-renderer', 'ytm-promoted-item-renderer', 'ytm-compact-promoted-item-renderer', 'ytm-in-feed-ad-layout-renderer',
        'ytm-display-ad-renderer', 'ytm-companion-ad-renderer', 'ytm-action-companion-ad-renderer', 'ytm-companion-slot',
        'ytm-video-masthead-ad-v3-renderer', 'ytm-brand-video-singleton-renderer', 'ytm-brand-video-shelf-renderer',
        'ytm-primetime-promo-renderer', 'ytm-merch-shelf-renderer', 'ytm-product-list-renderer', 'ytm-shopping-carousel-renderer',
        'ytm-offer-module-renderer', 'ytm-ticket-shelf-renderer', 'ytm-ad-badge-renderer',
        '.ytp-ad-overlay-container', '.ytp-ad-text-overlay', '.ytp-ad-image-overlay', 'a[href^="intent:"]', 'ytm-open-app-promo-renderer',
        'ytm-rich-item-renderer:has(> ad-slot-renderer)', 'ytm-rich-item-renderer:has(> ytm-ad-slot-renderer)',
        'ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer:only-child)',
        'ytm-rich-item-renderer:has(ytm-ad-badge-renderer)', 'ytm-compact-video-renderer:has(ytm-ad-badge-renderer)'
      ],
      // "Includes paid promotion" strip (7)
      paid: ['ytm-paid-content-overlay-renderer', '.ytp-paid-content-overlay', '.ytmPaidContentOverlayHost', 'ytm-paid-content-overlay-view-model'],
      // YouTube Premium banners / trial prompts (12)
      premium: [
        'ytm-mealbar-promo-renderer', 'yt-mealbar-promo-renderer', 'ytm-statement-banner-renderer', 'ytm-banner-promo-renderer',
        'ytm-background-promo-renderer', 'ytm-upsell-dialog-renderer', 'yt-upsell-dialog-renderer',
        'ytm-popup-container:has(> ytm-mealbar-promo-renderer)', 'ytm-popup-container:has(> ytm-upsell-dialog-renderer)'
      ],
      // creator end screen elements + info cards inside the player (8); the autoplay countdown is kept
      endscreen: [
        '.html5-video-player .ytp-ce-element', '.html5-video-player .ytp-ce-covering-overlay', '.html5-video-player .ytp-ce-element-shadow',
        '.html5-video-player .ytp-ce-expanding-overlay', '.html5-video-player .ytp-cards-teaser', '.html5-video-player .ytp-cards-button',
        '.html5-video-player .ytp-cards-teaser-box', '.html5-video-player .iv-branding', '.html5-video-player .video-annotations',
        '.html5-video-player .ytp-suggested-action', '.html5-video-player .ytp-featured-product'
      ],
      // action button row under the video: like/dislike/share/save/remix/download/clip/thanks
      actionRow: ['ytm-slim-video-action-bar-renderer', '.slim-video-action-bar-actions', 'ytm-video-actions-bar', 'ytw-slim-video-action-bar-view-model'],
      shorts: [
        'ytm-reel-shelf-renderer', 'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2', 'ytm-reel-item-renderer',
        '.big-shorts-singleton', 'ytm-reel-video-renderer', 'a[role="tab"][href$="/shorts"]', '.scbrr-tab[href$="/shorts"]',
        'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
        'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)', 'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)',
        'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2)', 'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)',
        'grid-shelf-view-model:has(ytm-shorts-lockup-view-model-v2)',
        'ytm-rich-item-renderer:has(a[href^="/shorts/"])', 'ytm-video-with-context-renderer:has(a[href^="/shorts/"])',
        'ytm-compact-video-renderer:has(a[href^="/shorts/"])'
      ],
      help: [
        'ytm-multi-page-menu-renderer a[href*="support.google.com"]', 'ytm-multi-page-menu-renderer a[href*="feedback"]',
        'ytm-compact-link-renderer:has(a[href*="support.google.com"])', 'ytm-compact-link-renderer:has(a[href*="feedback"])',
        'ytm-compact-link-renderer:has(a[href*="reporthistory"])'
      ],
      own: ['[data-ytl-hide]', '[data-ytl-seen]']
    },
    // ad fallback: black cover over the picture while an ad still plays (4)
    adCover: ['#movie_player.ad-showing video', '#movie_player.ad-interrupting video', '#movie_player.ytl-ad video'],
    adCoverPlayer: ['#movie_player.ad-showing', '#movie_player.ad-interrupting', '#movie_player.ytl-ad'],
    // smoothness rules, active only while html[data-ytl-perf] is set (settings toggle)
    perf: {
      cards: ['ytm-rich-item-renderer', 'ytm-video-with-context-renderer'],             // 49/50
      compact: ['ytm-compact-video-renderer', 'ytm-playlist-video-renderer'],
      noAnim: ['[class*="skeleton"]', '[class*="Skeleton"]', '[class*="shimmer"]', '[class*="Shimmer"]'], // 51
      noRipple: ['yt-touch-feedback-shape', '.yt-spec-touch-feedback-shape', '.ytSpecTouchFeedbackShapeHost']
    },
    sel: {
      video: 'video.video-stream',
      adSkip: '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-container,' +
        ' .ytp-ad-skip-button-slot button, button[id^="skip-button"], .ytm-skip-ad-button, ytm-skip-ad-button button',
      watchedBar: '.ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment, .thumbnail-overlay-resume-playback-progress,' +
        ' ytm-thumbnail-overlay-resume-playback-renderer [style*="width"]',
      feedItem: 'ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer',
      menu: 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, ytm-menu-item, ytm-compact-link-renderer,' +
        ' [role="menuitem"], .menu-item, ytm-bottom-sheet-renderer yt-list-item-view-model,' +
        ' bottom-sheet-container yt-list-item-view-model, ytm-multi-page-menu-renderer yt-list-item-view-model,' +
        ' ytm-account-item-renderer, ytm-setting-generic-category',
      shortsText: 'ytm-pivot-bar-item-renderer, [role="tab"], .scbrr-tab, yt-tab-shape, ytm-chip-cloud-chip-renderer,' +
        ' yt-chip-cloud-chip-renderer, chip-shape',
      stillDialog: 'ytm-confirm-dialog-renderer, yt-confirm-dialog-renderer',
      anyDialog: 'ytm-dialog, tp-yt-paper-dialog, [role="dialog"], .dialog-container',
      autonav: '.ytm-autonav-toggle-button-container',
      playerError: '.ytp-error, ytm-player-error-message-renderer',
      playPause: '.player-control-play-pause-icon, .ytp-play-button, button[aria-label*="ause"]',
      contentRoot: 'ytm-app',
      maxresImg: 'img[src*="/maxresdefault"]'
    }
  };

  function sheet() {
    var out = [];
    function rule(list, body) {
      var plain = [], has = [];
      for (var i = 0; i < list.length; i++) (list[i].indexOf(':has(') >= 0 ? has : plain).push(list[i]);
      if (plain.length) out.push(plain.join(',\n') + '{' + body + '}');
      for (var j = 0; j < has.length; j++) out.push(has[j] + '{' + body + '}');
    }
    function pre(list) { return list.map(function (s) { return 'html[data-ytl-perf] ' + s; }); }
    var H = RULES.hide;
    for (var k in H) if (H.hasOwnProperty(k)) rule(H[k], 'display:none!important');
    rule(RULES.adCover, 'opacity:0!important');
    rule(RULES.adCoverPlayer, 'background:#000!important');
    rule(pre(RULES.perf.cards), 'content-visibility:auto;contain-intrinsic-size:auto 360px;contain:layout style');
    rule(pre(RULES.perf.compact), 'content-visibility:auto;contain-intrinsic-size:auto 110px;contain:layout style');
    rule(pre(RULES.perf.noAnim), 'animation:none!important;transition:none!important');
    rule(pre(RULES.perf.noRipple), 'display:none!important');
    out.push('#ytl-gear{display:flex;align-items:center;justify-content:center;width:40px;height:40px;background:none;border:0;padding:0;margin:0 2px;color:inherit;fill:currentColor}');
    out.push('#ytl-sb{position:fixed;left:50%;bottom:76px;transform:translateX(-50%);z-index:2147483646;display:flex;align-items:center;gap:14px;' +
      'background:rgba(33,33,33,.96);color:#fff;font:14px/20px Roboto,Arial,sans-serif;padding:10px 14px 10px 16px;border-radius:10px;' +
      'box-shadow:0 2px 10px rgba(0,0,0,.5);max-width:90vw;white-space:nowrap}' +
      '#ytl-sb button{background:none;border:0;color:#3ea6ff;font:500 14px Roboto,Arial,sans-serif;padding:4px 6px;margin:0}');
    return out.join('\n');
  }
  var CSS = sheet();

  /* ---------- helpers, logging and per-module guards (74/75/76) ---------- */
  var errCount = {};
  function call(fn) {
    if (!B || typeof B[fn] !== 'function') return;
    try { B[fn].apply(B, Array.prototype.slice.call(arguments, 1)); } catch (e) {}
  }
  function log(tag, msg) { call('log', String(tag), String(msg).slice(0, 400)); }
  function report(name, e) {
    var n = errCount[name] = (errCount[name] || 0) + 1;
    if (n <= 3) log('js', name + ': ' + String((e && (e.stack || e.message)) || e).slice(0, 300));
  }
  function guard(name, fn) {
    return function () {
      try { return fn.apply(this, arguments); } catch (e) { report(name, e); }
    };
  }
  function video() { return document.querySelector(RULES.sel.video) || document.querySelector('video'); }
  function player() { return document.getElementById('movie_player'); }
  function videoId() {
    var p = location.pathname;
    if (p.indexOf('/shorts/') === 0) return p.substr(8).split('/')[0];
    if (p === '/watch') return new URLSearchParams(location.search).get('v') || '';
    // miniplayer while browsing: ask the player itself
    try {
      var pl = player();
      var d = pl && pl.getVideoData && pl.getVideoData();
      if (d && d.video_id) return d.video_id;
    } catch (e) {}
    return '';
  }
  /* page partition (53): each feature only runs on the page types it is for */
  function pageType(p) {
    p = p == null ? location.pathname : p;
    if (p === '/watch' || p.indexOf('/live/') === 0) return 'watch';
    if (p === '/' || p === '') return 'home';
    if (p === '/results') return 'search';
    if (p.indexOf('/feed/') === 0) return 'feed';
    if (/^\/(?:@|channel\/|c\/|user\/)/.test(p)) return 'channel';
    return 'other';
  }
  function isWatch() { return pageType() === 'watch'; }
  function adShowing() {
    var p = player();
    return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
  }
  // navigation indirection so tests can observe reloads
  var nav = {
    replace: function (u) { location.replace(u); },
    reload: function () { location.reload(); }
  };

  /* ---------- 1. keep playing in background: visibility spoof ---------- */
  try {
    var dp = Document.prototype;
    Object.defineProperty(dp, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(dp, 'webkitHidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(dp, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    Object.defineProperty(dp, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
    var block = function (e) { e.stopImmediatePropagation(); };
    document.addEventListener('visibilitychange', block, true);
    document.addEventListener('webkitvisibilitychange', block, true);
    window.addEventListener('visibilitychange', block, true);
  } catch (e) { report('visibility', e); }

  /* ---------- 2. media session bridge (WebView has none) ---------- */
  var handlers = {};
  var meta = null;
  if (typeof window.MediaMetadata === 'undefined') {
    window.MediaMetadata = function (d) {
      d = d || {};
      this.title = d.title || ''; this.artist = d.artist || '';
      this.album = d.album || ''; this.artwork = d.artwork || [];
    };
  }
  var ms = {
    get metadata() { return meta; },
    set metadata(v) { meta = v; scheduleMeta(); },
    playbackState: 'none',
    setActionHandler: function (a, h) { if (typeof h === 'function') handlers[a] = h; else delete handlers[a]; },
    setPositionState: function () {},
    setCameraActive: function () {},
    setMicrophoneActive: function () {}
  };
  try { Object.defineProperty(navigator, 'mediaSession', { value: ms, configurable: true }); } catch (e) { report('mediaSession', e); }

  var metaTimer = 0, lastMetaKey = '';
  function scheduleMeta() { clearTimeout(metaTimer); metaTimer = setTimeout(sendMeta, 150); }
  var sendMeta = guard('meta', function () {
    var v = video();
    var id = videoId();
    if (!v || !id) return;
    var title = '', artist = '', art = '';
    if (meta && meta.title) {
      title = meta.title; artist = meta.artist || '';
      var aw = meta.artwork || [];
      if (aw.length) art = aw[aw.length - 1].src || '';
    }
    if (!title) title = (document.title || '').replace(/ - YouTube$/, '');
    // notification art: hqdefault is plenty, never the multi-megabyte maxres (47)
    if (art) art = art.replace('/maxresdefault', '/hqdefault');
    if (!art) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
    var dur = isFinite(v.duration) ? Math.round(v.duration * 1000) : 0;
    var key = id + '|' + title + '|' + artist + '|' + art + '|' + dur;
    if (key === lastMetaKey) return;
    lastMetaKey = key;
    call('mediaMeta', title, artist, art, dur, id);
  });

  /* ---------- 3. commands from Java (notification / headset / timer) ---------- */
  function play() {
    userPaused = false;
    if (handlers.play) { try { handlers.play(); return; } catch (e) {} }
    var p = player(); if (p && p.playVideo) { p.playVideo(); return; }
    var v = video(); if (v) v.play().catch(function () {});
  }
  function pause() {
    userPaused = true;
    if (handlers.pause) { try { handlers.pause(); } catch (e) {} }
    var v = video(); if (v && !v.paused) v.pause();
  }
  function next() {
    userPaused = false;
    if (handlers.nexttrack) { try { handlers.nexttrack(); return; } catch (e) {} }
    var p = player(); if (p && p.nextVideo) { try { p.nextVideo(); return; } catch (e) {} }
    var a = document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"], ytm-item-section-renderer a[href*="/watch?v="]');
    if (a) a.click();
  }
  function prev() {
    userPaused = false;
    if (handlers.previoustrack) { try { handlers.previoustrack(); return; } catch (e) {} }
    var v = video(); if (v) v.currentTime = 0;
  }
  function seek(ms) {
    var t = Number(ms) / 1000;
    if (handlers.seekto) { try { handlers.seekto({ seekTime: t, action: 'seekto' }); return; } catch (e) {} }
    var v = video(); if (v) v.currentTime = t;
  }
  function setVolume(x) {
    var v = video(); if (!v) return;
    if (savedVolume < 0) savedVolume = v.volume || 1;
    v.volume = Math.max(0, Math.min(1, x)) * savedVolume;
  }
  function restoreVolume() {
    var v = video();
    if (v && savedVolume >= 0) v.volume = savedVolume;
    savedVolume = -1;
  }
  function sleepPause() { pause(); setTimeout(restoreVolume, 400); }

  /* ---------- 4. low quality in background ---------- */
  var applyQuality = guard('quality', function () {
    if (!isWatch()) return;
    var p = player(); if (!p) return;
    if (bg && cfg.lowQualityBg) {
      if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('tiny', 'tiny');
      if (p.setPlaybackQuality) p.setPlaybackQuality('tiny');
    } else if (p.__ytlLow) {
      if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('auto', 'auto');
      if (p.setPlaybackQuality) p.setPlaybackQuality('auto');
    }
    p.__ytlLow = bg && cfg.lowQualityBg;
  });

  /* ---------- 5. recovery: lost network (23), failed / stuck playback (38) ---------- */
  var netTries = 0, netTimer = 0, stuckTimer = 0;
  var retryMem = {};
  function retried(id) {
    try { if (sessionStorage.getItem('ytl_retry_' + id) === '1') return true; } catch (e) {}
    return !!retryMem[id];
  }
  function markRetried(id) {
    retryMem[id] = true;
    try { sessionStorage.setItem('ytl_retry_' + id, '1'); } catch (e) {}
  }
  function reloadAt(reason) {
    var v = video();
    var t = v && isFinite(v.currentTime) ? Math.floor(v.currentTime) : 0;
    log('retry', reason + ' v=' + (videoId() || '-') + ' t=' + t);
    var u = new URL(location.href);
    if (u.pathname === '/watch') {
      if (t > 0) u.searchParams.set('t', t + 's');
      nav.replace(u.toString());
    } else nav.reload();
  }
  /** Reload the watch page at the current position, at most once per video and tab session. */
  function retryOnce(reason) {
    if (!isWatch() || userPaused || !navigator.onLine) return false;
    var id = videoId();
    if (!id || retried(id)) return false;
    markRetried(id);
    reloadAt(reason);
    return true;
  }
  var onNet = guard('net', function () {
    clearTimeout(netTimer);
    if (!stalled || userPaused || !navigator.onLine) return;
    var v = video();
    var p = player();
    var errored = (v && v.error) || document.querySelector(RULES.sel.playerError);
    stalled = false;
    if (errored) { reloadAt('net-error'); return; }
    try { if (p && p.playVideo) p.playVideo(); else if (v) v.play().catch(function () {}); } catch (e) {}
    netTimer = setTimeout(checkResumed, 8000);
  });
  var checkResumed = guard('net-check', function () {
    var v = video();
    if (!v || userPaused) { netTries = 0; return; }
    if (!v.paused && v.readyState >= 3) { netTries = 0; return; }
    if (!navigator.onLine) { stalled = true; return; } // wait for the next 'online'
    if (++netTries >= 3) { netTries = 0; reloadAt('net-retry'); return; }
    stalled = true;
    onNet();
  });
  var checkStuck = guard('stuck', function () {
    var v = video();
    if (!v || v.paused || userPaused || adNow()) return;
    if (v.readyState >= 3) return;
    if (!navigator.onLine) { stalled = true; return; }
    retryOnce('stuck');
  });
  // "An error occurred, please try again (playback id …)": retry once. Unavailable / private /
  // age-gated videos say something else and are left alone.
  var RETRY_TEXT_RE = /try again|playback id|error occurred|重试|重新整理|稍后|出错|發生錯誤|错误/i;
  function checkPlayerError() {
    var el = document.querySelector(RULES.sel.playerError);
    if (!el || !navigator.onLine) return;
    if (!RETRY_TEXT_RE.test(el.textContent || '')) return;
    retryOnce('player-error');
  }
  window.addEventListener('online', function () { setTimeout(onNet, 1500); });
  window.addEventListener('offline', function () { var v = video(); if (v && !v.paused) stalled = true; });

  /* ---------- 6. SponsorBlock: skip + 2 s undo prompt (9/10) ---------- */
  var SB_LABEL = {
    sponsor: '赞助片段', selfpromo: '自我推广', interaction: '求订阅/互动', intro: '片头', outro: '片尾',
    preview: '预告/回顾', music_offtopic: '非音乐部分', filler: '离题闲聊'
  };
  var sbTimer = 0;
  function hideSbPrompt() {
    clearTimeout(sbTimer);
    var el = document.getElementById('ytl-sb');
    if (el && el.parentNode) el.parentNode.removeChild(el);
  }
  function showSbPrompt(idx, seg) {
    hideSbPrompt();
    var host = document.body || document.documentElement;
    if (!host) return;
    var el = document.createElement('div');
    el.id = 'ytl-sb';
    var span = document.createElement('span');
    span.textContent = '已跳过' + (SB_LABEL[seg[2]] || '赞助片段');
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = '撤销';
    btn.addEventListener('click', guard('sb-undo', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      segSkipped[idx] = true;
      var v = video();
      if (v) v.currentTime = seg[0];
      hideSbPrompt();
    }), true);
    el.appendChild(span);
    el.appendChild(btn);
    host.appendChild(el);
    sbTimer = setTimeout(hideSbPrompt, 2000);
  }
  function sbTick(v, now) {
    if (!segments.length || now - lastSkip <= 1000 || adNow()) return;
    var t = v.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var s = segments[i];
      if (segSkipped[i]) continue;
      if (t >= s[0] && t < s[1] - 0.5) {
        lastSkip = now;
        v.currentTime = s[1];
        if (!bg) showSbPrompt(i, s);
        break;
      }
    }
  }

  window.ytl = {
    play: play, pause: pause, next: next, prev: prev, seek: seek,
    setVolume: setVolume, restoreVolume: restoreVolume, sleepPause: sleepPause,
    stopAtEnd: function (b) { stopAtEnd = !!b; if (!b) restoreVolume(); },
    bg: function (b) { setBg(!!b); },
    cfg: function (o) { cfg = Object.assign(cfg, o || {}); applyCfg(); },
    onSegments: function (id, arr) {
      if (id !== segFor) return;
      segments = (arr || []).filter(function (s) { return s && isFinite(s[0]) && isFinite(s[1]) && s[1] > s[0]; });
      segSkipped = {};
    },
    onNet: onNet,
    noisy: function () { pause(); },
    trim: function () { trimCaches(); }
  };

  /* ---------- 7. ad removal ----------
   * The player only plays ads it is told about in the player response. The player response
   * is rebuilt from a whitelist of the fields playback / sign-in / history need (1); known ad
   * and promo fields go, unknown fields are kept unless they look like ads (stability first).
   * Ad keys are also renamed in raw text on every path the response can take:
   *   - watch HTML: renamed natively in shouldInterceptRequest (Java)
   *   - window.ytInitialPlayerResponse assignment (setter below)
   *   - JSON.parse of anything that looks like an innertube response (fast path first, 43)
   *   - fetch() + Response.text()/json() for /youtubei/v1/player|next|reel and pbj
   *   - XMLHttpRequest responseText/response (m.youtube.com uses XHR for /player)
   * This script is a document-start script, so all hooks exist before YouTube's code. */
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var AD_TEST = /"(?:adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/;
  var AD_SUB = /"(adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/g;
  var PRUNE_URL = /\/youtubei\/v1\/(?:player|next|reel\/)|[?&]pbj=1/;
  function toSet(a) { var o = {}; for (var i = 0; i < a.length; i++) o[a[i]] = true; return o; }
  // fields a player response keeps (playback, captions, storyboards, history/watch time, sign-in, end screen)
  var PR_KEEP = toSet([
    'responseContext', 'playabilityStatus', 'streamingData', 'heartbeatParams', 'playbackTracking', 'captions',
    'videoDetails', 'annotations', 'playerConfig', 'storyboards', 'microformat', 'cards', 'trackingParams',
    'attestation', 'messages', 'endscreen', 'frameworkUpdates', 'playerSettingsMenuData', 'cacheMetadata',
    'onResponseReceivedEndpoints', 'auxiliaryUi', 'playerCueRanges', 'playerCueRangeSet', 'interpreterSafeUrl',
    'serviceIntegrityDimensions', 'overlay', 'engagementPanels',
    'botguardData', 'playerOverlays', 'captionTracks', 'audioTracks', 'storyboard', 'thumbnail'
  ]);
  // fields that only carry ads / paid-promotion strip / Premium upsell
  var PR_DROP = toSet(AD_KEYS.concat(['paidContentOverlay', 'videoQualityPromoSupportedRenderers',
    'adPlacements_ytl', 'playerAds_ytl', 'adSlots_ytl', 'adBreakHeartbeatParams_ytl']));
  var AD_LIKE_KEY = /^ads?$|^ad[A-Z_]|[a-z]Ads?(?:_ytl)?$|Ad(?:Placement|Slot|Break)|^paidContent|Promo(?:Supported)?Renderers?$|^promo[A-Z]/;
  var unknownSeen = {};
  var lenById = {}, lenIds = [];   // videoId -> {len, live}  (3)
  function hasOwn(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function isPlayerResponse(o) {
    return !!o && typeof o === 'object' && !Array.isArray(o) && hasOwn(o, 'playabilityStatus') &&
      (hasOwn(o, 'videoDetails') || hasOwn(o, 'streamingData'));
  }
  function rebuildPR(o) {
    var keys = Object.keys(o);
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      if (PR_DROP[k]) { delete o[k]; continue; }
      if (PR_KEEP[k]) continue;
      if (AD_LIKE_KEY.test(k)) { delete o[k]; continue; }
      if (!unknownSeen[k]) { unknownSeen[k] = true; log('pr-keep', 'unknown player field kept: ' + k); }
    }
    if (Array.isArray(o.messages)) {
      o.messages = o.messages.filter(function (m) {
        return !(m && typeof m === 'object' && (m.mealbarPromoRenderer || m.upsellDialogRenderer));
      });
    }
    var vd = o.videoDetails;
    if (vd && vd.videoId) {
      var len = parseInt(vd.lengthSeconds, 10);
      if (!lenById[vd.videoId]) { lenIds.push(vd.videoId); if (lenIds.length > 40) delete lenById[lenIds.shift()]; }
      lenById[vd.videoId] = { len: isFinite(len) ? len : 0, live: !!(vd.isLive || vd.isLiveContent || vd.isUpcoming || vd.isPostLiveDvr) };
    }
    preconnectStream(o.streamingData);
    return o;
  }
  function dropKeys(o) {
    for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) delete o[AD_KEYS[i]];
  }
  function stripText(t) {
    return (typeof t === 'string' && AD_TEST.test(t)) ? t.replace(AD_SUB, '"$1_ytl"') : t;
  }
  function prune(o, depth) {
    if (!o || typeof o !== 'object') return o;
    depth = depth || 0;
    if (Array.isArray(o)) {
      if (depth < 2) for (var j = 0; j < o.length && j < 8; j++) prune(o[j], depth + 1);
      return o;
    }
    dropKeys(o);
    if (isPlayerResponse(o)) rebuildPR(o);
    if (depth < 3) {
      if (o.playerResponse) prune(o.playerResponse, depth + 1);
      if (o.response && typeof o.response === 'object') prune(o.response, depth + 1);
      if (o.args && o.args.raw_player_response) prune(o.args.raw_player_response, depth + 1);
    }
    return o;
  }
  // cheap test so JSON.parse stays fast: only touch innertube-shaped objects
  function looksInnertube(o) {
    return o && typeof o === 'object' && ('responseContext' in o || 'playabilityStatus' in o ||
      'playerResponse' in o || 'videoDetails' in o || 'streamingData' in o);
  }
  // pbj responses: [{page..}, {playerResponse..}, {response..}, ...]
  function pbjLike(a) {
    for (var i = 0; i < a.length && i < 8; i++) {
      var x = a[i];
      if (x && typeof x === 'object' && (looksInnertube(x) || (x.response && typeof x.response === 'object'))) return true;
    }
    return false;
  }
  // fast path (43): innertube payloads are big JSON objects/arrays; small strings
  // (localStorage values, settings, numbers) are returned untouched without any key checks
  function worthChecking(text) {
    if (typeof text !== 'string' || text.length < 120) return false;
    var c = text.charCodeAt(0);
    if (c === 32 || c === 10 || c === 13 || c === 9) { var m = /\S/.exec(text); c = m ? m[0].charCodeAt(0) : 0; }
    return c === 123 || c === 91; // { or [
  }
  try {
    var _parse = JSON.parse;
    var hookedParse = function (text, reviver) {
      var r = _parse.apply(this, arguments);
      if (worthChecking(text)) {
        try {
          if (r && typeof r === 'object') {
            if (looksInnertube(r)) prune(r);
            else if (Array.isArray(r) && r.length && pbjLike(r)) prune(r);
          }
        } catch (e) { report('json', e); }
      }
      return r;
    };
    hookedParse.__ytl = true;
    JSON.parse = hookedParse;
  } catch (e) { report('json-hook', e); }

  var iprHooked = false;
  try {
    var _ipr;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return _ipr; },
      set: function (v) { try { _ipr = prune(v); } catch (e) { _ipr = v; report('ipr', e); } }
    });
    iprHooked = true;
  } catch (e) { report('ipr-hook', e); }

  function urlOf(input) {
    try { return typeof input === 'string' ? input : (input && input.url) || String(input || ''); } catch (e) { return ''; }
  }
  var fetchHooked = false;
  try {
    if (typeof Response !== 'undefined' && window.fetch) {
      var RP = Response.prototype, _rText = RP.text, _rJson = RP.json;
      RP.text = function () {
        var p = _rText.apply(this, arguments);
        return PRUNE_URL.test(this.url || '') ? p.then(stripText) : p;
      };
      RP.json = function () {
        if (!PRUNE_URL.test(this.url || '')) return _rJson.apply(this, arguments);
        return _rText.apply(this, arguments).then(function (t) { return prune(JSON.parse(stripText(t))); });
      };
      var _fetch = window.fetch;
      var hookedFetch = function (input, init) {
        var p = _fetch.apply(window, arguments);
        if (!PRUNE_URL.test(urlOf(input))) return p;
        return p.then(function (res) {
          if (!res || !res.ok) return res;
          return _rText.call(res.clone()).then(function (t) {
            if (!AD_TEST.test(t)) return res;
            var h = new Headers(res.headers);
            h.delete('content-length');
            h.delete('content-encoding');
            var r2 = new Response(t.replace(AD_SUB, '"$1_ytl"'), { status: res.status, statusText: res.statusText, headers: h });
            try {
              Object.defineProperty(r2, 'url', { value: res.url });
              Object.defineProperty(r2, 'redirected', { value: res.redirected });
            } catch (e) {}
            return r2;
          }, function () { return res; });
        });
      };
      hookedFetch.__ytl = true;
      window.fetch = hookedFetch;
      fetchHooked = true;
    }
  } catch (e) { report('fetch-hook', e); }

  var xhrHooked = false;
  try {
    var XP = XMLHttpRequest.prototype, _open = XP.open;
    XP.open = function (method, url) {
      this.__ytlU = PRUNE_URL.test(String(url || ''));
      this.__ytlS = this.__ytlR = undefined;
      return _open.apply(this, arguments);
    };
    var hookXhr = function (name) {
      var d = Object.getOwnPropertyDescriptor(XP, name);
      if (!d || !d.get) return false;
      Object.defineProperty(XP, name, {
        configurable: true, enumerable: d.enumerable,
        get: function () {
          var v = d.get.call(this);
          if (!this.__ytlU || this.readyState !== 4) return v;
          try {
            if (typeof v === 'string') {
              if (this.__ytlS !== v) { this.__ytlS = v; this.__ytlR = stripText(v); }
              return this.__ytlR;
            }
            if (v && typeof v === 'object' && !(v instanceof ArrayBuffer) && !(v instanceof Blob)) prune(v);
          } catch (e) { report('xhr', e); }
          return v;
        }
      });
      return true;
    };
    xhrHooked = hookXhr('responseText') && hookXhr('response');
  } catch (e) { report('xhr-hook', e); }

  /* ad reporting beacons (6): the Java side blocks these hosts/paths too; this saves the request */
  var AD_REPORT_RE = /\/(?:api\/stats\/(?:ads|atr)|pagead\/|ptracking|pcs\/activeview|get_midroll_info)/;
  try {
    if (navigator.sendBeacon) {
      var _sb = navigator.sendBeacon;
      navigator.sendBeacon = function (url) {
        if (AD_REPORT_RE.test(String(url || ''))) return true;
        return _sb.apply(navigator, arguments);
      };
    }
  } catch (e) { report('beacon', e); }

  /* preconnect (46): thumbnail/avatar hosts at start, the video host as soon as it is known */
  var preconnected = {}, preCount = 0;
  function preconnect(origin) {
    if (!origin || preconnected[origin] || preCount >= 6) return;
    preconnected[origin] = true; preCount++;
    try {
      var l = document.createElement('link');
      l.rel = 'preconnect';
      l.href = origin;
      (document.head || document.documentElement).appendChild(l);
    } catch (e) {}
  }
  function preconnectStream(sd) {
    if (!sd || typeof sd !== 'object') return;
    try {
      var u = sd.serverAbrStreamingUrl || (sd.adaptiveFormats && sd.adaptiveFormats[0] && sd.adaptiveFormats[0].url) ||
        (sd.formats && sd.formats[0] && sd.formats[0].url);
      if (!u) return;
      var x = new URL(u);
      if (/\.googlevideo\.com$/.test(x.hostname)) preconnect(x.protocol + '//' + x.hostname);
    } catch (e) {}
  }
  try { preconnect('https://i.ytimg.com'); preconnect('https://yt3.ggpht.com'); } catch (e) {}

  /* Fallback if an ad still gets through: black cover, mute, run it at 16x, jump to its end,
   * press skip, and note it in the local "missed ads" log (11). */
  var lastAdCheck = 0;
  var adByLen = false;          // ad detected because the element's duration != lengthSeconds (3)
  var lenMis = { id: '', since: 0, hits: 0, gaveUp: '' };
  var metaFor = '';             // video id that was current when the element last loaded metadata
  var adLogged = {};
  function adNow() { return adShowing() || adByLen; }
  function logAdOnce(type) {
    var id = videoId() || curId || '-';
    var k = id + '|' + type;
    if (adLogged[k]) return;
    adLogged[k] = true;
    call('logAd', id, type, pageType());
  }
  /** Ad detection by length: main video element plays something far shorter than the video. */
  function lenMismatch(v) {
    if (!v || !isWatch()) return false;
    var id = videoId();
    var info = lenById[id];
    if (!id || !info || info.live || !(info.len > 0) || metaFor !== id || lenMis.gaveUp === id) return false;
    var d = v.duration;
    if (!isFinite(d) || d <= 0 || d > 65) return false;
    return info.len >= d * 2 + 30;
  }
  function updateLenAd(v, now) {
    var mis = lenMismatch(v);
    if (!mis) {
      lenMis.hits = 0; lenMis.id = '';
      if (adByLen) { adByLen = false; skipAd(); }
      return;
    }
    var id = videoId();
    if (lenMis.id !== id) { lenMis.id = id; lenMis.since = now; lenMis.hits = 0; }
    lenMis.hits++;
    // need it twice, at least a second apart, and never for longer than an ad can last
    if (now - lenMis.since > 75000) { lenMis.gaveUp = id; if (adByLen) { adByLen = false; skipAd(); } return; }
    if (!adByLen && lenMis.hits >= 2 && now - lenMis.since >= 1000) { adByLen = true; skipAd(); }
  }
  var skipAd = guard('skipAd', function () {
    var v = video();
    var p = player();
    if (p) {
      if (adByLen) { if (!p.classList.contains('ytl-ad')) p.classList.add('ytl-ad'); }
      else if (p.classList.contains('ytl-ad')) p.classList.remove('ytl-ad');
    }
    if (!adNow()) {
      if (v && adMuted) { adMuted = false; v.muted = false; }
      if (v && adRate) { try { v.playbackRate = adRate; } catch (e) {} adRate = 0; }
      return;
    }
    logAdOnce(adByLen ? 'length-mismatch' : 'ad-showing');
    if (v) {
      try {
        if (!v.muted) { v.muted = true; adMuted = true; }
        if (!adRate) adRate = (v.playbackRate && v.playbackRate !== 16) ? v.playbackRate : 1;
        if (v.playbackRate !== 16) v.playbackRate = 16;
        // only jump when the element really holds just the ad (server-stitched ads share the
        // element with the video, and jumping there would skip the video itself)
        if (isFinite(v.duration) && v.duration > 0 && v.duration <= 65 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.1;
      } catch (e) {}
    }
    var b = document.querySelector(RULES.sel.adSkip);
    if (b) { try { b.click(); } catch (e) {} }
  });
  var watchedPlayer = null;
  function watchPlayer() {
    var p = player();
    if (!p || p === watchedPlayer) return;
    watchedPlayer = p;
    new MutationObserver(skipAd).observe(p, { attributes: true, attributeFilter: ['class'] });
    skipAd();
  }

  /* ---------- 8. CSS (41): one stylesheet, injected once ---------- */
  function addCss() {
    if (document.getElementById('ytl-css')) return;
    var s = document.createElement('style');
    s.id = 'ytl-css';
    s.textContent = CSS;
    (document.head || document.documentElement).appendChild(s);
  }
  function applyPerfAttr() {
    var h = document.documentElement;
    if (!h) return;
    if (cfg.perfCss) { if (!h.hasAttribute('data-ytl-perf')) h.setAttribute('data-ytl-perf', ''); }
    else if (h.hasAttribute('data-ytl-perf')) h.removeAttribute('data-ytl-perf');
  }
  function applyCfg() {
    if (!cfg.sponsorBlock) { segments = []; hideSbPrompt(); }
    applyQuality();
    applyPerfAttr();
    if (document.documentElement) markWatched();
  }

  /* ---------- 8b. home feed: hide videos already watched (>= 80 %) ----------
   * Reads only the inline width of YouTube's red progress bar (no layout), runs inside
   * the throttled DOM pass, home page only. Partly watched videos stay so they can be resumed. */
  var SEEN_MIN = 80;
  var seenCount = 0;
  function watchedPct(el) {
    var w = (el.style && el.style.width) || '';
    var m = /^([\d.]+)%$/.exec(w.trim());
    return m ? parseFloat(m[1]) : -1;
  }
  var markWatched = guard('watched', function () {
    var hits = [];
    if (cfg.hideWatched && pageType() === 'home') {
      var bars = document.querySelectorAll(RULES.sel.watchedBar);
      for (var i = 0; i < bars.length; i++) {
        if (watchedPct(bars[i]) < SEEN_MIN) continue;
        var item = bars[i].closest(RULES.sel.feedItem);
        if (item && hits.indexOf(item) < 0) hits.push(item);
      }
    }
    if (seenCount || hits.length) {
      var old = document.querySelectorAll('[data-ytl-seen]');
      for (var j = 0; j < old.length; j++) if (hits.indexOf(old[j]) < 0) old[j].removeAttribute('data-ytl-seen');
    }
    for (var k = 0; k < hits.length; k++) if (!hits[k].hasAttribute('data-ytl-seen')) hits[k].setAttribute('data-ytl-seen', '');
    seenCount = hits.length;
  });

  try { addCss(); applyCfg(); } catch (e) { report('css-init', e); }

  /* thumbnails (47): never the maxres image in lists, hqdefault is what the card shows anyway */
  function trimThumbs() {
    var l = document.querySelectorAll(RULES.sel.maxresImg);
    for (var i = 0; i < l.length; i++) {
      var s = l[i].getAttribute('src') || '';
      if (/^https:\/\/i\d?\.ytimg\.com\/vi(?:_webp)?\//.test(s)) l[i].setAttribute('src', s.replace('/maxresdefault', '/hqdefault'));
    }
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

  /* ---------- 10. player events (capture on document: no element polling) ---------- */
  // Muted inline previews in the feed are not "playback": ignore them.
  function isMain(e) {
    var t = e.target;
    if (!t || t.tagName !== 'VIDEO') return false;
    if (!(t.classList.contains('video-stream') || t === video())) return false;
    return isWatch() || !t.muted;
  }
  var unmutedFor = '';

  function onVideoChange() {
    var id = videoId();
    if (!id || id === curId) return;
    curId = id;
    lastMetaKey = '';
    stalled = false;
    netTries = 0;
    segments = [];
    segSkipped = {};
    segFor = id;
    hideSbPrompt();
    if (cfg.sponsorBlock) call('fetchSegments', id);
    applyQuality();
  }

  function pos(v) { return Math.round((v.currentTime || 0) * 1000); }
  function on(type, name, fn) { document.addEventListener(type, guard(name, fn), true); }

  on('loadedmetadata', 'ev-meta', function (e) {
    if (!isMain(e)) return;
    onVideoChange();
    metaFor = videoId();
    watchPlayer();
    skipAd();
    var v = e.target;
    call('setPortrait', v.videoHeight > v.videoWidth);
    if (bg) applyQuality();
  });

  on('playing', 'ev-playing', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(endTimer);
    clearTimeout(stuckTimer);
    userPaused = false;
    stalled = false;
    netTries = 0;
    onVideoChange();
    watchPlayer();
    if (adNow()) { skipAd(); return; }
    if (adMuted || adRate) skipAd();
    // m.youtube sometimes starts autoplayed videos muted (YTPro had an unmute loop for this)
    if (v.muted && isWatch() && unmutedFor !== curId) { unmutedFor = curId; v.muted = false; }
    sendMeta();
    call('mediaState', 'playing', pos(v), v.playbackRate || 1);
  });

  on('pause', 'ev-pause', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(stuckTimer);
    call('mediaState', 'paused', pos(v), v.playbackRate || 1);
    if (!userPaused && Date.now() - lastInput > 1500 && !v.ended) setTimeout(dismissStillWatching, 400);
  });

  on('waiting', 'ev-waiting', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    if (!navigator.onLine) stalled = true;
    clearTimeout(stuckTimer);
    if (isWatch()) stuckTimer = setTimeout(checkStuck, 20000);
    call('mediaState', 'buffering', pos(v), v.playbackRate || 1);
  });

  on('error', 'ev-error', function (e) {
    if (!isMain(e)) return;
    if (userPaused) return;
    stalled = true;
    // e.g. 403 on the stream: reload at the same position once (online only)
    if (navigator.onLine) setTimeout(function () { if (video() && video().error) retryOnce('media-error'); }, 1500);
  });

  on('seeked', 'ev-seeked', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', v.paused ? 'paused' : 'playing', pos(v), v.playbackRate || 1);
  });

  on('ratechange', 'ev-rate', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', v.paused ? 'paused' : 'playing', pos(v), v.playbackRate || 1);
  });

  on('ended', 'ev-ended', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(stuckTimer);
    call('mediaState', 'paused', pos(v), 1);
    if (stopAtEnd || userPaused || adNow()) return;
    // Auto-continue fallback: if YouTube's own autoplay didn't move on, do it ourselves.
    var id = curId;
    clearTimeout(endTimer);
    endTimer = setTimeout(function () {
      if (videoId() === id && !userPaused) next();
    }, 3500);
  });

  on('timeupdate', 'ev-time', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    var now = Date.now();
    if (now - lastAdCheck > 300) {
      lastAdCheck = now;
      updateLenAd(v, now);
      if (adNow() || adMuted) skipAd();
    }
    // keep YouTube's "last activity" fresh so it never asks "Continue watching?"
    if (now - lastLact > 30000) { lastLact = now; try { window._lact = now; } catch (x) {} }
    sbTick(v, now);
    // sleep timer: end of current video (fade over the last 60 s, then pause)
    if (stopAtEnd && isFinite(v.duration) && v.duration > 0) {
      var left = v.duration - v.currentTime;
      if (left <= 60) setVolume(Math.max(0, (left - 1) / 59));
      if (left <= 1.2) {
        stopAtEnd = false;
        sleepPause();
        call('sleepFired');
      }
    }
  });

  /* ---------- 11. "Video paused. Continue watching?" ---------- */
  var STILL_RE = /continue watching|still watching|still there|are you there|video paused|还在看吗|你还在吗|继续观看|仍在观看|视频已暂停|繼續觀看|仍在觀看|影片已暫停/i;
  var dismissStillWatching = guard('still', function () {
    if (userPaused) return;
    var dlg = document.querySelector(RULES.sel.stillDialog);
    if (!dlg) {
      var list = document.querySelectorAll(RULES.sel.anyDialog);
      for (var i = 0; i < list.length && !dlg; i++) if (STILL_RE.test(list[i].textContent || '')) dlg = list[i];
    }
    if (!dlg) return;
    var btns = dlg.querySelectorAll('button, [role="button"]');
    if (!btns.length) return;
    btns[btns.length - 1].click();
    setTimeout(function () { var v = video(); if (v && v.paused && !userPaused) play(); }, 300);
  });

  /* ---------- 12. user input, Shorts links, home-tab refresh ---------- */
  // Pull-to-refresh / tapping Home: throw away YouTube's client-side copies of the feed,
  // then Java reloads with the HTTP cache bypassed (LOAD_NO_CACHE + cache-busting param).
  // Cookies and localStorage are never touched (66): that is the sign-in and the settings.
  var refreshing = false;
  function refresh() {
    if (refreshing) return;
    refreshing = true;
    var done = false;
    var go = function () { if (done) return; done = true; refreshing = false; call('hardReload'); };
    try { sessionStorage.clear(); } catch (e) {}
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
      if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
        jobs.push(navigator.serviceWorker.getRegistrations().then(function (rs) {
          return Promise.all(rs.map(function (r) { return r.unregister(); }));
        }));
      }
    } catch (e) {}
    try {
      if (window.caches && caches.keys) {
        jobs.push(caches.keys().then(function (ks) {
          return Promise.all(ks.map(function (k) { return caches.delete(k); }));
        }));
      }
    } catch (e) {}
    Promise.all(jobs).then(go, go);
    setTimeout(go, 800);
  }
  window.ytl.refresh = refresh;

  document.addEventListener('touchstart', function () { lastInput = Date.now(); }, { capture: true, passive: true });
  on('click', 'click', function (e) {
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
      window.scrollTo(0, 0);
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
        var r = orig.apply(this, arguments);
        try { checkRoute(); } catch (e) {}
        if (!bg) queuePass();
        return r;
      };
    });
    window.addEventListener('popstate', guard('route', checkRoute));
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

  /* ---------- 14. one throttled observer for the few DOM tasks left ----------
   * Watches the content root (ytm-app) and the top level of <body> for popups only (42).
   * Mutations only set a flag; the work runs at most every ~200 ms, in idle time, never reads
   * layout, and stops completely while the screen is off / app in background (54). */
  var FEEDBACK_RE = new RegExp('^(?:help|help ?(?:&|and) ?feedback|send feedback|feedback|give feedback|report|report history|' +
    'report (?:video|playlist|channel|comment|ad|this ad)|why this ad\\??|帮助|帮助和反馈|帮助与反馈|帮助及反馈|发送反馈|提供反馈|' +
    '反馈|意见反馈|举报|举报记录|举报视频|举报播放列表|举报频道|說明|說明與意見回饋|提供意見|傳送意見回饋|提供意見回饋|' +
    '意見回饋|檢舉|檢舉記錄|檢舉影片)$', 'i');
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
  var ensureGear = guard('gear', function () {
    if (document.getElementById('ytl-gear')) return;
    var logo = document.querySelector('ytm-home-logo');
    if (!logo || !logo.parentNode) return;
    var b = document.createElement('button');
    b.id = 'ytl-gear';
    b.setAttribute('aria-label', 'YT Lite 设置');
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
  var forceAutonav = guard('autonav', function () {
    if (autonavFor === curId) return;
    var tg = document.querySelector(RULES.sel.autonav);
    if (tg) {
      autonavFor = curId;
      if (tg.getAttribute('aria-pressed') === 'false') tg.click();
    }
  });
  function domPass() {
    var pt = pageType();
    try { addCss(); applyPerfAttr(); } catch (e) { report('css', e); }
    try { watchPlayer(); if (player()) skipAd(); } catch (e) { report('player', e); }
    hideMenus();
    try { hideByText(RULES.sel.shortsText, /^shorts$/i); } catch (e) { report('shorts', e); }
    if (pt === 'home' || seenCount) markWatched();
    ensureGear();
    if (pt === 'watch') {
      forceAutonav();
      try { checkPlayerError(); } catch (e) { report('player-error', e); }
    }
    if (cfg.perfCss) { try { trimThumbs(); } catch (e) { report('thumbs', e); } }
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
  var onMutations = guard('observer', function (recs) {
    queuePass();
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

  /* ---------- 15. self check (74): are the hooks that keep ads away in place? ---------- */
  function selfCheck(silent) {
    var r = {
      bridge: !!B,
      json: !!(JSON.parse && JSON.parse.__ytl),
      ipr: iprHooked,
      fetch: !window.fetch || fetchHooked,
      xhr: xhrHooked,
      css: !!document.getElementById('ytl-css'),
      mediaSession: navigator.mediaSession === ms
    };
    if (!silent) {
      var bad = Object.keys(r).filter(function (k) { return !r[k]; });
      var once = false;
      try { once = sessionStorage.getItem('ytl_selfcheck') === '1'; sessionStorage.setItem('ytl_selfcheck', '1'); } catch (e) {}
      if (bad.length) log('selfcheck', 'FAIL ' + bad.join(',') + ' @' + pageType());
      else if (!once) log('selfcheck', 'ok');
    }
    return r;
  }

  // test / diagnostics surface (no behaviour of its own)
  window.ytl._ = {
    RULES: RULES, css: function () { return CSS; }, pageType: pageType, isPlayerResponse: isPlayerResponse,
    prune: prune, lenById: function () { return lenById; }, lenMismatch: lenMismatch, adNow: adNow,
    cleanUrl: cleanUrl, cleanText: cleanText, retryOnce: retryOnce, nav: nav, selfCheck: selfCheck,
    guard: guard, worthChecking: worthChecking, domPass: domPass,
    observing: function () { return !!mo; }, observerRoot: function () { return moRoot; },
    segments: function () { return segments; }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserver, { once: true });
  else startObserver();
})();

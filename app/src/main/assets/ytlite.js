/*
 * YT Lite injected script (bundled in the APK, never loaded from a CDN).
 * Based on ideas from YTPro by Prateek Chaubey (MIT) – rewritten lean:
 *  - no setInterval polling, one debounced MutationObserver
 *  - injected once per document (document-start script, guarded)
 *  - CSS does the hiding work, JS only does what CSS can't
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
  var cfg = { sponsorBlock: true, lowQualityBg: true };
  try { if (B) cfg = Object.assign(cfg, JSON.parse(B.getConfig())); } catch (e) {}

  var bg = false;            // app in background / screen off (told by Java)
  var userPaused = false;    // pause requested by user / timer / noisy
  var lastInput = 0;         // last touch time
  var stopAtEnd = false;     // sleep timer "end of current video"
  var savedVolume = -1;
  var segments = [];         // SponsorBlock [[start,end],...]
  var segFor = '';
  var curId = '';
  var lastSkip = 0;
  var lastLact = 0;
  var stalled = false;
  var endTimer = 0;
  var autonavFor = '';
  var adMuted = false;
  var adRate = 0;

  /* ---------- helpers ---------- */
  function call(fn) {
    if (!B) return;
    try { B[fn].apply(B, Array.prototype.slice.call(arguments, 1)); } catch (e) {}
  }
  function video() { return document.querySelector('video.video-stream') || document.querySelector('video'); }
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
  function isWatch() { return location.pathname === '/watch' || location.pathname.indexOf('/live/') === 0; }
  function adShowing() {
    var p = player();
    return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
  }

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
  } catch (e) {}

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
  try { Object.defineProperty(navigator, 'mediaSession', { value: ms, configurable: true }); } catch (e) {}

  var metaTimer = 0, lastMetaKey = '';
  function scheduleMeta() { clearTimeout(metaTimer); metaTimer = setTimeout(sendMeta, 150); }
  function sendMeta() {
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
    if (!art) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
    var dur = isFinite(v.duration) ? Math.round(v.duration * 1000) : 0;
    var key = id + '|' + title + '|' + artist + '|' + art + '|' + dur;
    if (key === lastMetaKey) return;
    lastMetaKey = key;
    call('mediaMeta', title, artist, art, dur, id);
  }

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
  function applyQuality() {
    if (!isWatch()) return;
    var p = player(); if (!p) return;
    try {
      if (bg && cfg.lowQualityBg) {
        if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('tiny', 'tiny');
        if (p.setPlaybackQuality) p.setPlaybackQuality('tiny');
      } else if (p.__ytlLow) {
        if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('auto', 'auto');
        if (p.setPlaybackQuality) p.setPlaybackQuality('auto');
      }
      p.__ytlLow = bg && cfg.lowQualityBg;
    } catch (e) {}
  }

  /* ---------- 5. auto-reconnect ---------- */
  function onNet() {
    if (!stalled || userPaused) return;
    var v = video();
    var p = player();
    var errored = (v && v.error) || document.querySelector('.ytp-error, ytm-player-error-message-renderer');
    stalled = false;
    if (errored) {
      var t = v && isFinite(v.currentTime) ? Math.floor(v.currentTime) : 0;
      var u = new URL(location.href);
      if (u.pathname === '/watch') { u.searchParams.set('t', t + 's'); location.replace(u.toString()); }
      else location.reload();
      return;
    }
    try { if (p && p.playVideo) p.playVideo(); else if (v) v.play().catch(function () {}); } catch (e) {}
  }
  window.addEventListener('online', function () { setTimeout(onNet, 1500); });
  window.addEventListener('offline', function () { var v = video(); if (v && !v.paused) stalled = true; });

  window.ytl = {
    play: play, pause: pause, next: next, prev: prev, seek: seek,
    setVolume: setVolume, restoreVolume: restoreVolume, sleepPause: sleepPause,
    stopAtEnd: function (b) { stopAtEnd = !!b; if (!b) restoreVolume(); },
    bg: function (b) { bg = !!b; applyQuality(); },
    cfg: function (o) { cfg = Object.assign(cfg, o || {}); applyCfg(); },
    onSegments: function (id, arr) { if (id === segFor) segments = arr || []; },
    onNet: onNet,
    noisy: function () { pause(); }
  };

  /* ---------- 6. ad removal ----------
   * The player only plays ads it is told about in the player response
   * (adPlacements / playerAds / adSlots / adBreakHeartbeatParams). Those keys are
   * renamed (text) or deleted (objects) on every path the response can take:
   *   - watch HTML: renamed natively in shouldInterceptRequest (Java)
   *   - window.ytInitialPlayerResponse assignment (setter below)
   *   - JSON.parse of anything that looks like an innertube response
   *   - fetch() + Response.text()/json() for /youtubei/v1/player|next|reel and pbj
   *   - XMLHttpRequest responseText/response (m.youtube.com uses XHR for /player)
   * This script is a document-start script, so all hooks exist before YouTube's code. */
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var AD_TEST = /"(?:adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/;
  var AD_SUB = /"(adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/g;
  var PRUNE_URL = /\/youtubei\/v1\/(?:player|next|reel\/)|[?&]pbj=1/;
  function stripText(t) {
    return (typeof t === 'string' && AD_TEST.test(t)) ? t.replace(AD_SUB, '"$1_ytl"') : t;
  }
  function dropKeys(o) {
    for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) delete o[AD_KEYS[i]];
  }
  function prune(o, depth) {
    if (!o || typeof o !== 'object') return o;
    depth = depth || 0;
    if (Array.isArray(o)) {
      if (depth < 2) for (var j = 0; j < o.length && j < 8; j++) prune(o[j], depth + 1);
      return o;
    }
    dropKeys(o);
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
  try {
    var _parse = JSON.parse;
    JSON.parse = function (text, reviver) {
      var r = _parse.apply(this, arguments);
      try {
        if (r && typeof r === 'object') {
          if (looksInnertube(r)) prune(r);
          else if (Array.isArray(r) && r.length && looksInnertube(r[r.length - 1])) prune(r);
        }
      } catch (e) {}
      return r;
    };
  } catch (e) {}

  try {
    var _ipr;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return _ipr; },
      set: function (v) { _ipr = prune(v); }
    });
  } catch (e) {}

  function urlOf(input) {
    try { return typeof input === 'string' ? input : (input && input.url) || String(input || ''); } catch (e) { return ''; }
  }
  try {
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
    if (_fetch) {
      window.fetch = function (input, init) {
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
    }
  } catch (e) {}

  try {
    var XP = XMLHttpRequest.prototype, _open = XP.open;
    XP.open = function (method, url) {
      this.__ytlU = PRUNE_URL.test(String(url || ''));
      this.__ytlS = this.__ytlR = undefined;
      return _open.apply(this, arguments);
    };
    var hookXhr = function (name) {
      var d = Object.getOwnPropertyDescriptor(XP, name);
      if (!d || !d.get) return;
      Object.defineProperty(XP, name, {
        configurable: true, enumerable: d.enumerable,
        get: function () {
          var v = d.get.call(this);
          if (!this.__ytlU || this.readyState !== 4) return v;
          if (typeof v === 'string') {
            if (this.__ytlS !== v) { this.__ytlS = v; this.__ytlR = stripText(v); }
            return this.__ytlR;
          }
          if (v && typeof v === 'object' && !(v instanceof ArrayBuffer) && !(v instanceof Blob)) prune(v);
          return v;
        }
      });
    };
    hookXhr('responseText');
    hookXhr('response');
  } catch (e) {}

  /* Fallback if an ad still gets through: mute, run it at 16x, jump to its end, press skip. */
  var AD_SKIP_SEL = '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-container,' +
    ' .ytp-ad-skip-button-slot button, button[id^="skip-button"], .ytm-skip-ad-button, ytm-skip-ad-button button';
  var lastAdCheck = 0;
  function skipAd() {
    var v = video();
    if (!adShowing()) {
      if (v && adMuted) { adMuted = false; v.muted = false; }
      if (v && adRate) { try { v.playbackRate = adRate; } catch (e) {} adRate = 0; }
      return;
    }
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
    var b = document.querySelector(AD_SKIP_SEL);
    if (b) { try { b.click(); } catch (e) {} }
  }
  var watchedPlayer = null;
  function watchPlayer() {
    var p = player();
    if (!p || p === watchedPlayer) return;
    watchedPlayer = p;
    new MutationObserver(skipAd).observe(p, { attributes: true, attributeFilter: ['class'] });
    skipAd();
  }

  /* ---------- 7. CSS: ads, action row, Shorts, help/feedback ---------- */
  var CSS = [
    /* ads & promos */
    'ad-slot-renderer, ytm-ad-slot-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-sparkles-text-search-renderer,',
    'ytm-promoted-video-renderer, ytm-promoted-item-renderer, ytm-in-feed-ad-layout-renderer, ytm-display-ad-renderer,',
    'ytm-companion-ad-renderer, ytm-action-companion-ad-renderer, ytm-companion-slot, ytm-paid-content-overlay-renderer,',
    'ytm-mealbar-promo-renderer, ytm-statement-banner-renderer, ytm-banner-promo-renderer, ytm-video-masthead-ad-v3-renderer,',
    'ytm-brand-video-singleton-renderer, ytm-brand-video-shelf-renderer, ytm-primetime-promo-renderer,',
    'ytm-rich-item-renderer:has(> ad-slot-renderer), ytm-rich-item-renderer:has(> ytm-ad-slot-renderer),',
    'ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer:only-child),',
    '.ytp-ad-overlay-container, .ytp-ad-text-overlay, .ytp-ad-image-overlay, a[href^="intent:"], ytm-open-app-promo-renderer',
    '{display:none!important}',
    /* action button row under the video: like/dislike/share/save/remix/download/clip/thanks */
    'ytm-slim-video-action-bar-renderer, .slim-video-action-bar-actions, ytm-video-actions-bar,',
    'ytw-slim-video-action-bar-view-model {display:none!important}',
    /* Shorts: always removed */
    'ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytm-reel-item-renderer,',
    '.big-shorts-singleton, ytm-pivot-bar-item-renderer:has(.pivot-shorts),',
    'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer), ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model),',
    'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2), grid-shelf-view-model:has(ytm-shorts-lockup-view-model),',
    'grid-shelf-view-model:has(ytm-shorts-lockup-view-model-v2),',
    'ytm-rich-item-renderer:has(a[href^="/shorts/"]), ytm-video-with-context-renderer:has(a[href^="/shorts/"]),',
    'ytm-compact-video-renderer:has(a[href^="/shorts/"]), ytm-reel-video-renderer,',
    'a[role="tab"][href$="/shorts"], .scbrr-tab[href$="/shorts"] {display:none!important}',
    /* Help / feedback / report */
    'ytm-compact-link-renderer:has(a[href*="support.google.com"]), ytm-compact-link-renderer:has(a[href*="feedback"]),',
    'ytm-compact-link-renderer:has(a[href*="reporthistory"]), ytm-multi-page-menu-renderer a[href*="support.google.com"],',
    'ytm-multi-page-menu-renderer a[href*="feedback"], [data-ytl-hide] {display:none!important}',
    /* settings gear */
    '#ytl-gear{display:flex;align-items:center;justify-content:center;width:40px;height:40px;background:none;border:0;padding:0;margin:0 2px;color:inherit;fill:currentColor}'
  ].join('\n');

  function addCss() {
    if (document.getElementById('ytl-css')) return;
    var s = document.createElement('style');
    s.id = 'ytl-css';
    s.textContent = CSS;
    (document.head || document.documentElement).appendChild(s);
  }
  function applyCfg() {
    if (!cfg.sponsorBlock) segments = [];
    applyQuality();
  }
  addCss();
  applyCfg();

  /* ---------- 8. player events (capture on document: no element polling) ---------- */
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
    segments = [];
    segFor = id;
    if (cfg.sponsorBlock) call('fetchSegments', id);
    applyQuality();
  }

  function pos(v) { return Math.round((v.currentTime || 0) * 1000); }

  document.addEventListener('loadedmetadata', function (e) {
    if (!isMain(e)) return;
    onVideoChange();
    skipAd();
    var v = e.target;
    call('setPortrait', v.videoHeight > v.videoWidth);
    if (bg) applyQuality();
  }, true);

  document.addEventListener('playing', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(endTimer);
    userPaused = false;
    stalled = false;
    onVideoChange();
    if (adShowing()) { skipAd(); return; }
    if (adMuted || adRate) skipAd();
    // m.youtube sometimes starts autoplayed videos muted (YTPro had an unmute loop for this)
    if (v.muted && isWatch() && unmutedFor !== curId) { unmutedFor = curId; v.muted = false; }
    sendMeta();
    call('mediaState', 'playing', pos(v), v.playbackRate || 1);
  }, true);

  document.addEventListener('pause', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', 'paused', pos(v), v.playbackRate || 1);
    if (!userPaused && Date.now() - lastInput > 1500 && !v.ended) setTimeout(dismissStillWatching, 400);
  }, true);

  document.addEventListener('waiting', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    if (!navigator.onLine) stalled = true;
    call('mediaState', 'buffering', pos(v), v.playbackRate || 1);
  }, true);

  document.addEventListener('error', function (e) {
    if (!isMain(e)) return;
    if (!userPaused) stalled = true;
  }, true);

  document.addEventListener('seeked', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', v.paused ? 'paused' : 'playing', pos(v), v.playbackRate || 1);
  }, true);

  document.addEventListener('ratechange', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', v.paused ? 'paused' : 'playing', pos(v), v.playbackRate || 1);
  }, true);

  document.addEventListener('ended', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    call('mediaState', 'paused', pos(v), 1);
    if (stopAtEnd || userPaused || adShowing()) return;
    // Auto-continue fallback: if YouTube's own autoplay didn't move on, do it ourselves.
    var id = curId;
    clearTimeout(endTimer);
    endTimer = setTimeout(function () {
      if (videoId() === id && !userPaused) next();
    }, 3500);
  }, true);

  document.addEventListener('timeupdate', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    var now = Date.now();
    if (now - lastAdCheck > 300) { lastAdCheck = now; if (adShowing() || adMuted) skipAd(); }
    // keep YouTube's "last activity" fresh so it never asks "Continue watching?"
    if (now - lastLact > 30000) { lastLact = now; try { window._lact = now; } catch (x) {} }
    var t = v.currentTime;
    // SponsorBlock
    if (segments.length && now - lastSkip > 1000 && !adShowing()) {
      for (var i = 0; i < segments.length; i++) {
        var s = segments[i];
        if (t >= s[0] && t < s[1] - 0.5) {
          lastSkip = now;
          v.currentTime = s[1];
          if (!bg) call('toast', '已跳过赞助片段');
          break;
        }
      }
    }
    // sleep timer: end of current video (fade over the last 60 s, then pause)
    if (stopAtEnd && isFinite(v.duration) && v.duration > 0) {
      var left = v.duration - t;
      if (left <= 60) setVolume(Math.max(0, (left - 1) / 59));
      if (left <= 1.2) {
        stopAtEnd = false;
        sleepPause();
        call('sleepFired');
      }
    }
  }, true);

  /* ---------- 9. "Video paused. Continue watching?" ---------- */
  var STILL_RE = /continue watching|still watching|video paused|继续观看|仍在观看|视频已暂停|繼續觀看|仍在觀看|影片已暫停/i;
  function dismissStillWatching() {
    if (userPaused) return;
    var dlg = document.querySelector('ytm-confirm-dialog-renderer, yt-confirm-dialog-renderer');
    if (!dlg) {
      var list = document.querySelectorAll('ytm-dialog, tp-yt-paper-dialog, [role="dialog"], .dialog-container');
      for (var i = 0; i < list.length && !dlg; i++) if (STILL_RE.test(list[i].textContent || '')) dlg = list[i];
    }
    if (!dlg) return;
    var btns = dlg.querySelectorAll('button, [role="button"]');
    if (!btns.length) return;
    btns[btns.length - 1].click();
    setTimeout(function () { var v = video(); if (v && v.paused && !userPaused) play(); }, 300);
  }

  /* ---------- 10. user input, Shorts links, home-tab refresh ---------- */
  // Pull-to-refresh / tapping Home: throw away YouTube's client-side copies of the feed,
  // then Java reloads with the HTTP cache bypassed (LOAD_NO_CACHE + cache-busting param).
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
  document.addEventListener('click', function (e) {
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
    if (v && t.closest('.player-control-play-pause-icon, .ytp-play-button, button[aria-label*="ause"]')) {
      // user toggled playback: remember intent
      userPaused = !v.paused;
    }
    if (location.pathname !== '/') return;
    var pivot = t.closest('ytm-pivot-bar-item-renderer');
    var isHome = pivot && pivot.parentElement && pivot.parentElement.querySelector('ytm-pivot-bar-item-renderer') === pivot;
    if (isHome || t.closest('ytm-home-logo')) {
      e.preventDefault();
      e.stopPropagation();
      window.scrollTo(0, 0);
      refresh();
    }
  }, true);

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
        return r;
      };
    });
    window.addEventListener('popstate', checkRoute);
  } catch (e) {}

  /* ---------- 11. scroll position for pull-to-refresh ---------- */
  var atTop = true, scrollQueued = false;
  function reportScroll() {
    scrollQueued = false;
    var top = (window.scrollY || document.documentElement.scrollTop || 0) <= 0;
    if (top !== atTop) { atTop = top; call('setAtTop', top); }
  }
  window.addEventListener('scroll', function () {
    if (!scrollQueued) { scrollQueued = true; requestAnimationFrame(reportScroll); }
  }, { passive: true });

  /* ---------- 12. one throttled observer for the few DOM tasks left ----------
   * Mutations only set a flag; the work runs at most every ~200 ms, in idle time,
   * and never reads layout (no offsetHeight/getBoundingClientRect). */
  var FEEDBACK_RE = new RegExp('^(?:help|help ?(?:&|and) ?feedback|send feedback|feedback|give feedback|report|report history|' +
    'report (?:video|playlist|channel|comment|ad|this ad)|why this ad\\??|帮助|帮助和反馈|帮助与反馈|帮助及反馈|发送反馈|提供反馈|' +
    '反馈|意见反馈|举报|举报记录|举报视频|举报播放列表|举报频道|說明|說明與意見回饋|提供意見|傳送意見回饋|提供意見回饋|' +
    '意見回饋|檢舉|檢舉記錄|檢舉影片)$', 'i');
  var MENU_SEL = 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, ytm-menu-item, ytm-compact-link-renderer,' +
    ' [role="menuitem"], .menu-item, ytm-bottom-sheet-renderer yt-list-item-view-model,' +
    ' bottom-sheet-container yt-list-item-view-model, ytm-multi-page-menu-renderer yt-list-item-view-model,' +
    ' ytm-account-item-renderer, ytm-setting-generic-category';
  var SHORTS_TEXT_SEL = 'ytm-pivot-bar-item-renderer, [role="tab"], .scbrr-tab, yt-tab-shape, ytm-chip-cloud-chip-renderer,' +
    ' yt-chip-cloud-chip-renderer, chip-shape';
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
  function hideMenus() { hideByText(MENU_SEL, FEEDBACK_RE); }
  function domPass() {
    addCss();
    watchPlayer();
    skipAd();
    hideMenus();
    hideByText(SHORTS_TEXT_SEL, /^shorts$/i);
    // settings gear in the top bar
    if (!document.getElementById('ytl-gear')) {
      var logo = document.querySelector('ytm-home-logo');
      if (logo && logo.parentNode) {
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
      }
    }
    // force autoplay toggle on (once per video)
    if (location.pathname === '/watch' && autonavFor !== curId) {
      var tg = document.querySelector('.ytm-autonav-toggle-button-container');
      if (tg) {
        autonavFor = curId;
        if (tg.getAttribute('aria-pressed') === 'false') tg.click();
      }
    }
  }
  var passQueued = false, menuQueued = false;
  var idle = window.requestIdleCallback
    ? function (f) { window.requestIdleCallback(f, { timeout: 300 }); }
    : function (f) { setTimeout(f, 16); };
  function queuePass() {
    if (passQueued) return;
    passQueued = true;
    setTimeout(function () { idle(function () { passQueued = false; domPass(); }); }, 200);
  }
  // menus / bottom sheets: hide feedback items in the next frame so they never flash
  var MENU_NODE_RE = /SHEET|MENU|DIALOG|POPUP|LIST-ITEM/;
  function queueMenus() {
    if (menuQueued) return;
    menuQueued = true;
    requestAnimationFrame(function () { menuQueued = false; hideMenus(); });
  }
  function startObserver() {
    new MutationObserver(function (recs) {
      queuePass();
      if (menuQueued) return;
      for (var i = 0; i < recs.length; i++) {
        var add = recs[i].addedNodes;
        for (var j = 0; j < add.length; j++) {
          var n = add[j];
          if (n.nodeType === 1 && MENU_NODE_RE.test(n.nodeName)) { queueMenus(); return; }
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
    domPass();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserver, { once: true });
  else startObserver();
})();

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
  window.__ytl = true;

  var B = window.YTL || null; // Java bridge
  var cfg = { hideShorts: false, sponsorBlock: true, lowQualityBg: true };
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
  function isWatch() { return location.pathname === '/watch' || location.pathname.indexOf('/shorts/') === 0; }
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

  /* ---------- 6. ad removal: prune ad payloads from player responses ---------- */
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var AD_RE = /"(adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/;
  function prune(o) {
    if (!o || typeof o !== 'object') return o;
    for (var i = 0; i < AD_KEYS.length; i++) delete o[AD_KEYS[i]];
    if (o.playerResponse) prune(o.playerResponse);
    if (Array.isArray(o)) for (var j = 0; j < o.length; j++) prune(o[j]);
    return o;
  }
  try {
    var _ipr;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return _ipr; },
      set: function (v) { _ipr = prune(v); }
    });
  } catch (e) {}

  var PRUNE_URL = /\/youtubei\/v1\/(player|next|reel\/reel_item_watch|reel\/reel_watch_sequence)/;
  var _fetch = window.fetch;
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var p = _fetch.apply(window, arguments);
    if (!PRUNE_URL.test(url)) return p;
    return p.then(function (res) {
      if (!res || !res.ok) return res;
      return res.clone().text().then(function (txt) {
        if (!AD_RE.test(txt)) return res;
        var data;
        try { data = JSON.parse(txt); } catch (e) { return res; }
        var body = JSON.stringify(prune(data));
        var h = new Headers(res.headers);
        h.delete('content-length');
        h.set('content-type', 'application/json; charset=utf-8');
        return new Response(body, { status: res.status, statusText: res.statusText, headers: h });
      }, function () { return res; });
    });
  };

  function skipAd() {
    if (!adShowing()) return;
    var v = video();
    try { if (v && isFinite(v.duration) && v.duration > 0) { if (!v.muted) { v.muted = true; adMuted = true; } v.currentTime = v.duration; } } catch (e) {}
    var b = document.querySelector('.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, button.ytp-ad-skip-button-container');
    if (b) b.click();
  }

  /* ---------- 7. CSS: ads, action row, Shorts ---------- */
  var CSS = [
    /* ads & promos */
    'ad-slot-renderer, ytm-ad-slot-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer,',
    'ytm-companion-ad-renderer, ytm-paid-content-overlay-renderer, ytm-mealbar-promo-renderer,',
    'ytm-statement-banner-renderer, ytm-brand-video-singleton-renderer, ytm-brand-video-shelf-renderer,',
    'ytm-rich-item-renderer:has(> ad-slot-renderer), ytm-rich-item-renderer:has(> ytm-ad-slot-renderer),',
    '.ytp-ad-overlay-container, .ytp-ad-text-overlay, a[href^="intent:"], ytm-open-app-promo-renderer',
    '{display:none!important}',
    /* action button row under the video: like/dislike/share/save/remix/download/clip/thanks */
    'ytm-slim-video-action-bar-renderer, .slim-video-action-bar-actions, ytm-video-actions-bar,',
    'ytw-slim-video-action-bar-view-model {display:none!important}',
    /* Shorts (toggle) */
    'html.ytl-noshorts ytm-reel-shelf-renderer, html.ytl-noshorts ytm-shorts-lockup-view-model,',
    'html.ytl-noshorts .big-shorts-singleton, html.ytl-noshorts ytm-rich-section-renderer:has(ytm-reel-shelf-renderer),',
    'html.ytl-noshorts ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model),',
    'html.ytl-noshorts ytm-pivot-bar-item-renderer:has(.pivot-shorts) {display:none!important}',
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
    document.documentElement.classList.toggle('ytl-noshorts', !!cfg.hideShorts);
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
    if (adMuted) { adMuted = false; v.muted = false; }
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

  /* ---------- 10. user input tracking & home-tab refresh ---------- */
  document.addEventListener('touchstart', function () { lastInput = Date.now(); }, { capture: true, passive: true });
  document.addEventListener('click', function (e) {
    lastInput = Date.now();
    var t = e.target;
    if (!t || !t.closest) return;
    var v = video();
    if (v && t.closest('.player-control-play-pause-icon, .ytp-play-button, button[aria-label*="ause"]')) {
      // user toggled playback: remember intent
      userPaused = !v.paused;
    }
    if (location.pathname !== '/') return;
    var pivot = t.closest('ytm-pivot-bar-item-renderer');
    var isHome = pivot && pivot.parentElement && pivot.parentElement.firstElementChild === pivot;
    if (isHome || t.closest('ytm-home-logo')) {
      e.preventDefault();
      e.stopPropagation();
      window.scrollTo(0, 0);
      call('reloadPage');
    }
  }, true);

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

  /* ---------- 12. one debounced observer for the few DOM tasks left ---------- */
  var obsTimer = 0;
  function domPass() {
    obsTimer = 0;
    addCss();
    skipAd();
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
  function startObserver() {
    new MutationObserver(function () {
      if (!obsTimer) obsTimer = setTimeout(domPass, 250);
    }).observe(document.documentElement, { childList: true, subtree: true });
    domPass();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserver, { once: true });
  else startObserver();
})();

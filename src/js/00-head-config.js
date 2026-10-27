/*
 * YiWei (formerly YT Lite) injected script (bundled in the APK, never loaded from a CDN).
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
  var wasRefresh = /[?&]ytlr=/.test(location.search);
  if (wasRefresh) {
    try {
      var u0 = new URL(location.href);
      u0.searchParams.delete('ytlr');
      history.replaceState(history.state, '', u0.pathname + u0.search + u0.hash);
    } catch (e) {}
  }

  var B = window.YTL || null; // Java bridge
  var cfg = {
    sponsorBlock: true, lowQualityBg: true, hideWatched: true, perfCss: true,
    // batch 2 (v1.3.0) – every one has a safe default that keeps the old behaviour
    fadeSec: 60,            // sleep fade length (13)
    nextSource: 'rec',      // what plays next: rec | channel | playlist (22)
    sleepCalm: false,       // while a sleep timer runs: same channel / playlist only (21)
    resumeLocal: true,      // signed out: remember where each video was left (28)
    quality: 'auto',        // auto | wifiMax | 1080 | 720 | 480 (29)
    rememberRate: false, rate: 1,   // (30)
    captions: 'yt',         // yt | off | zh | zhHans | zhHant | en (31/341)
    origAudio: true,        // prefer the original audio track over auto-dubs (32)
    noEndWall: true,        // no recommendation wall when a video ends (36)
    normalize: false,       // WebAudio compressor, experimental (20)
    sleepBadge: true,       // sleep time left in the player corner (154)
    subSize: 'default', subBg: 'default',  // caption style (155)
    // batch 3 (v1.4.0) – lists, look, interaction; defaults keep the old behaviour
    seenMin: 80,            // "watched" threshold for hiding on home (62)
    blockChannels: [],      // local channel blocklist (56)
    blockWords: [],         // title keyword blocklist (57)
    minDur: 0,              // hide videos shorter than N minutes, home + up next (58)
    maxAgeDays: 0,          // hide videos older than N days, home + up next (59)
    hideLive: false,        // hide live / premiere / upcoming, home + up next (60)
    hideShelves: false,     // hide home shelves (news, mixes ...) (61)
    subsOnlyVideos: false,  // subscriptions feed: videos only (63)
    refreshCount: true,     // "N new videos" after a refresh (64)
    hideSearchExtras: true, // search: no "related searches" / "people also watched" (70)
    amoled: false,          // pure black dark theme (91)
    compact: false,         // compact list layout on home (108)
    cardStyle: false,       // uniform corners, 2-line titles, clearer badges (106/107/111)
    dimThumbs: false,       // slightly darker thumbnails in dark mode (110)
    accent: '',             // accent colour for progress bar / own controls (103/104)
    longPress: true,        // long-press a video card for a quick menu (123)
    searchFocus: true,      // keyboard opens with search (124)
    searchChips: true,      // quick filter chips on search results (125)
    keepScroll: true,       // back to a list returns to where you were (119)
    powerSave: false,       // battery saver on: cap quality, no animations (147) – set by Java
    updateDot: false,       // a new version is ready: dot on the settings gear (137) – set by Java
    // batch 5 (v1.7.0)
    lowMotion: false,       // system "remove animations" is on (367) – set by Java
    fontLarge: false,       // text zoom may go up to 160 %: titles get a third line (378)
    skipWatchedNext: false, // recommended autoplay: skip a target already watched (311)
    appName: '一维视频',     // display name for the gear's screen-reader label (374)
    // batch 6 (v1.8.0) – every default keeps the old behaviour
    hideWatchedRelated: false, // related videos under the player also hide watched ones (327)
    hideSearchMix: false,   // search results: no auto-generated mixes (333)
    lightRefresh: false,    // refresh keeps the service worker / static caches (316, experimental)
    listEndNext: false,     // a playlist played to its end goes on with a recommendation (313)
    sleepStrict: false      // sleeping with the same-channel rule: stop when there is none (315)
    // removed in v1.10 (personal-use trim, 454): landscape (387)
  };
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
  var sleeping = false;       // any sleep timer running (21)
  var countMode = false;     // sleep timer "after N videos": report each finished video (16)
  var badgeText = '';        // sleep time left shown on the player (154)
  // state Java still holds when this document is (re)loaded: sleep timer mode, background
  if (cfg.stopAtEnd) stopAtEnd = true;
  if (cfg.bg) bg = true;
  if (cfg.sleeping) sleeping = true;
  if (cfg.countMode) countMode = true;
  if (cfg.badge) badgeText = String(cfg.badge);
  delete cfg.stopAtEnd; delete cfg.bg; delete cfg.sleeping; delete cfg.countMode; delete cfg.badge;
  var sleepMode = !!cfg.sleepMode; delete cfg.sleepMode;   // one-tap sleep mode (97)
  // ad keys shared with the native watch-page filter (279): assets/adkeys.json, handed over by getConfig
  var extraAdKeys = Array.isArray(cfg.adKeys) ? cfg.adKeys.filter(function (k) { return typeof k === 'string' && /^[A-Za-z][A-Za-z0-9]{2,40}$/.test(k); }) : [];
  delete cfg.adKeys;
  // texts from the app's string resources (381); the built-in Chinese stays as the fallback
  var strings = cfg.strings && typeof cfg.strings === 'object' ? cfg.strings : {};
  delete cfg.strings;
  function S(key, def) { var v = strings[key]; return typeof v === 'string' && v ? v : def; }
  // remote rules (73): extra hide selectors fetched natively from this app's own repository,
  // checked there (SHA-256 + schema) and here (each selector parsed on its own); never required
  var remote = { hide: [] };
  /** Checked copy of a rules.json (73); also used when Java hands over a fresh one (288). */
  function parseRemote(rr) {
    var r = { hide: [], version: 0 };
    if (!rr || typeof rr !== 'object') return r;
    if (Array.isArray(rr.hide)) r.hide = rr.hide.filter(function (x) { return typeof x === 'string' && x.length < 200; }).slice(0, 200);
    r.version = rr.version || 0;
    // sponsored-card labels the text fallback should also match (285), short exact texts only
    if (Array.isArray(rr.adText)) r.adText = rr.adText.filter(function (x) { return typeof x === 'string' && x.trim().length > 0 && x.length <= 20; }).slice(0, 20);
    // search quick filters (328): [label, sp] pairs replace the built-in ones when valid
    if (Array.isArray(rr.chips)) {
      var ch = rr.chips.filter(function (c) {
        return Array.isArray(c) && typeof c[0] === 'string' && c[0].trim().length > 0 && c[0].length <= 12 &&
          typeof c[1] === 'string' && /^[A-Za-z0-9%_-]{0,60}$/.test(c[1]);
      }).slice(0, 10);
      if (ch.length >= 2) r.chips = ch;
    }
    // extra words for the text matchers (382): plain words only, appended, never replacing
    if (rr.text && typeof rr.text === 'object') {
      r.text = {};
      for (var tk in rr.text) {
        if (!Object.prototype.hasOwnProperty.call(rr.text, tk) || !Array.isArray(rr.text[tk])) continue;
        r.text[tk] = rr.text[tk].filter(function (x) { return typeof x === 'string' && x.trim().length > 0 && x.length <= 30; }).slice(0, 20);
      }
    }
    return r;
  }
  try {
    if (B && typeof B.getRemoteRules === 'function') remote = parseRemote(JSON.parse(B.getRemoteRules() || '{}'));
  } catch (e) {}


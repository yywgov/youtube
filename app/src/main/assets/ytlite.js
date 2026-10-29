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
        '.ytp-ad-overlay-container', '.ytp-ad-player-overlay-layout', '.ytp-ad-persistent-progress-bar-container', '.ytp-ad-action-interstitial', '.ytp-ad-text-overlay', '.ytp-ad-image-overlay', 'a[href^="intent:"]', 'ytm-open-app-promo-renderer',
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
      // pivot "create / upload" entry (126), in-player channel watermark (163)
      pivotExtra: ['ytm-pivot-bar-item-renderer:has(a[href*="/upload"])', 'ytm-pivot-bar-item-renderer:has(.pivot-create)'],
      watermark: ['.html5-video-player .ytp-watermark', '.html5-video-player .annotation-type-custom.iv-branding'],
      own: ['[data-ytl-hide]', '[data-ytl-seen]', '[data-ytl-filter]']
    },
    // recommendation wall at the end of a video (36): only the grid, never the autoplay countdown
    endWall: ['.html5-video-player .ytp-endscreen-content', '.html5-video-player .ytp-endscreen-previous',
      '.html5-video-player .ytp-endscreen-next'],
    captionSeg: '.ytp-caption-segment',
    accentFill: ['.ytp-play-progress', '.ytp-swatch-background-color', '.ytm-progress-bar-played', '.YtProgressBarLineProgressBarPlayed',
      '.ytProgressBarLineProgressBarPlayed', '.ytp-scrubber-button'],
    amoledBg: ['body', 'ytm-app', 'ytm-mobile-topbar-renderer', '.mobile-topbar-header', 'ytm-pivot-bar-renderer', '#header-bar',
      'ytm-browse', 'ytm-watch', 'ytm-single-column-watch-next-results-renderer', 'ytm-engagement-panel'],
    menuBtn: ['ytm-media-item .media-item-menu button', 'ytm-video-with-context-renderer ytm-menu-renderer button',
      'ytm-compact-video-renderer ytm-menu-renderer button'],
    searchExtras: ['ytm-horizontal-card-list-renderer', '[data-ytl-searchx]'],
    subsPosts: ['ytm-backstage-post-thread-renderer', 'ytm-post-renderer', 'ytm-rich-section-renderer:has(ytm-backstage-post-thread-renderer)',
      'ytm-rich-item-renderer:has(ytm-backstage-post-thread-renderer)', 'ytm-rich-item-renderer:has(ytm-post-renderer)'],
    homeShelves: ['ytm-rich-section-renderer'],
    captionWin: '.caption-window',
    // ad fallback: black cover over the picture while an ad still plays (4)
    adCover: ['#movie_player.ad-showing video', '#movie_player.ad-interrupting video', '#movie_player.ytl-ad video'],
    adCoverPlayer: ['#movie_player.ad-showing', '#movie_player.ad-interrupting', '#movie_player.ytl-ad'],
    // smoothness rules, active only while html[data-ytl-perf] is set (settings toggle)
    perf: {
      cards: ['ytm-rich-item-renderer', 'ytm-video-with-context-renderer'],             // 49/50
      compact: ['ytm-compact-video-renderer', 'ytm-playlist-video-renderer'],
      // 51; scoped to the app root (258) so the substring tests never run on <head> / popups outside it
      noAnim: ['ytm-app [class*="skeleton"]', 'ytm-app [class*="Skeleton"]', 'ytm-app [class*="shimmer"]', 'ytm-app [class*="Shimmer"]'],
      noRipple: ['yt-touch-feedback-shape', '.yt-spec-touch-feedback-shape', '.ytSpecTouchFeedbackShapeHost']
    },
    sel: {
      video: 'video.video-stream',
      adSkip: '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-container,' +
        ' .ytp-ad-skip-button-slot button, button[id^="skip-button"], .ytm-skip-ad-button, ytm-skip-ad-button button',
      watchedBar: '.ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment, .thumbnail-overlay-resume-playback-progress,' +
        ' ytm-thumbnail-overlay-resume-playback-renderer [style*="width"]',
      feedItem: 'ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-playlist-video-renderer',
      // short labels on a card that mark it as sponsored (285): matched by exact text only
      adLabel: 'badge-shape, .badge-shape-wiz__text, ytm-badge, ytm-badge-supported-renderer, [class*="ad-badge" i], [class*="AdBadge"]',
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
      relatedItem: 'ytm-video-with-context-renderer, ytm-compact-video-renderer',
      byline: '[class*="byline" i], ytm-badge-and-byline-renderer, .subhead',
      ownerLink: 'ytm-slim-owner-renderer a[href], .slim-owner-icon-and-title[href], ytm-video-owner-renderer a[href]',
      cardTitle: '.media-item-headline, h3, h4, [class*="title" i]',
      timeBadge: 'ytm-thumbnail-overlay-time-status-renderer, .badge-shape-wiz__text, badge-shape, [class*="time-status" i]',
      cardMeta: '.media-item-metadata, [class*="metadata" i], ytm-badge-and-byline-renderer',
      cardMenuBtn: 'ytm-menu-renderer button, .media-item-menu button',
      searchInput: 'input.searchbox-input, ytm-searchbox input, input[name="search_query"]',
      searchList: 'ytm-search ytm-section-list-renderer, ytm-section-list-renderer',
      searchShelf: 'ytm-shelf-renderer, ytm-horizontal-card-list-renderer, ytm-rich-shelf-renderer',
      shelfTitle: 'h2, h3, .shelf-header, [class*="title" i]',
      maxresImg: 'img[src*="/maxresdefault"]',
      // batch 6
      // comments / description panels on the watch page (264): mutations in here never touch a list card
      quietZone: 'ytm-engagement-panel, ytm-comment-section-renderer, ytm-comment-thread-renderer, ytm-comments-entry-point-header-renderer',
      // a result that is an auto-generated mix (333): its link carries list=RD…
      mixLink: 'a[href*="list=RD"]',
      mixCard: 'ytm-compact-radio-renderer, ytm-radio-renderer, ytm-compact-playlist-renderer, ytm-playlist-renderer,' +
        ' ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-rich-item-renderer, yt-lockup-view-model',
      listImg: 'img:not([decoding])',
      playerBox: '#player-container-id'
    },
    // every on-page text the script matches, by language, in one place (382); mode: full = the whole
    // text, start = the text starts with it, any = anywhere. rules.json "text" can append plain words.
    text: {
      still: ['any', ['continue watching', 'still watching', 'still there', 'are you there', 'video paused', '还在看吗', '你还在吗', '继续观看', '仍在观看', '视频已暂停', '繼續觀看', '仍在觀看', '影片已暫停']],
      stillYes: ['full', ['是', '是的', '继续', '继续观看', '继续播放', '繼續', '繼續觀看', '繼續播放', 'yes', 'continue', 'continue watching', 'keep watching', 'i\'m still here', 'still watching']],
      stillNo: ['full', ['否', '不', '取消', '退出', '停止', 'no', 'cancel', 'exit', 'stop', 'dismiss']],
      retry: ['any', ['try again', 'playback id', 'error occurred', '重试', '重新整理', '稍后', '出错', '發生錯誤', '错误']],
      live: ['start', ['LIVE', '直播', '直播中', '正在直播', 'PREMIERE', 'PREMIERES?', '首映', '首播', 'UPCOMING', '即将', '即將', 'SCHEDULED']],
      searchExtra: ['any', ['people also', 'also watched', 'also search', 'for you', 'previously watched', 'related to your search', '人们还', '大家还', '其他人还', '为你推荐', '為你推薦', '相关搜索', '相關搜尋', '你看过', '看過的影片']],
      adLabel: ['full', ['赞助', '贊助', '广告', '廣告', '赞助商广告', '贊助商廣告', 'sponsored', 'ad', 'ads']],
      feedback: ['full', ['help', 'help ?(?:&|and) ?feedback', 'send feedback', 'feedback', 'give feedback', 'report', 'report history', 'report (?:video|playlist|channel|comment|ad|this ad)', 'why this ad\\??', '帮助', '帮助和反馈', '帮助与反馈', '帮助及反馈', '发送反馈', '提供反馈', '反馈', '意见反馈', '举报', '举报记录', '举报视频', '举报播放列表', '举报频道', '說明', '說明與意見回饋', '提供意見', '傳送意見回饋', '提供意見回饋', '意見回饋', '檢舉', '檢舉記錄', '檢舉影片']]
    },
    // one-tap sleep mode (340): comments only; related videos stay for the same-channel rule
    sleepHide: ['ytm-comment-section-renderer', 'ytm-comments-entry-point-header-renderer',
      'ytm-item-section-renderer[section-identifier="comments-entry-point"]', 'ytm-item-section-renderer[data-content-type="comments"]'],
    // search quick filters (125/328): YouTube's own sp values; rules.json "chips" can replace them
    chips: [['全部', ''], ['最新', 'CAI%3D'], ['只看视频', 'EgIQAQ%3D%3D'], ['本周', 'EgIIAw%3D%3D'], ['4–20 分钟', 'EgIYAw%3D%3D'], ['20 分钟以上', 'EgIYAg%3D%3D']]
  };

  function escRe(x) { return String(x).replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&'); }
  /** Matcher for one entry of RULES.text (382), with the words rules.json appends. */
  function textRe(name) {
    var t = RULES.text[name], alts = t[1].slice();
    var add = remote.text && remote.text[name];
    if (add) for (var i = 0; i < add.length; i++) alts.push(escRe(add[i].trim()));
    var src = '(?:' + alts.join('|') + ')';
    if (t[0] === 'full') src = '^' + src + '$';
    else if (t[0] === 'start') src = '^' + src;
    return new RegExp(src, 'i');
  }

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
    // placeholder height from the screen width (265): 16:9 picture + title block, the real height is remembered (auto)
    rule(pre(RULES.perf.cards), 'content-visibility:auto;contain-intrinsic-size:auto 360px;contain-intrinsic-size:auto min(calc(56.25vw + 112px),720px);contain:layout style');
    rule(pre(RULES.perf.compact), 'content-visibility:auto;contain-intrinsic-size:auto 110px;contain:layout style');
    rule(pre(RULES.perf.noAnim), 'animation:none!important;transition:none!important');
    rule(pre(RULES.perf.noRipple), 'display:none!important');
    rule(RULES.endWall.map(function (x) { return 'html[data-ytl-noendwall] ' + x; }), 'display:none!important');
    // caption style (155): size by transform (no layout change), background opacity
    var cw = RULES.captionWin, cs = RULES.captionSeg;
    out.push('html[data-ytl-subsize="l"] ' + cw + '{transform:scale(1.25);transform-origin:50% 100%}');
    out.push('html[data-ytl-subsize="xl"] ' + cw + '{transform:scale(1.5);transform-origin:50% 100%}');
    out.push('html[data-ytl-subsize="s"] ' + cw + '{transform:scale(.85);transform-origin:50% 100%}');
    out.push('html[data-ytl-subbg="half"] ' + cs + '{background:rgba(8,8,8,.45)!important}');
    out.push('html[data-ytl-subbg="none"] ' + cs + '{background:transparent!important;text-shadow:0 0 3px #000,0 0 2px #000,1px 1px 2px #000}');
    // ---- batch 3 look (each behind its own html attribute, so a setting off means no rule applies) ----
    // design tokens for our own controls (164)
    out.push(':root{--ytl-accent:#2f6bff;--ytl-accent-text:#6b95ff;--ytl-radius:12px;--ytl-surface:rgba(33,33,33,.96);--ytl-anim:200ms}');
    out.push('html[data-ytl-accent]{--ytl-accent:var(--ytl-accent-pick);--ytl-accent-text:var(--ytl-accent-pick)}');
    rule(RULES.accentFill.map(function (x) { return 'html[data-ytl-accent] ' + x; }), 'background:var(--ytl-accent)!important;background-color:var(--ytl-accent)!important');
    // pure black (91)
    out.push('html[data-ytl-amoled][dark]{--yt-spec-base-background:#000!important;--yt-spec-brand-background-solid:#000!important;' +
      '--yt-spec-brand-background-primary:#000!important;--yt-spec-general-background-a:#000!important;--yt-spec-general-background-b:#000!important;' +
      '--yt-spec-raised-background:#121212!important;--yt-spec-menu-background:#121212!important;background:#000!important}');
    rule(RULES.amoledBg.map(function (x) { return 'html[data-ytl-amoled][dark] ' + x; }), 'background-color:#000!important');
    // compact list (108): small thumbnail left, text right
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item{display:flex!important;flex-direction:row!important;align-items:flex-start;padding:6px 12px!important;margin:0!important}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>.media-item-thumbnail-container,' +
      'html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>a:first-child{flex:0 0 42%;max-width:42%;border-radius:8px;overflow:hidden}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>.details{flex:1 1 auto;min-width:0;padding:0 0 0 10px!important;margin:0!important}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item .media-channel{display:none!important}');
    out.push('html[data-ytl-compact][data-ytl-perf] ytm-rich-item-renderer{contain-intrinsic-size:auto 120px;contain-intrinsic-size:auto max(96px,calc(23.625vw + 16px))}');
    // uniform cards (106/107/111)
    out.push('html[data-ytl-cards] .media-item-thumbnail-container,html[data-ytl-cards] yt-thumbnail-view-model{border-radius:12px;overflow:hidden}');
    out.push('html[data-ytl-cards] .media-item-headline,html[data-ytl-cards] ytm-media-item h3{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;font-weight:500}');
    out.push('html[data-ytl-cards] ' + RULES.sel.watchedBar.split(',').join(',html[data-ytl-cards] ') + '{min-height:4px}');
    out.push('html[data-ytl-cards] ytm-thumbnail-overlay-time-status-renderer,html[data-ytl-cards] .badge-shape-wiz--thumbnail-default{font-weight:600}');
    // dim thumbnails in dark mode (110)
    out.push('html[dark][data-ytl-dimthumb] .media-item-thumbnail-container img,html[dark][data-ytl-dimthumb] yt-thumbnail-view-model img{filter:brightness(.88)}');
    // bigger hit area for the card "more" button without moving anything (122)
    rule(RULES.menuBtn, 'position:relative');
    rule(RULES.menuBtn.map(function (x) { return x + '::after'; }), "content:'';position:absolute;inset:-8px");
    // search extras (70), subscriptions videos only (63), home shelves (61)
    rule(RULES.searchExtras.map(function (x) { return 'html[data-ytl-nosearchx][data-ytl-page="search"] ' + x; }), 'display:none!important');
    rule(RULES.subsPosts.map(function (x) { return 'html[data-ytl-subsvideos][data-ytl-page="subs"] ' + x; }), 'display:none!important');
    rule(RULES.homeShelves.map(function (x) { return 'html[data-ytl-noshelves][data-ytl-page="home"] ' + x; }), 'display:none!important');
    // battery saver (147): animations off even when the smoothness CSS is off
    rule(RULES.perf.noAnim.map(function (x) { return 'html[data-ytl-lowpower] ' + x; }), 'animation:none!important;transition:none!important');
    // system "remove animations" (367): page transitions and our own fades off
    rule(RULES.perf.noAnim.map(function (x) { return 'html[data-ytl-lowmotion] ' + x; }), 'animation:none!important;transition:none!important');
    out.push('html[data-ytl-lowmotion] #ytl-sb,html[data-ytl-lowmotion] #ytl-chips a{transition:none!important;animation:none!important}');
    // very large text (378): card titles get a third line instead of being cut after two
    out.push('html[data-ytl-bigfont] .media-item-headline,html[data-ytl-bigfont] ytm-media-item h3{-webkit-line-clamp:3!important}');
    // update ready (137)
    out.push('html[data-ytl-update] #ytl-gear{position:relative}html[data-ytl-update] #ytl-gear::after{content:"";position:absolute;top:9px;right:9px;width:8px;height:8px;border-radius:50%;background:#ff4e45}');
    // search quick filters (125)
    out.push('#ytl-chips{display:flex;gap:8px;overflow-x:auto;padding:8px 12px;scrollbar-width:none}#ytl-chips::-webkit-scrollbar{display:none}' +
      '#ytl-chips a{flex:0 0 auto;padding:6px 12px;border-radius:8px;background:rgba(128,128,128,.18);color:inherit;font:500 13px/18px system-ui,sans-serif;text-decoration:none;white-space:nowrap}' +
      '#ytl-chips a[aria-current]{background:var(--ytl-accent);color:#fff}');
    // sleep badge (154): a tap adds time, a long press opens the timer choices (309); 40px touch target
    out.push('#ytl-badge{position:absolute;top:4px;left:4px;z-index:70;min-height:40px;display:flex;align-items:center;' +
      'padding:0 4px;background:none;border:0;margin:0;color:#fff;cursor:pointer;-webkit-tap-highlight-color:transparent}' +
      '#ytl-badge span{background:rgba(0,0,0,.6);font:500 12px/16px system-ui,sans-serif;padding:3px 8px;border-radius:10px;white-space:nowrap}');
    out.push('#ytl-tools{display:flex;align-items:center;justify-content:center;width:48px;height:48px;background:none;border:0;padding:0;margin:0 0 0 2px;color:inherit;fill:currentColor}');
    out.push('#ytl-gear{display:flex;align-items:center;justify-content:center;width:48px;height:48px;background:none;border:0;padding:0;margin:0 2px;color:inherit;fill:currentColor}');
    out.push('#ytl-sb{position:fixed;left:50%;bottom:76px;transform:translateX(-50%);z-index:2147483646;display:flex;align-items:center;gap:14px;' +
      'background:rgba(33,33,33,.96);color:#fff;font:14px/20px system-ui,sans-serif;padding:10px 14px 10px 16px;border-radius:10px;' +
      'box-shadow:0 2px 10px rgba(0,0,0,.5);max-width:90vw;white-space:nowrap}' +
      '#ytl-sb button{background:none;border:0;color:var(--ytl-accent-text);font:500 14px system-ui,sans-serif;padding:4px 6px;margin:0}');
    // batch 6 (v1.8.0)
    // search: auto-generated mixes (333)
    out.push('html[data-ytl-nomix][data-ytl-page="search"] [data-ytl-searchmix]{display:none!important}');
    // long press feedback (368): a light wash on the card while the finger is held
    out.push('[data-ytl-press]{background-color:rgba(128,128,128,.16)!important;border-radius:12px;transition:background-color .12s}');
    out.push('html[data-ytl-lowmotion] [data-ytl-press]{transition:none}');
    // moon button only with a video or a timer (363)
    out.push('#ytl-tools[data-ytl-off]{display:none!important}');
    // one-tap sleep mode (340): no comments under the player (related videos stay: the same-channel rule reads them)
    rule(RULES.sleepHide.map(function (x) { return 'html[data-ytl-sleepmode][data-ytl-page="watch"] ' + x; }), 'display:none!important');
    // empty feed note (326)
    out.push('#ytl-empty{margin:12px;padding:12px 14px;border-radius:12px;background:rgba(128,128,128,.14);font:14px/20px system-ui,sans-serif;' +
      'color:inherit;display:flex;gap:12px;align-items:center}#ytl-empty button{margin-left:auto;background:none;border:0;' +
      'color:var(--ytl-accent-text);font:500 14px system-ui,sans-serif;padding:8px 4px;white-space:nowrap}');
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
    // finer list pages (325): rules for the subscriptions feed no longer reach history, and the
    // playlist page (watch later) gets the list features
    if (p === '/feed/subscriptions') return 'subs';
    if (p === '/feed/history') return 'history';
    if (p === '/feed/library' || p === '/feed/you' || p === '/feed/playlists') return 'library';
    if (p === '/playlist') return 'playlist';
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

  var metaTimer = 0, lastMetaKey = '', metaHeld = false;
  function scheduleMeta() { clearTimeout(metaTimer); metaTimer = setTimeout(sendMeta, 150); }
  var sendMeta = guard('meta', function () {
    var v = video();
    var id = videoId();
    if (!v || !id) return;
    // an ad still playing (283): its title / picture never reach the notification or lock screen;
    // the real metadata goes out once the ad is over (skipAd)
    if (adNow()) { metaHeld = true; return; }
    var title = '', artist = '', art = '';
    if (meta && meta.title) {
      title = meta.title; artist = meta.artist || '';
      var aw = meta.artwork || [];
      if (aw.length) art = aw[aw.length - 1].src || '';
    }
    if (!title) title = (document.title || '').replace(/ - YouTube$/, '');
    // channel name for the notification when YouTube's metadata has none (116)
    if (!artist) { try { var vd0 = player() && player().getVideoData && player().getVideoData(); artist = (vd0 && vd0.author) || ''; } catch (e) {} }
    // notification art: hqdefault is plenty, never the multi-megabyte maxres (47)
    if (art) art = art.replace('/maxresdefault', '/hqdefault');
    if (!art) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
    var dur = isFinite(v.duration) ? Math.round(v.duration * 1000) : 0;
    // a live stream right now (294): the lock screen gets no length and no seek bar
    var li = lenById[id], isLive = !!((li && li.now) || v.duration === Infinity);
    var key = id + '|' + title + '|' + artist + '|' + art + '|' + dur + '|' + isLive;
    if (key === lastMetaKey) return;
    lastMetaKey = key;
    call('mediaLive', isLive);
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
    fadeStop();
    var v = video();
    if (v && savedVolume >= 0) v.volume = savedVolume;
    savedVolume = -1;
  }
  /* Sleep fade driven here (299): Java says once "the fade ends in leftMs, it lasts fadeMs" and the
   * page steps the volume every 250 ms on that curve (setTimeout: rAF does not run with the screen
   * off). Java sends it again every few seconds, which also covers a reload in the middle. */
  var fade = { timer: 0, end: 0, len: 0 };
  function fadeStop() { clearTimeout(fade.timer); fade.timer = 0; fade.end = 0; }
  function fadeStep() {
    fade.timer = 0;
    if (!fade.end) return;
    var left = fade.end - Date.now();
    setVolume(fadeVolume(Math.max(0, left), fade.len));
    if (left > 0) fade.timer = setTimeout(guard('fade', fadeStep), 250);
  }
  function fadeStart(leftMs, fadeMs) {
    leftMs = Number(leftMs); fadeMs = Number(fadeMs);
    if (!(fadeMs > 0) || !isFinite(leftMs)) return;
    fade.end = Date.now() + Math.max(0, leftMs);
    fade.len = fadeMs;
    clearTimeout(fade.timer);
    fadeStep();
  }
  function sleepPause() { fadeStop(); pause(); setTimeout(restoreVolume, 400); }
  /** Sleep fade (298): (left / fade)², same curve as the Java timer (Logic.fadeVolume). */
  function fadeVolume(left, fade) {
    if (!(fade > 0)) return left > 0 ? 1 : 0;
    var x = left / fade;
    if (x >= 1) return 1;
    if (x <= 0) return 0;
    return x * x;
  }

  /* ---------- 3b. playback tools: seek by, fine speed, single-video loop (26/151/152) ---------- */
  // A-B loop, playlist shuffle (152/153) and keyboard keys (133) were removed in v1.10 (450/451/453)
  var loopOne = false;
  var ourRate = 0;            // rate we set ourselves (not a user change to remember)
  function seekBy(sec) {
    var v = video(); if (!v || adNow()) return;
    var t = (v.currentTime || 0) + Number(sec || 0);
    if (isFinite(v.duration) && v.duration > 0) t = Math.min(t, v.duration - 0.5);
    v.currentTime = Math.max(0, t);
  }
  function clampRate(r) {
    r = Math.round(Number(r) * 100) / 100;
    if (!isFinite(r) || r <= 0) return 1;
    return Math.max(0.25, Math.min(2, r));
  }
  function setRate(r) {
    var v = video(); if (!v || adNow()) return;
    r = clampRate(r);
    ourRate = r;
    try { v.playbackRate = r; } catch (e) {}
    if (cfg.rememberRate) { cfg.rate = r; call('saveRate', String(r)); }
  }
  function loopChanged() { autonavFor = ''; if (started) forceAutonav(); }
  function setLoop(on) { loopOne = !!on; loopChanged(); }
  function hasList() { return /[?&]list=/.test(location.search); }
  function toolState() {
    var v = video();
    return {
      // a loaded video counts, also in the mini player while browsing (338)
      watch: !!(curId && v) || isWatch(), id: curId, rate: v ? (v.playbackRate || 1) : 1, loop: loopOne,
      list: hasList()
    };
  }
  function looping() { return loopOne; }
  /** A loop came round (312): with an "after N videos" timer every repeat counts as one video. */
  var loopRound = 0;
  function loopWrapped() {
    loopRound++;
    if (countMode) call('videoEnded', curId + '#' + loopRound);
  }
  /** Loop check on every timeupdate; jumps back before the end so YouTube never moves on. */
  function loopTick(v) {
    if (adNow() || stopAtEnd) return;
    var t = v.currentTime || 0, rate = v.playbackRate || 1;
    if (loopOne && isFinite(v.duration) && v.duration > 3 && v.duration - t <= 0.6 * rate) { v.currentTime = 0; loopWrapped(); }
  }

  /* ---------- 4. quality: lowest in background, preferred default in foreground (29) ---------- */
  var QMAP = { '1080': 'hd1080', '720': 'hd720', '480': 'large' };
  function metered() {
    try { return !!(B && typeof B.isMetered === 'function' && B.isMetered()); } catch (e) { return false; }
  }
  /** Preferred foreground quality name for the player, or 'auto'. */
  var QORDER = ['tiny', 'small', 'medium', 'large', 'hd720', 'hd1080', 'highres'];
  function prefQuality() {
    if (sleepMode) return 'tiny';                       // one-tap sleep mode (97)
    var q = cfg.quality || 'auto';
    q = q === 'wifiMax' ? (metered() ? 'hd720' : 'highres') : (QMAP[q] || 'auto');
    // battery saver (147): at most 480p
    if (cfg.powerSave && (q === 'auto' || QORDER.indexOf(q) > QORDER.indexOf('large'))) q = 'large';
    return q;
  }
  function setQ(p, q) {
    if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(q, q);
    if (p.setPlaybackQuality) p.setPlaybackQuality(q);
  }
  var qualityFor = '';
  var applyQuality = guard('quality', function () {
    if (!isWatch()) return;
    var p = player(); if (!p) return;
    if (bg && cfg.lowQualityBg) {
      setQ(p, 'tiny');
    } else if (p.__ytlLow) {
      setQ(p, prefQuality());
      qualityFor = curId;
    } else if (qualityFor !== curId && curId) {
      // once per video, so a quality the user picks by hand afterwards is kept
      qualityFor = curId;
      var q = prefQuality();
      if (q !== 'auto') { setQ(p, q); verifyQuality(q); }
    }
    p.__ytlLow = bg && cfg.lowQualityBg;
  });
  /* default quality really applied? (339) once per video, 3 s later: a mismatch goes to the diagnostics
   * log (only when the wanted level is one this video offers) */
  var qCheckedFor = '';
  function verifyQuality(q) {
    var id = curId;
    if (!id || qCheckedFor === id || q === 'auto') return;
    qCheckedFor = id;
    setTimeout(guard('quality-check', function () {
      var p = player();
      if (!p || curId !== id || bg || adNow() || typeof p.getPlaybackQuality !== 'function') return;
      var got = p.getPlaybackQuality();
      var avail = typeof p.getAvailableQualityLevels === 'function' ? (p.getAvailableQualityLevels() || []) : [];
      if (got && got !== q && avail.indexOf(q) >= 0) log('quality', 'wanted ' + q + ', player kept ' + got + ' (' + id + ')');
    }), 3000);
  }
  /* the phone moved to another network (314): "Wi-Fi best, mobile 720p" is decided again */
  var netChanged = guard('net-change', function () {
    if (cfg.quality !== 'wifiMax' || !isWatch() || bg || sleepMode) return;
    qualityFor = '';
    applyQuality();
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
    if (!navigator.onLine) { stalled = true; call('netStalled', true); return; } // wait for the next 'online'
    if (++netTries >= 3) { netTries = 0; call('netStalled', true); reloadAt('net-retry'); return; }
    stalled = true;
    onNet();
  });
  var checkStuck = guard('stuck', function () {
    var v = video();
    if (!v || v.paused || userPaused || adNow()) return;
    if (v.readyState >= 3) return;
    if (!navigator.onLine) { stalled = true; call('netStalled', true); return; }
    retryOnce('stuck');
  });
  // "An error occurred, please try again (playback id …)": retry once. Unavailable / private /
  // age-gated videos say something else and are left alone.
  var RETRY_TEXT_RE = textRe('retry');
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
  /** Small bar at the bottom of the page with one action (SponsorBlock undo, hide undo 324). */
  function snackBar(text, action, fn, ms) {
    hideSbPrompt();
    var host = document.body || document.documentElement;
    if (!host) return;
    var el = document.createElement('div');
    el.id = 'ytl-sb';
    el.setAttribute('role', 'status');
    var span = document.createElement('span');
    span.textContent = text;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = action;
    btn.addEventListener('click', guard('snack-action', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      hideSbPrompt();
      fn();
    }), true);
    el.appendChild(span);
    el.appendChild(btn);
    host.appendChild(el);
    sbTimer = setTimeout(hideSbPrompt, ms);
  }
  function showSbPrompt(idx, seg) {
    snackBar(S('skipped', '已跳过') + (SB_LABEL[seg[2]] || '赞助片段'), S('undo', '撤销'), function () {
      segSkipped[idx] = true;
      var v = video();
      if (v) v.currentTime = seg[0];
    }, 2000);
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
    fadeStart: function (l, f) { fadeStart(l, f); },
    // a native command threw inside the page (247): goes to the diagnostics log instead of nowhere
    report: function (name, e) { report('java:' + String(name || '').slice(0, 40), e); },
    stopAtEnd: function (b) { stopAtEnd = !!b; if (!b) { pendingStop = false; restoreVolume(); } },
    // "after N videos" reached its last one (16). Looping (312): the repeat now playing is that one.
    // Counted on an early switch (301): the video that just started is the last one.
    stopAtEndNext: function () { if (looping() || Date.now() - countedOnChange < 5000) stopAtEnd = true; else pendingStop = true; },
    bg: function (b) { setBg(!!b); },
    cfg: function (o) { cfg = Object.assign(cfg, o || {}); applyCfg(); },
    onSegments: function (id, arr) {
      if (id !== segFor) return;
      segments = (arr || []).filter(function (s) { return s && isFinite(s[0]) && isFinite(s[1]) && s[1] > s[0]; });
      segSkipped = {};
    },
    onNet: onNet,
    netChanged: function () { netChanged(); },
    /* backup with local data (403): hidden videos and local resume positions, only from this phone */
    localData: function () {
      var o = { hidden: hiddenIds().filter(function (x) { return /^[\w-]{6,20}$/.test(x); }).slice(0, 300) };
      var p = posStore(), out = {};
      for (var k in p) if (Object.prototype.hasOwnProperty.call(p, k) && /^[\w-]{6,20}$/.test(k) && Array.isArray(p[k])) out[k] = p[k];
      o.pos = out;
      return o;
    },
    /* restore it: merged into what is here, never replacing it; returns how many entries were added */
    importLocal: guard('import-local', function (json) {
      var o = typeof json === 'string' ? JSON.parse(json) : json, added = 0;
      if (!o || typeof o !== 'object') return 0;
      if (Array.isArray(o.hidden)) {
        hiddenList = hiddenIds();
        o.hidden.forEach(function (id) { if (/^[\w-]{6,20}$/.test(id) && hiddenList.indexOf(id) < 0) { hiddenList.push(id); added++; } });
        if (hiddenList.length > 300) hiddenList.length = 300;
        saveHidden();
        filterKey = '';
      }
      if (o.pos && typeof o.pos === 'object') {
        var st = posStore();
        for (var id in o.pos) {
          if (!Object.prototype.hasOwnProperty.call(o.pos, id) || !/^[\w-]{6,20}$/.test(id)) continue;
          var e = o.pos[id];
          if (!Array.isArray(e) || !(e[0] > 0) || !(e[1] > 0)) continue;
          if (!st[id] || st[id][1] < e[1]) { st[id] = [Math.floor(e[0]), e[1]]; added++; }
        }
        posSave(st);
      }
      if (started) filterCards();
      return added;
    }),
    /* smoothness self test (268): scroll the list up and down for a while; Java counts the frames */
    selfScroll: function (ms) {
      if (!listPage(pageType())) return false;
      var end = Date.now() + Math.min(15000, ms || 10000), dir = 1;
      (function f() {
        if (Date.now() > end) return;
        var y = window.scrollY || 0;
        window.scrollBy(0, dir * 24);
        if ((window.scrollY || 0) === y) dir = -dir;
        requestAnimationFrame(f);
      })();
      return true;
    },
    /* fresh verified rules from Java (288): applied at once instead of on the next page load */
    remote: guard('remote-apply', function (json) {
      var r;
      try { r = parseRemote(JSON.parse(json)); } catch (e) { return false; }
      if ((r.version || 0) < (remote.version || 0)) return false;
      remote = r;
      var old = document.getElementById('ytl-css-remote');
      if (old && old.parentNode) old.parentNode.removeChild(old);
      filterKey = '';
      fullNeeded = true;
      if (started) { addRemoteCss(); queuePass(); }
      log('rules', 'applied v' + r.version);
      return true;
    }),
    noisy: function () { pause(); },
    trim: function () { trimCaches(); },
    seekBy: function (s) { seekBy(s); },
    setRate: function (r) { setRate(r); },
    loop: function (b) { setLoop(b); },
    state: function () { return JSON.stringify(toolState()); },
    sleepState: function (b) { sleeping = !!b; autonavFor = ''; if (started && isWatch()) forceAutonav(); if (started) updateToolsBtn(); },
    countMode: function (b) { countMode = !!b; },
    badge: function (t) { badgeText = t ? String(t) : ''; updateBadge(); },
    // batch 3
    watchLater: function (id) { watchLater(id ? String(id) : ''); },
    hideVideo: function (id) { hideVideo(String(id || '')); },
    openCardMenu: function () { openCardMenu(); },
    hiddenCount: function () { return hiddenIds().length; },   // settings: "N hidden videos" (235)
    unhideAll: function () { hiddenList = []; try { localStorage.removeItem(HIDDEN_KEY); } catch (e) {} filterKey = ''; if (started) filterCards(); },
    sleepMode: function (b) {
      sleepMode = !!b; qualityFor = ''; autonavFor = '';
      try { applyPerfAttr(); } catch (e) {}
      if (!sleepMode) { var p = player(); if (p && isWatch() && !bg) { try { setQ(p, prefQuality()); } catch (e) {} } }
      applyQuality();
      if (started && isWatch()) forceAutonav();
    }
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
  // built-in list == assets/adkeys.json == WatchPageFilter's fallback (279, contract test);
  // keys added to adkeys.json reach both layers from one place
  var AD_KEYS_BUILTIN = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var AD_KEYS = AD_KEYS_BUILTIN.slice();
  for (var ak = 0; ak < extraAdKeys.length; ak++) if (AD_KEYS.indexOf(extraAdKeys[ak]) < 0) AD_KEYS.push(extraAdKeys[ak]);
  var AD_ALT = AD_KEYS.join('|');
  var AD_TEST = new RegExp('"(?:' + AD_ALT + ')"');
  var AD_SUB = new RegExp('"(' + AD_ALT + ')"', 'g');
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
  var PR_DROP = toSet(AD_KEYS.concat(['paidContentOverlay', 'videoQualityPromoSupportedRenderers'],
    AD_KEYS.map(function (k) { return k + '_ytl'; })));
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
      // live, upcoming, premieres and their replays never use the length check (282): their
      // lengthSeconds is often not what the element ends up playing
      var pmr = o.microformat && o.microformat.playerMicroformatRenderer;
      var liveish = !!(vd.isLive || vd.isLiveContent || vd.isUpcoming || vd.isPostLiveDvr ||
        (pmr && (pmr.liveBroadcastDetails || pmr.isLiveContent)));
      lenById[vd.videoId] = { len: isFinite(len) ? len : 0, live: liveish, now: !!(vd.isLive || vd.isUpcoming) };
      noteChannel(vd.videoId, vd.channelId, o.microformat);
    }
    preconnectStream(o.streamingData);
    if (vd && vd.videoId) noteAudioTracks(vd.videoId, o.streamingData);
    return o;
  }
  /* channel of each video (310): from videoDetails.channelId and the microformat's owner URL */
  var chanById = {}, chanIds = [];
  function noteChannel(id, channelId, mf) {
    try {
      var url = mf && mf.playerMicroformatRenderer && mf.playerMicroformatRenderer.ownerProfileUrl;
      var h = /\/(@[^\/?#]+)/.exec(url || '');
      var ci = { id: typeof channelId === 'string' ? channelId : '', handle: h ? decodeURIComponent(h[1]).toLowerCase() : '' };
      if (!ci.id && !ci.handle) return;
      if (!chanById[id]) { chanIds.push(id); if (chanIds.length > 40) delete chanById[chanIds.shift()]; }
      chanById[id] = ci;
    } catch (e) {}
  }
  /* original audio track per video (32): "… original" track, else the one marked default */
  var origAudio = {}, origIds = [];
  var ORIG_RE = /original|原始|原声|原聲|オリジナル|원본/i;
  function noteAudioTracks(id, sd) {
    try {
      var f = sd && sd.adaptiveFormats;
      if (!f || !f.length) return;
      var ids = {}, n = 0, byName = '', byDefault = '';
      for (var i = 0; i < f.length; i++) {
        var t = f[i] && f[i].audioTrack;
        if (!t || !t.id) continue;
        if (!ids[t.id]) { ids[t.id] = true; n++; }
        if (!byName && ORIG_RE.test(t.displayName || '')) byName = t.id;
        if (!byDefault && t.audioIsDefault) byDefault = t.id;
      }
      if (n < 2) return;
      if (!origAudio[id]) { origIds.push(id); if (origIds.length > 40) delete origAudio[origIds.shift()]; }
      origAudio[id] = byName || byDefault || '';
    } catch (e) {}
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
    call('logAd', id, type, pageType(), adContext());
  }
  /** What a missed-ad line needs to tell which layer failed (280); Java adds WebView / native rewrite. */
  function adContext() {
    var sc = selfCheck(true), bad = Object.keys(sc).filter(function (k) { return !sc[k]; });
    var li = '';
    try { li = window.ytcfg && typeof ytcfg.get === 'function' ? String(ytcfg.get('LOGGED_IN')) : ''; } catch (e) {}
    return 'sc=' + (bad.length ? 'FAIL:' + bad.join('+') : 'ok') + ' login=' + (li || '?') + ' rules=' + (remote.version || 0) +
      ' keys=' + AD_KEYS.length;
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
      if (metaHeld) { metaHeld = false; lastMetaKey = ''; scheduleMeta(); }   // the real metadata now (283)
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


  /* ---------- 10b. per-video preferences, applied once when a video really starts ---------- */
  var startedFor = '';
  function onVideoStart(v) {
    if (!curId || startedFor === curId || adNow() || !isWatch()) return;
    startedFor = curId;
    applyRate(v);
    applyCaptions();
    applyOrigAudio();
    resumeLocal(v);
  }
  // remembered speed (30)
  function applyRate(v) {
    if (!cfg.rememberRate) return;
    var r = clampRate(cfg.rate || 1);
    if (r !== 1 && v.playbackRate !== r) { ourRate = r; try { v.playbackRate = r; } catch (e) {} }
  }
  // captions default (31/341): follow YouTube / always off / turn on when the video has Chinese
  // (any, simplified first or traditional first) or English; human captions before auto ones
  var CAP_LANGS = {
    zh: [/^zh/i],
    zhHans: [/^zh-(?:hans|cn|sg|my)\b/i, /^zh$/i, /^zh/i],
    zhHant: [/^zh-(?:hant|tw|hk|mo)\b/i, /^zh/i],
    en: [/^en\b/i, /^en/i]
  };
  function pickCaption(list, mode) {
    var order = CAP_LANGS[mode];
    if (!order || !list || !list.length) return null;
    for (var asr = 0; asr < 2; asr++) {
      for (var o = 0; o < order.length; o++) {
        for (var i = 0; i < list.length; i++) {
          var t = list[i], code = (t && (t.languageCode || t.vss_id || '')) || '';
          if (!t || (asr === 0 && t.kind === 'asr') || (asr === 1 && t.kind !== 'asr')) continue;
          if (order[o].test(code)) return t;
        }
      }
    }
    return null;
  }
  function applyCaptions() {
    var p = player(); if (!p || cfg.captions === 'yt' || !cfg.captions) return;
    if (cfg.captions === 'off') { if (typeof p.unloadModule === 'function') p.unloadModule('captions'); return; }
    if (!CAP_LANGS[cfg.captions] || typeof p.getOption !== 'function' || typeof p.setOption !== 'function') return;
    var id = curId, mode = cfg.captions;
    setTimeout(guard('captions', function () {
      if (videoId() !== id) return;
      var list = p.getOption('captions', 'tracklist') || [];
      if (!list.length && typeof p.loadModule === 'function') { p.loadModule('captions'); list = p.getOption('captions', 'tracklist') || []; }
      var pick = pickCaption(list, mode);
      if (pick) { if (typeof p.loadModule === 'function') p.loadModule('captions'); p.setOption('captions', 'track', pick); }
    }), 1500);
  }
  // original audio instead of an auto-dub (32): only for videos that have several audio tracks
  function trackId(t) {
    try {
      var li = t && t.getLanguageInfo && t.getLanguageInfo();
      if (li && typeof li.getId === 'function') return li.getId() || '';
      return (t && t.id) || '';
    } catch (e) { return ''; }
  }
  function applyOrigAudio() {
    if (!cfg.origAudio) return;
    var id = curId, want = origAudio[id];
    var p = player();
    if (!want || !p || typeof p.getAvailableAudioTracks !== 'function' || typeof p.setAudioTrack !== 'function') return;
    setTimeout(guard('orig-audio', function () {
      if (videoId() !== id || adNow()) return;
      var cur = typeof p.getAudioTrack === 'function' ? p.getAudioTrack() : null;
      if (cur && trackId(cur) === want) return;
      var list = p.getAvailableAudioTracks() || [];
      for (var i = 0; i < list.length; i++) {
        if (trackId(list[i]) === want) { p.setAudioTrack(list[i]); log('audio', 'original track ' + want); return; }
      }
    }), 800);
  }
  // signed out: remember the position of each video locally (28)
  function loggedOut() {
    try { return !!(window.ytcfg && typeof ytcfg.get === 'function' && ytcfg.get('LOGGED_IN') === false); } catch (e) { return false; }
  }
  var POS_KEY = 'ytl_pos';
  function posStore() { try { return JSON.parse(localStorage.getItem(POS_KEY) || '{}') || {}; } catch (e) { return {}; } }
  function posSave(st) {
    var ids = Object.keys(st);
    if (ids.length > 150) {
      ids.sort(function (a, b) { return st[a][1] - st[b][1]; });
      for (var i = 0; i < ids.length - 150; i++) delete st[ids[i]];
    }
    try { localStorage.setItem(POS_KEY, JSON.stringify(st)); } catch (e) {}
  }
  function resumeLocal(v) {
    if (!cfg.resumeLocal || !loggedOut() || /[?&]t=/.test(location.search) || (v.currentTime || 0) > 5) return;
    var e = posStore()[curId];
    if (!e || !(e[0] > 30)) return;
    if (isFinite(v.duration) && v.duration > 0 && e[0] > v.duration - 30) return;
    v.currentTime = e[0];
    log('resume', curId + ' t=' + e[0]);
  }
  var lastPos = 0, lastAppPos = 0;
  function posTick(v, now, force) {
    if (!isWatch() || adNow() || !curId) return;
    var t = Math.floor(v.currentTime || 0), d = v.duration;
    // app restart resume (27): Java keeps the last video + position
    if (force || now - lastAppPos > 30000) {
      lastAppPos = now;
      if (t > 0) call('savePos', curId, t, (meta && meta.title) || (document.title || '').replace(/ - YouTube$/, ''));
    }
    if (!cfg.resumeLocal || !(force || now - lastPos > 10000)) return;
    lastPos = now;
    var info = lenById[curId];
    if (!isFinite(d) || d < 300 || (info && info.live) || !loggedOut()) return;
    var st = posStore();
    if (t > 30 && t < d - 30) st[curId] = [t, now];
    else if (st[curId]) delete st[curId];
    else return;
    posSave(st);
  }

  /* ---------- 10d. sleep time left on the player (154) ---------- */
  var updateBadge = guard('badge', function () {
    var el = document.getElementById('ytl-badge');
    var p = player();
    if (!cfg.sleepBadge || !badgeText || !p || !isWatch()) { if (el && el.parentNode) el.parentNode.removeChild(el); return; }
    if (!el || el.parentNode !== p) {
      if (el && el.parentNode) el.parentNode.removeChild(el);
      // tap = more time, long press = the timer choices (309); its own events never reach the player
      el = document.createElement('button');
      el.id = 'ytl-badge';
      el.type = 'button';
      el.appendChild(document.createElement('span'));
      var held = 0, longDone = false;
      var stop = function (ev) { ev.stopPropagation(); };
      el.addEventListener('touchstart', function (ev) {
        stop(ev); longDone = false; clearTimeout(held);
        held = setTimeout(function () { longDone = true; call('sleepBadgeLong'); }, 600);
      }, { passive: true });
      el.addEventListener('touchend', function (ev) { stop(ev); clearTimeout(held); }, { passive: true });
      el.addEventListener('touchmove', function () { clearTimeout(held); }, { passive: true });
      el.addEventListener('contextmenu', function (ev) { ev.preventDefault(); stop(ev); });
      el.addEventListener('click', guard('badge-tap', function (ev) {
        ev.preventDefault(); ev.stopPropagation();
        if (longDone) { longDone = false; return; }
        call('sleepBadgeTap');
      }), true);
      p.appendChild(el);
    }
    var span = el.firstChild;
    if (span.textContent !== badgeText) {
      span.textContent = badgeText;
      el.setAttribute('aria-label', badgeText + '，点按延长，长按选择时长');
    }
  });

  /* ---------- 10e. what plays next (21/22) ----------
   * Default: YouTube's own autoplay (unchanged). With "same channel" / "playlist" (or the calm rule
   * while a sleep timer runs) YouTube's autoplay is switched off and the next video is picked here. */
  function ruleMode() {
    if (cfg.nextSource === 'playlist') return 'playlist';
    if (cfg.nextSource === 'channel' || ((cfg.sleepCalm || sleepMode) && sleeping)) return 'channel';
    return 'rec';
  }
  function wantAutonav() {
    if (ruleMode() !== 'rec' || loopOne) return false;
    // skip watched autoplay targets (311, off by default): YouTube's autoplay stays off while its
    // target is one already watched, the ended handler picks the next one
    if (cfg.skipWatchedNext && upNextWatched()) return false;
    // blocked autoplay target (444): channel / keyword / locally hidden; judged on the spot because the
    // DOM pass that marks cards is off with the screen off
    if (upNextBlocked()) return false;
    return true;
  }
  function itemWatched(item) {
    var bars = item.querySelectorAll(RULES.sel.watchedBar);
    for (var i = 0; i < bars.length; i++) if (watchedPct(bars[i]) >= seenMin()) return true;
    return false;
  }
  /** Channel identifiers of the playing video (310): channel id, @handle, owner link on the page. */
  var CH_LINK_RE = /^\/(?:(@[^\/?#]+)|channel\/(UC[\w-]{10,}))/;
  function chKey(href) {
    try {
      var u = new URL(href, location.href);
      var m = CH_LINK_RE.exec(u.pathname);
      if (!m) return '';
      return m[1] ? decodeURIComponent(m[1]).toLowerCase() : m[2];
    } catch (e) { return ''; }
  }
  function currentChannelKeys(id) {
    var keys = [];
    var add = function (k) { if (k && keys.indexOf(k) < 0) keys.push(k); };
    var ci = chanById[id];
    if (ci) { add(ci.id); add(ci.handle); }
    try {
      var owner = document.querySelectorAll(RULES.sel.ownerLink);
      for (var i = 0; i < owner.length; i++) add(chKey(owner[i].getAttribute('href') || ''));
    } catch (e) {}
    return keys;
  }
  function cardChannelKeys(it) {
    var out = [];
    var links = it.querySelectorAll('a[href^="/@"], a[href^="/channel/"], a[href*="youtube.com/@"], a[href*="youtube.com/channel/"]');
    for (var i = 0; i < links.length; i++) { var k = chKey(links[i].getAttribute('href') || ''); if (k && out.indexOf(k) < 0) out.push(k); }
    return out;
  }
  /** First up-next video by the same channel, not watched: by channel link, the byline text as a fallback. */
  function sameChannelLink(id) {
    var author = '';
    try { var p = player(), d = p && p.getVideoData && p.getVideoData(); author = ((d && d.author) || '').trim(); } catch (e) {}
    var mine = currentChannelKeys(id);
    if (author.length < 2 && !mine.length) return null;
    var items = document.querySelectorAll(RULES.sel.relatedItem);
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var a = it.querySelector('a[href*="/watch?v="]');
      if (!a) continue;
      var m = /[?&]v=([\w-]{6,})/.exec(a.getAttribute('href') || '');
      if (!m || m[1] === id || it.hasAttribute('data-ytl-seen') || itemWatched(it)) continue;
      var theirs = mine.length ? cardChannelKeys(it) : [];
      if (theirs.length) {
        var hit = false;
        for (var k = 0; k < theirs.length && !hit; k++) if (mine.indexOf(theirs[k]) >= 0) hit = true;
        if (hit) return a;
        continue;   // both sides have a channel link and they differ: another channel
      }
      if (author.length < 2) continue;
      var by = it.querySelector(RULES.sel.byline);
      if (!by) continue;
      var t = textOf(by);
      if (t === author || t.indexOf(author + ' ') === 0 || t.indexOf(author + '·') === 0 || t.indexOf(author + '•') === 0) return a;
    }
    return null;
  }
  function nextByRule(mode, id) {
    if (hasList()) {
      var p = player();
      try {
        var pl = p && p.getPlaylist && p.getPlaylist();
        var idx = p && p.getPlaylistIndex ? p.getPlaylistIndex() : -1;
        if (pl && pl.length && idx >= 0 && idx < pl.length - 1) { log('next', 'playlist'); p.nextVideo(); return; }
        if (pl && pl.length && idx >= pl.length - 1) {
          // list finished (313): stop as before, or go on with a recommendation; never while a timer runs
          var rec = cfg.listEndNext && !sleeping ? (firstUnwatchedLink(id) || recLink()) : null;
          if (rec) { log('next', 'playlist end: recommended'); rec.click(); return; }
          log('next', 'playlist end: stop');
          return;
        }
      } catch (e) {}
      next();
      return;
    }
    if (mode === 'channel') {
      var a = sameChannelLink(id);
      if (a) { log('next', 'same channel'); a.click(); return; }
      // sleeping, "same channel or stop" (315): no unknown video after falling asleep
      if (sleeping && cfg.sleepStrict) {
        log('next', 'no same-channel video: sleep timer ends here');
        sleepPause();
        call('sleepFired');
        return;
      }
      log('next', 'no same-channel video, recommended');
    }
    if (mode === 'unseen') {
      var u = firstUnwatchedLink(id);
      if (u) { log('next', 'skipped a watched autoplay target'); u.click(); return; }
    }
    if (mode === 'allowed') {
      var al = firstAllowedLink(id, !!cfg.skipWatchedNext);
      if (al) { log('next', 'skipped a blocked autoplay target'); al.click(); return; }
    }
    next();
  }
  /** Link of what YouTube's autoplay would play (313). */
  function recLink() {
    var it = upNextItem();
    return it ? it.querySelector('a[href*="/watch?v="]') : null;
  }
  /** Up next (311): the first related video, i.e. what YouTube's autoplay would play. */
  function upNextItem() {
    return document.querySelector('ytm-compact-autoplay-renderer') || document.querySelector(RULES.sel.relatedItem);
  }
  /** What autoplay would play is hidden by the user's own filters (444). */
  function cardBlocked(it) {
    if (!it) return false;
    var f = it.getAttribute('data-ytl-filter');
    if (f && f !== 'ad') return true;
    if (filtersPaused || !anyFilter()) return false;
    try { return !!filterReason(cardInfo(it), false, it); } catch (e) { return false; }
  }
  function upNextBlocked() { return cardBlocked(upNextItem()); }
  /** First related video that is not blocked (and not watched when that is asked for), 444. */
  function firstAllowedLink(id, skipWatched) {
    var items = document.querySelectorAll(RULES.sel.relatedItem);
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var a = it.querySelector('a[href*="/watch?v="]');
      var m = a && /[?&]v=([\w-]{6,})/.exec(a.getAttribute('href') || '');
      if (!m || m[1] === id || cardBlocked(it)) continue;
      if (skipWatched && (it.hasAttribute('data-ytl-seen') || itemWatched(it))) continue;
      return a;
    }
    return null;
  }
  function upNextWatched() {
    var it = upNextItem();
    return !!(it && (it.hasAttribute('data-ytl-seen') || itemWatched(it)));
  }
  function firstUnwatchedLink(id) {
    var items = document.querySelectorAll(RULES.sel.relatedItem);
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var a = it.querySelector('a[href*="/watch?v="]');
      var m = a && /[?&]v=([\w-]{6,})/.exec(a.getAttribute('href') || '');
      if (!m || m[1] === id || it.hasAttribute('data-ytl-seen') || it.hasAttribute('data-ytl-filter') || itemWatched(it)) continue;
      return a;
    }
    return null;
  }

  /* ---------- 10. player events (capture on document: no element polling) ---------- */
  // Muted inline previews in the feed are not "playback": ignore them.
  function isMain(e) {
    var t = e.target;
    if (!t || t.tagName !== 'VIDEO') return false;
    if (!(t.classList.contains('video-stream') || t === video())) return false;
    return isWatch() || !t.muted;
  }
  var unmutedFor = '';
  var lateNavFor = '';

  var pendingStop = false;    // sleep "after N videos": stop at the end of the next video (16)
  var bgFallbacks = 0;
  var endedFor = '';
  var curProgress = 0;        // share of the current video played, for "after N videos" (301)
  var countedOnChange = 0;
  function onVideoChange() {
    var id = videoId();
    if (!id || id === curId) return;
    var had = !!curId;
    // "after N videos" (301): moving on by hand, a SponsorBlock jump to the end or YouTube switching
    // early still counts the previous video when more than 90 % of it was played
    if (had && countMode && endedFor !== curId && curProgress >= 0.9) { endedFor = curId; countedOnChange = Date.now(); call('videoEnded', curId); }
    curProgress = 0;
    curId = id;
    if (had) loopOne = false;
    if (pendingStop) { pendingStop = false; stopAtEnd = true; }
    lastMetaKey = '';
    stalled = false;
    netTries = 0;
    segments = [];
    segSkipped = {};
    segFor = id;
    hideSbPrompt();
    if (cfg.sponsorBlock) call('fetchSegments', id);
    applyQuality();
    // the observer is off with the screen off, so the per-page pass that keeps autoplay right does
    // not run: set it here for the new video as well (249)
    autonavFor = '';
    forceAutonav();
    updateToolsBtn();
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
    onVideoStart(v);
    sendMeta();
    call('mediaState', 'playing', pos(v), v.playbackRate || 1);
  });

  on('pause', 'ev-pause', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(stuckTimer);
    call('mediaState', 'paused', pos(v), v.playbackRate || 1);
    try { posTick(v, Date.now(), true); } catch (x) { report('pos', x); }
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
    // remember a speed the user picked (30): never the 16x of the ad fallback or one we set
    var r = v.playbackRate;
    if (cfg.rememberRate && isWatch() && !adNow() && !adRate && !adMuted && r !== ourRate && r >= 0.25 && r <= 2) {
      cfg.rate = r; call('saveRate', String(r));
    }
    call('mediaState', v.paused ? 'paused' : 'playing', pos(v), v.playbackRate || 1);
  });

  on('ended', 'ev-ended', function (e) {
    if (!isMain(e)) return;
    var v = e.target;
    clearTimeout(stuckTimer);
    call('mediaState', 'paused', pos(v), 1);
    var id = curId;
    // the length check took the video itself for an ad and jumped to its end (282): never again
    // for this video, and note it (a real ad shows ad-showing and is left alone here)
    if (adByLen && !adShowing() && lenMis.id === videoId()) {
      lenMis.gaveUp = lenMis.id; adByLen = false; skipAd();
      log('len-misjudge', lenMis.id + ' d=' + Math.round(v.duration || 0));
    }
    if (adNow()) return;
    // single-video loop (152): start over instead of moving on
    if (loopOne && !stopAtEnd && !userPaused) {
      v.currentTime = 0;
      play();
      loopWrapped();
      return;
    }
    if (countMode && endedFor !== id) { endedFor = id; call('videoEnded', id); }
    if (stopAtEnd || userPaused) return;
    // "Continue watching?" can come up at the end instead of the next video (446)
    setTimeout(function () { if (!userPaused && !stopAtEnd) dismissStillWatching(true); }, 1200);
    clearTimeout(endTimer);
    var mode = ruleMode();
    // recommended, but the autoplay target was already watched (311): pick the first unwatched one
    if (mode === 'rec' && cfg.skipWatchedNext && !looping() && upNextWatched()) mode = 'unseen';
    // the autoplay target is one the user blocked (444): the first allowed one instead
    if (mode === 'rec' && !looping() && upNextBlocked()) mode = 'allowed';
    if (mode !== 'rec') {
      endTimer = setTimeout(guard('next-rule', function () {
        if (videoId() === id && !userPaused) nextByRule(mode, id);
      }), 700);
      return;
    }
    // Auto-continue fallback: if YouTube's own autoplay didn't move on, do it ourselves.
    endTimer = setTimeout(function () {
      if (videoId() === id && !userPaused) {
        if (bg) { bgFallbacks++; log('next', 'fallback next() with the screen off #' + bgFallbacks); }   // (249) data
        next();
      }
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
    loopTick(v);
    posTick(v, now, false);
    if (isFinite(v.duration) && v.duration > 0 && !adNow()) curProgress = Math.max(curProgress, (v.currentTime || 0) / v.duration);
    // 20 s before the end (445): the related list is there by now, so the autoplay switch is decided
    // again (with the screen off the DOM pass that does this is not running)
    if (lateNavFor !== curId && isFinite(v.duration) && v.duration > 40 && v.duration - v.currentTime <= 20 && !adNow()) {
      lateNavFor = curId; autonavFor = ''; forceAutonav();
    }
    // sleep timer: end of current video (fade over the last N s, then pause)
    if (stopAtEnd && isFinite(v.duration) && v.duration > 0) {
      var left = v.duration - v.currentTime;
      var F = Math.max(5, Math.min(120, Number(cfg.fadeSec) || 60));
      if (left <= F) setVolume(fadeVolume(left - 1, F - 1));
      if (left <= 1.2) {
        stopAtEnd = false;
        sleepPause();
        call('sleepFired');
      }
    }
  });

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


  /* ---------- 16. lists: local filters (56-60), refresh count (64), search (70/124/125) ---------- */
  var DUR_RE = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/;
  function parseDur(t) {
    var m = DUR_RE.exec(String(t || '').trim());
    return m ? (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) : -1;
  }
  var AGO = [[/(\d+)\s*(?:秒|second)/i, 1 / 86400], [/(\d+)\s*(?:分钟|分鐘|minute)/i, 1 / 1440], [/(\d+)\s*(?:小时|小時|hour)/i, 1 / 24],
    [/(\d+)\s*(?:天|day)/i, 1], [/(\d+)\s*(?:周|週|星期|week)/i, 7], [/(\d+)\s*(?:个月|個月|month)/i, 30], [/(\d+)\s*(?:年|year)/i, 365]];
  /** "3 天前" / "2 years ago" -> days, -1 when there is no age in the text. */
  function ageDays(t) {
    t = String(t || '');
    if (!/前|ago/i.test(t)) return -1;
    for (var i = 0; i < AGO.length; i++) { var m = AGO[i][0].exec(t); if (m) return (+m[1]) * AGO[i][1]; }
    return -1;
  }
  var LIVE_RE = textRe('live');
  function linkId(a) {
    var m = a && /[?&]v=([\w-]{6,})/.exec(a.getAttribute('href') || '');
    return m ? m[1] : '';
  }
  function cardInfo(it) {
    var a = it.querySelector('a[href*="/watch?v="]');
    var tEl = it.querySelector(RULES.sel.cardTitle);
    var by = it.querySelector(RULES.sel.byline);
    var bt = by ? textOf(by) : '';
    var info = { id: linkId(a), title: tEl ? textOf(tEl) : ((a && a.getAttribute('aria-label')) || ''), channel: (bt.split(/\s*[·•]\s*/)[0] || '').trim(), dur: -1, live: false, age: -1 };
    var badges = it.querySelectorAll(RULES.sel.timeBadge);
    for (var i = 0; i < badges.length; i++) {
      var tx = textOf(badges[i]), d = parseDur(tx), ds = badges[i].getAttribute('data-style') || '';
      if (d >= 0) info.dur = d;
      else if (LIVE_RE.test(tx) || ds === 'LIVE' || ds === 'UPCOMING') info.live = true;
    }
    if (it.querySelector('[data-style="LIVE"], [data-style="UPCOMING"], .badge-shape-wiz--thumbnail-live')) info.live = true;
    var metas = it.querySelectorAll(RULES.sel.cardMeta);
    info.age = ageDays(bt);
    for (var j = 0; j < metas.length && info.age < 0; j++) info.age = ageDays(textOf(metas[j]));
    return info;
  }
  var HIDDEN_KEY = 'ytl_hidden';
  function hiddenIds() { try { return JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]') || []; } catch (e) { return []; } }
  var hiddenList = hiddenIds();
  function lc(x) { return String(x || '').toLowerCase().trim(); }
  /** Block-list line (322): "@handle|Name" / "UC…|Name" from the long-press menu, or a plain name. */
  function blockEntry(e) {
    e = String(e || '').trim();
    var bar = e.indexOf('|'), key = '', name = e;
    if (bar > 0) { key = e.slice(0, bar).trim(); name = e.slice(bar + 1); }
    else if (/^@[^\s|]{2,}$/.test(e) || /^UC[\w-]{10,}$/.test(e)) { key = e; name = ''; }
    if (key.charAt(0) === '@') key = key.toLowerCase();
    return { key: key, name: lc(name) };
  }
  /* parsed once per list (449): the block list and the hidden ids are looked up for every card */
  var blockCache = { src: null, list: [] }, hiddenCache = { src: null, len: -1, set: null };
  function blockEntries() {
    var bl = cfg.blockChannels || [];
    if (blockCache.src !== bl) blockCache = { src: bl, list: bl.map(blockEntry) };
    return blockCache.list;
  }
  function isHiddenId(id) {
    if (hiddenCache.src !== hiddenList || hiddenCache.len !== hiddenList.length) {
      var st = new Set(hiddenList);
      hiddenCache = { src: hiddenList, len: hiddenList.length, set: st };
    }
    return hiddenCache.set.has(id);
  }
  function filterReason(info, deep, it) {
    if (info.id && isHiddenId(info.id)) return 'hidden';
    var ch = lc(info.channel), bl = blockEntries(), keys = null;
    for (var i = 0; i < bl.length; i++) {
      var be = bl[i];
      if (be.name && ch && be.name === ch) return 'channel';
      if (be.key && it) {
        if (keys === null) keys = cardChannelKeys(it);
        if (keys.indexOf(be.key) >= 0) return 'channel';
      }
    }
    if (wordBlocked(info.title)) return 'word';
    if (!deep) return '';
    if (cfg.hideLive && info.live) return 'live';
    if (cfg.minDur > 0 && info.dur >= 0 && info.dur < cfg.minDur * 60) return 'short';
    if (cfg.maxAgeDays > 0 && info.age > cfg.maxAgeDays) return 'old';
    return '';
  }
  /* keyword lines (57/323): plain words, "re:" + a regular expression (≤ 100 characters, a broken one
   * is ignored) and "!" + an allow word (a title with it is never hidden by the keyword rules) */
  var wordCache = { key: null };
  function wordRules() {
    var bw = cfg.blockWords || [];
    var k = JSON.stringify(bw);
    if (wordCache.key === k) return wordCache;
    var w = { key: k, plain: [], res: [], allow: [] };
    for (var i = 0; i < bw.length; i++) {
      var x = String(bw[i] || '').trim();
      if (!x) continue;
      if (/^re:/i.test(x)) {
        var src = x.slice(3).trim();
        if (src && src.length <= 100) { try { w.res.push(new RegExp(src, 'i')); } catch (e) { log('words', 'bad pattern ignored: ' + src.slice(0, 40)); } }
      } else if (x.charAt(0) === '!') {
        var al = lc(x.slice(1));
        if (al) w.allow.push(al);
      } else w.plain.push(lc(x));
    }
    wordCache = w;
    return w;
  }
  function wordBlocked(title) {
    var w = wordRules(), ti = lc(title);
    if (!ti || (!w.plain.length && !w.res.length)) return false;
    for (var a = 0; a < w.allow.length; a++) if (ti.indexOf(w.allow[a]) >= 0) return false;
    for (var p = 0; p < w.plain.length; p++) if (ti.indexOf(w.plain[p]) >= 0) return true;
    for (var r = 0; r < w.res.length; r++) if (w.res[r].test(title)) return true;
    return false;
  }
  function anyFilter() {
    return !!(hiddenList.length || (cfg.blockChannels || []).length || (cfg.blockWords || []).length || cfg.minDur > 0 || cfg.maxAgeDays > 0 || cfg.hideLive);
  }
  /* sponsored cards by their label (285): when YouTube renames its ad components the element rules
   * miss them, the short "Sponsored / 广告" label stays. Exact match of a short text only. */
  var AD_LABEL_RE = textRe('adLabel');
  function adLabelHit(it) {
    var l = it.querySelectorAll(RULES.sel.adLabel);
    var extra = remote.adText || [];
    for (var i = 0; i < l.length; i++) {
      var t = textOf(l[i]);
      if (!t || t.length > 20) continue;
      if (AD_LABEL_RE.test(t)) return true;
      for (var j = 0; j < extra.length; j++) if (lc(extra[j]) === lc(t)) return true;
    }
    return false;
  }
  var filterKey = '', filtered = 0;
  /** items: only these cards (incremental pass, 256); none: every card on the page. */
  var filtersPaused = false;   // "show everything" on an emptied feed (326), this page session only
  var filterCards = guard('filter', function (items) {
    var pt = pageType();
    var userOn = !filtersPaused && anyFilter() && pt !== 'channel' && pt !== 'other' && pt !== 'playlist';
    // channel pages (330): only what was hidden by hand; channel / keyword rules would empty the page
    var chanHidden = !filtersPaused && pt === 'channel' && hiddenList.length > 0;
    var key = JSON.stringify([cfg.blockChannels, cfg.blockWords, cfg.minDur, cfg.maxAgeDays, cfg.hideLive, hiddenList.length, pt, userOn,
      (remote.adText || []).length, chanHidden]);
    if (key !== filterKey) {
      filterKey = key;
      items = null;   // something changed: judge every card again
      if (filtered) {
        var old = document.querySelectorAll('[data-ytl-filter]');
        for (var o = 0; o < old.length; o++) old[o].removeAttribute('data-ytl-filter');
        filtered = 0;
      }
    }
    var deep = pt === 'home' || pt === 'watch';
    var list = items || document.querySelectorAll(RULES.sel.feedItem);
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      if (!it.isConnected) continue;
      var a = it.querySelector('a[href*="/watch?v="]');
      var mark = key + '|' + (a ? a.getAttribute('href') : '');
      if (it.__ytlF === mark) continue;   // already judged (recycled nodes get a new href)
      it.__ytlF = mark;
      var why = adLabelHit(it) ? 'ad' : userOn ? filterReason(cardInfo(it), deep, it)
        : chanHidden && isHiddenId(linkId(a)) ? 'hidden' : '';
      if (why) { if (it.getAttribute('data-ytl-filter') !== why) { it.setAttribute('data-ytl-filter', why); filtered++; } }
      else if (it.hasAttribute('data-ytl-filter')) it.removeAttribute('data-ytl-filter');
    }
  });
  function saveHidden() { try { localStorage.setItem(HIDDEN_KEY, JSON.stringify(hiddenList)); } catch (e) {} }
  function hideVideo(id) {
    if (!/^[\w-]{6,20}$/.test(id || '')) return;
    hiddenList = hiddenIds();
    if (hiddenList.indexOf(id) < 0) hiddenList.unshift(id);
    if (hiddenList.length > 300) hiddenList.length = 300;
    saveHidden();
    filterKey = '';
    filterCards();
    // a slip of the finger costs nothing (324): 4 s to take it back
    snackBar(S('hidden', '已隐藏这个视频'), S('undo', '撤销'), function () { unhideVideo(id); }, 4000);
  }
  function unhideVideo(id) {
    hiddenList = hiddenIds().filter(function (x) { return x !== id; });
    saveHidden();
    filterKey = '';
    filterCards();
  }

  // "N new videos" after a refresh (64): compared with the first screens of the last 3 refreshes (320)
  var countDone = false;
  var HOME_HIST = 'ytl_home_hist';
  function homeHistory() {
    var h = null;
    try { h = JSON.parse(localStorage.getItem(HOME_HIST) || 'null'); } catch (e) {}
    if (Array.isArray(h)) return h.filter(Array.isArray).slice(-3);
    var prev = [];
    try { prev = JSON.parse(localStorage.getItem('ytl_home_ids') || '[]') || []; } catch (e) {}   // v1.6 and older
    return Array.isArray(prev) && prev.length ? [prev] : [];
  }
  function newCount(ids, hist) {
    var seen = {};
    for (var i = 0; i < hist.length; i++) for (var j = 0; j < hist[i].length; j++) seen[hist[i][j]] = true;
    return ids.filter(function (x) { return !seen[x]; }).length;
  }
  var countNew = guard('refresh-count', function () {
    if (countDone || pageType() !== 'home') return;
    var links = document.querySelectorAll('ytm-rich-item-renderer a[href*="/watch?v="], ytm-video-with-context-renderer a[href*="/watch?v="]');
    var ids = [];
    for (var i = 0; i < links.length && ids.length < 40; i++) { var id = linkId(links[i]); if (id && ids.indexOf(id) < 0) ids.push(id); }
    if (ids.length < 4) return;   // feed not there yet
    countDone = true;
    var hist = homeHistory();
    var nh = hist.concat([ids]).slice(-3);
    try { localStorage.setItem(HOME_HIST, JSON.stringify(nh)); localStorage.setItem('ytl_home_ids', JSON.stringify(ids)); } catch (e) {}
    if (!wasRefresh || !hist.length) return;
    var n = newCount(ids, hist);
    noteRefreshResult(n);
    if (!cfg.refreshCount) return;
    // how many cards the filters took away (321), so a short feed is not a mystery
    var gone = document.querySelectorAll('[data-ytl-filter]:not([data-ytl-filter="ad"]), [data-ytl-seen]').length;
    call('toast', (n ? S('newVideos', '更新了 {n} 个新视频').replace('{n}', n) : S('noNew', '暂时没有新视频，稍后再刷新试试')) +
      (gone ? S('filteredN', ' · 已过滤 {n} 个').replace('{n}', gone) : ''));
  });

  /* filters emptied the home feed (326): say so at the top, with a way to see everything this time */
  var checkEmptyFeed = guard('empty-feed', function () {
    var el = document.getElementById('ytl-empty');
    var drop = function () { if (el && el.parentNode) el.parentNode.removeChild(el); };
    if (pageType() !== 'home' || filtersPaused) { drop(); return; }
    var all = document.querySelectorAll(RULES.sel.feedItem);
    var gone = 0, shown = 0;
    for (var i = 0; i < all.length; i++) {
      var it = all[i], f = it.getAttribute('data-ytl-filter');
      if (it.hasAttribute('data-ytl-seen') || (f && f !== 'ad')) gone++;
      else if (!f) shown++;
    }
    if (shown > 0 || gone < 4) { drop(); return; }
    var first = all[0], box = first && first.parentNode;
    if (!box || !box.parentNode) return;
    if (!el) {
      el = document.createElement('div');
      el.id = 'ytl-empty';
      el.setAttribute('role', 'status');
      var span = document.createElement('span');
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = S('showAll', '暂时显示全部');
      btn.addEventListener('click', guard('empty-show', function (ev) {
        ev.preventDefault(); ev.stopPropagation();
        filtersPaused = true;
        filterKey = '';
        var seen = document.querySelectorAll('[data-ytl-seen]');
        for (var k = 0; k < seen.length; k++) seen[k].removeAttribute('data-ytl-seen');
        seenCount = 0;
        filterCards();
        drop();
      }), true);
      el.appendChild(span);
      el.appendChild(btn);
    }
    if (el.parentNode !== box.parentNode || el.nextSibling !== box) box.parentNode.insertBefore(el, box);
    var txt = S('filteredAll', '已按你的过滤隐藏 {n} 个视频').replace('{n}', gone);
    if (el.firstChild.textContent !== txt) el.firstChild.textContent = txt;
  });

  /* once a day, the first time home has its feed (284): cards that still say "Sponsored / 广告" in a
   * short label but are on screen go to the missed-ads log. Read only, nothing is changed. */
  var LEAK_KEY = 'ytl_leakscan';
  var LEAK_RE = /(?:^|[\s·•|])(?:赞助|贊助|广告|廣告|sponsored|ad)(?:$|[\s·•|:：])/i;
  var leakQueued = false;
  function today() { var d = new Date(); return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate(); }
  var leakScan = guard('leak-scan', function () {
    var items = document.querySelectorAll(RULES.sel.feedItem), n = 0, ids = [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.closest('[data-ytl-filter], [data-ytl-hide], [data-ytl-seen]')) continue;
      try { if (window.getComputedStyle && getComputedStyle(it).display === 'none') continue; } catch (e) {}
      var labels = it.querySelectorAll(RULES.sel.adLabel + ', ' + RULES.sel.cardMeta), hit = false;
      for (var j = 0; j < labels.length && !hit; j++) { var t = textOf(labels[j]); if (t.length <= 40 && LEAK_RE.test(t)) hit = true; }
      if (hit) { n++; var id = linkId(it.querySelector('a[href*="/watch?v="]')); if (id && ids.length < 3) ids.push(id); }
    }
    if (n > 0) call('logAd', ids[0] || '-', 'suspect-leak x' + n, 'home', adContext() + (ids.length ? ' ids=' + ids.join(',') : ''));
    else log('leak-scan', 'home ok, ' + items.length + ' cards');
  });
  function maybeLeakScan() {
    if (leakQueued || pageType() !== 'home' || !countDone) return;
    var d = today();
    try { if (localStorage.getItem(LEAK_KEY) === d) { leakQueued = true; return; } localStorage.setItem(LEAK_KEY, d); } catch (e) { return; }
    leakQueued = true;
    setTimeout(leakScan, 3000);   // the feed settles first
  }

  // search: shelves like "People also watched" (70)
  var SEARCHX_RE = textRe('searchExtra');
  var markSearchExtras = guard('search-extras', function () {
    if (pageType() !== 'search') return;
    // auto-generated mixes (333): marked here, hidden by CSS only while the setting is on
    var mixes = document.querySelectorAll(RULES.sel.mixLink);
    for (var m = 0; m < mixes.length; m++) {
      var card = mixes[m].closest(RULES.sel.mixCard);
      if (card && !card.hasAttribute('data-ytl-searchmix')) card.setAttribute('data-ytl-searchmix', '');
    }
    var l = document.querySelectorAll(RULES.sel.searchShelf);
    for (var i = 0; i < l.length; i++) {
      var h = l[i].querySelector(RULES.sel.shelfTitle);
      var hit = h && SEARCHX_RE.test(textOf(h).slice(0, 80));
      if (hit) { if (!l[i].hasAttribute('data-ytl-searchx')) l[i].setAttribute('data-ytl-searchx', ''); }
      else if (l[i].hasAttribute('data-ytl-searchx')) l[i].removeAttribute('data-ytl-searchx');
    }
  });
  // keyboard comes up with the search box (124), only for an empty, visible box
  var searchFocused = null;
  var searchFocus = guard('search-focus', function () {
    var inp = document.querySelector(RULES.sel.searchInput);
    if (!inp) { searchFocused = null; return; }
    if (!cfg.searchFocus || inp === searchFocused) return;
    if (inp.value || !(inp.offsetParent || (inp.getClientRects && inp.getClientRects().length))) return;
    searchFocused = inp;
    if (document.activeElement !== inp) { try { inp.focus(); } catch (e) {} }
    call('showKeyboard');
  });
  // quick filters on search results (125): plain links to YouTube's own filter URLs
  function chipList() { return remote.chips || RULES.chips; }   // (328) rules.json can replace them
  function decSp(x) { try { return decodeURIComponent(x || ''); } catch (e) { return x || ''; } }
  var CHIP_KEY = 'ytl_chip';
  /** A chip was used (328): the result page must still carry its sp, else YouTube changed the format. */
  function checkChipSp(sp) {
    var want = null;
    try { want = sessionStorage.getItem(CHIP_KEY); if (want !== null) sessionStorage.removeItem(CHIP_KEY); } catch (e) {}
    if (want === null || decSp(want) === decSp(sp)) return;
    log('chips', 'filter not applied: sent sp=' + want.slice(0, 40) + ' page sp=' + String(sp).slice(0, 40));
  }
  var ensureChips = guard('chips', function () {
    var el = document.getElementById('ytl-chips');
    var params = new URLSearchParams(location.search);
    var q = params.get('search_query');
    if (!cfg.searchChips || pageType() !== 'search' || !q) { if (el && el.parentNode) el.parentNode.removeChild(el); return; }
    var host = document.querySelector(RULES.sel.searchList);
    if (!host || !host.parentNode) return;
    var sp = params.get('sp') || '';
    checkChipSp(sp);
    var key = q + '|' + sp;
    if (el && el.nextSibling === host && el.getAttribute('data-k') === key) return;
    if (el && el.parentNode) el.parentNode.removeChild(el);
    el = document.createElement('div');
    el.id = 'ytl-chips';
    el.setAttribute('data-k', key);
    el.setAttribute('role', 'toolbar');
    el.setAttribute('aria-label', '搜索筛选');
    var CHIPS = chipList();
    for (var i = 0; i < CHIPS.length; i++) {
      var a = document.createElement('a');
      a.href = '/results?search_query=' + encodeURIComponent(q) + (CHIPS[i][1] ? '&sp=' + CHIPS[i][1] : '');
      a.textContent = CHIPS[i][0];
      a.setAttribute('data-sp', CHIPS[i][1]);
      if (decSp(sp) === decSp(CHIPS[i][1])) a.setAttribute('aria-current', 'true');
      el.appendChild(a);
    }
    el.addEventListener('click', function (ev) {
      var ca = ev.target && ev.target.closest && ev.target.closest('a[data-sp]');
      if (ca) { try { sessionStorage.setItem(CHIP_KEY, ca.getAttribute('data-sp') || ''); } catch (e) {} }
    }, true);
    host.parentNode.insertBefore(el, host);
  });

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
      if (bad.length) {
        log('selfcheck', 'FAIL ' + bad.join(',') + ' @' + pageType());
        call('selfCheckFailed', bad.join(','));   // Java tells the user once per version (254)
      } else if (!once) log('selfcheck', 'ok');
    }
    return r;
  }

  // test / diagnostics surface (no behaviour of its own)
  window.ytl._ = {
    upNextBlocked: upNextBlocked, firstAllowedLink: firstAllowedLink, lightZeros: lightZeros, noteRefreshResult: noteRefreshResult,
    blockEntries: blockEntries, isHiddenId: isHiddenId, lateNavFor: function () { return lateNavFor; },
    RULES: RULES, css: function () { return CSS; }, pageType: pageType, isPlayerResponse: isPlayerResponse,
    prune: prune, lenById: function () { return lenById; }, lenMismatch: lenMismatch, adNow: adNow,
    cleanUrl: cleanUrl, cleanText: cleanText, retryOnce: retryOnce, nav: nav, selfCheck: selfCheck,
    guard: guard, worthChecking: worthChecking, domPass: domPass,
    observing: function () { return !!mo; }, observerRoot: function () { return moRoot; },
    segments: function () { return segments; },
    prefQuality: prefQuality, ruleMode: ruleMode, wantAutonav: wantAutonav, sameChannelLink: sameChannelLink,
    origAudio: function () { return origAudio; }, posStore: posStore, toolState: toolState,
    stopAtEnd: function () { return stopAtEnd; },
    parseDur: parseDur, ageDays: ageDays, cardInfo: cardInfo, filterCards: filterCards, remoteSelectors: remoteSelectors,
    scrollMem: function () { return scrollMem; }, restoreScroll: restoreScroll, watchLater: watchLater, reportTheme: reportTheme,
    // batch 4 (v1.6.0)
    fadeVolume: fadeVolume, dismissStillWatching: dismissStillWatching, checkLogin: checkLogin,
    playingNow: playingNow, chanById: function () { return chanById; }, cfg: function () { return cfg; },
    // batch 5 (v1.7.0)
    adKeys: function () { return AD_KEYS.slice(); }, adKeysBuiltin: AD_KEYS_BUILTIN, adLabelHit: adLabelHit,
    blockEntry: blockEntry, filterReason: filterReason, newCount: newCount, homeHistory: homeHistory,
    pickCaption: pickCaption, gearLabel: gearLabel, upNextWatched: upNextWatched, firstUnwatchedLink: firstUnwatchedLink,
    fullPending: function () { return fullNeeded; }, dirtyCount: function () { return dirtyItems.length; },
    curProgress: function () { return curProgress; }, adContext: adContext, sendMeta: function () { sendMeta(); },
    fadeState: function () { return { end: fade.end, len: fade.len, running: !!fade.timer }; },
    // batch 6 (v1.8.0)
    wordBlocked: wordBlocked, textRe: textRe, checkEmptyFeed: checkEmptyFeed, leakScan: leakScan, quietBatch: quietBatch,
    quietSkipped: function () { return quietSkipped; }, chipList: chipList, remote: function () { return remote; },
    filtersPaused: function () { return filtersPaused; }, refresh: refresh
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserver, { once: true });
  else startObserver();
})();

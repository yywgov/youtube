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


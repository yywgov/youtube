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

  /* ---------- 10c. volume levelling (20, experimental, off by default) ----------
   * Audio is only routed through WebAudio once the user switched it on; switching it off later
   * connects the element straight to the output again. */
  var audioFx = { ctx: null, src: null, comp: null, el: null };
  var applyNormalize = guard('normalize', function (v) {
    if (!v || (!cfg.normalize && !audioFx.ctx)) return;
    if (!audioFx.ctx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      audioFx.ctx = new AC();
    }
    var ctx = audioFx.ctx;
    if (audioFx.el !== v) {
      audioFx.src = ctx.createMediaElementSource(v);
      audioFx.el = v;
    }
    if (!audioFx.comp) {
      var c = ctx.createDynamicsCompressor();
      c.threshold.value = -26; c.knee.value = 24; c.ratio.value = 4; c.attack.value = 0.005; c.release.value = 0.3;
      var g = ctx.createGain(); g.gain.value = 1.35;
      c.connect(g); g.connect(ctx.destination);
      audioFx.comp = c;
    }
    try { audioFx.src.disconnect(); } catch (e) {}
    audioFx.src.connect(cfg.normalize ? audioFx.comp : ctx.destination);
    if (ctx.state === 'suspended' && ctx.resume) ctx.resume().catch(function () {});
  });

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


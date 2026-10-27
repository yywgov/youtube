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


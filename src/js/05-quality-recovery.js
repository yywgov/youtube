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
  // full screen video (387): the landscape layout rule steps aside
  document.addEventListener('fullscreenchange', function () {
    var on = !!(document.fullscreenElement || document.webkitFullscreenElement);
    if (on) document.documentElement.setAttribute('data-ytl-fs', ''); else document.documentElement.removeAttribute('data-ytl-fs');
  });
  window.addEventListener('offline', function () { var v = video(); if (v && !v.paused) stalled = true; });


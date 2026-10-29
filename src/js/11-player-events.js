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


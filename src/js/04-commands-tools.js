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


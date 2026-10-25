  /* ---------- 10e. what plays next (21/22) ----------
   * Default: YouTube's own autoplay (unchanged). With "same channel" / "playlist" (or the calm rule
   * while a sleep timer runs) YouTube's autoplay is switched off and the next video is picked here. */
  function ruleMode() {
    if (cfg.nextSource === 'playlist') return 'playlist';
    if (cfg.nextSource === 'channel' || ((cfg.sleepCalm || sleepMode) && sleeping)) return 'channel';
    return 'rec';
  }
  function wantAutonav() {
    if (ruleMode() !== 'rec' || loopOne || (abA >= 0 && abB > abA)) return false;
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


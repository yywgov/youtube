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


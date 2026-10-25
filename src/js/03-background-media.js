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


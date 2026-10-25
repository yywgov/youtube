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

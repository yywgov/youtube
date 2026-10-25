  /* ---------- 16. lists: local filters (56-60), refresh count (64), search (70/124/125) ---------- */
  var DUR_RE = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/;
  function parseDur(t) {
    var m = DUR_RE.exec(String(t || '').trim());
    return m ? (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) : -1;
  }
  var AGO = [[/(\d+)\s*(?:秒|second)/i, 1 / 86400], [/(\d+)\s*(?:分钟|分鐘|minute)/i, 1 / 1440], [/(\d+)\s*(?:小时|小時|hour)/i, 1 / 24],
    [/(\d+)\s*(?:天|day)/i, 1], [/(\d+)\s*(?:周|週|星期|week)/i, 7], [/(\d+)\s*(?:个月|個月|month)/i, 30], [/(\d+)\s*(?:年|year)/i, 365]];
  /** "3 天前" / "2 years ago" -> days, -1 when there is no age in the text. */
  function ageDays(t) {
    t = String(t || '');
    if (!/前|ago/i.test(t)) return -1;
    for (var i = 0; i < AGO.length; i++) { var m = AGO[i][0].exec(t); if (m) return (+m[1]) * AGO[i][1]; }
    return -1;
  }
  var LIVE_RE = textRe('live');
  function linkId(a) {
    var m = a && /[?&]v=([\w-]{6,})/.exec(a.getAttribute('href') || '');
    return m ? m[1] : '';
  }
  function cardInfo(it) {
    var a = it.querySelector('a[href*="/watch?v="]');
    var tEl = it.querySelector(RULES.sel.cardTitle);
    var by = it.querySelector(RULES.sel.byline);
    var bt = by ? textOf(by) : '';
    var info = { id: linkId(a), title: tEl ? textOf(tEl) : ((a && a.getAttribute('aria-label')) || ''), channel: (bt.split(/\s*[·•]\s*/)[0] || '').trim(), dur: -1, live: false, age: -1 };
    var badges = it.querySelectorAll(RULES.sel.timeBadge);
    for (var i = 0; i < badges.length; i++) {
      var tx = textOf(badges[i]), d = parseDur(tx), ds = badges[i].getAttribute('data-style') || '';
      if (d >= 0) info.dur = d;
      else if (LIVE_RE.test(tx) || ds === 'LIVE' || ds === 'UPCOMING') info.live = true;
    }
    if (it.querySelector('[data-style="LIVE"], [data-style="UPCOMING"], .badge-shape-wiz--thumbnail-live')) info.live = true;
    var metas = it.querySelectorAll(RULES.sel.cardMeta);
    info.age = ageDays(bt);
    for (var j = 0; j < metas.length && info.age < 0; j++) info.age = ageDays(textOf(metas[j]));
    return info;
  }
  var HIDDEN_KEY = 'ytl_hidden';
  function hiddenIds() { try { return JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]') || []; } catch (e) { return []; } }
  var hiddenList = hiddenIds();
  function lc(x) { return String(x || '').toLowerCase().trim(); }
  /** Block-list line (322): "@handle|Name" / "UC…|Name" from the long-press menu, or a plain name. */
  function blockEntry(e) {
    e = String(e || '').trim();
    var bar = e.indexOf('|'), key = '', name = e;
    if (bar > 0) { key = e.slice(0, bar).trim(); name = e.slice(bar + 1); }
    else if (/^@[^\s|]{2,}$/.test(e) || /^UC[\w-]{10,}$/.test(e)) { key = e; name = ''; }
    if (key.charAt(0) === '@') key = key.toLowerCase();
    return { key: key, name: lc(name) };
  }
  /* parsed once per list (449): the block list and the hidden ids are looked up for every card */
  var blockCache = { src: null, list: [] }, hiddenCache = { src: null, len: -1, set: null };
  function blockEntries() {
    var bl = cfg.blockChannels || [];
    if (blockCache.src !== bl) blockCache = { src: bl, list: bl.map(blockEntry) };
    return blockCache.list;
  }
  function isHiddenId(id) {
    if (hiddenCache.src !== hiddenList || hiddenCache.len !== hiddenList.length) {
      var st = new Set(hiddenList);
      hiddenCache = { src: hiddenList, len: hiddenList.length, set: st };
    }
    return hiddenCache.set.has(id);
  }
  function filterReason(info, deep, it) {
    if (info.id && isHiddenId(info.id)) return 'hidden';
    var ch = lc(info.channel), bl = blockEntries(), keys = null;
    for (var i = 0; i < bl.length; i++) {
      var be = bl[i];
      if (be.name && ch && be.name === ch) return 'channel';
      if (be.key && it) {
        if (keys === null) keys = cardChannelKeys(it);
        if (keys.indexOf(be.key) >= 0) return 'channel';
      }
    }
    if (wordBlocked(info.title)) return 'word';
    if (!deep) return '';
    if (cfg.hideLive && info.live) return 'live';
    if (cfg.minDur > 0 && info.dur >= 0 && info.dur < cfg.minDur * 60) return 'short';
    if (cfg.maxAgeDays > 0 && info.age > cfg.maxAgeDays) return 'old';
    return '';
  }
  /* keyword lines (57/323): plain words, "re:" + a regular expression (≤ 100 characters, a broken one
   * is ignored) and "!" + an allow word (a title with it is never hidden by the keyword rules) */
  var wordCache = { key: null };
  function wordRules() {
    var bw = cfg.blockWords || [];
    var k = JSON.stringify(bw);
    if (wordCache.key === k) return wordCache;
    var w = { key: k, plain: [], res: [], allow: [] };
    for (var i = 0; i < bw.length; i++) {
      var x = String(bw[i] || '').trim();
      if (!x) continue;
      if (/^re:/i.test(x)) {
        var src = x.slice(3).trim();
        if (src && src.length <= 100) { try { w.res.push(new RegExp(src, 'i')); } catch (e) { log('words', 'bad pattern ignored: ' + src.slice(0, 40)); } }
      } else if (x.charAt(0) === '!') {
        var al = lc(x.slice(1));
        if (al) w.allow.push(al);
      } else w.plain.push(lc(x));
    }
    wordCache = w;
    return w;
  }
  function wordBlocked(title) {
    var w = wordRules(), ti = lc(title);
    if (!ti || (!w.plain.length && !w.res.length)) return false;
    for (var a = 0; a < w.allow.length; a++) if (ti.indexOf(w.allow[a]) >= 0) return false;
    for (var p = 0; p < w.plain.length; p++) if (ti.indexOf(w.plain[p]) >= 0) return true;
    for (var r = 0; r < w.res.length; r++) if (w.res[r].test(title)) return true;
    return false;
  }
  function anyFilter() {
    return !!(hiddenList.length || (cfg.blockChannels || []).length || (cfg.blockWords || []).length || cfg.minDur > 0 || cfg.maxAgeDays > 0 || cfg.hideLive);
  }
  /* sponsored cards by their label (285): when YouTube renames its ad components the element rules
   * miss them, the short "Sponsored / 广告" label stays. Exact match of a short text only. */
  var AD_LABEL_RE = textRe('adLabel');
  function adLabelHit(it) {
    var l = it.querySelectorAll(RULES.sel.adLabel);
    var extra = remote.adText || [];
    for (var i = 0; i < l.length; i++) {
      var t = textOf(l[i]);
      if (!t || t.length > 20) continue;
      if (AD_LABEL_RE.test(t)) return true;
      for (var j = 0; j < extra.length; j++) if (lc(extra[j]) === lc(t)) return true;
    }
    return false;
  }
  var filterKey = '', filtered = 0;
  /** items: only these cards (incremental pass, 256); none: every card on the page. */
  var filtersPaused = false;   // "show everything" on an emptied feed (326), this page session only
  var filterCards = guard('filter', function (items) {
    var pt = pageType();
    var userOn = !filtersPaused && anyFilter() && pt !== 'channel' && pt !== 'other' && pt !== 'playlist';
    // channel pages (330): only what was hidden by hand; channel / keyword rules would empty the page
    var chanHidden = !filtersPaused && pt === 'channel' && hiddenList.length > 0;
    var key = JSON.stringify([cfg.blockChannels, cfg.blockWords, cfg.minDur, cfg.maxAgeDays, cfg.hideLive, hiddenList.length, pt, userOn,
      (remote.adText || []).length, chanHidden]);
    if (key !== filterKey) {
      filterKey = key;
      items = null;   // something changed: judge every card again
      if (filtered) {
        var old = document.querySelectorAll('[data-ytl-filter]');
        for (var o = 0; o < old.length; o++) old[o].removeAttribute('data-ytl-filter');
        filtered = 0;
      }
    }
    var deep = pt === 'home' || pt === 'watch';
    var list = items || document.querySelectorAll(RULES.sel.feedItem);
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      if (!it.isConnected) continue;
      var a = it.querySelector('a[href*="/watch?v="]');
      var mark = key + '|' + (a ? a.getAttribute('href') : '');
      if (it.__ytlF === mark) continue;   // already judged (recycled nodes get a new href)
      it.__ytlF = mark;
      var why = adLabelHit(it) ? 'ad' : userOn ? filterReason(cardInfo(it), deep, it)
        : chanHidden && isHiddenId(linkId(a)) ? 'hidden' : '';
      if (why) { if (it.getAttribute('data-ytl-filter') !== why) { it.setAttribute('data-ytl-filter', why); filtered++; } }
      else if (it.hasAttribute('data-ytl-filter')) it.removeAttribute('data-ytl-filter');
    }
  });
  function saveHidden() { try { localStorage.setItem(HIDDEN_KEY, JSON.stringify(hiddenList)); } catch (e) {} }
  function hideVideo(id) {
    if (!/^[\w-]{6,20}$/.test(id || '')) return;
    hiddenList = hiddenIds();
    if (hiddenList.indexOf(id) < 0) hiddenList.unshift(id);
    if (hiddenList.length > 300) hiddenList.length = 300;
    saveHidden();
    filterKey = '';
    filterCards();
    // a slip of the finger costs nothing (324): 4 s to take it back
    snackBar(S('hidden', '已隐藏这个视频'), S('undo', '撤销'), function () { unhideVideo(id); }, 4000);
  }
  function unhideVideo(id) {
    hiddenList = hiddenIds().filter(function (x) { return x !== id; });
    saveHidden();
    filterKey = '';
    filterCards();
  }

  // "N new videos" after a refresh (64): compared with the first screens of the last 3 refreshes (320)
  var countDone = false;
  var HOME_HIST = 'ytl_home_hist';
  function homeHistory() {
    var h = null;
    try { h = JSON.parse(localStorage.getItem(HOME_HIST) || 'null'); } catch (e) {}
    if (Array.isArray(h)) return h.filter(Array.isArray).slice(-3);
    var prev = [];
    try { prev = JSON.parse(localStorage.getItem('ytl_home_ids') || '[]') || []; } catch (e) {}   // v1.6 and older
    return Array.isArray(prev) && prev.length ? [prev] : [];
  }
  function newCount(ids, hist) {
    var seen = {};
    for (var i = 0; i < hist.length; i++) for (var j = 0; j < hist[i].length; j++) seen[hist[i][j]] = true;
    return ids.filter(function (x) { return !seen[x]; }).length;
  }
  var countNew = guard('refresh-count', function () {
    if (countDone || pageType() !== 'home') return;
    var links = document.querySelectorAll('ytm-rich-item-renderer a[href*="/watch?v="], ytm-video-with-context-renderer a[href*="/watch?v="]');
    var ids = [];
    for (var i = 0; i < links.length && ids.length < 40; i++) { var id = linkId(links[i]); if (id && ids.indexOf(id) < 0) ids.push(id); }
    if (ids.length < 4) return;   // feed not there yet
    countDone = true;
    var hist = homeHistory();
    var nh = hist.concat([ids]).slice(-3);
    try { localStorage.setItem(HOME_HIST, JSON.stringify(nh)); localStorage.setItem('ytl_home_ids', JSON.stringify(ids)); } catch (e) {}
    if (!wasRefresh || !hist.length) return;
    var n = newCount(ids, hist);
    noteRefreshResult(n);
    if (!cfg.refreshCount) return;
    // how many cards the filters took away (321), so a short feed is not a mystery
    var gone = document.querySelectorAll('[data-ytl-filter]:not([data-ytl-filter="ad"]), [data-ytl-seen]').length;
    call('toast', (n ? S('newVideos', '更新了 {n} 个新视频').replace('{n}', n) : S('noNew', '暂时没有新视频，稍后再刷新试试')) +
      (gone ? S('filteredN', ' · 已过滤 {n} 个').replace('{n}', gone) : ''));
  });

  /* filters emptied the home feed (326): say so at the top, with a way to see everything this time */
  var checkEmptyFeed = guard('empty-feed', function () {
    var el = document.getElementById('ytl-empty');
    var drop = function () { if (el && el.parentNode) el.parentNode.removeChild(el); };
    if (pageType() !== 'home' || filtersPaused) { drop(); return; }
    var all = document.querySelectorAll(RULES.sel.feedItem);
    var gone = 0, shown = 0;
    for (var i = 0; i < all.length; i++) {
      var it = all[i], f = it.getAttribute('data-ytl-filter');
      if (it.hasAttribute('data-ytl-seen') || (f && f !== 'ad')) gone++;
      else if (!f) shown++;
    }
    if (shown > 0 || gone < 4) { drop(); return; }
    var first = all[0], box = first && first.parentNode;
    if (!box || !box.parentNode) return;
    if (!el) {
      el = document.createElement('div');
      el.id = 'ytl-empty';
      el.setAttribute('role', 'status');
      var span = document.createElement('span');
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = S('showAll', '暂时显示全部');
      btn.addEventListener('click', guard('empty-show', function (ev) {
        ev.preventDefault(); ev.stopPropagation();
        filtersPaused = true;
        filterKey = '';
        var seen = document.querySelectorAll('[data-ytl-seen]');
        for (var k = 0; k < seen.length; k++) seen[k].removeAttribute('data-ytl-seen');
        seenCount = 0;
        filterCards();
        drop();
      }), true);
      el.appendChild(span);
      el.appendChild(btn);
    }
    if (el.parentNode !== box.parentNode || el.nextSibling !== box) box.parentNode.insertBefore(el, box);
    var txt = S('filteredAll', '已按你的过滤隐藏 {n} 个视频').replace('{n}', gone);
    if (el.firstChild.textContent !== txt) el.firstChild.textContent = txt;
  });

  /* once a day, the first time home has its feed (284): cards that still say "Sponsored / 广告" in a
   * short label but are on screen go to the missed-ads log. Read only, nothing is changed. */
  var LEAK_KEY = 'ytl_leakscan';
  var LEAK_RE = /(?:^|[\s·•|])(?:赞助|贊助|广告|廣告|sponsored|ad)(?:$|[\s·•|:：])/i;
  var leakQueued = false;
  function today() { var d = new Date(); return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate(); }
  var leakScan = guard('leak-scan', function () {
    var items = document.querySelectorAll(RULES.sel.feedItem), n = 0, ids = [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.closest('[data-ytl-filter], [data-ytl-hide], [data-ytl-seen]')) continue;
      try { if (window.getComputedStyle && getComputedStyle(it).display === 'none') continue; } catch (e) {}
      var labels = it.querySelectorAll(RULES.sel.adLabel + ', ' + RULES.sel.cardMeta), hit = false;
      for (var j = 0; j < labels.length && !hit; j++) { var t = textOf(labels[j]); if (t.length <= 40 && LEAK_RE.test(t)) hit = true; }
      if (hit) { n++; var id = linkId(it.querySelector('a[href*="/watch?v="]')); if (id && ids.length < 3) ids.push(id); }
    }
    if (n > 0) call('logAd', ids[0] || '-', 'suspect-leak x' + n, 'home', adContext() + (ids.length ? ' ids=' + ids.join(',') : ''));
    else log('leak-scan', 'home ok, ' + items.length + ' cards');
  });
  function maybeLeakScan() {
    if (leakQueued || pageType() !== 'home' || !countDone) return;
    var d = today();
    try { if (localStorage.getItem(LEAK_KEY) === d) { leakQueued = true; return; } localStorage.setItem(LEAK_KEY, d); } catch (e) { return; }
    leakQueued = true;
    setTimeout(leakScan, 3000);   // the feed settles first
  }

  // search: shelves like "People also watched" (70)
  var SEARCHX_RE = textRe('searchExtra');
  var markSearchExtras = guard('search-extras', function () {
    if (pageType() !== 'search') return;
    // auto-generated mixes (333): marked here, hidden by CSS only while the setting is on
    var mixes = document.querySelectorAll(RULES.sel.mixLink);
    for (var m = 0; m < mixes.length; m++) {
      var card = mixes[m].closest(RULES.sel.mixCard);
      if (card && !card.hasAttribute('data-ytl-searchmix')) card.setAttribute('data-ytl-searchmix', '');
    }
    var l = document.querySelectorAll(RULES.sel.searchShelf);
    for (var i = 0; i < l.length; i++) {
      var h = l[i].querySelector(RULES.sel.shelfTitle);
      var hit = h && SEARCHX_RE.test(textOf(h).slice(0, 80));
      if (hit) { if (!l[i].hasAttribute('data-ytl-searchx')) l[i].setAttribute('data-ytl-searchx', ''); }
      else if (l[i].hasAttribute('data-ytl-searchx')) l[i].removeAttribute('data-ytl-searchx');
    }
  });
  // keyboard comes up with the search box (124), only for an empty, visible box
  var searchFocused = null;
  var searchFocus = guard('search-focus', function () {
    var inp = document.querySelector(RULES.sel.searchInput);
    if (!inp) { searchFocused = null; return; }
    if (!cfg.searchFocus || inp === searchFocused) return;
    if (inp.value || !(inp.offsetParent || (inp.getClientRects && inp.getClientRects().length))) return;
    searchFocused = inp;
    if (document.activeElement !== inp) { try { inp.focus(); } catch (e) {} }
    call('showKeyboard');
  });
  // quick filters on search results (125): plain links to YouTube's own filter URLs
  function chipList() { return remote.chips || RULES.chips; }   // (328) rules.json can replace them
  function decSp(x) { try { return decodeURIComponent(x || ''); } catch (e) { return x || ''; } }
  var CHIP_KEY = 'ytl_chip';
  /** A chip was used (328): the result page must still carry its sp, else YouTube changed the format. */
  function checkChipSp(sp) {
    var want = null;
    try { want = sessionStorage.getItem(CHIP_KEY); if (want !== null) sessionStorage.removeItem(CHIP_KEY); } catch (e) {}
    if (want === null || decSp(want) === decSp(sp)) return;
    log('chips', 'filter not applied: sent sp=' + want.slice(0, 40) + ' page sp=' + String(sp).slice(0, 40));
  }
  var ensureChips = guard('chips', function () {
    var el = document.getElementById('ytl-chips');
    var params = new URLSearchParams(location.search);
    var q = params.get('search_query');
    if (!cfg.searchChips || pageType() !== 'search' || !q) { if (el && el.parentNode) el.parentNode.removeChild(el); return; }
    var host = document.querySelector(RULES.sel.searchList);
    if (!host || !host.parentNode) return;
    var sp = params.get('sp') || '';
    checkChipSp(sp);
    var key = q + '|' + sp;
    if (el && el.nextSibling === host && el.getAttribute('data-k') === key) return;
    if (el && el.parentNode) el.parentNode.removeChild(el);
    el = document.createElement('div');
    el.id = 'ytl-chips';
    el.setAttribute('data-k', key);
    el.setAttribute('role', 'toolbar');
    el.setAttribute('aria-label', '搜索筛选');
    var CHIPS = chipList();
    for (var i = 0; i < CHIPS.length; i++) {
      var a = document.createElement('a');
      a.href = '/results?search_query=' + encodeURIComponent(q) + (CHIPS[i][1] ? '&sp=' + CHIPS[i][1] : '');
      a.textContent = CHIPS[i][0];
      a.setAttribute('data-sp', CHIPS[i][1]);
      if (decSp(sp) === decSp(CHIPS[i][1])) a.setAttribute('aria-current', 'true');
      el.appendChild(a);
    }
    el.addEventListener('click', function (ev) {
      var ca = ev.target && ev.target.closest && ev.target.closest('a[data-sp]');
      if (ca) { try { sessionStorage.setItem(CHIP_KEY, ca.getAttribute('data-sp') || ''); } catch (e) {} }
    }, true);
    host.parentNode.insertBefore(el, host);
  });


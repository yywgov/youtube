'use strict';
// v1.8.0 (batch 6): home, look and feel, interaction polish + engineering
const test = require('node:test');
const assert = require('node:assert/strict');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="true"></button></ytm-app>';
const jsErrors = (calls) => calls.filter((c) => c[0] === 'log' && c[1] === 'js');

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML }, opts || {}));
  env.v = env.w.document.querySelector('video');
  env.p = env.w.document.getElementById('movie_player');
  return env;
}
function card(id, title, channel, extra, tag) {
  tag = tag || 'ytm-rich-item-renderer';
  return '<' + tag + ' id="c' + id + '"><a href="/watch?v=' + id + '">x</a><h3>' + title + '</h3>' +
    '<span class="YtmBadgeAndBylineRendererItemByline">' + channel + ' · 1K views · 2 天前</span>' + (extra || '') + '</' + tag + '>';
}
const WATCHED = '<div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 100%;"></div>';

/* ---------- 323: keyword rules ---------- */
test('keywords: "re:" patterns, "!" allow words, broken patterns ignored', async () => {
  const env = await load({ path: '/', cfg: { blockWords: ['spoiler', 're:^\\d+ hours? of', '!review', 're:([unclosed'] } });
  const wb = env.w.ytl._.wordBlocked;
  assert.equal(wb('Big SPOILER inside'), true);
  assert.equal(wb('10 hours of rain'), true, 'regex');
  assert.equal(wb('Spoiler-free review'), false, 'allow word wins');
  assert.equal(wb('Rain sounds'), false);
  assert.ok(env.calls.some((c) => c[0] === 'log' && c[1] === 'words'), 'broken pattern logged');
  assert.equal(jsErrors(env.calls).length, 0);
  const none = await load({ path: '/', cfg: { blockWords: ['!only allow'] } });
  assert.equal(none.w.ytl._.wordBlocked('only allow words'), false);
});

/* ---------- 330: channel pages apply local hiding only ---------- */
test('channel page: locally hidden videos stay hidden, channel / keyword rules do not apply', async () => {
  const body = '<ytm-app>' + card('hidden11111', 'Hidden one', 'Me') + card('spoiler1111', 'Spoiler', 'Me') + '</ytm-app>';
  const env = await load({ path: '/@me/videos', body, cfg: { blockWords: ['spoiler'], blockChannels: ['Me'] },
    before: (w) => w.localStorage.setItem('ytl_hidden', JSON.stringify(['hidden11111'])) });
  env.w.ytl._.domPass(true);
  const d = env.w.document;
  assert.equal(d.getElementById('chidden11111').getAttribute('data-ytl-filter'), 'hidden');
  assert.equal(d.getElementById('cspoiler1111').hasAttribute('data-ytl-filter'), false);
});

/* ---------- 327: related list hides watched videos when chosen ---------- */
test('related videos hide watched ones only with the setting on', async () => {
  const rel = card('watched1111', 'W', 'C', WATCHED, 'ytm-video-with-context-renderer') + card('fresh111111', 'F', 'C', '', 'ytm-video-with-context-renderer');
  const off = await setupWatch({ body: PLAYER_HTML + rel });
  off.w.ytl._.domPass(true);
  assert.equal(off.w.document.getElementById('cwatched1111').hasAttribute('data-ytl-seen'), false, 'off by default');
  const on = await setupWatch({ body: PLAYER_HTML + rel, cfg: { hideWatchedRelated: true } });
  on.w.ytl._.domPass(true);
  assert.equal(on.w.document.getElementById('cwatched1111').hasAttribute('data-ytl-seen'), true);
  assert.equal(on.w.document.getElementById('cfresh111111').hasAttribute('data-ytl-seen'), false);
});

/* ---------- 333: mixes in search results ---------- */
test('search: mix results are marked, hidden only while the setting is on', async () => {
  const body = '<ytm-app><ytm-section-list-renderer>' +
    '<ytm-compact-radio-renderer id=mix><a href="/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa">Mix</a></ytm-compact-radio-renderer>' +
    card('plain111111', 'Plain', 'C', '', 'ytm-video-with-context-renderer') + '</ytm-section-list-renderer></ytm-app>';
  const env = await load({ path: '/results?search_query=rain', body, cfg: { hideSearchMix: true } });
  env.w.ytl._.domPass(true);
  const d = env.w.document;
  assert.ok(d.getElementById('mix').hasAttribute('data-ytl-searchmix'));
  assert.equal(d.getElementById('cplain111111').hasAttribute('data-ytl-searchmix'), false);
  assert.equal(d.documentElement.getAttribute('data-ytl-nomix'), '1');
  assert.ok(/\[data-ytl-searchmix\]\{display:none/.test(env.w.ytl._.css()));
  env.w.ytl.cfg({ hideSearchMix: false });
  assert.equal(d.documentElement.hasAttribute('data-ytl-nomix'), false);
});

/* ---------- 328: chips from the rules + a check that the filter took ---------- */
test('search chips: rules.json can replace them; a lost sp after a chip tap is logged', async () => {
  const remote = { schema: 1, version: 2, hide: [], chips: [['全部', ''], ['新', 'CAI%3D'], ['bad<', 'x'], ['坏', 'a b']] };
  const body = '<ytm-app><ytm-search><ytm-section-list-renderer></ytm-section-list-renderer></ytm-search></ytm-app>';
  const env = await load({ path: '/results?search_query=rain', body, remote });
  env.w.ytl._.domPass(true);
  const chips = [...env.w.document.querySelectorAll('#ytl-chips a')].map((a) => a.textContent);
  assert.deepEqual(chips, ['全部', '新', 'bad<'], 'invalid sp dropped, short labels kept');
  const def = await load({ path: '/results?search_query=rain', body });
  assert.equal(def.w.ytl._.chipList().length, 6, 'built-in list without rules');
  // a chip was tapped with sp=CAI%3D but the page came back without it
  const lost = await load({ path: '/results?search_query=rain', body, before: (w) => w.sessionStorage.setItem('ytl_chip', 'CAI%3D') });
  lost.w.ytl._.domPass(true);
  assert.ok(lost.calls.some((c) => c[0] === 'log' && c[1] === 'chips'), 'mismatch logged');
  const kept = await load({ path: '/results?search_query=rain&sp=CAI%253D', body, before: (w) => w.sessionStorage.setItem('ytl_chip', 'CAI%3D') });
  kept.w.ytl._.domPass(true);
  assert.equal(kept.calls.filter((c) => c[0] === 'log' && c[1] === 'chips').length, 0);
});

/* ---------- 326: an emptied feed says why ---------- */
test('home emptied by filters: a note at the top, "show all" lifts the filters for this page', async () => {
  const ids = ['aaaaaaaaaa1', 'aaaaaaaaaa2', 'aaaaaaaaaa3', 'aaaaaaaaaa4'];
  const body = '<ytm-app><div id=feed>' + ids.map((id) => card(id, 'spoiler ' + id, 'C')).join('') + '</div></ytm-app>';
  const env = await load({ path: '/', body, cfg: { blockWords: ['spoiler'] } });
  const d = env.w.document;
  env.w.ytl._.domPass(true);
  const note = d.getElementById('ytl-empty');
  assert.ok(note, 'note shown');
  assert.ok(/已按你的过滤隐藏 4 个视频/.test(note.textContent));
  assert.equal(note.nextSibling, d.getElementById('feed'));
  note.querySelector('button').click();
  assert.equal(env.w.ytl._.filtersPaused(), true);
  assert.equal(d.querySelectorAll('[data-ytl-filter]').length, 0, 'filters lifted');
  assert.equal(d.getElementById('ytl-empty'), null);
  // a feed that still shows cards gets no note
  const ok = await load({ path: '/', body: '<ytm-app>' + card('aaaaaaaaaa1', 'fine', 'C') + ids.slice(1).map((id) => card(id, 'spoiler', 'C')).join('') + '</ytm-app>',
    cfg: { blockWords: ['spoiler'] } });
  ok.w.ytl._.domPass(true);
  assert.equal(ok.w.document.getElementById('ytl-empty'), null);
});

/* ---------- 266: scroll memory is capped ---------- */
test('back to a list: only the last 20 lists are remembered', async () => {
  const env = await load({ path: '/', cfg: { keepScroll: true } });
  const { w } = env;
  Object.defineProperty(w, 'scrollY', { configurable: true, get: () => 900 });
  for (let i = 0; i < 25; i++) {
    w.history.replaceState(null, '', '/results?search_query=q' + i);
    w.history.pushState(null, '', WATCH);
  }
  const mem = w.ytl._.scrollMem();
  assert.equal(Object.keys(mem).length, 20);
  assert.ok(!('/results?search_query=q0' in mem), 'oldest dropped');
  assert.ok('/results?search_query=q24' in mem);
});

/* ---------- 270: list images decode off the frame ---------- */
test('list images get decoding=async; an explicit value is left alone', async () => {
  const body = '<ytm-app>' + card('aaaaaaaaaa1', 'T', 'C', '<img id=i1 src="https://i.ytimg.com/vi/aaaaaaaaaa1/hqdefault.jpg">' +
    '<img id=i2 decoding="sync" src="x.jpg">') + '</ytm-app>';
  const env = await load({ path: '/', body });
  env.w.ytl._.domPass(true);
  assert.equal(env.w.document.getElementById('i1').getAttribute('decoding'), 'async');
  assert.equal(env.w.document.getElementById('i2').getAttribute('decoding'), 'sync');
});

/* ---------- 363: moon button only with a video or a timer ---------- */
test('moon button: hidden on home with nothing loaded, back with a timer; always on the watch page', async () => {
  const head = '<ytm-mobile-topbar-renderer><ytm-home-logo></ytm-home-logo></ytm-mobile-topbar-renderer>';
  const env = await load({ path: '/', body: '<ytm-app>' + head + '</ytm-app>', before: (w) => { w.YTL_openTools = 1; } });
  env.w.YTL.openTools = () => {};
  env.w.ytl._.domPass(true);
  const b = env.w.document.getElementById('ytl-tools');
  assert.ok(b, 'button exists');
  assert.ok(b.hasAttribute('data-ytl-off'), 'hidden without a video');
  env.w.ytl.sleepState(true);
  assert.equal(b.hasAttribute('data-ytl-off'), false, 'a running timer shows it');
  const w2 = await load({ path: WATCH, body: '<ytm-app>' + head + '</ytm-app>' });
  w2.w.ytl._.domPass(true);
  assert.equal(w2.w.document.getElementById('ytl-tools').hasAttribute('data-ytl-off'), false);
});

/* ---------- 368: long press shows that the card is held ---------- */
test('long press: the card is marked after 150 ms and unmarked when the finger lifts', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' + card('aaaaaaaaaa1', 'T', 'C') + '</ytm-app>' });
  const { w } = env;
  const it = w.document.getElementById('caaaaaaaaaa1');
  const h3 = it.querySelector('h3');
  const ev = new w.Event('touchstart', { bubbles: true });
  ev.touches = [{ clientX: 5, clientY: 5 }];
  h3.dispatchEvent(ev);
  await sleep(200);
  assert.ok(it.hasAttribute('data-ytl-press'));
  h3.dispatchEvent(new w.Event('touchend', { bubbles: true }));
  assert.equal(it.hasAttribute('data-ytl-press'), false);
});

/* ---------- 264: comment panel mutations do not cost a DOM pass ---------- */
test('watch page: mutations inside the comments panel are skipped, others still run the pass', async () => {
  const env = await setupWatch({ body: PLAYER_HTML.replace('</ytm-app>', '<ytm-engagement-panel id=ep><div id=cm></div></ytm-engagement-panel></ytm-app>') });
  const { w } = env;
  const before = w.ytl._.quietSkipped();
  w.document.getElementById('cm').appendChild(w.document.createElement('p'));
  await sleep(30);
  assert.equal(w.ytl._.quietSkipped(), before + 1);
  assert.equal(w.ytl._.quietBatch([{ target: w.document.getElementById('movie_player') }]), false);
});

/* ---------- 294: live streams ---------- */
test('metadata says when the video is a live stream', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  fakeMedia(v, { duration: Infinity });
  w.document.title = 'Live - YouTube';
  w.ytl._.sendMeta();
  const live = env.calls.filter((c) => c[0] === 'mediaLive');
  assert.deepEqual(live[live.length - 1], ['mediaLive', true]);
  const vod = await setupWatch();
  fakeMedia(vod.v, { duration: 600 });
  vod.w.document.title = 'Vod - YouTube';
  vod.w.ytl._.sendMeta();
  assert.deepEqual(vod.calls.filter((c) => c[0] === 'mediaLive').pop(), ['mediaLive', false]);
});

/* ---------- 309: the sleep badge can be tapped ---------- */
test('sleep badge: a tap asks Java for more time and never reaches the player', async () => {
  const env = await setupWatch({ cfg: { sleepBadge: true, badge: '睡眠 · 20 分' } });
  const { w, p } = env;
  w.ytl._.domPass(true);
  const b = w.document.getElementById('ytl-badge');
  assert.ok(b && b.tagName === 'BUTTON');
  assert.equal(b.textContent, '睡眠 · 20 分');
  let playerClicks = 0;
  p.addEventListener('click', () => playerClicks++);
  b.click();
  assert.ok(env.calls.some((c) => c[0] === 'sleepBadgeTap'));
  assert.equal(playerClicks, 0);
});

/* ---------- 313 / 315: end of a playlist, strict sleep rule ---------- */
const REL = '<ytm-video-with-context-renderer id=r1><a href="/watch?v=other111111">a</a><span class="byline">Someone</span></ytm-video-with-context-renderer>';
function fakeList(p, len, idx) {
  p.getPlaylist = () => new Array(len).fill('x');
  p.getPlaylistIndex = () => idx;
  p.nextVideo = () => { p.__next = true; };
  p.getVideoData = () => ({ author: 'Me', video_id: VID });
}
test('playlist finished: stops by default, goes on with a recommendation when chosen (never while sleeping)', async () => {
  for (const [cfg, sleeping, want] of [[{}, false, ''], [{ listEndNext: true }, false, '/watch?v=other111111'], [{ listEndNext: true }, true, '']]) {
    const env = await load({ path: WATCH + '&list=PLx', body: PLAYER_HTML + REL, cfg: Object.assign({ nextSource: 'playlist', sleeping }, cfg) });
    const { w } = env;
    const v = w.document.querySelector('video');
    fakeList(w.document.getElementById('movie_player'), 3, 2);
    let clicked = '';
    w.document.querySelectorAll('a').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); clicked = a.getAttribute('href'); }));
    const st = fakeMedia(v, { duration: 100, currentTime: 99.9 });
    fire(w, v, 'loadedmetadata');
    st.ended = true;
    fire(w, v, 'ended');
    await sleep(800);
    assert.equal(clicked, want, JSON.stringify(cfg) + ' sleeping=' + sleeping);
  }
});
test('sleeping, "same channel or stop": no same-channel video ends the timer instead of a stranger', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + REL, cfg: { sleepCalm: true, sleepStrict: true, sleeping: true } });
  const { w, v } = env;
  w.document.getElementById('movie_player').getVideoData = () => ({ author: 'Me', video_id: VID });
  let clicked = '';
  w.document.querySelectorAll('a').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); clicked = a.getAttribute('href'); }));
  const st = fakeMedia(v, { duration: 100, currentTime: 99.9 });
  fire(w, v, 'loadedmetadata');
  st.ended = true;
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(clicked, '');
  assert.ok(env.calls.some((c) => c[0] === 'sleepFired'));
  // not strict: the recommendation as before
  const soft = await setupWatch({ body: PLAYER_HTML + REL, cfg: { sleepCalm: true, sleeping: true } });
  soft.w.document.getElementById('movie_player').getVideoData = () => ({ author: 'Me', video_id: VID });
  const st2 = fakeMedia(soft.v, { duration: 100, currentTime: 99.9 });
  fire(soft.w, soft.v, 'loadedmetadata');
  st2.ended = true;
  fire(soft.w, soft.v, 'ended');
  await sleep(800);
  assert.equal(soft.calls.filter((c) => c[0] === 'sleepFired').length, 0);
});

/* ---------- 345 / 346 / 347: accounts ---------- */
function ytcfg(w, o) { w.ytcfg = { get: (k) => o[k] }; }
test('another account on home refreshes the feed once; the same account does nothing', async () => {
  const env = await load({ path: '/', before: (w) => { w.localStorage.setItem('ytl_acct', 'A||'); ytcfg(w, { LOGGED_IN: true, DATASYNC_ID: 'B||' }); } });
  await sleep(900);
  assert.ok(env.calls.some((c) => c[0] === 'hardReload'), 'refreshed');
  assert.equal(env.w.localStorage.getItem('ytl_acct'), 'B||');
  assert.ok(env.calls.some((c) => c[0] === 'loginState' && c[1] === true));
  const same = await load({ path: '/', before: (w) => { w.localStorage.setItem('ytl_acct', 'B||'); ytcfg(w, { LOGGED_IN: true, DATASYNC_ID: 'B||' }); } });
  await sleep(900);
  assert.equal(same.calls.filter((c) => c[0] === 'hardReload').length, 0);
  const first = await load({ path: '/', before: (w) => ytcfg(w, { LOGGED_IN: true, DATASYNC_ID: 'B||' }) });
  await sleep(900);
  assert.equal(first.calls.filter((c) => c[0] === 'hardReload').length, 0, 'first time only remembers');
});
test('signed out on home: the sign-in hint is asked for once', async () => {
  const env = await load({ path: '/', before: (w) => ytcfg(w, { LOGGED_IN: false }) });
  assert.equal(env.calls.filter((c) => c[0] === 'loginHint').length, 1);
  assert.ok(env.calls.some((c) => c[0] === 'loginState' && c[1] === false));
  env.w.ytl._.domPass(true);
  assert.equal(env.calls.filter((c) => c[0] === 'loginHint').length, 1, 'never twice');
});

/* ---------- 382: text table ---------- */
test('text matchers come from one table and rules.json can append plain words', async () => {
  const env = await load({ path: '/', remote: { schema: 1, version: 3, hide: [], text: { adLabel: ['Gesponsert', 'a.b'], still: ['noch da?'] } } });
  const t = env.w.ytl._.textRe;
  assert.ok(t('adLabel').test('Gesponsert'));
  assert.ok(t('adLabel').test('赞助'));
  assert.equal(t('adLabel').test('Gesponsert video'), false, 'full match stays full');
  assert.equal(t('adLabel').test('aXb'), false, 'appended words are literal');
  assert.ok(t('still').test('Bist du noch da? Weiter'));
  assert.ok(t('live').test('LIVE now'));
  assert.equal(t('live').test('not LIVE'), false, 'start anchor kept');
  assert.ok(t('feedback').test('Why this ad?'));
  assert.ok(t('feedback').test('help & feedback'));
  assert.equal(t('feedback').test('Helpful tips'), false);
});

/* ---------- 316: light refresh ---------- */
test('light refresh keeps the service worker and the static caches; the default clears them', async () => {
  for (const light of [false, true]) {
    let unreg = 0, cacheDel = 0;
    const env = await load({ path: '/', cfg: { lightRefresh: light }, before: (w) => {
      Object.defineProperty(w.navigator, 'serviceWorker', { configurable: true, value: { getRegistrations: () => Promise.resolve([{ unregister: () => { unreg++; return Promise.resolve(true); } }]) } });
      w.caches = { keys: () => Promise.resolve(['a']), delete: () => { cacheDel++; return Promise.resolve(true); } };
      w.sessionStorage.setItem('ytl_retry_x', '1');
      w.sessionStorage.setItem('yt-x', '1');
    } });
    env.w.ytl._.refresh(true);
    await sleep(50);
    assert.equal(unreg > 0, !light, 'service worker, light=' + light);
    assert.equal(cacheDel > 0, !light, 'caches, light=' + light);
    if (light) {
      assert.equal(env.w.sessionStorage.getItem('ytl_retry_x'), '1', 'own keys stay');
      assert.equal(env.w.sessionStorage.getItem('yt-x'), null, 'YouTube keys go');
    }
    await sleep(850);
    assert.ok(env.calls.some((c) => c[0] === 'hardReload'));
  }
});

/* ---------- 284: daily missed-ad check ---------- */
test('once a day: a visible card still labelled "Sponsored" is noted in the missed-ads log', async () => {
  const leak = card('leak1111111', 'Buy this', 'Shop', '<span class="media-item-metadata">Sponsored · Shop</span>');
  const env = await load({ path: '/', body: '<ytm-app>' + leak + card('fine1111111', 'Fine', 'C') + '</ytm-app>' });
  env.w.ytl._.leakScan();
  const ad = env.calls.filter((c) => c[0] === 'logAd');
  assert.equal(ad.length, 1);
  assert.equal(ad[0][1], 'leak1111111');
  assert.ok(/suspect-leak x1/.test(ad[0][2]));
  const clean = await load({ path: '/', body: '<ytm-app>' + card('fine1111111', 'Fine', 'C') + '</ytm-app>' });
  clean.w.ytl._.leakScan();
  assert.equal(clean.calls.filter((c) => c[0] === 'logAd').length, 0);
});

/* ---------- 339 / 314: quality ---------- */
test('default quality: a mismatch is logged once; a network change applies Wi-Fi best / mobile 720p again', async () => {
  let metered = false;
  const env = await setupWatch({ cfg: { quality: 'wifiMax' }, before: (w) => {} });
  const { w, v, p } = env;
  w.YTL.isMetered = () => metered;
  const set = [];
  p.setPlaybackQualityRange = (q) => set.push(q);
  p.getPlaybackQuality = () => 'hd720';
  p.getAvailableQualityLevels = () => ['highres', 'hd1080', 'hd720'];
  fakeMedia(v, { duration: 300 });
  fire(w, v, 'loadedmetadata');
  assert.equal(set[set.length - 1], 'highres');
  await sleep(3100);
  assert.ok(env.calls.some((c) => c[0] === 'log' && c[1] === 'quality' && /wanted highres/.test(c[2])));
  metered = true;
  w.ytl.netChanged();
  assert.equal(set[set.length - 1], 'hd720', 'mobile data: 720p');
});

/* ---------- 381: page texts from Java ---------- */
test('texts handed over by Java replace the built-in ones', async () => {
  const env = await load({ path: '/', cfg: { strings: { offline: 'OFFLINE!' } }, online: false });
  env.w.ytl._.refresh(true);
  assert.ok(env.calls.some((c) => c[0] === 'toast' && c[1] === 'OFFLINE!'));
});

/* ---------- 288: fresh rules apply without a reload ---------- */
test('rules handed over by Java apply at once; an older version is ignored', async () => {
  const body = '<ytm-app><div class="new-ad-thing" id=x></div></ytm-app>';
  const env = await load({ path: '/', body, remote: { schema: 1, version: 2, hide: [] } });
  const { w } = env;
  assert.equal(w.document.getElementById('ytl-css-remote'), null);
  assert.equal(w.ytl.remote(JSON.stringify({ schema: 1, version: 3, hide: ['.new-ad-thing'] })), true);
  const st = w.document.getElementById('ytl-css-remote');
  assert.ok(st && /\.new-ad-thing\{display:none/.test(st.textContent));
  assert.equal(w.ytl.remote(JSON.stringify({ schema: 1, version: 1, hide: ['.other'] })), false, 'older rules never replace newer');
  assert.equal(w.ytl.remote('not json'), false);
  assert.equal(jsErrors(env.calls).length, 0);
});

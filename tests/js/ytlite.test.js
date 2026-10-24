'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div></ytm-app>';

function playerResponse(extra) {
  return Object.assign({
    responseContext: { a: 1 },
    playabilityStatus: { status: 'OK' },
    streamingData: { adaptiveFormats: [{ url: 'https://rr1---sn-abc.googlevideo.com/videoplayback?x=1' }] },
    videoDetails: { videoId: VID, lengthSeconds: '600', isLiveContent: false },
    captions: { c: 1 }, storyboards: { s: 1 }, microformat: { m: 1 }, playerConfig: { p: 1 },
    endscreen: { e: 1 }, playbackTracking: { t: 1 }, heartbeatParams: { h: 1 }, attestation: { a: 1 },
    adPlacements: [{ x: 1 }], playerAds: [{ x: 1 }], adSlots: [1], adBreakHeartbeatParams: 'x',
    paidContentOverlay: { p: 1 }, videoQualityPromoSupportedRenderers: { v: 1 },
    messages: [{ mealbarPromoRenderer: { m: 1 } }, { importantMessageRenderer: { i: 1 } }]
  }, extra || {});
}

/* ---------- stylesheet / rule table (41/72, 4/7/8/12/49-51) ---------- */
test('one stylesheet, built from the rule table, :has() rules isolated', async () => {
  const { w } = await load({ path: '/' });
  const styles = w.document.querySelectorAll('style#ytl-css');
  assert.equal(styles.length, 1);
  const css = styles[0].textContent;
  for (const s of ['ytm-paid-content-overlay-renderer', '.ytp-ce-element', '.ytp-cards-teaser', 'ytm-mealbar-promo-renderer',
    '#movie_player.ytl-ad video', 'content-visibility:auto', 'yt-touch-feedback-shape', 'ytm-reel-shelf-renderer']) {
    assert.ok(css.includes(s), 'css has ' + s);
  }
  // every rule containing :has() has exactly one selector
  css.split('}').forEach((r) => {
    const sel = r.split('{')[0];
    if (sel.includes(':has(')) assert.ok(!sel.includes(','), 'isolated: ' + sel.trim());
  });
  // the autoplay countdown / end screen container itself is NOT hidden
  assert.ok(!/\.html5-endscreen\b/.test(css));
});

test('plain hide rules apply', async () => {
  const { w } = await load({ path: '/', body: '<ytm-promoted-video-renderer id=p>x</ytm-promoted-video-renderer><ytm-mealbar-promo-renderer id=m>x</ytm-mealbar-promo-renderer><div id=ok>x</div>' });
  assert.equal(w.getComputedStyle(w.document.getElementById('p')).display, 'none');
  assert.equal(w.getComputedStyle(w.document.getElementById('m')).display, 'none');
  assert.notEqual(w.getComputedStyle(w.document.getElementById('ok')).display, 'none');
});

test('perf css follows the setting via html[data-ytl-perf]', async () => {
  const { w } = await load({ path: '/', cfg: { perfCss: false } });
  assert.equal(w.document.documentElement.hasAttribute('data-ytl-perf'), false);
  w.ytl.cfg({ perfCss: true });
  assert.equal(w.document.documentElement.hasAttribute('data-ytl-perf'), true);
});

/* ---------- player response whitelist (1) ---------- */
test('player response rebuilt: playback fields kept, ad/promo fields dropped', async () => {
  const { w, calls } = await load({ path: WATCH, body: PLAYER_HTML });
  const pr = w.JSON.parse(JSON.stringify(playerResponse({ someFutureField: { k: 1 }, sponsoredAds: [1] })));
  for (const k of ['responseContext', 'playabilityStatus', 'streamingData', 'videoDetails', 'captions', 'storyboards',
    'microformat', 'playerConfig', 'endscreen', 'playbackTracking', 'heartbeatParams', 'attestation', 'someFutureField']) {
    assert.ok(k in pr, 'kept ' + k);
  }
  for (const k of ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'paidContentOverlay',
    'videoQualityPromoSupportedRenderers', 'sponsoredAds']) {
    assert.ok(!(k in pr), 'dropped ' + k);
  }
  assert.equal(pr.messages.length, 1);
  assert.ok(pr.messages[0].importantMessageRenderer);
  assert.ok(calls.some((c) => c[0] === 'log' && c[1] === 'pr-keep' && /someFutureField/.test(c[2])));
  assert.equal(JSON.stringify(w.ytl._.lenById()[VID]), JSON.stringify({ len: 600, live: false }));
  // video host preconnected
  assert.ok(w.document.querySelector('link[rel=preconnect][href="https://rr1---sn-abc.googlevideo.com"]'));
});

test('renamed (_ytl) ad keys from the text filter are removed too', async () => {
  const { w } = await load({ path: WATCH });
  const pr = w.JSON.parse(JSON.stringify(playerResponse({ adPlacements_ytl: [1], playerAds_ytl: [1] })));
  assert.ok(!('adPlacements_ytl' in pr));
  assert.ok(!('playerAds_ytl' in pr));
});

test('non-player innertube responses only lose ad keys', async () => {
  const { w } = await load({ path: '/' });
  const r = w.JSON.parse(JSON.stringify({ responseContext: {}, contents: { big: 'x'.repeat(200) }, onResponseReceivedActions: [1], adSlots: [1] }));
  assert.ok(r.contents && r.onResponseReceivedActions);
  assert.ok(!('adSlots' in r));
});

test('nested pbj / next responses are pruned', async () => {
  const { w } = await load({ path: WATCH });
  const r = w.JSON.parse(JSON.stringify([{ page: 'watch', pad: 'x'.repeat(150) }, { playerResponse: playerResponse() }, { response: { responseContext: {}, contents: {} } }]));
  assert.ok(!('adPlacements' in r[1].playerResponse));
  assert.ok('streamingData' in r[1].playerResponse);
});

test('ytInitialPlayerResponse assignment is pruned', async () => {
  const { w } = await load({ path: WATCH });
  w.ytInitialPlayerResponse = playerResponse();
  assert.ok(!('playerAds' in w.ytInitialPlayerResponse));
  assert.ok('streamingData' in w.ytInitialPlayerResponse);
});

test('JSON.parse fast path leaves small / non-object payloads alone', async () => {
  const { w } = await load({ path: '/' });
  assert.equal(w.ytl._.worthChecking('{"a":1}'), false);
  assert.equal(w.ytl._.worthChecking('  {"responseContext":{}' + ' '.repeat(200) + '}'), true);
  assert.equal(w.ytl._.worthChecking('"' + 'x'.repeat(200) + '"'), false);
  assert.equal(JSON.stringify(w.JSON.parse('{"adSlots":[1],"playabilityStatus":{}}')), '{"adSlots":[1],"playabilityStatus":{}}');
  assert.throws(() => w.JSON.parse('{bad'));
  assert.equal(w.JSON.parse('5'), 5);
});

/* ---------- ad fallback: length mismatch (3), cover (4), missed-ad log (11) ---------- */
async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML }, opts || {}));
  const { w } = env;
  let now = 1000000;
  w.Date.now = () => now;
  env.tick = (ms) => { now += ms; };
  env.v = w.document.querySelector('video');
  env.p = w.document.getElementById('movie_player');
  return env;
}

test('length mismatch is treated as an ad: cover, mute, 16x, logged once; restored after', async () => {
  const env = await setupWatch();
  const { w, v, p, calls } = env;
  w.JSON.parse(JSON.stringify(playerResponse()));
  const st = fakeMedia(v, { duration: 15, currentTime: 1 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'timeupdate');
  assert.equal(p.classList.contains('ytl-ad'), false, 'needs to be seen twice');
  env.tick(1200);
  fire(w, v, 'timeupdate');
  assert.equal(p.classList.contains('ytl-ad'), true);
  assert.equal(st.muted, true);
  assert.equal(st.playbackRate, 16);
  assert.ok(st.currentTime > 14, 'jumped to the end of the short ad element');
  env.tick(400);
  fire(w, v, 'timeupdate');
  assert.equal(calls.filter((c) => c[0] === 'logAd').length, 1);
  assert.equal(JSON.stringify(calls.find((c) => c[0] === 'logAd').slice(1, 4)), JSON.stringify([VID, 'length-mismatch', 'watch']));
  // the real video arrives
  st.duration = 600; st.currentTime = 0;
  fire(w, v, 'loadedmetadata');
  env.tick(400);
  fire(w, v, 'timeupdate');
  assert.equal(p.classList.contains('ytl-ad'), false);
  assert.equal(st.muted, false);
  assert.equal(st.playbackRate, 1);
});

test('length mismatch never fires for live, matching length, or a stale element', async () => {
  for (const variant of ['live', 'match', 'stale', 'long']) {
    const env = await setupWatch();
    const { w, v, p } = env;
    const vd = { videoId: VID, lengthSeconds: variant === 'live' ? '0' : '600', isLiveContent: variant === 'live' };
    w.JSON.parse(JSON.stringify(playerResponse({ videoDetails: vd })));
    const dur = variant === 'match' ? 600 : variant === 'long' ? 120 : 15;
    const st = fakeMedia(v, { duration: dur, currentTime: 1 });
    if (variant !== 'stale') fire(w, v, 'loadedmetadata');
    for (let i = 0; i < 4; i++) { env.tick(600); fire(w, v, 'timeupdate'); }
    assert.equal(p.classList.contains('ytl-ad'), false, variant);
    assert.equal(st.playbackRate, 1, variant);
  }
});

test('ad-showing class: muted + logged as ad-showing', async () => {
  const env = await setupWatch();
  const { w, v, p, calls } = env;
  const st = fakeMedia(v, { duration: 600, currentTime: 3 });
  p.classList.add('ad-showing');
  fire(w, v, 'playing');
  assert.equal(st.muted, true);
  assert.ok(calls.some((c) => c[0] === 'logAd' && c[2] === 'ad-showing'));
  p.classList.remove('ad-showing');
  env.tick(400);
  fire(w, v, 'timeupdate');
  assert.equal(st.muted, false);
});

/* ---------- SponsorBlock prompt + undo (9/10) ---------- */
test('SponsorBlock skip shows a prompt with undo; undone segment is not skipped again', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 600, currentTime: 0 });
  fire(w, v, 'loadedmetadata');
  w.ytl.onSegments(VID, [[5, 30, 'sponsor']]);
  st.currentTime = 10;
  env.tick(2000);
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 30);
  const box = w.document.getElementById('ytl-sb');
  assert.ok(box && /已跳过赞助片段/.test(box.textContent));
  box.querySelector('button').click();
  assert.equal(st.currentTime, 5);
  assert.equal(w.document.getElementById('ytl-sb'), null);
  st.currentTime = 6;
  env.tick(2000);
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 6, 'not skipped after undo');
});

test('SponsorBlock prompt disappears after 2 s and is not shown in background', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 600, currentTime: 0 });
  fire(w, v, 'loadedmetadata');
  w.ytl.onSegments(VID, [[5, 30, 'selfpromo'], [100, 120, 'sponsor']]);
  st.currentTime = 6; env.tick(2000); fire(w, v, 'timeupdate');
  assert.ok(/自我推广/.test(w.document.getElementById('ytl-sb').textContent));
  await sleep(2100);
  assert.equal(w.document.getElementById('ytl-sb'), null);
  w.ytl.bg(true);
  st.currentTime = 101; env.tick(2000); fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 120, 'still skips in background');
  assert.equal(w.document.getElementById('ytl-sb'), null);
});

test('segments for another video are ignored', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  fakeMedia(v, { duration: 600 });
  fire(w, v, 'loadedmetadata');
  w.ytl.onSegments('zzzzzzzzzzz', [[1, 2, 'sponsor']]);
  assert.equal(w.ytl._.segments().length, 0);
});

/* ---------- share links (68) ---------- */
test('tracking params removed from copied / shared links', async () => {
  let written = null;
  const { w } = await load({
    path: '/', before: (win) => {
      Object.defineProperty(win.navigator, 'clipboard', { configurable: true, value: { writeText(t) { written = t; return Promise.resolve(); } } });
    }
  });
  const c = w.ytl._.cleanText;
  assert.equal(c('https://youtu.be/abc?si=XYZ123'), 'https://youtu.be/abc');
  assert.equal(c('看这个 https://youtu.be/abc?si=XYZ&t=42 不错'), '看这个 https://youtu.be/abc?t=42 不错');
  assert.equal(c('https://m.youtube.com/watch?v=abc&pp=ygU&feature=shared&list=PL1'), 'https://m.youtube.com/watch?v=abc&list=PL1');
  assert.equal(c('https://example.com/?si=1'), 'https://example.com/?si=1');
  assert.equal(c('no links here'), 'no links here');
  await w.navigator.clipboard.writeText('https://youtu.be/abc?si=Q');
  assert.equal(written, 'https://youtu.be/abc');
});

/* ---------- ad beacons (6) ---------- */
test('ad report beacons are dropped, others pass', async () => {
  const sent = [];
  const { w } = await load({
    path: WATCH, before: (win) => { win.navigator.sendBeacon = (u) => { sent.push(u); return true; }; }
  });
  assert.equal(w.navigator.sendBeacon('https://m.youtube.com/api/stats/ads?x=1'), true);
  assert.equal(w.navigator.sendBeacon('https://m.youtube.com/pagead/adview?x=1'), true);
  w.navigator.sendBeacon('https://m.youtube.com/api/stats/watchtime?x=1');
  assert.deepEqual(sent, ['https://m.youtube.com/api/stats/watchtime?x=1']);
});

/* ---------- self check (74), guards (75/76) ---------- */
test('self check reports hooks in place and logs once', async () => {
  const { w, calls } = await load({ path: '/' });
  const r = w.ytl._.selfCheck(true);
  assert.equal(r.json, true); assert.equal(r.ipr, true); assert.equal(r.xhr, true);
  assert.equal(r.css, true); assert.equal(r.bridge, true); assert.equal(r.mediaSession, true);
  assert.deepEqual(calls.filter((c) => c[0] === 'log' && c[1] === 'selfcheck').map((c) => c[2]), ['ok']);
});

test('module errors are caught and logged (max 3 per module)', async () => {
  const { w, calls } = await load({ path: '/' });
  const f = w.ytl._.guard('boom-mod', () => { throw new Error('kaboom'); });
  for (let i = 0; i < 5; i++) assert.doesNotThrow(() => f());
  const l = calls.filter((c) => c[0] === 'log' && c[1] === 'js' && /boom-mod: .*kaboom/.test(c[2]));
  assert.equal(l.length, 3);
});

test('a broken bridge never breaks the page', async () => {
  const { w } = await load({ path: '/', before: (win) => { win.YTL.log = () => { throw new Error('bridge'); }; } });
  assert.doesNotThrow(() => w.ytl._.guard('x', () => { throw new Error('y'); })());
});

/* ---------- page partition (53), observer scope (42), background (54) ---------- */
test('page types', async () => {
  const { w } = await load({ path: '/' });
  const t = w.ytl._.pageType;
  assert.equal(t('/'), 'home'); assert.equal(t('/watch'), 'watch'); assert.equal(t('/live/x'), 'watch');
  assert.equal(t('/results'), 'search'); assert.equal(t('/feed/subscriptions'), 'subs');
  assert.equal(t('/feed/history'), 'history'); assert.equal(t('/feed/trending'), 'feed'); assert.equal(t('/playlist'), 'playlist');
  assert.equal(t('/feed/library'), 'library');
  assert.equal(t('/@chan/videos'), 'channel'); assert.equal(t('/account'), 'other');
});

const FEED = '<ytm-app><ytm-rich-item-renderer id=a><div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 90%;"></div></ytm-rich-item-renderer>' +
  '<ytm-rich-item-renderer id=b><div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 30%;"></div></ytm-rich-item-renderer></ytm-app>';

test('watched videos hidden on home only', async () => {
  const home = await load({ path: '/', body: FEED });
  assert.ok(home.w.document.getElementById('a').hasAttribute('data-ytl-seen'));
  assert.ok(!home.w.document.getElementById('b').hasAttribute('data-ytl-seen'));
  const subs = await load({ path: '/feed/subscriptions', body: FEED });
  assert.ok(!subs.w.document.getElementById('a').hasAttribute('data-ytl-seen'));
});

test('observer watches ytm-app, stops in background, resumes in foreground', async () => {
  const { w } = await load({ path: '/', body: FEED });
  assert.equal(w.ytl._.observerRoot(), w.document.querySelector('ytm-app'));
  assert.equal(w.ytl._.observing(), true);
  w.ytl.bg(true);
  assert.equal(w.ytl._.observing(), false);
  w.ytl.bg(false);
  assert.equal(w.ytl._.observing(), true);
});

test('page loaded while in background does not observe until foreground', async () => {
  const { w } = await load({ path: '/', body: FEED, cfg: { bg: true } });
  assert.equal(w.ytl._.observing(), false);
  w.ytl.bg(false);
  assert.equal(w.ytl._.observing(), true);
});

test('feedback items in a bottom sheet added to <body> later are still hidden', async () => {
  const { w } = await load({ path: '/', body: '<ytm-app><div>feed</div></ytm-app>' });
  const sheet = w.document.createElement('bottom-sheet-container');
  w.document.body.appendChild(sheet);
  await sleep(30);
  const item = w.document.createElement('yt-list-item-view-model');
  item.textContent = '反馈';
  const keep = w.document.createElement('yt-list-item-view-model');
  keep.textContent = '稍后观看';
  sheet.appendChild(item); sheet.appendChild(keep);
  await sleep(600);
  assert.ok(item.hasAttribute('data-ytl-hide'));
  assert.ok(!keep.hasAttribute('data-ytl-hide'));
});

/* ---------- recovery (23/38) ---------- */
test('playback retry reloads at the current position only once per video', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  fakeMedia(v, { duration: 600, currentTime: 125.7 });
  const urls = [];
  w.ytl._.nav.replace = (u) => urls.push(u);
  assert.equal(w.ytl._.retryOnce('stuck'), true);
  assert.equal(w.ytl._.retryOnce('stuck'), false);
  assert.equal(urls.length, 1);
  assert.ok(urls[0].includes('v=' + VID) && urls[0].includes('t=125s'));
});

test('no retry when the user paused or off the watch page', async () => {
  const home = await load({ path: '/' });
  assert.equal(home.w.ytl._.retryOnce('stuck'), false);
  const env = await setupWatch();
  env.w.ytl._.nav.replace = () => { throw new Error('should not reload'); };
  env.w.ytl.pause();
  assert.equal(env.w.ytl._.retryOnce('stuck'), false);
});

test('player error with a "try again" message triggers one retry, unavailable video does not', async () => {
  const env = await setupWatch({ body: PLAYER_HTML.replace('</div></ytm-app>', '<div class="ytp-error">Video unavailable</div></div></ytm-app>') });
  const urls = [];
  env.w.ytl._.nav.replace = (u) => urls.push(u);
  env.w.ytl._.domPass();
  assert.equal(urls.length, 0);
  env.w.document.querySelector('.ytp-error').textContent = 'An error occurred. Please try again later. (Playback ID: x)';
  env.w.ytl._.domPass();
  env.w.ytl._.domPass();
  assert.equal(urls.length, 1);
});

/* ---------- refresh keeps the sign-in (66) ---------- */
test('home refresh clears feed caches but never cookies or localStorage', async () => {
  const { w, calls } = await load({ path: '/' });
  w.document.cookie = 'SID=abc; path=/';
  w.localStorage.setItem('k', 'v');
  w.sessionStorage.setItem('s', '1');
  w.ytl.refresh();
  await sleep(900);
  assert.ok(calls.some((c) => c[0] === 'hardReload'));
  assert.ok(w.document.cookie.includes('SID=abc'));
  assert.equal(w.localStorage.getItem('k'), 'v');
  assert.equal(w.sessionStorage.getItem('s'), null);
});

/* ---------- thumbnails (47) ---------- */
test('maxres thumbnails swapped for hqdefault', async () => {
  const { w } = await load({ path: '/', body: '<ytm-app><img id=i src="https://i.ytimg.com/vi/abc/maxresdefault.jpg"><img id=o src="https://example.com/maxresdefault.jpg"></ytm-app>' });
  assert.equal(w.document.getElementById('i').getAttribute('src'), 'https://i.ytimg.com/vi/abc/hqdefault.jpg');
  assert.equal(w.document.getElementById('o').getAttribute('src'), 'https://example.com/maxresdefault.jpg');
});

/* ---------- existing behaviour still intact ---------- */
test('sleep timer "end of video" still fires', async () => {
  const env = await setupWatch({ cfg: { stopAtEnd: true } });
  const { w, v, calls } = env;
  fakeMedia(v, { duration: 100, currentTime: 99.5 });
  fire(w, v, 'timeupdate');
  assert.ok(calls.some((c) => c[0] === 'sleepFired'));
});

test('memory trim keeps the current video length', async () => {
  const env = await setupWatch();
  env.w.JSON.parse(JSON.stringify(playerResponse()));
  env.w.JSON.parse(JSON.stringify(playerResponse({ videoDetails: { videoId: 'otherother1', lengthSeconds: '100' } })));
  env.w.ytl.trim();
  assert.ok(env.w.ytl._.lenById()[VID]);
  assert.ok(!env.w.ytl._.lenById().otherother1);
});

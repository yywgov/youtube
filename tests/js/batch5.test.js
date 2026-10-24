'use strict';
// v1.7.0 (batch 5): battery, stability, ad-blocking reliability + low-risk long tail
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="true"></button></ytm-app>';
const jsErrors = (calls) => calls.filter((c) => c[0] === 'log' && c[1] === 'js');
const ROOT = path.join(__dirname, '..', '..');
const JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'google', 'android', 'youtube', 'pro');

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML }, opts || {}));
  env.v = env.w.document.querySelector('video');
  env.p = env.w.document.getElementById('movie_player');
  return env;
}
function card(id, title, channel, extra) {
  return '<ytm-rich-item-renderer id="c' + id + '"><a href="/watch?v=' + id + '">x</a><h3>' + title + '</h3>' +
    '<span class="YtmBadgeAndBylineRendererItemByline">' + channel + ' · 1K views · 2 天前</span>' + (extra || '') + '</ytm-rich-item-renderer>';
}

/* ---------- 256: incremental DOM pass ---------- */
test('incremental pass: new cards are filtered without re-reading the whole list', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' + card('aaaaaaaaaa1', 'Spoiler ahead', 'Ch') + card('aaaaaaaaaa2', 'Fine', 'Ch') + '</ytm-app>',
    cfg: { blockWords: ['spoiler'] } });
  const { w } = env;
  const d = w.document;
  w.ytl._.domPass(true);
  assert.equal(d.getElementById('caaaaaaaaaa1').getAttribute('data-ytl-filter'), 'word');
  assert.equal(w.ytl._.fullPending(), false);
  // count querySelectorAll over the whole document during the next (incremental) pass
  let docQueries = 0;
  const orig = d.querySelectorAll.bind(d);
  d.querySelectorAll = function (sel) { if (/^ytm-rich-item-renderer, ytm-video-with-context-renderer/.test(sel)) docQueries++; return orig(sel); };
  const app = d.querySelector('ytm-app');
  app.insertAdjacentHTML('beforeend', card('aaaaaaaaaa3', 'Another SPOILER', 'Ch'));
  await sleep(450);
  assert.equal(d.getElementById('caaaaaaaaaa3').getAttribute('data-ytl-filter'), 'word', 'new card judged');
  assert.equal(docQueries, 0, 'no document-wide card query in the incremental pass');
  // a recycled card (title text replaced, href changed) is judged again
  const c2 = d.getElementById('caaaaaaaaaa2');
  c2.querySelector('a').setAttribute('href', '/watch?v=bbbbbbbbbb2');
  c2.querySelector('h3').textContent = 'spoiler inside';
  await sleep(450);
  assert.equal(c2.getAttribute('data-ytl-filter'), 'word');
  assert.equal(jsErrors(env.calls).length, 0);
});

test('incremental pass: settings change, navigation and the smoothness switch force a full pass', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' + card('aaaaaaaaaa1', 'Hello', 'Ch') + '</ytm-app>' });
  const { w } = env;
  w.ytl._.domPass(true);
  w.ytl.cfg({ blockWords: ['hello'] });
  assert.equal(w.ytl._.fullPending(), true, 'settings change asks for a full pass');
  w.ytl._.domPass();
  assert.equal(w.document.getElementById('caaaaaaaaaa1').getAttribute('data-ytl-filter'), 'word');
  w.ytl.cfg({ perfCss: false });
  w.ytl._.domPass();
  assert.equal(w.ytl._.fullPending(), false);
});

/* ---------- 279: one ad-key list ---------- */
test('ad keys: adkeys.json == the script\'s built-in list == the Java fallback; extra keys from Java are used', async () => {
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'assets', 'adkeys.json'), 'utf8'));
  const env = await load({ path: '/' });
  assert.deepEqual(JSON.parse(JSON.stringify(env.w.ytl._.adKeysBuiltin)), json.keys);
  const wpf = fs.readFileSync(path.join(JAVA, 'webview', 'WatchPageFilter.java'), 'utf8');
  const javaList = /AD_KEYS_BUILTIN = \{([^}]*)\}/.exec(wpf)[1].match(/"(\w+)"/g).map((s) => s.slice(1, -1));
  assert.deepEqual(javaList, json.keys);
  const ext = await load({ path: '/', cfg: { adKeys: json.keys.concat(['adNewThing', 'bad key!']) } });
  assert.ok(ext.w.ytl._.adKeys().includes('adNewThing'));
  assert.ok(!ext.w.ytl._.adKeys().includes('bad key!'));
  const big = '{"responseContext":{},"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"' + VID + '"},"adNewThing":[1,2,3],"pad":"' + 'x'.repeat(200) + '"}';
  const o = ext.w.JSON.parse(big);
  assert.equal(o.adNewThing, undefined, 'the extra key is dropped like the built-in ones');
  assert.equal('adNewThing' in ext.w.ytl._.prune({ adNewThing: 1 }), false);
});

/* ---------- 282: length check guards ---------- */
test('length check: premieres / live replays (microformat) are never judged by length', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  w.ytInitialPlayerResponse = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: VID, lengthSeconds: '3600' },
    microformat: { playerMicroformatRenderer: { liveBroadcastDetails: { isLiveNow: false } } } };
  fakeMedia(v, { duration: 30, currentTime: 1 });
  fire(w, v, 'loadedmetadata');
  assert.equal(w.ytl._.lenMismatch(v), false);
});

test('length check: a "fake ad" that ends as the video itself disables the check for that video', async () => {
  const env = await setupWatch();
  const { w, v, calls } = env;
  w.ytInitialPlayerResponse = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: VID, lengthSeconds: '600' } };
  const st = fakeMedia(v, { duration: 30, currentTime: 1 });
  let now = Date.now();
  w.Date.now = () => now;
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'timeupdate'); now += 1500; fire(w, v, 'timeupdate');
  assert.equal(w.ytl._.adNow(), true, 'taken for an ad');
  st.currentTime = 30; st.ended = true;
  fire(w, v, 'ended');
  assert.equal(w.ytl._.adNow(), false);
  assert.ok(calls.some((c) => c[0] === 'log' && c[1] === 'len-misjudge'));
  now += 1500; fire(w, v, 'timeupdate');
  assert.equal(w.ytl._.lenMismatch(v), false, 'never again for this video');
});

/* ---------- 283: no ad metadata on the lock screen ---------- */
test('metadata is held back while an ad plays and sent once it is over', async () => {
  const env = await setupWatch();
  const { w, v, p, calls } = env;
  fakeMedia(v, { duration: 300, currentTime: 1 });
  p.classList.add('ad-showing');
  w.navigator.mediaSession.metadata = new w.MediaMetadata({ title: 'Buy now', artist: 'Advertiser' });
  w.ytl._.sendMeta();
  assert.equal(calls.filter((c) => c[0] === 'mediaMeta').length, 0);
  p.classList.remove('ad-showing');
  w.navigator.mediaSession.metadata = new w.MediaMetadata({ title: 'Real title', artist: 'Real channel' });
  await sleep(50);
  w.ytl._.domPass(true);   // skipAd notices the ad is gone
  await sleep(250);
  const m = calls.filter((c) => c[0] === 'mediaMeta');
  assert.ok(m.length >= 1);
  assert.equal(m[m.length - 1][1], 'Real title');
});

/* ---------- 285: sponsored cards by their label ---------- */
test('sponsored label hides a card (exact short text); a normal title containing "Ad" stays', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' +
    card('aaaaaaaaaa1', 'Product', 'Brand', '<badge-shape><div class="badge-shape-wiz__text">Sponsored</div></badge-shape>') +
    card('aaaaaaaaaa2', 'Ad astra review', 'Ad Lab', '<badge-shape><div class="badge-shape-wiz__text">12:34</div></badge-shape>') +
    card('aaaaaaaaaa3', 'X', 'Y', '<badge-shape>广告</badge-shape>') + '</ytm-app>' });
  const d = env.w.document;
  env.w.ytl._.domPass(true);
  assert.equal(d.getElementById('caaaaaaaaaa1').getAttribute('data-ytl-filter'), 'ad');
  assert.equal(d.getElementById('caaaaaaaaaa2').hasAttribute('data-ytl-filter'), false);
  assert.equal(d.getElementById('caaaaaaaaaa3').getAttribute('data-ytl-filter'), 'ad');
  const remote = await load({ path: '/', remote: { schema: 1, version: 2, hide: [], adText: ['Promoted'] },
    body: '<ytm-app>' + card('aaaaaaaaaa4', 'T', 'C', '<badge-shape>Promoted</badge-shape>') + '</ytm-app>' });
  remote.w.ytl._.domPass(true);
  assert.equal(remote.w.document.getElementById('caaaaaaaaaa4').getAttribute('data-ytl-filter'), 'ad', 'label added by remote rules');
});

/* ---------- 299: fade driven by the page ---------- */
test('sleep fade: one message from Java, the page steps the volume on the squared curve', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 300, volume: 1 });
  w.ytl.fadeStart(400, 1000);   // 0.4 s left of a 1 s fade
  assert.ok(Math.abs(st.volume - 0.16) < 0.03, 'starts at (0.4)^2: ' + st.volume);
  assert.equal(w.ytl._.fadeState().running, true);
  await sleep(600);
  assert.ok(st.volume < 0.02, 'reached silence: ' + st.volume);
  w.ytl.restoreVolume();
  assert.equal(st.volume, 1, 'volume back');
  assert.equal(w.ytl._.fadeState().running, false);
});

/* ---------- 301: "after N videos" counts early switches ---------- */
test('"after N videos": moving on after 90 % counts, moving on at 50 % does not', async () => {
  const env = await setupWatch({ cfg: { countMode: true } });
  const { w, v, p, calls } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 92 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'timeupdate');
  p.getVideoData = () => ({ video_id: 'nextvideo11' });
  w.history.pushState({}, '', '/watch?v=nextvideo11');
  st.currentTime = 50;
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'timeupdate');
  w.history.pushState({}, '', '/watch?v=third333333');
  fire(w, v, 'loadedmetadata');
  const ended = calls.filter((c) => c[0] === 'videoEnded').map((c) => c[1]);
  assert.deepEqual(ended, [VID]);
});

test('"after N videos": when an early switch used up the count, the video that just started is the last one', async () => {
  const env = await setupWatch({ cfg: { countMode: true } });
  const { w, v, p } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 95 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'timeupdate');
  p.getVideoData = () => ({ video_id: 'nextvideo11' });
  w.history.pushState({}, '', '/watch?v=nextvideo11');
  st.currentTime = 1;
  fire(w, v, 'loadedmetadata');
  // Java: that was the second-to-last one -> countMode(false); stopAtEndNext()
  w.ytl.countMode(false);
  w.ytl.stopAtEndNext();
  assert.equal(w.ytl._.stopAtEnd(), true, 'stops at the end of this video, not the one after');
});

/* ---------- 249: autoplay set on every video change (observer is off with the screen off) ---------- */
test('video change with the screen off sets YouTube autoplay without a DOM pass', async () => {
  const env = await setupWatch({ cfg: { nextSource: 'channel', bg: true } });
  const { w, v } = env;
  const tg = w.document.querySelector('.ytm-autonav-toggle-button-container');
  let clicks = 0;
  tg.addEventListener('click', () => { clicks++; tg.setAttribute('aria-pressed', 'false'); });
  fakeMedia(v, { duration: 100 });
  fire(w, v, 'loadedmetadata');
  assert.ok(clicks >= 1, 'switched off for the channel rule');
  assert.equal(tg.getAttribute('aria-pressed'), 'false');
});

/* ---------- 311: skip watched autoplay targets ---------- */
const RELATED = '<ytm-app><ytm-video-with-context-renderer id=w1><a href="/watch?v=watched1111">a</a>' +
  '<div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 100%;"></div></ytm-video-with-context-renderer>' +
  '<ytm-video-with-context-renderer id=u1><a href="/watch?v=unseen11111">b</a></ytm-video-with-context-renderer></ytm-app>';
test('skip watched: autoplay off while its target is watched; at the end the first unwatched video plays', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + RELATED, cfg: { skipWatchedNext: true } });
  const { w, v } = env;
  assert.equal(w.ytl._.upNextWatched(), true);
  assert.equal(w.ytl._.wantAutonav(), false);
  let clicked = '';
  w.document.querySelectorAll('a').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); clicked = a.getAttribute('href'); }));
  const st = fakeMedia(v, { duration: 100, currentTime: 99.9 });
  fire(w, v, 'loadedmetadata');
  st.ended = true;
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(clicked, '/watch?v=unseen11111');
  const off = await setupWatch({ body: PLAYER_HTML + RELATED });
  assert.equal(off.w.ytl._.wantAutonav(), true, 'off by default: YouTube autoplay as before');
});

/* ---------- 320 / 321: refresh count ---------- */
test('new videos are compared with the last three refreshes; filtered cards are counted in the toast', async () => {
  const env = await load({ path: '/' });
  const { newCount } = env.w.ytl._;
  assert.equal(newCount(['a', 'b', 'c', 'd'], [['a'], ['b', 'x'], ['c']]), 1);
  assert.equal(newCount(['a', 'b'], []), 2);
  const ids = ['aaaaaaaaaa1', 'aaaaaaaaaa2', 'aaaaaaaaaa3', 'aaaaaaaaaa4', 'aaaaaaaaaa5'];
  const body = '<ytm-app>' + ids.map((id, i) => card(id, i === 4 ? 'spoiler' : 'T' + i, 'C')).join('') + '</ytm-app>';
  const r = await load({ path: '/?ytlr=abc', body, cfg: { blockWords: ['spoiler'] },
    before: (w) => { w.localStorage.setItem('ytl_home_ids', JSON.stringify(['aaaaaaaaaa1', 'aaaaaaaaaa2'])); } });
  const t = r.calls.filter((c) => c[0] === 'toast').map((c) => c[1]);
  assert.ok(t.some((x) => /更新了 3 个新视频 · 已过滤 1 个/.test(x)), JSON.stringify(t));
  const hist = JSON.parse(r.w.localStorage.getItem('ytl_home_hist'));
  assert.equal(hist.length, 2, 'old single list taken over + this one');
});

/* ---------- 322: block by channel link ---------- */
test('channel block "@handle|Name" matches the link after a rename; plain names still work', async () => {
  const env = await load({ path: '/' });
  const { blockEntry } = env.w.ytl._;
  assert.deepEqual(JSON.parse(JSON.stringify(blockEntry('@MyChan|My Channel'))), { key: '@mychan', name: 'my channel' });
  assert.deepEqual(JSON.parse(JSON.stringify(blockEntry('Old Name'))), { key: '', name: 'old name' });
  assert.deepEqual(JSON.parse(JSON.stringify(blockEntry('UCabcdefghijklmnopqrstuv'))), { key: 'UCabcdefghijklmnopqrstuv', name: '' });
  const body = '<ytm-app>' + card('aaaaaaaaaa1', 'T', 'Renamed Channel', '<a href="/@mychan">avatar</a>') +
    card('aaaaaaaaaa2', 'T', 'My Channel', '<a href="/@someoneelse">avatar</a>') + card('aaaaaaaaaa3', 'T', 'Old Name') + '</ytm-app>';
  const r = await load({ path: '/', body, cfg: { blockChannels: ['@mychan|My Channel', 'Old Name'] } });
  r.w.ytl._.domPass(true);
  const d = r.w.document;
  assert.equal(d.getElementById('caaaaaaaaaa1').getAttribute('data-ytl-filter'), 'channel', 'renamed, same link');
  assert.equal(d.getElementById('caaaaaaaaaa2').getAttribute('data-ytl-filter'), 'channel', 'same name (old behaviour kept)');
  assert.equal(d.getElementById('caaaaaaaaaa3').getAttribute('data-ytl-filter'), 'channel');
});

/* ---------- 324: hide with undo ---------- */
test('hiding a video offers a 4 s undo that brings it back', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' + card('aaaaaaaaaa1', 'T', 'C') + '</ytm-app>' });
  const { w } = env;
  w.ytl.hideVideo('aaaaaaaaaa1');
  const c = w.document.getElementById('caaaaaaaaaa1');
  assert.equal(c.getAttribute('data-ytl-filter'), 'hidden');
  const bar = w.document.getElementById('ytl-sb');
  assert.ok(bar && /已隐藏/.test(bar.textContent));
  bar.querySelector('button').click();
  assert.equal(c.hasAttribute('data-ytl-filter'), false);
  assert.equal(w.ytl.hiddenCount(), 0);
});

/* ---------- 325 / 331: list pages ---------- */
test('subscriptions-only rule is scoped to the subscriptions page, not history', async () => {
  const subs = await load({ path: '/feed/subscriptions', cfg: { subsOnlyVideos: true } });
  assert.equal(subs.w.document.documentElement.getAttribute('data-ytl-page'), 'subs');
  assert.ok(/data-ytl-page="subs"\] ytm-backstage-post-thread-renderer/.test(subs.w.ytl._.css()));
  const hist = await load({ path: '/feed/history' });
  assert.equal(hist.w.document.documentElement.getAttribute('data-ytl-page'), 'history');
});

test('watch later list: long press works on playlist rows and says it is the WL list', async () => {
  const body = '<ytm-app><ytm-playlist-video-renderer id=pl><a href="/watch?v=aaaaaaaaaa1&list=WL">x</a><h4>Saved</h4>' +
    '<span class="YtmBadgeAndBylineRendererItemByline">Chan</span></ytm-playlist-video-renderer></ytm-app>';
  const env = await load({ path: '/playlist?list=WL', body });
  const { w, calls } = env;
  const el = w.document.querySelector('#pl h4');
  const t = new w.Event('touchstart', { bubbles: true });
  t.touches = [{ clientX: 5, clientY: 5 }];
  el.dispatchEvent(t);
  await sleep(600);
  const m = calls.filter((c) => c[0] === 'cardMenu');
  assert.equal(m.length, 1);
  assert.equal(JSON.parse(m[0][1]).wl, true);
});

/* ---------- 338: tools in the mini player ---------- */
test('playback tools show the playback group when a video plays in the mini player', async () => {
  const env = await load({ path: '/', body: PLAYER_HTML });
  const { w } = env;
  const v = w.document.querySelector('video');
  fakeMedia(v, { duration: 100, muted: false });
  w.document.getElementById('movie_player').getVideoData = () => ({ video_id: VID });
  fire(w, v, 'playing');
  assert.equal(w.ytl._.toolState().watch, true);
});

/* ---------- 341: caption language ---------- */
test('captions: simplified / traditional / English preferences, human captions first', async () => {
  const env = await load({ path: '/' });
  const pick = env.w.ytl._.pickCaption;
  const list = [{ languageCode: 'en', kind: 'asr' }, { languageCode: 'zh-Hant' }, { languageCode: 'zh-Hans' }, { languageCode: 'en' }];
  assert.equal(pick(list, 'zhHans').languageCode, 'zh-Hans');
  assert.equal(pick(list, 'zhHant').languageCode, 'zh-Hant');
  assert.equal(pick(list, 'en').kind, undefined, 'human English before auto');
  assert.equal(pick([{ languageCode: 'zh-TW' }], 'zhHans').languageCode, 'zh-TW', 'falls back to any Chinese');
  assert.equal(pick([{ languageCode: 'ja' }], 'en'), null);
});

/* ---------- 367 / 374 / 378: look ---------- */
test('gear label uses the app name and says when an update is ready; low-motion / big-font attributes', async () => {
  const env = await load({ path: '/', body: '<ytm-app><ytm-home-logo></ytm-home-logo></ytm-app>', cfg: { appName: '一维视频', lowMotion: true, fontLarge: true } });
  const { w } = env;
  const gear = w.document.getElementById('ytl-gear');
  assert.equal(gear.getAttribute('aria-label'), '一维视频 设置');
  w.ytl.cfg({ updateDot: true });
  assert.equal(gear.getAttribute('aria-label'), '一维视频 设置，有新版本');
  const h = w.document.documentElement;
  assert.equal(h.getAttribute('data-ytl-lowmotion'), '1');
  assert.equal(h.getAttribute('data-ytl-bigfont'), '1');
});

/* ---------- 254 / 280 / 247 ---------- */
test('self check failure is handed to Java; missed-ad lines carry context; native errors have a report hook', async () => {
  const env = await setupWatch();
  const { w, calls } = env;
  delete w.JSON.parse.__ytl;
  w.ytl._.selfCheck(false);
  const f = calls.filter((c) => c[0] === 'selfCheckFailed');
  assert.equal(f.length, 1);
  assert.ok(/json/.test(f[0][1]));
  assert.ok(/^sc=FAIL:json login=\S+ rules=0 keys=4$/.test(w.ytl._.adContext()), w.ytl._.adContext());
  assert.equal(typeof w.ytl.report, 'function');
  w.ytl.report('ytl.play()', new Error('boom'));
  assert.ok(calls.some((c) => c[0] === 'log' && c[1] === 'js' && /java:ytl\.play\(\): .*boom/.test(c[2])));
});

/* ---------- 350: native colours from the design tokens only ---------- */
test('contract: no colour literals outside Ui.java (350)', async () => {
  const files = [];
  (function walk(d) { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); if (fs.statSync(p).isDirectory()) walk(p); else if (f.endsWith('.java')) files.push(p); } })(JAVA);
  for (const f of files) {
    if (path.basename(f) === 'Ui.java') continue;
    const hits = fs.readFileSync(f, 'utf8').match(/0x[0-9A-Fa-f]{8}\b/g) || [];
    assert.deepEqual(hits, [], path.basename(f) + ' has colour literals');
  }
});

/* ---------- 429 helper: the bridge method list the CI check compares with the release dex ---------- */
test('contract: every bridge method the script calls exists on WebAppInterface', async () => {
  const src = fs.readFileSync(path.join(JAVA, 'webview', 'WebAppInterface.java'), 'utf8');
  const methods = [...src.matchAll(/@JavascriptInterface\s+public\s+[\w<>]+\s+(\w+)\(/g)].map((m) => m[1]);
  const js = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'assets', 'ytlite.js'), 'utf8');
  const called = new Set([...js.matchAll(/call\('(\w+)'/g)].map((m) => m[1]));
  for (const m of ['getConfig', 'getRemoteRules', 'isMetered', 'isOnline']) called.add(m);
  for (const c of called) assert.ok(methods.includes(c), 'missing bridge method ' + c);
});

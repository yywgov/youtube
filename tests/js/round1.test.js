'use strict';
// v1.9.0 (iteration round 1): autoplay never goes into blocked videos, end-of-video checks, light refresh fallback, faster filters
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

function rel(id, vid, title, channel) {
  return '<ytm-video-with-context-renderer id="' + id + '"><a href="/watch?v=' + vid + '">x</a><h3>' + title + '</h3>' +
    '<span class="YtmBadgeAndBylineRendererItemByline">' + channel + ' · 1K views</span></ytm-video-with-context-renderer>';
}
const RELATED = '<ytm-app>' + rel('r1', 'blocked1111', 'Loud spoiler', 'Noisy') + rel('r2', 'fine1111111', 'Calm', 'Quiet') + '</ytm-app>';

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML + RELATED }, opts || {}));
  env.v = env.w.document.querySelector('video');
  return env;
}

/* ---------- 444: autoplay never goes into a blocked video ---------- */
test('blocked autoplay target: YouTube autoplay off, the first allowed related video plays at the end', async () => {
  const env = await setupWatch({ cfg: { blockChannels: ['Noisy'] } });
  const { w, v } = env;
  assert.equal(w.ytl._.upNextBlocked(), true, 'judged on the spot, without a DOM pass');
  assert.equal(w.ytl._.wantAutonav(), false);
  let clicked = '';
  w.document.querySelectorAll('a').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); clicked = a.getAttribute('href'); }));
  const st = fakeMedia(v, { duration: 100, currentTime: 99.9 });
  fire(w, v, 'loadedmetadata');
  st.ended = true;
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(clicked, '/watch?v=fine1111111');
  assert.ok(env.calls.some((c) => c[0] === 'log' && c[1] === 'next' && /blocked/.test(c[2])));
  assert.equal(jsErrors(env.calls).length, 0);
});

test('blocked autoplay target: keywords and locally hidden videos count, ads labels do not; no filters = YouTube as before', async () => {
  const words = await setupWatch({ cfg: { blockWords: ['spoiler'] } });
  assert.equal(words.w.ytl._.wantAutonav(), false);
  assert.equal(words.w.ytl._.firstAllowedLink(VID, false).getAttribute('href'), '/watch?v=fine1111111');
  const hidden = await setupWatch({ before: (w) => { w.localStorage.setItem('ytl_hidden', JSON.stringify(['blocked1111'])); } });
  assert.equal(hidden.w.ytl._.upNextBlocked(), true);
  const none = await setupWatch();
  assert.equal(none.w.ytl._.upNextBlocked(), false);
  assert.equal(none.w.ytl._.wantAutonav(), true, 'nothing blocked: YouTube autoplay stays on');
  // an "ad" mark alone (sponsored label) is not the user's own block
  none.w.document.getElementById('r1').setAttribute('data-ytl-filter', 'ad');
  assert.equal(none.w.ytl._.upNextBlocked(), false);
});

test('blocked + skip watched: the first video that is neither blocked nor watched', async () => {
  const body = PLAYER_HTML + '<ytm-app>' + rel('r1', 'blocked1111', 'x', 'Noisy') +
    '<ytm-video-with-context-renderer id="r2"><a href="/watch?v=watched1111">w</a>' +
    '<div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 100%;"></div></ytm-video-with-context-renderer>' +
    rel('r3', 'unseen11111', 'ok', 'Quiet') + '</ytm-app>';
  const env = await setupWatch({ body, cfg: { blockChannels: ['Noisy'], skipWatchedNext: true } });
  assert.equal(env.w.ytl._.firstAllowedLink(VID, true).getAttribute('href'), '/watch?v=unseen11111');
  assert.equal(env.w.ytl._.firstAllowedLink(VID, false).getAttribute('href'), '/watch?v=watched1111');
});

/* ---------- 445: autoplay switch decided again 20 s before the end ---------- */
test('20 s before the end the autoplay switch is set again (screen off: no DOM pass)', async () => {
  const env = await load({ path: WATCH, body: PLAYER_HTML, cfg: { blockChannels: ['Noisy'] } });
  const { w } = env;
  const v = w.document.querySelector('video');
  const tg = w.document.querySelector('.ytm-autonav-toggle-button-container');
  let clicks = 0;
  tg.addEventListener('click', () => { clicks++; tg.setAttribute('aria-pressed', tg.getAttribute('aria-pressed') === 'true' ? 'false' : 'true'); });
  w.ytl.bg(true);
  const st = fakeMedia(v, { duration: 300, currentTime: 10 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'playing');
  assert.equal(tg.getAttribute('aria-pressed'), 'true', 'no related list yet: autoplay on');
  // the related list arrives later, with a blocked target first
  w.document.querySelector('ytm-app').insertAdjacentHTML('beforeend', rel('r1', 'blocked1111', 'x', 'Noisy'));
  st.currentTime = 200; fire(w, v, 'timeupdate');
  assert.equal(tg.getAttribute('aria-pressed'), 'true', 'not yet: more than 20 s left');
  st.currentTime = 285; fire(w, v, 'timeupdate');
  assert.equal(tg.getAttribute('aria-pressed'), 'false', 'switched off near the end');
  assert.equal(w.ytl._.lateNavFor(), VID);
  const before = clicks;
  st.currentTime = 290; fire(w, v, 'timeupdate');
  assert.equal(clicks, before, 'once per video');
  assert.equal(jsErrors(env.calls).length, 0);
});

/* ---------- 446: "Continue watching?" at the end of a video ---------- */
test('a "continue watching?" dialog at the end is answered, without restarting the ended video', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 99.9, paused: true });
  fire(w, v, 'loadedmetadata');
  w.document.body.insertAdjacentHTML('beforeend', '<ytm-popup-container><div role="dialog">Video paused. Continue watching?' +
    '<button id="no">No</button><button id="yes">Yes</button></div></ytm-popup-container>');
  let yes = 0, plays = 0;
  w.document.getElementById('yes').addEventListener('click', () => { yes++; });
  w.HTMLMediaElement.prototype.play = function () { plays++; return Promise.resolve(); };
  st.ended = true;
  fire(w, v, 'ended');
  await sleep(1700);
  assert.equal(yes, 1, 'continue pressed');
  assert.equal(plays, 0, 'the old video is not played again');
});

/* ---------- 447: light refresh falls back to a full one ---------- */
test('two light refreshes with nothing new: the next refresh is a full one; a find resets the count', async () => {
  const env = await load({ path: '/', cfg: { lightRefresh: true } });
  const { w } = env;
  const ls = w.localStorage;
  ls.setItem('ytl_light_last', '1');
  w.ytl._.noteRefreshResult(0);
  w.ytl._.noteRefreshResult(0);
  assert.equal(w.ytl._.lightZeros(), 2);
  let swUnreg = 0;
  Object.defineProperty(w.navigator, 'serviceWorker', { configurable: true, value: { getRegistrations: () => { swUnreg++; return Promise.resolve([]); } } });
  w.ytl.refresh(true);
  await sleep(900);
  assert.equal(swUnreg, 1, 'full refresh clears the service worker');
  assert.equal(ls.getItem('ytl_light_last'), '0');
  w.ytl._.noteRefreshResult(0);
  assert.equal(w.ytl._.lightZeros(), 0, 'a full refresh resets the count');
  ls.setItem('ytl_light_last', '1');
  w.ytl._.noteRefreshResult(3);
  assert.equal(w.ytl._.lightZeros(), 0);
});

test('light refresh off: nothing counted, the refresh stays full', async () => {
  const env = await load({ path: '/' });
  env.w.ytl.refresh(true);
  await sleep(900);
  assert.equal(env.w.localStorage.getItem('ytl_light_last'), '0');
});

/* ---------- 449: parsed block list and hidden id set ---------- */
test('block list parsed once per list; hidden ids looked up in a set that follows changes', async () => {
  const env = await load({ path: '/', cfg: { blockChannels: ['@a|A', 'B'] } });
  const { w } = env;
  const l1 = w.ytl._.blockEntries();
  assert.equal(w.ytl._.blockEntries(), l1, 'same list object while the setting is unchanged');
  w.ytl.cfg({ blockChannels: ['C'] });
  assert.notEqual(w.ytl._.blockEntries(), l1);
  assert.equal(w.ytl._.blockEntries()[0].name, 'c');
  assert.equal(w.ytl._.isHiddenId('zzzzzzzzzz1'), false);
  w.ytl.hideVideo('zzzzzzzzzz1');
  assert.equal(w.ytl._.isHiddenId('zzzzzzzzzz1'), true);
});

/* ---------- 435: assets/ytlite.js is built from src/js ---------- */
test('the page script asset is exactly the src/js modules joined in order', () => {
  const dir = path.join(ROOT, 'src', 'js');
  if (!fs.existsSync(dir)) return;   // before the split
  const files = fs.readdirSync(dir).filter((f) => /^\d\d-[\w-]+\.js$/.test(f)).sort();
  assert.ok(files.length >= 5);
  const joined = files.map((f) => fs.readFileSync(path.join(dir, f), 'utf8')).join('');
  const asset = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'assets', 'ytlite.js'), 'utf8');
  assert.equal(joined, asset, 'run node tools/build-js.js after editing src/js');
});

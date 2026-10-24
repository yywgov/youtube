'use strict';
// v1.3.0 (batch 2): sleep / playback / notification features
const test = require('node:test');
const assert = require('node:assert/strict');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="false"></button></ytm-app>';

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
const jsErrors = (calls) => calls.filter((c) => c[0] === 'log' && c[1] === 'js');

test('no module errors on load (home and watch), defaults keep old behaviour', async () => {
  const home = await load({ path: '/' });
  assert.deepEqual(jsErrors(home.calls), []);
  const env = await setupWatch();
  fakeMedia(env.v, { duration: 600, currentTime: 0 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  fire(env.w, env.v, 'timeupdate');
  assert.deepEqual(jsErrors(env.calls), []);
  assert.equal(env.w.ytl._.ruleMode(), 'rec');
  assert.equal(env.w.ytl._.wantAutonav(), true);
  assert.equal(env.w.ytl._.prefQuality(), 'auto');
});

/* 13: fade length */
test('end-of-video fade follows the fade length setting', async () => {
  const env = await setupWatch({ cfg: { stopAtEnd: true, fadeSec: 10 } });
  const st = fakeMedia(env.v, { duration: 100, currentTime: 80, volume: 1 });
  fire(env.w, env.v, 'timeupdate');
  assert.equal(st.volume, 1, 'no fade 20 s before the end');
  st.currentTime = 95;
  fire(env.w, env.v, 'timeupdate');
  assert.ok(Math.abs(st.volume - (4 / 9) * (4 / 9)) < 0.01, 'fading (squared curve, 298): ' + st.volume);
  st.currentTime = 99.2;
  fire(env.w, env.v, 'timeupdate');
  assert.ok(env.calls.some((c) => c[0] === 'sleepFired'));
});

/* 16: after N videos */
test('"after N videos": each finished video is reported once; last one stops at its end', async () => {
  const env = await setupWatch({ cfg: { countMode: true } });
  const { w, v, calls } = env;
  fakeMedia(v, { duration: 300, currentTime: 300 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'ended');
  fire(w, v, 'ended');
  assert.equal(calls.filter((c) => c[0] === 'videoEnded').length, 1);
  w.ytl.stopAtEndNext();
  assert.equal(w.ytl._.stopAtEnd(), false, 'not for the video that just ended');
  w.history.pushState({}, '', '/watch?v=zyxwvutsrqp');
  fire(w, v, 'loadedmetadata');
  assert.equal(w.ytl._.stopAtEnd(), true, 'armed for the next video');
});

/* 152: loop */
test('single-video loop jumps back before the end; A-B loop stays inside A..B', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 200, currentTime: 10 });
  fire(w, v, 'loadedmetadata');
  w.ytl.loop(true);
  assert.equal(w.ytl._.wantAutonav(), false, 'autoplay off while looping');
  st.currentTime = 199.7;
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 0);
  st.currentTime = 200;
  fire(w, v, 'ended');
  assert.equal(st.currentTime, 0, 'ended also starts over');
  w.ytl.loop(false);
  st.currentTime = 50; w.ytl.ab('a');
  st.currentTime = 60; w.ytl.ab('b');
  assert.equal(JSON.parse(w.ytl.state()).a, 50);
  st.currentTime = 61;
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 50);
  w.ytl.ab('clear');
  st.currentTime = 70;
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 70);
  assert.equal(w.ytl._.wantAutonav(), true);
});

test('loop is cleared when another video starts', async () => {
  const env = await setupWatch();
  const { w, v } = env;
  fakeMedia(v, { duration: 200, currentTime: 10 });
  fire(w, v, 'loadedmetadata');
  w.ytl.loop(true);
  w.history.pushState({}, '', '/watch?v=zyxwvutsrqp');
  fire(w, v, 'loadedmetadata');
  assert.equal(JSON.parse(w.ytl.state()).loop, false);
});

/* 26/151: seek by, speed */
test('seekBy clamps to the video; setRate clamps to 0.25..2 in 0.05 steps', async () => {
  const env = await setupWatch({ cfg: { rememberRate: true } });
  const { w, v, calls } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 5 });
  w.ytl.seekBy(-10); assert.equal(st.currentTime, 0);
  w.ytl.seekBy(30); assert.equal(st.currentTime, 30);
  st.currentTime = 90; w.ytl.seekBy(30); assert.equal(st.currentTime, 99.5);
  w.ytl.setRate(3); assert.equal(st.playbackRate, 2);
  w.ytl.setRate(1.35); assert.equal(st.playbackRate, 1.35);
  w.ytl.setRate(0.1); assert.equal(st.playbackRate, 0.25);
  assert.ok(calls.some((c) => c[0] === 'saveRate' && c[1] === '1.35'));
});

/* 30: remember speed */
test('remembered speed: user change saved, applied to the next video, ad 16x never saved', async () => {
  const env = await setupWatch({ cfg: { rememberRate: true, rate: 1.5 } });
  const { w, v, p, calls } = env;
  const st = fakeMedia(v, { duration: 600, currentTime: 0 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'playing');
  assert.equal(st.playbackRate, 1.5);
  fire(w, v, 'ratechange');
  assert.equal(calls.filter((c) => c[0] === 'saveRate').length, 0, 'own change not saved');
  st.playbackRate = 1.25;
  fire(w, v, 'ratechange');
  assert.ok(calls.some((c) => c[0] === 'saveRate' && c[1] === '1.25'));
  p.classList.add('ad-showing');
  fire(w, v, 'playing');
  fire(w, v, 'ratechange');
  assert.ok(!calls.some((c) => c[0] === 'saveRate' && c[1] === '16'));
});

test('speed is not touched when the setting is off', async () => {
  const env = await setupWatch({ cfg: { rememberRate: false, rate: 1.5 } });
  const st = fakeMedia(env.v, { duration: 600 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  assert.equal(st.playbackRate, 1);
  st.playbackRate = 1.25;
  fire(env.w, env.v, 'ratechange');
  assert.equal(env.calls.filter((c) => c[0] === 'saveRate').length, 0);
});

/* 29: default quality */
test('default quality: mapping, Wi-Fi vs metered, applied once per video, auto untouched', async () => {
  for (const [q, metered, want] of [['auto', false, 'auto'], ['720', false, 'hd720'], ['1080', true, 'hd1080'], ['480', false, 'large'], ['wifiMax', false, 'highres'], ['wifiMax', true, 'hd720']]) {
    const env = await setupWatch({ cfg: { quality: q }, metered });
    assert.equal(env.w.ytl._.prefQuality(), want, q + '/' + metered);
  }
  const env = await setupWatch({ cfg: { quality: '720' } });
  const set = [];
  env.p.setPlaybackQualityRange = (a) => set.push(a);
  fakeMedia(env.v, { duration: 600 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  assert.deepEqual(set, ['hd720']);
  const env2 = await setupWatch({ cfg: { quality: 'auto' } });
  const set2 = [];
  env2.p.setPlaybackQualityRange = (a) => set2.push(a);
  fakeMedia(env2.v, { duration: 600 });
  fire(env2.w, env2.v, 'loadedmetadata');
  assert.deepEqual(set2, []);
  // background still forces the lowest quality, and foreground goes back to the preference
  env.w.ytl.bg(true);
  assert.equal(set[set.length - 1], 'tiny');
  env.w.ytl.bg(false);
  assert.equal(set[set.length - 1], 'hd720');
});

/* 133: keyboard */
test('keyboard: space pauses on the watch page, ignored while typing', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + '<input id=q>' });
  const { w, v } = env;
  const st = fakeMedia(v, { duration: 600, currentTime: 100, paused: false });
  let paused = 0;
  w.HTMLMediaElement.prototype.pause = function () { paused++; };
  w.document.dispatchEvent(new w.KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
  assert.equal(paused, 1);
  w.document.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
  assert.equal(st.currentTime, 105);
  const q = w.document.getElementById('q');
  q.focus();
  q.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'l', bubbles: true, cancelable: true }));
  assert.equal(st.currentTime, 105);
});

/* 36/155: CSS switches */
test('end wall and caption style follow the settings', async () => {
  const env = await load({ path: WATCH, cfg: { noEndWall: true, subSize: 'l', subBg: 'none' } });
  const h = env.w.document.documentElement;
  assert.equal(h.getAttribute('data-ytl-noendwall'), '1');
  assert.equal(h.getAttribute('data-ytl-subsize'), 'l');
  assert.equal(h.getAttribute('data-ytl-subbg'), 'none');
  const css = env.w.document.getElementById('ytl-css').textContent;
  assert.ok(css.includes('html[data-ytl-noendwall] .html5-video-player .ytp-endscreen-content'));
  assert.ok(!/ytp-autonav-endscreen/.test(css), 'autoplay countdown never hidden');
  env.w.ytl.cfg({ noEndWall: false, subSize: 'bogus', subBg: 'default' });
  assert.equal(h.hasAttribute('data-ytl-noendwall'), false);
  assert.equal(h.hasAttribute('data-ytl-subsize'), false);
  assert.equal(h.hasAttribute('data-ytl-subbg'), false);
});

/* 154: badge */
test('sleep badge appears on the player and goes away', async () => {
  const env = await setupWatch();
  const { w, p } = env;
  w.ytl.badge('睡眠 · 25 分');
  const b = w.document.getElementById('ytl-badge');
  assert.ok(b && b.parentNode === p && b.textContent === '睡眠 · 25 分');
  w.ytl.badge('');
  assert.equal(w.document.getElementById('ytl-badge'), null);
  const off = await setupWatch({ cfg: { sleepBadge: false, badge: '睡眠 · 5 分' } });
  off.w.ytl._.domPass();
  assert.equal(off.w.document.getElementById('ytl-badge'), null);
  const reload = await setupWatch({ cfg: { badge: '睡眠 · 5 分' } });
  assert.ok(reload.w.document.getElementById('ytl-badge'), 'restored after a page reload');
});

/* 21/22: what plays next */
const RELATED = '<ytm-video-with-context-renderer id=r1><a href="/watch?v=other111111">x</a><span class="YtmBadgeAndBylineRendererItemByline">Other · 1M views</span></ytm-video-with-context-renderer>' +
  '<ytm-video-with-context-renderer id=r2><a href="/watch?v=same2222222">y</a><span class="YtmBadgeAndBylineRendererItemByline">My Channel · 3K views</span></ytm-video-with-context-renderer>';

test('autoplay toggle: on by default, off with a "same channel" rule or the calm rule while sleeping', async () => {
  const env = await setupWatch();
  const tg = env.w.document.querySelector('.ytm-autonav-toggle-button-container');
  let clicks = 0;
  tg.addEventListener('click', () => { clicks++; tg.setAttribute('aria-pressed', tg.getAttribute('aria-pressed') === 'true' ? 'false' : 'true'); });
  fakeMedia(env.v, { duration: 600 });
  fire(env.w, env.v, 'loadedmetadata');
  env.w.ytl._.domPass();
  assert.equal(tg.getAttribute('aria-pressed'), 'true');
  env.w.ytl.cfg({ sleepCalm: true });
  assert.equal(env.w.ytl._.ruleMode(), 'rec', 'calm rule only while a timer runs');
  env.w.ytl.sleepState(true);
  assert.equal(env.w.ytl._.ruleMode(), 'channel');
  assert.equal(tg.getAttribute('aria-pressed'), 'false');
  env.w.ytl.sleepState(false);
  assert.equal(tg.getAttribute('aria-pressed'), 'true');
  assert.equal(clicks, 3);
});

test('same-channel rule picks the up-next video by the same channel', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + '<ytm-app>' + RELATED + '</ytm-app>', cfg: { nextSource: 'channel' } });
  const { w, v, p } = env;
  p.getVideoData = () => ({ author: 'My Channel', video_id: VID });
  const link = w.ytl._.sameChannelLink(VID);
  assert.ok(link && link.getAttribute('href') === '/watch?v=same2222222');
  let clicked = '';
  w.document.addEventListener('click', (e) => { const a = e.target.closest('a'); if (a) { clicked = a.getAttribute('href'); e.preventDefault(); } });
  fakeMedia(v, { duration: 300, currentTime: 300 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(clicked, '/watch?v=same2222222');
});

test('same-channel rule falls back to the normal next video when there is no match', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + '<ytm-app>' + RELATED + '</ytm-app>', cfg: { nextSource: 'channel' } });
  const { w, v, p } = env;
  p.getVideoData = () => ({ author: 'Nobody Here', video_id: VID });
  let nexts = 0;
  w.navigator.mediaSession.setActionHandler('nexttrack', () => { nexts++; });
  fakeMedia(v, { duration: 300, currentTime: 300 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(nexts, 1);
});

test('playlist rule stops at the end of the list', async () => {
  const env = await setupWatch({ path: WATCH + '&list=PL123', cfg: { nextSource: 'playlist' } });
  const { w, v, p } = env;
  let nexts = 0;
  p.getPlaylist = () => ['a', 'b', 'c'];
  p.getPlaylistIndex = () => 2;
  p.nextVideo = () => { nexts++; };
  w.navigator.mediaSession.setActionHandler('nexttrack', () => { nexts++; });
  fakeMedia(v, { duration: 300, currentTime: 300 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'ended');
  await sleep(800);
  assert.equal(nexts, 0);
});

/* 28/27: positions */
test('signed out: position remembered locally and resumed; signed in or t= untouched', async () => {
  const before = (logged) => (win) => {
    win.ytcfg = { get: (k) => (k === 'LOGGED_IN' ? logged : undefined) };
    win.localStorage.setItem('ytl_pos', JSON.stringify({ [VID]: [200, 1] }));
  };
  const env = await setupWatch({ before: before(false) });
  const st = fakeMedia(env.v, { duration: 600, currentTime: 0 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  assert.equal(st.currentTime, 200);
  // saves the new position every 10 s
  st.currentTime = 321;
  env.tick(11000);
  fire(env.w, env.v, 'timeupdate');
  assert.equal(env.w.ytl._.posStore()[VID][0], 321);
  assert.ok(env.calls.some((c) => c[0] === 'savePos' && c[1] === VID && c[2] === 321));

  const inn = await setupWatch({ before: before(true) });
  const st2 = fakeMedia(inn.v, { duration: 600, currentTime: 0 });
  fire(inn.w, inn.v, 'loadedmetadata');
  fire(inn.w, inn.v, 'playing');
  assert.equal(st2.currentTime, 0);

  const t = await setupWatch({ path: WATCH + '&t=42s', before: before(false) });
  const st3 = fakeMedia(t.v, { duration: 600, currentTime: 0 });
  fire(t.w, t.v, 'loadedmetadata');
  fire(t.w, t.v, 'playing');
  assert.equal(st3.currentTime, 0);
});

/* 32: original audio */
test('original audio track chosen when a video has several audio tracks', async () => {
  const env = await setupWatch();
  const { w, v, p } = env;
  const pr = {
    responseContext: {}, playabilityStatus: { status: 'OK' },
    videoDetails: { videoId: VID, lengthSeconds: '600' },
    streamingData: {
      adaptiveFormats: [
        { itag: 1, audioTrack: { id: 'zh.3', displayName: 'Chinese', audioIsDefault: false } },
        { itag: 2, audioTrack: { id: 'en.4', displayName: 'English original', audioIsDefault: true } }
      ]
    }
  };
  w.JSON.parse(JSON.stringify(pr));
  assert.equal(w.ytl._.origAudio()[VID], 'en.4');
  const mk = (id) => ({ getLanguageInfo: () => ({ getId: () => id }) });
  const tracks = [mk('zh.3'), mk('en.4')];
  let set = null;
  p.getAudioTrack = () => tracks[0];
  p.getAvailableAudioTracks = () => tracks;
  p.setAudioTrack = (t) => { set = t; };
  fakeMedia(v, { duration: 600 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'playing');
  await sleep(900);
  assert.equal(set, tracks[1]);
});

test('single-track videos and the switch off leave audio alone', async () => {
  const env = await setupWatch({ cfg: { origAudio: false } });
  env.w.JSON.parse(JSON.stringify({
    responseContext: {}, playabilityStatus: {}, videoDetails: { videoId: VID, lengthSeconds: '600' },
    streamingData: { adaptiveFormats: [{ audioTrack: { id: 'en.4', displayName: 'English', audioIsDefault: true } }] }
  }));
  assert.equal(env.w.ytl._.origAudio()[VID], undefined);
});

/* 31: captions */
test('captions "off" unloads the captions module once per video', async () => {
  const env = await setupWatch({ cfg: { captions: 'off' } });
  let n = 0;
  env.p.unloadModule = (m) => { if (m === 'captions') n++; };
  fakeMedia(env.v, { duration: 600 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  fire(env.w, env.v, 'playing');
  assert.equal(n, 1);
});

/* tools button */
test('moon button opens the native tools sheet', async () => {
  const env = await load({ path: '/', body: '<ytm-app><div class=hdr><ytm-home-logo></ytm-home-logo></div></ytm-app>' });
  const btn = env.w.document.getElementById('ytl-tools');
  assert.ok(btn && env.w.document.getElementById('ytl-gear'));
  btn.click();
  assert.ok(env.calls.some((c) => c[0] === 'openTools'));
});

test('tool state reports the watch page and playlist', async () => {
  const env = await setupWatch({ path: WATCH + '&list=PL1' });
  fakeMedia(env.v, { duration: 600, playbackRate: 1.5 });
  fire(env.w, env.v, 'loadedmetadata');
  const st = JSON.parse(env.w.ytl.state());
  assert.equal(st.watch, true); assert.equal(st.list, true); assert.equal(st.rate, 1.5); assert.equal(st.id, VID);
});

/* 20: levelling never touches audio while off */
test('volume levelling: no AudioContext unless switched on', async () => {
  let made = 0;
  const env = await setupWatch({ before: (win) => { win.AudioContext = function () { made++; }; } });
  fakeMedia(env.v, { duration: 600 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  assert.equal(made, 0);
});

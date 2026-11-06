'use strict';
// v1.15.0: seamless background playback, removed features leave nothing behind, AMOLED really black,
// lighter start-up. Runs against the built assets/ytlite.js like the other suites.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { load, fakeMedia, fire, sleep, SRC } = require('./helpers');

const ROOT = path.join(__dirname, '..', '..');
const JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'google', 'android', 'youtube', 'pro');
const java = (f) => fs.readFileSync(path.join(JAVA, f), 'utf8');
const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="false"></button></ytm-app>';

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML }, opts || {}));
  env.v = env.w.document.querySelector('video');
  env.p = env.w.document.getElementById('movie_player');
  return env;
}

/* v1.15: leaving the app must not interrupt playback */
test('background / screen off: no quality switch, no pause, no reload, even with the old setting stored', async () => {
  const env = await setupWatch({ cfg: { quality: '1080', lowQualityBg: true } });
  const set = [];
  env.p.setPlaybackQualityRange = (a) => set.push(a);
  env.p.setPlaybackQuality = (a) => set.push('q:' + a);
  let paused = 0, reloads = 0;
  env.v.pause = () => { paused++; };
  env.w.ytl._.nav.replace = () => { reloads++; };
  env.w.ytl._.nav.reload = () => { reloads++; };
  fakeMedia(env.v, { duration: 600, currentTime: 100 });
  fire(env.w, env.v, 'loadedmetadata');
  fire(env.w, env.v, 'playing');
  const before = set.length;
  for (let i = 0; i < 3; i++) {
    env.w.ytl.bg(true);
    fire(env.w, env.v, 'loadedmetadata');   // a reload of metadata while hidden changes nothing either
    fire(env.w, env.v, 'timeupdate');
    env.w.ytl.bg(false);
  }
  assert.equal(set.length, before, 'quality calls while switching: ' + JSON.stringify(set.slice(before)));
  assert.equal(paused, 0);
  assert.equal(reloads, 0);
  assert.equal(env.w.document.hidden, false);
  assert.equal(env.w.document.visibilityState, 'visible');
});

test('the activity never pauses the WebView or its timers when it goes to the background', () => {
  const src = java('MainActivity.java');
  for (const m of ['onStop', 'onPause', 'onUserLeaveHint']) {
    const r = new RegExp('void ' + m + '\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}').exec(src);
    if (!r) continue;
    assert.ok(!/\.onPause\(\)|pauseTimers|setPlaybackQuality|ytl\.pause/.test(r[1]), m + ' must not stop playback: ' + r[1]);
  }
  assert.ok(!/pauseTimers/.test(src), 'no pauseTimers anywhere (the sleep-timer freeze is gone)');
  const wv = fs.readFileSync(path.join(JAVA, 'webview', 'YTProWebView.java'), 'utf8');
  assert.ok(/onWindowVisibilityChanged/.test(wv) && /onVisibilityChanged/.test(wv), 'hidden-state hooks still suppressed');
});

test('removed features leave no code behind (Java + page script)', () => {
  assert.ok(!fs.existsSync(path.join(JAVA, 'SleepTimer.java')), 'SleepTimer gone');
  assert.ok(!fs.existsSync(path.join(JAVA, 'NightLog.java')), 'NightLog gone');
  const all = fs.readdirSync(JAVA).filter((f) => f.endsWith('.java')).map((f) => java(f)).join('\n') +
    fs.readFileSync(path.join(JAVA, 'webview', 'WebAppInterface.java'), 'utf8');
  for (const gone of ['SleepTimer', 'NightLog', 'startSleepMode', 'ACTION_SLEEP', 'ACTION_EXTEND', 'idleGuardRun', 'freezeWeb',
    'maybeNightPrompt', 'offerResume', 'keepAliveSheet', 'openAutostartSettings', 'openBatterySettings', 'askBatteryOnce',
    'requestIgnoreBattery', 'REQ_BACKUP', 'onBackupSaved', 'importSettings', 'confirmDefaults', 'undoSnapshot', 'mirrorOrder',
    'runSmoothTest', 'sleepBadgeTap', 'sleepFired', 'videoEnded']) {
    assert.ok(!all.includes(gone), gone + ' still referenced');
  }
  // settings pages read none of the removed keys
  const settings = java('SettingsPage.java');
  for (const k of ['LOW_QUALITY_BG', 'SLEEP_DEFAULT', 'SLEEP_FADE', 'SLEEP_REMEMBER', 'SLEEP_BADGE', 'NIGHT_PROMPT', 'NIGHT_START', 'NIGHT_END',
    'SLEEP_CALM', 'SLEEP_STRICT', 'IDLE_GUARD', 'EXTEND_STEP', 'SLEEP_HOLD', 'SLEEP_MODE_BLACK', 'ORIG_AUDIO', 'UPDATE_MIRROR', 'RESUME_PROMPT']) {
    assert.ok(!new RegExp('Prefs\\.' + k + '\\b').test(settings), k + ' still has a settings row');
  }
  for (const t of ['睡眠', '定时', '备份', '恢复默认', '保活', '自启动', '镜像', '原声', '重开时提示']) {
    assert.ok(!new RegExp('"[^"\\n]*' + t + '[^"\\n]*"').test(settings), 'settings text still says ' + t);
  }
  // the page script neither calls nor exposes them
  for (const gone of ['sleepFired', 'videoEnded', 'sleepBadgeTap', 'sleepBadgeLong', 'stopAtEnd', 'fadeStart', 'sleepMode', 'sleepState',
    'countMode', 'importLocal', 'localData', 'selfScroll', 'lowQualityBg', 'ytl-badge']) {
    assert.ok(!SRC.includes(gone), 'script still has ' + gone);
  }
  const shortcuts = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'res', 'xml', 'shortcuts.xml'), 'utf8');
  assert.ok(!/SLEEP_MODE|sleep/.test(shortcuts), 'no sleep launcher shortcut');
});

test('a freshly loaded page gets no sleep state and the bridge has no sleep methods', async () => {
  const env = await setupWatch({ cfg: { stopAtEnd: true, sleeping: true, countMode: true, badge: '睡眠 · 5 分', sleepMode: true, fadeSec: 10 } });
  const { w, v, calls } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 99.5 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'playing');
  fire(w, v, 'timeupdate');
  st.ended = true;
  fire(w, v, 'ended');
  await sleep(50);
  assert.ok(!calls.some((c) => /sleep|videoEnded/i.test(c[0])), JSON.stringify(calls.filter((c) => /sleep/i.test(c[0]))));
  assert.equal(w.document.getElementById('ytl-badge'), null);
  assert.equal(v.volume, 1, 'no fade');
  for (const k of ['sleepState', 'countMode', 'badge', 'sleepMode', 'stopAtEnd', 'stopAtEndNext', 'fadeStart', 'sleepPause', 'localData', 'importLocal']) {
    assert.equal(typeof w.ytl[k], 'undefined', k);
  }
});

test('original audio is always preferred, even with an old "off" value stored', async () => {
  const env = await setupWatch({ cfg: { origAudio: false } });
  const { w, v, p } = env;
  w.JSON.parse(JSON.stringify({
    responseContext: {}, playabilityStatus: { status: 'OK' }, videoDetails: { videoId: VID, lengthSeconds: '600' },
    streamingData: { adaptiveFormats: [
      { itag: 1, audioTrack: { id: 'zh.3', displayName: 'Chinese', audioIsDefault: false } },
      { itag: 2, audioTrack: { id: 'en.4', displayName: 'English original', audioIsDefault: true } }] }
  }));
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

test('updates and remote rules only talk to GitHub; retries and the releases/latest fallback stay', () => {
  const up = java('Updater.java'), rr = java('RemoteRules.java');
  for (const src of [up, rr]) assert.ok(!/ghfast|gh-proxy|ghproxy|MIRROR/.test(src), 'no mirror left');
  assert.ok(/int tries = 3;/.test(up), 'each source is still tried 3 times');
  assert.ok(/releases\/latest/.test(up), 'releases/latest fallback kept');
  assert.ok(/raw\.githubusercontent\.com/.test(rr) && /api\.github\.com/.test(rr));
});

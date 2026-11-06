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

/* ---------- I: pure black ---------- */
// what today's m.youtube serves: hashed variable names in one :root block (dark values last), html painted
// through them, a top bar with a fixed colour, and no html[dark] attribute
const YT_CSS = ':root{--t3e41d7b17b187f69:#fff;--t518e925f61bdcb91:#f2f2f2;--tffc2fd3a644f6275:#0f0f0f}' +
  ':root{--t3e41d7b17b187f69:#0f0f0f;--t518e925f61bdcb91:#212121;--t08a7c6c176cbc5c2:#282828;--te1b64e6971040396:#272727;' +
  '--tffc2fd3a644f6275:#f1f1f1;--tf3fc855af2285f5f:rgba(255,255,255,0.2);--t3b8e1c2a9f0d1e2f:#3ea6ff}' +
  'html{color:var(--tffc2fd3a644f6275);background-color:var(--t3e41d7b17b187f69)}' +
  '.mobile-topbar-header-background{background-color:#0f0f0f;position:fixed}' +
  '.mobile-topbar-header[data-mode=searching]{z-index:4;background-color:#212121}' +
  '.thumb-overlay{background-color:rgba(0,0,0,.6)}';

function ytPage(css, htmlStyle) {
  return {
    path: '/', body: '<ytm-app><div class="mobile-topbar-header-background"></div></ytm-app>',
    before: (w) => {
      const st = w.document.createElement('style');
      st.textContent = css;
      w.document.head.appendChild(st);
      if (htmlStyle) w.document.documentElement.setAttribute('style', htmlStyle);
    }
  };
}

test('pure black is on by default, set at document start, and does not depend on html[dark]', async () => {
  const env = await load(ytPage(YT_CSS, 'color: rgb(241, 241, 241)'));
  const h = env.w.document.documentElement;
  assert.ok(h.hasAttribute('data-ytl-amoled'));
  assert.ok(h.hasAttribute('data-ytl-dark'));
  assert.ok(!h.hasAttribute('dark'), 'YouTube never set [dark] here');
  const css = env.w.document.getElementById('ytl-css').textContent;
  assert.ok(!/data-ytl-amoled\]\[dark\]/.test(css), 'no rule waits for html[dark] any more');
  for (const sel of ['ytm-pivot-bar-renderer', '.mobile-topbar-header-background', 'ytm-engagement-panel', 'ytm-search', 'body', 'ytm-app']) {
    assert.ok(css.includes('html[data-ytl-amoled][data-ytl-dark] ' + sel), sel);
  }
  const root = /html\[data-ytl-amoled\]\[data-ytl-dark\]\{(--yt-spec-[^}]*)\}/.exec(css)[1];
  for (const v of ['base-background', 'raised-background', 'menu-background', 'general-background-a', 'general-background-b', 'general-background-c']) {
    assert.ok(root.includes('--yt-spec-' + v + ':#000!important'), v);
  }
  assert.ok(!/#121212/.test(css), 'menus / sheets are black too, not #121212');
  assert.deepEqual(env.calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]).slice(-1), ['#000000']);
});

test('the hashed colour variables and fixed dark backgrounds are found and turned black', async () => {
  const env = await load(ytPage(YT_CSS, 'color: rgb(241, 241, 241)'));
  const { w } = env;
  w.ytl._.domPass(true);
  const vars = w.ytl._.amoledVars();
  for (const n of ['--t3e41d7b17b187f69', '--t518e925f61bdcb91', '--t08a7c6c176cbc5c2', '--te1b64e6971040396']) assert.ok(vars.includes(n), n);
  for (const n of ['--tffc2fd3a644f6275', '--tf3fc855af2285f5f', '--t3b8e1c2a9f0d1e2f']) assert.ok(!vars.includes(n), n + ' is text / overlay / accent');
  const sels = w.ytl._.amoledSels();
  assert.ok(sels.includes('.mobile-topbar-header-background'));
  assert.ok(sels.some((s) => s.indexOf('.mobile-topbar-header[data-mode') === 0));
  assert.ok(!sels.includes('.thumb-overlay'), 'translucent overlays stay');
  const out = w.document.getElementById('ytl-css-amoled').textContent;
  assert.ok(out.includes('html[data-ytl-amoled][data-ytl-dark]{--t3e41d7b17b187f69:#000!important'));
  assert.ok(out.includes('html[data-ytl-amoled][data-ytl-dark] .mobile-topbar-header-background{background-color:#000!important}'));
  // once per stylesheet: another pass reads nothing again
  const before = out;
  w.ytl._.domPass(true);
  w.ytl._.amoledScan();
  assert.equal(w.document.getElementById('ytl-css-amoled').textContent, before);
  // a stylesheet YouTube loads later (watch page module) is picked up
  const late = w.document.createElement('style');
  late.textContent = ':root{--t9999aaaa0000bbbb:#181818}.ytm-sheet-x{background-color:#212121}';
  w.document.head.appendChild(late);
  w.ytl._.domPass(true);
  assert.ok(w.ytl._.amoledVars().includes('--t9999aaaa0000bbbb'));
  assert.ok(w.ytl._.amoledSels().includes('.ytm-sheet-x'));
  assert.equal(w.ytl._.normColor('rgb(15, 15, 15)'), '#0f0f0f');
  assert.equal(w.ytl._.normColor(' #212121 '), '#212121');
});

test('light theme: the early dark guess is taken back; switching pure black off removes everything', async () => {
  const LIGHT = ':root{--a1:#fff;--tx:#0f0f0f}html{color:var(--tx);background-color:var(--a1)}';
  const env = await load(ytPage(LIGHT, 'color: rgb(15, 15, 15)'));
  const h = env.w.document.documentElement;
  env.w.ytl._.reportTheme(true);
  assert.ok(!h.hasAttribute('data-ytl-dark'), 'light page: no black');
  assert.equal(env.calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]).slice(-1)[0], '#ffffff');
  const dark = await load(ytPage(YT_CSS, 'color: rgb(241, 241, 241)'));
  dark.w.ytl.cfg({ amoled: false });
  assert.ok(!dark.w.document.documentElement.hasAttribute('data-ytl-amoled'));
  assert.equal(dark.calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]).slice(-1)[0], '#0f0f0f');
  // the browser's default black text before YouTube's CSS is in says nothing about the theme
  const early = await load(ytPage('', 'color: rgb(0, 0, 0)'));
  early.w.ytl._.reportTheme(true);
  assert.ok(early.w.document.documentElement.hasAttribute('data-ytl-dark'), 'guess kept until the page answers');
});

test('dark guess from the PREF cookie (f6 bits), dark when nothing says otherwise', async () => {
  const env = await load({ path: '/' });
  const { w } = env;
  assert.equal(w.ytl._.darkHint(), true);
  w.document.cookie = 'PREF=f6=80000&tz=Asia.Shanghai';
  assert.equal(w.ytl._.darkHint(), false);
  w.document.cookie = 'PREF=f6=400&tz=Asia.Shanghai';
  assert.equal(w.ytl._.darkHint(), true);
  w.document.cookie = 'PREF=f6=40000400';
  assert.equal(w.ytl._.darkHint(), true);
});

test('native side: black window, bars, WebView, sheets and settings with AMOLED, which is on by default', () => {
  const ui = java('Ui.java'), main = java('MainActivity.java'), settings = java('SettingsPage.java'), model = java('PrefsModel.java');
  assert.ok(/public static int bg\(Context c\) \{ return amoled\(c\) \? BLACK : BG; \}/.test(ui));
  assert.ok(/bg\.setColor\(sheetBg\(a\)\)/.test(ui), 'bottom sheets');
  assert.ok(/web\.setBackgroundColor\(Ui\.bg\(this\)\)/.test(main), 'WebView');
  assert.ok(/applyStartColors\(\);/.test(main), 'window + bars before the page reports');
  assert.ok(/setProgressBackgroundColorSchemeColor\(Ui\.sheetBg\(this\)\)/.test(main), 'pull-to-refresh');
  assert.ok(!/Ui\.BG\b/.test(settings) && /stack\.setBackgroundColor\(Ui\.bg\(a\)\)/.test(settings), 'settings pages');
  const falseBlock = /public static boolean defBool[\s\S]*?return false;/.exec(model)[0];
  assert.ok(!/Prefs\.AMOLED/.test(falseBlock), 'AMOLED defaults to on');
  const colors = fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'colors.xml'), 'utf8');
  assert.ok(/name="background">#000000</.test(colors), 'splash / window background');
});

/* ---------- J: lighter start ---------- */
test('SponsorBlock is asked 1.5 s after a video starts, once, and not for a video already left', async () => {
  const env = await setupWatch();
  const { w, v, calls } = env;
  fakeMedia(v, { duration: 600 });
  fire(w, v, 'loadedmetadata');
  fire(w, v, 'playing');
  assert.equal(calls.filter((c) => c[0] === 'fetchSegments').length, 0, 'not together with the video\'s own requests');
  await sleep(1700);
  assert.deepEqual(calls.filter((c) => c[0] === 'fetchSegments').map((c) => c[1]), [VID]);
  const off = await setupWatch({ cfg: { sponsorBlock: false } });
  fakeMedia(off.v, { duration: 600 });
  fire(off.w, off.v, 'loadedmetadata');
  await sleep(1700);
  assert.equal(off.calls.filter((c) => c[0] === 'fetchSegments').length, 0);
});

test('start-up: network jobs wait for the page and for buffering; nothing heavy right at onCreate', () => {
  const main = java('MainActivity.java');
  const q = /private void runStartupQueue\(\) \{([\s\S]*?)\n    \}/.exec(main)[1];
  assert.ok(/later\(\(\) -> \{ Updater\.cleanup\(this\); Updater\.autoCheck\(this\); \}, \d{4,}L, true\)/.test(q), 'update check later, as a network job');
  assert.ok(/later\(\(\) -> RemoteRules\.autoCheck\(this\), \d{4,}L, true\)/.test(q), 'remote rules later, as a network job');
  assert.ok(/pageProgress < 100 \|\| PlaybackService\.isBuffering\(\)/.test(main));
  const onCreate = /protected void onCreate\(Bundle savedInstanceState\) \{([\s\S]*?)\n    \}/.exec(main)[1];
  assert.ok(!/Updater\.|RemoteRules\.|clearCache/.test(onCreate), 'no update / rules / cache work in onCreate');
});

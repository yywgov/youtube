'use strict';
// v1.6.0 (batch 4): settings merge + "sleeps through the night"
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const VID = 'abcdefghijk';
const WATCH = '/watch?v=' + VID;
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="false"></button></ytm-app>';
const jsErrors = (calls) => calls.filter((c) => c[0] === 'log' && c[1] === 'js');

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML }, opts || {}));
  env.v = env.w.document.querySelector('video');
  env.p = env.w.document.getElementById('movie_player');
  return env;
}

/* 298: fade curve */
test('sleep fade is (left / fade)²: audible from the start, no cliff at the end', async () => {
  const { w } = await load({ path: '/' });
  const f = w.ytl._.fadeVolume;
  assert.equal(f(60, 60), 1);
  assert.equal(f(90, 60), 1);
  assert.equal(f(0, 60), 0);
  assert.ok(Math.abs(f(30, 60) - 0.25) < 1e-9, 'half way already at a quarter');
  assert.ok(f(54, 60) < 0.85, 'first 10 % of the fade is clearly quieter');
  let prev = 1;
  for (let s = 59; s >= 0; s--) { const x = f(s, 60); assert.ok(x <= prev); prev = x; }
});

/* 312: loop + "after N videos" */
test('single-video loop: every repeat counts for an "after N videos" timer, last one then stops', async () => {
  const env = await setupWatch({ cfg: { countMode: true } });
  const { w, v, calls } = env;
  const st = fakeMedia(v, { duration: 100, currentTime: 10 });
  fire(w, v, 'loadedmetadata');
  w.ytl.loop(true);
  st.currentTime = 99.7;
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 0, 'looped');
  const ended = calls.filter((c) => c[0] === 'videoEnded');
  assert.equal(ended.length, 1);
  assert.ok(/#1$/.test(ended[0][1]), 'each round has its own id: ' + ended[0][1]);
  // Java: last one reached -> stopAtEndNext; inside a loop the repeat now playing is the last
  w.ytl.countMode(false);
  w.ytl.stopAtEndNext();
  assert.equal(w.ytl._.stopAtEnd(), true);
  st.currentTime = 99.7;
  fire(w, v, 'timeupdate');
  assert.notEqual(st.currentTime, 0, 'no more looping once the timer wants to stop');
  assert.ok(calls.some((c) => c[0] === 'sleepFired'), 'paused at the end');
});

test('A-B loop rounds count too; without a count timer nothing is reported', async () => {
  const env = await setupWatch({ cfg: { countMode: true } });
  const { w, v, calls } = env;
  const st = fakeMedia(v, { duration: 300, currentTime: 20 });
  fire(w, v, 'loadedmetadata');
  w.ytl.ab('a');
  st.currentTime = 40;
  w.ytl.ab('b');
  st.currentTime = 40.5;
  fire(w, v, 'timeupdate');
  assert.equal(st.currentTime, 20);
  assert.equal(calls.filter((c) => c[0] === 'videoEnded').length, 1);
  const plain = await setupWatch();
  const st2 = fakeMedia(plain.v, { duration: 100, currentTime: 99.8 });
  fire(plain.w, plain.v, 'loadedmetadata');
  plain.w.ytl.loop(true);
  fire(plain.w, plain.v, 'timeupdate');
  assert.equal(st2.currentTime, 0);
  assert.equal(plain.calls.filter((c) => c[0] === 'videoEnded').length, 0);
});

/* 248: "still watching" */
test('"still watching" dialog: the continue button is found by its text, not by position', async () => {
  const dlg = '<ytm-confirm-dialog-renderer><div>Video paused. Continue watching?</div>' +
    '<button id=yes>是</button><button id=no>否</button></ytm-confirm-dialog-renderer>';
  const env = await setupWatch({ body: PLAYER_HTML + dlg });
  let clicked = '';
  env.w.document.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { clicked = b.id || clicked; }));
  env.w.ytl._.dismissStillWatching();
  assert.equal(clicked, 'yes');
  assert.ok(env.calls.some((c) => c[0] === 'log' && c[1] === 'still' && /text/.test(c[2])));
});

test('"still watching" fallback never presses a "cancel" button', async () => {
  const dlg = '<ytm-confirm-dialog-renderer><div>还在看吗？</div>' +
    '<button id=ok>好的，我在</button><button id=cancel>取消</button></ytm-confirm-dialog-renderer>';
  const env = await setupWatch({ body: PLAYER_HTML + dlg });
  let clicked = '';
  env.w.document.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { clicked = b.id; }));
  env.w.ytl._.dismissStillWatching();
  assert.equal(clicked, 'ok');
});

/* 257: theme from html[dark] */
test('page theme comes from html[dark]; no getComputedStyle polling once it is known', async () => {
  const env = await load({ path: '/', body: '<ytm-app></ytm-app>' });
  const { w, calls } = env;
  let gcs = 0;
  const orig = w.getComputedStyle;
  w.getComputedStyle = function () { gcs++; return orig.apply(this, arguments); };
  w.document.documentElement.setAttribute('dark', 'true');
  await sleep(20);
  assert.deepEqual(calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]).slice(-1), ['#0f0f0f']);
  const before = gcs;
  for (let i = 0; i < 5; i++) { w.ytl._.reportTheme(false); w.ytl._.domPass(); }
  assert.equal(gcs - before, 0, 'no style reads while the theme is known');
  // switched to light: attribute removed -> one read of the body colour
  w.document.body.style.backgroundColor = 'rgb(255, 255, 255)';
  w.document.documentElement.removeAttribute('dark');
  await sleep(400);
  assert.equal(calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]).slice(-1)[0], '#ffffff');
  assert.equal(jsErrors(calls).length, 0);
});

/* 310: same channel by link */
const RELATED_LINKS =
  '<ytm-video-with-context-renderer id=r1><a href="/watch?v=diff1111111">x</a><a href="/@otherone">o</a>' +
  '<span class="YtmBadgeAndBylineRendererItemByline">My Channel · 1M views</span></ytm-video-with-context-renderer>' +
  '<ytm-video-with-context-renderer id=r2><a href="/watch?v=same2222222">y</a><a href="/channel/UCabcdefghijklmnopqrstuv">c</a>' +
  '<span class="YtmBadgeAndBylineRendererItemByline">我的频道 · 3K views</span></ytm-video-with-context-renderer>';

test('same channel: matched by channel id from the player response, not by byline text', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + '<ytm-app>' + RELATED_LINKS + '</ytm-app>', cfg: { nextSource: 'channel' } });
  const { w, p } = env;
  w.ytInitialPlayerResponse = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: VID, channelId: 'UCabcdefghijklmnopqrstuv', lengthSeconds: '300' } };
  p.getVideoData = () => ({ author: 'My Channel', video_id: VID });
  const link = w.ytl._.sameChannelLink(VID);
  assert.ok(link, 'found');
  assert.equal(link.getAttribute('href'), '/watch?v=same2222222', 'byline text "My Channel" on another channel is not taken');
});

test('same channel: @handle from the microformat; byline text still works when cards have no links', async () => {
  const env = await setupWatch({ body: PLAYER_HTML + '<ytm-app>' + RELATED_LINKS + '</ytm-app>' });
  const { w, p } = env;
  w.ytInitialPlayerResponse = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: VID, lengthSeconds: '300' },
    microformat: { playerMicroformatRenderer: { ownerProfileUrl: 'http://www.youtube.com/@OtherOne' } } };
  p.getVideoData = () => ({ author: 'Someone', video_id: VID });
  assert.equal(w.ytl._.sameChannelLink(VID).getAttribute('href'), '/watch?v=diff1111111');
  const plain = await setupWatch({ body: PLAYER_HTML + '<ytm-app><ytm-video-with-context-renderer><a href="/watch?v=same2222222">y</a>' +
    '<span class="YtmBadgeAndBylineRendererItemByline">My Channel · 3K views</span></ytm-video-with-context-renderer></ytm-app>' });
  plain.p.getVideoData = () => ({ author: 'My Channel', video_id: VID });
  assert.equal(plain.w.ytl._.sameChannelLink(VID).getAttribute('href'), '/watch?v=same2222222');
});

/* 317 / 318: refresh while playing, refresh offline */
test('refresh while a video plays asks first; "refresh anyway" reloads', async () => {
  const env = await load({ path: '/', body: PLAYER_HTML });
  const v = env.w.document.querySelector('video');
  fakeMedia(v, { paused: false, duration: 300, currentTime: 20 });
  env.w.ytl.refresh();
  await sleep(900);
  assert.ok(env.calls.some((c) => c[0] === 'confirmRefresh'));
  assert.ok(!env.calls.some((c) => c[0] === 'hardReload'));
  env.w.ytl.refresh(true);
  await sleep(900);
  assert.ok(env.calls.some((c) => c[0] === 'hardReload'));
});

test('refresh offline keeps the list: nothing cleared, no reload', async () => {
  const env = await load({ path: '/', online: false });
  env.w.sessionStorage.setItem('s', '1');
  env.w.ytl.refresh();
  await sleep(900);
  assert.ok(!env.calls.some((c) => c[0] === 'hardReload'));
  assert.equal(env.w.sessionStorage.getItem('s'), '1');
  assert.ok(env.calls.some((c) => c[0] === 'toast' && /离线/.test(c[1])));
});

/* 319: Home tab */
const PIVOT = '<ytm-pivot-bar-renderer><ytm-pivot-bar-item-renderer id=home><span>首页</span></ytm-pivot-bar-item-renderer>' +
  '<ytm-pivot-bar-item-renderer id=subs><span>订阅</span></ytm-pivot-bar-item-renderer></ytm-pivot-bar-renderer>';

test('Home tab: scrolled down only goes back to the top; at the top it refreshes', async () => {
  const env = await load({ path: '/', body: '<ytm-app>' + PIVOT + '</ytm-app>' });
  const { w, calls } = env;
  let scrolledTo = null;
  w.scrollTo = (x) => { scrolledTo = x; };
  Object.defineProperty(w, 'scrollY', { configurable: true, get: () => 800 });
  w.document.getElementById('home').click();
  await sleep(900);
  assert.ok(scrolledTo && scrolledTo.top === 0, 'back to top');
  assert.ok(!calls.some((c) => c[0] === 'hardReload'), 'no refresh while scrolled down');
  Object.defineProperty(w, 'scrollY', { configurable: true, get: () => 0 });
  w.document.getElementById('home').click();
  await sleep(900);
  assert.ok(calls.some((c) => c[0] === 'hardReload'), 'refresh at the top');
});

/* 343: sign-in */
test('signing in or switching account tells Java to flush cookies; the first look does not', async () => {
  const env = await load({ path: '/' });
  const { w, calls } = env;
  let now = Date.now() + 10000;
  w.Date.now = () => now;
  const state = { LOGGED_IN: false };
  w.ytcfg = { get: (k) => state[k] };
  w.ytl._.checkLogin();
  assert.equal(calls.filter((c) => c[0] === 'loginChanged').length, 0);
  state.LOGGED_IN = true; state.DATASYNC_ID = 'a||';
  now += 4000; w.ytl._.checkLogin();
  state.DATASYNC_ID = 'b||';
  now += 4000; w.ytl._.checkLogin();
  now += 4000; w.ytl._.checkLogin();
  assert.deepEqual(calls.filter((c) => c[0] === 'loginChanged').map((c) => c[1]), [true, true]);
});

/* 235 */
test('hidden video count for the settings page', async () => {
  const env = await load({ path: '/' });
  assert.equal(env.w.ytl.hiddenCount(), 0);
  env.w.ytl.hideVideo('aaaaaaaaaa1');
  env.w.ytl.hideVideo('aaaaaaaaaa2');
  assert.equal(env.w.ytl.hiddenCount(), 2);
  env.w.ytl.unhideAll();
  assert.equal(env.w.ytl.hiddenCount(), 0);
});

/* ---------- 418: settings key contract (Java <-> JS <-> backup whitelist) ---------- */
const JAVA = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'com', 'google', 'android', 'youtube', 'pro');
const prefsSrc = fs.readFileSync(path.join(JAVA, 'Prefs.java'), 'utf8');
const modelSrc = fs.readFileSync(path.join(JAVA, 'PrefsModel.java'), 'utf8');
const jsSrc = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'ytlite.js'), 'utf8');

const consts = {};
prefsSrc.replace(/public static final String (\w+) = "([^"]*)";/g, (m, n, v) => { consts[n] = v; return m; });
function listOf(name) {
  const m = new RegExp(name + ' = \\{([^}]*)\\}').exec(modelSrc);
  assert.ok(m, name);
  return m[1].split(',').map((s) => s.trim()).filter(Boolean).map((s) => {
    const k = s.replace(/^Prefs\./, '');
    assert.ok(consts[k] !== undefined, 'unknown constant ' + s);
    return consts[k];
  });
}
const BOOL = listOf('BOOL_KEYS'), INT = listOf('INT_KEYS'), STR = listOf('STR_KEYS');
const STATE = ['nightShown', 'lastVideo', 'lastPos', 'lastTitle', 'lastAt', 'updateReady', 'lastRunVersion', 'notesFor', 'notes',
  'watchStats', 'askedBattery', 'askedNotif', 'lastUpdateCheck', 'skipVersion', 'pendingApk', 'sleepState', 'fgsDeniedAt',
  // batch 5 (v1.7.0)
  'selfCheckWarned', 'updateLastResult', 'chUaFix',
  // batch 6 (v1.8.0)
  'sleepBattery', 'blockStats', 'mirrorOk'];
// keys added after v1.5.1 (new names only; never a rename of an old one)
const ADDED = {
  bool: ['wifiSaver', 'idleGuard', 'skipWatchedNext', 'fontLarge', 'orientRemember',
    // batch 6 (v1.8.0)
    'hideWatchedRelated', 'hideSearchMix', 'lightRefresh', 'sleepPauseHold', 'sleepModeBlack', 'listEndNext', 'sleepStrict',
    'preRaster', 'landscapeLayout'],
  int: ['nightStart', 'nightEnd', 'orientLock', 'extendMin'],
  str: []
};

test('contract: the 51 v1.5.1 setting keys are unchanged, new ones only added, each in exactly one backup list', async () => {
  const all = BOOL.concat(INT, STR);
  assert.equal(BOOL.length, 35 + ADDED.bool.length);
  assert.equal(INT.length, 5 + ADDED.int.length);
  assert.equal(STR.length, 11 + ADDED.str.length);
  for (const k of ADDED.bool) assert.ok(BOOL.includes(k), k);
  for (const k of ADDED.int) assert.ok(INT.includes(k), k);
  assert.equal(new Set(all).size, all.length, 'no duplicates');
  // the exact names shipped up to v1.5.1 (renaming any would lose the user's value on upgrade)
  const V151 = ['sponsorBlock', 'lowQualityBg', 'hideWatched', 'perfCss', 'sleepRemember', 'sleepBadge', 'nightPrompt', 'sleepCalm',
    'notifSeek', 'headsetMulti', 'resumePrompt', 'resumeLocal', 'rememberRate', 'origAudio', 'noEndWall', 'normalize', 'hideLive',
    'hideShelves', 'subsOnlyVideos', 'refreshCount', 'hideSearchExtras', 'remoteRules', 'updateMirror', 'amoled', 'fontFollow',
    'compact', 'cardStyle', 'dimThumbs', 'longPress', 'searchFocus', 'searchChips', 'keepScroll', 'haptic', 'powerSaveAuto',
    'notifWatchLater', 'sleepDefault', 'sleepFade', 'seenMin', 'minDur', 'maxAgeDays', 'sponsorCats', 'nextSource', 'quality',
    'rate', 'captions', 'subSize', 'subBg', 'blockChannels', 'blockWords', 'startPage', 'accent'];
  const added = ADDED.bool.concat(ADDED.int, ADDED.str);
  assert.deepEqual([...all].filter((k) => !added.includes(k)).sort(), [...V151].sort());
  for (const k of added) assert.ok(!V151.includes(k), k + ' is new');
  // every constant in Prefs is either a setting in a backup list or a known state key
  for (const [n, v] of Object.entries(consts)) {
    if (n === 'FILE') continue;
    assert.ok(all.includes(v) || STATE.includes(v), 'constant ' + n + '="' + v + '" is neither backed up nor declared state');
  }
  for (const s of STATE.concat(['prefsSchema', 'lastSnapshot', 'compact_choice'])) assert.ok(!all.includes(s), s + ' must not be backed up');
});

test('contract: jsConfig keys == the script\'s cfg defaults, and defaults agree with Java', async () => {
  const body = /public static String jsConfig\(Context c\) \{([\s\S]*?)return o\.toString\(\);/.exec(prefsSrc)[1];
  const javaKeys = [...body.matchAll(/o\.put\("(\w+)",\s*([^;]+)\);/g)].map((m) => ({ key: m[1], expr: m[2] }));
  const cfgBlock = /var cfg = \{([\s\S]*?)\n  \};/.exec(jsSrc)[1].replace(/\/\/[^\n]*/g, '');
  const jsKeys = [...cfgBlock.matchAll(/(\w+):\s*([^,\n]+)/g)].map((m) => ({ key: m[1], val: m[2].trim() }));
  assert.deepEqual(javaKeys.map((k) => k.key).sort(), jsKeys.map((k) => k.key).sort());
  // boolean defaults: Java on(c, KEY) uses PrefsModel.defBool, the script must start from the same value
  const falseCases = /public static boolean defBool[\s\S]*?\{([\s\S]*?)return false;/.exec(modelSrc)[1];
  const defFalse = [...falseCases.matchAll(/case Prefs\.(\w+):/g)].map((m) => consts[m[1]]);
  for (const k of javaKeys) {
    const m = /^on\(c, (\w+)\)$/.exec(k.expr.trim());
    if (!m) continue;
    const js = jsKeys.find((x) => x.key === k.key);
    const javaDef = !defFalse.includes(consts[m[1]]);
    assert.equal(js.val, String(javaDef), 'default of ' + k.key);
  }
});

test('contract: backup v2 keeps app "YiWei"; SponsorBlock categories agree', async () => {
  assert.ok(/o\.put\("app", "YiWei"\)/.test(prefsSrc));
  assert.ok(/o\.put\("v", 2\)/.test(prefsSrc));
  assert.ok(/"YiWei"\.equals\(app\) \|\| "YT Lite"\.equals\(app\)/.test(modelSrc), 'old backups (YT Lite) still import');
  const cats = /SB_CATS = \{([^}]*)\}/.exec(modelSrc)[1].match(/"(\w+)"/g).map((s) => s.slice(1, -1));
  const jsLabels = /var SB_LABEL = \{([\s\S]*?)\};/.exec(jsSrc)[1].match(/(\w+):/g).map((s) => s.slice(0, -1));
  assert.deepEqual(cats.sort(), jsLabels.sort());
});

test('rules.json matches its sha256 file and schema (423)', async () => {
  const dir = path.join(__dirname, '..', '..', 'rules');
  const raw = fs.readFileSync(path.join(dir, 'rules.json'));
  const sha = require('crypto').createHash('sha256').update(raw).digest('hex');
  assert.equal(fs.readFileSync(path.join(dir, 'rules.json.sha256'), 'utf8').trim(), sha);
  const r = JSON.parse(raw.toString('utf8'));
  assert.equal(r.schema, 1);
  assert.ok(Number.isInteger(r.version) && r.version >= 1);
  assert.ok(Array.isArray(r.hide) && r.hide.every((s) => typeof s === 'string' && s.length > 0 && s.length < 200));
});

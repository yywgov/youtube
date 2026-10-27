'use strict';
// v1.10.0 (iteration round 2): personal-use trim; removed features stay removed, merged rows keep their keys
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { load, fakeMedia, fire } = require('./helpers');

const WATCH = '/watch?v=abcdefghijk';
const PLAYER_HTML = '<ytm-app><div id="movie_player" class="html5-video-player"><video class="video-stream"></video></div>' +
  '<button class="ytm-autonav-toggle-button-container" aria-pressed="false"></button></ytm-app>';
const ROOT = path.join(__dirname, '..', '..');
const JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'google', 'android', 'youtube', 'pro');
const read = (f) => fs.readFileSync(path.join(JAVA, f), 'utf8');

async function setupWatch(opts) {
  const env = await load(Object.assign({ path: WATCH, body: PLAYER_HTML + '<input id=q>' }, opts || {}));
  env.v = env.w.document.querySelector('video');
  return env;
}

/* 450 / 451: A-B loop and playlist shuffle are gone from the page API and the tools state */
test('A-B loop and shuffle are no longer offered; the single-video loop still works', async () => {
  const { w, v } = await setupWatch();
  assert.equal(typeof w.ytl.ab, 'undefined');
  assert.equal(typeof w.ytl.shuffle, 'undefined');
  const st = JSON.parse(w.ytl.state());
  assert.ok(!('a' in st) && !('b' in st) && !('shuffle' in st), JSON.stringify(st));
  const m = fakeMedia(v, { duration: 100, currentTime: 99.8 });
  fire(w, v, 'loadedmetadata');
  w.ytl.loop(true);
  assert.equal(JSON.parse(w.ytl.state()).loop, true);
  fire(w, v, 'timeupdate');
  assert.equal(m.currentTime, 0);
  w.ytl.loop(false);
  assert.equal(w.ytl._.wantAutonav(), true);
});

/* 453: keyboard keys */
test('keyboard keys no longer pause or seek', async () => {
  const { w, v } = await setupWatch();
  const st = fakeMedia(v, { duration: 600, currentTime: 100, paused: false });
  let paused = 0;
  w.HTMLMediaElement.prototype.pause = function () { paused++; };
  w.document.dispatchEvent(new w.KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
  w.document.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
  assert.equal(paused, 0);
  assert.equal(st.currentTime, 100);
});

/* 454: landscape layout */
test('landscape layout: no attribute, no rule, even when an old config still says landscape', async () => {
  const env = await setupWatch({ cfg: { landscape: true } });
  assert.equal(env.w.document.documentElement.hasAttribute('data-ytl-landscape'), false);
  assert.ok(!/data-ytl-landscape|70vh/.test(env.w.ytl._.css()));
  assert.ok(!/"landscape"/.test(read('Prefs.java')), 'jsConfig no longer sends it');
});

/* 452: orientation lock */
test('orientation lock: no tools row and nothing re-applied at start', async () => {
  const tools = read('PlayerTools.java'), main = read('MainActivity.java');
  assert.ok(!/锁定竖屏|锁定横屏|记住屏幕方向|ytl\.ab\(|ytl\.shuffle\(/.test(tools));
  assert.ok(!/restoreOrientation|setOrientationLock|ORIENT_REMEMBER/.test(main));
  // full screen still asks for landscape and goes back to "follow the system" afterwards
  assert.ok(/orientationLock\(\)/.test(read('webview/YTProWebChromeClient.java')));
});

/* 455-466: settings rows that went away or were merged; their keys stay known for old backups */
test('settings: removed rows are gone, merged rows still write the original keys', async () => {
  const sp = read('SettingsPage.java'), model = read('PrefsModel.java');
  for (const gone of ['横屏时播放器不占满', '允许超大字号', '打开搜索时弹出键盘', '未登录时记住观看进度', '观看记录和搜索记录',
    '复制到剪贴板', '"夜间时段"', 'dyn("当前"', '搜索结果隐藏「合辑」']) {
    assert.ok(!sp.includes(gone), 'still in settings: ' + gone);
  }
  assert.ok(sp.includes('从剪贴板恢复'), 'clipboard restore kept for old backups');
  assert.ok(/PrefsModel\.writeFontChoice/.test(sp) && /PrefsModel\.writeSearchTidy/.test(sp));
  for (const k of ['Prefs.ORIENT_LOCK', 'Prefs.ORIENT_REMEMBER', 'Prefs.LANDSCAPE_LAYOUT', 'Prefs.SEARCH_FOCUS', 'Prefs.RESUME_LOCAL',
    'Prefs.FONT_LARGE', 'Prefs.HIDE_SEARCH_MIX']) {
    assert.ok(new RegExp('KEYS = \\{[^}]*' + k.replace('.', '\\.') + '\\b').test(model), k + ' still in a backup list');
  }
});

/* 467: ColorOS only */
test('keep-alive pages: only ColorOS / OPPO components', async () => {
  const main = read('MainActivity.java');
  assert.ok(!/miui|vivo\.permission|huawei/i.test(main));
  assert.ok(/com\.coloros\.safecenter/.test(main) && /com\.oplus\.battery/.test(main));
});

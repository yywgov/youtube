'use strict';
// v1.4.0 (batch 3): lists, look, interaction, remote rules
const test = require('node:test');
const assert = require('node:assert/strict');
const { load, fakeMedia, fire, sleep } = require('./helpers');

const card = (id, title, channel, extra) =>
  `<ytm-rich-item-renderer id="c-${id}"><ytm-video-with-context-renderer><ytm-media-item>` +
  `<a class="media-item-thumbnail-container" href="/watch?v=${id}"><ytm-thumbnail-overlay-time-status-renderer>${(extra && extra.dur) || '12:34'}</ytm-thumbnail-overlay-time-status-renderer></a>` +
  `<div class="details"><h3 class="media-item-headline">${title}</h3>` +
  `<ytm-badge-and-byline-renderer><span class="YtmBadgeAndBylineRendererItemByline">${channel} · 1万次观看 · ${(extra && extra.age) || '2天前'}</span></ytm-badge-and-byline-renderer>` +
  `<ytm-menu-renderer><button class="menu-btn">⋮</button></ytm-menu-renderer></div></ytm-media-item></ytm-video-with-context-renderer></ytm-rich-item-renderer>`;
const FEED = '<ytm-app>' +
  card('aaaaaaaaaa1', 'Normal video', 'Good Channel') +
  card('aaaaaaaaaa2', 'Something about Gossip news', 'Other') +
  card('aaaaaaaaaa3', 'From blocked', 'Bad Channel') +
  card('aaaaaaaaaa4', 'Tiny clip', 'Good Channel', { dur: '0:45' }) +
  card('aaaaaaaaaa5', 'Ancient', 'Good Channel', { age: '3 years ago' }) +
  card('aaaaaaaaaa6', 'Live now', 'Good Channel', { dur: '直播' }) +
  '</ytm-app>';
const F = { blockChannels: ['bad channel'], blockWords: ['gossip'], minDur: 1, maxAgeDays: 365, hideLive: true };
const flag = (w, id) => w.document.getElementById('c-' + id).getAttribute('data-ytl-filter');
const jsErrors = (calls) => calls.filter((c) => c[0] === 'log' && c[1] === 'js');

test('durations and "x ago" ages parse in Chinese and English', async () => {
  const { w } = await load({ path: '/' });
  const { parseDur, ageDays } = w.ytl._;
  assert.equal(parseDur('12:34'), 754); assert.equal(parseDur('1:02:03'), 3723); assert.equal(parseDur('直播'), -1);
  assert.equal(ageDays('2天前'), 2); assert.equal(ageDays('3 years ago'), 1095); assert.equal(ageDays('5 个月前'), 150);
  assert.equal(ageDays('1 小时前'), 1 / 24); assert.equal(ageDays('1.2万次观看'), -1);
});

test('home filters: channel, keyword, short, old, live; normal video kept; no module errors', async () => {
  const { w, calls } = await load({ path: '/', body: FEED, cfg: F });
  assert.equal(flag(w, 'aaaaaaaaaa1'), null);
  assert.equal(flag(w, 'aaaaaaaaaa2'), 'word');
  assert.equal(flag(w, 'aaaaaaaaaa3'), 'channel');
  assert.equal(flag(w, 'aaaaaaaaaa4'), 'short');
  assert.equal(flag(w, 'aaaaaaaaaa5'), 'old');
  assert.equal(flag(w, 'aaaaaaaaaa6'), 'live');
  assert.equal(w.getComputedStyle(w.document.getElementById('c-aaaaaaaaaa3')).display, 'none');
  assert.deepEqual(jsErrors(calls), []);
  // switching filters off shows everything again
  w.ytl.cfg({ blockChannels: [], blockWords: [], minDur: 0, maxAgeDays: 0, hideLive: false });
  w.ytl._.domPass();
  assert.equal(w.document.querySelectorAll('[data-ytl-filter]').length, 0);
});

test('search applies only channel/keyword filters; channel pages are never filtered', async () => {
  const s = await load({ path: '/results?search_query=x', body: FEED, cfg: F });
  assert.equal(flag(s.w, 'aaaaaaaaaa3'), 'channel');
  assert.equal(flag(s.w, 'aaaaaaaaaa2'), 'word');
  assert.equal(flag(s.w, 'aaaaaaaaaa4'), null);
  assert.equal(flag(s.w, 'aaaaaaaaaa6'), null);
  const c = await load({ path: '/@bad/videos', body: FEED, cfg: F });
  assert.equal(c.w.document.querySelectorAll('[data-ytl-filter]').length, 0);
});

test('defaults filter nothing', async () => {
  const { w } = await load({ path: '/', body: FEED });
  assert.equal(w.document.querySelectorAll('[data-ytl-filter]').length, 0);
});

test('locally hidden video stays hidden after reload; unhide brings it back', async () => {
  const env = await load({ path: '/', body: FEED });
  env.w.ytl.hideVideo('aaaaaaaaaa1');
  assert.equal(flag(env.w, 'aaaaaaaaaa1'), 'hidden');
  const stored = env.w.localStorage.getItem('ytl_hidden');
  const again = await load({ path: '/', body: FEED, before: (w) => w.localStorage.setItem('ytl_hidden', stored) });
  assert.equal(flag(again.w, 'aaaaaaaaaa1'), 'hidden');
  again.w.ytl.unhideAll();
  assert.equal(flag(again.w, 'aaaaaaaaaa1'), null);
});

test('watched threshold follows the setting', async () => {
  const item = '<ytm-app><ytm-rich-item-renderer id=a><div class="ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment" style="width: 60%;"></div></ytm-rich-item-renderer></ytm-app>';
  const def = await load({ path: '/', body: item });
  assert.ok(!def.w.document.getElementById('a').hasAttribute('data-ytl-seen'));
  const half = await load({ path: '/', body: item, cfg: { seenMin: 50 } });
  assert.ok(half.w.document.getElementById('a').hasAttribute('data-ytl-seen'));
});

test('after a refresh: "N new videos"; plain load only remembers the feed', async () => {
  const prev = JSON.stringify(['aaaaaaaaaa1', 'aaaaaaaaaa2', 'aaaaaaaaaa3']);
  const env = await load({ path: '/?ytlr=abc', body: FEED, before: (w) => w.localStorage.setItem('ytl_home_ids', prev) });
  const t = env.calls.filter((c) => c[0] === 'toast');
  assert.deepEqual(t.map((c) => c[1]), ['更新了 3 个新视频']);
  assert.ok(!/ytlr/.test(env.w.location.search));
  const plain = await load({ path: '/', body: FEED, before: (w) => w.localStorage.setItem('ytl_home_ids', prev) });
  assert.equal(plain.calls.filter((c) => c[0] === 'toast').length, 0);
  assert.equal(JSON.parse(plain.w.localStorage.getItem('ytl_home_ids')).length, 6);
});

test('search: quick filter chips and "people also watched" shelf hidden', async () => {
  const body = '<ytm-app><ytm-search><ytm-section-list-renderer>' +
    '<ytm-shelf-renderer id=s1><h2>People also watched</h2></ytm-shelf-renderer><ytm-shelf-renderer id=s2><h2>Latest from Foo</h2></ytm-shelf-renderer>' +
    '</ytm-section-list-renderer></ytm-search></ytm-app>';
  const { w } = await load({ path: '/results?search_query=lofi&sp=CAI%253D', body });
  const chips = w.document.getElementById('ytl-chips');
  assert.ok(chips);
  const links = [...chips.querySelectorAll('a')];
  assert.equal(links[0].textContent, '全部');
  assert.ok(links.find((a) => a.textContent === '最新').hasAttribute('aria-current'));
  assert.ok(links[2].getAttribute('href').includes('search_query=lofi&sp=EgIQAQ%3D%3D'));
  assert.ok(w.document.getElementById('s1').hasAttribute('data-ytl-searchx'));
  assert.ok(!w.document.getElementById('s2').hasAttribute('data-ytl-searchx'));
  assert.equal(w.getComputedStyle(w.document.getElementById('s1')).display, 'none');
  const off = await load({ path: '/results?search_query=lofi', body, cfg: { searchChips: false, hideSearchExtras: false } });
  assert.equal(off.w.document.getElementById('ytl-chips'), null);
  assert.notEqual(off.w.getComputedStyle(off.w.document.getElementById('s1')).display, 'none');
});

test('long press on a card opens the quick menu and swallows the click; a scroll does not', async () => {
  const { w, calls } = await load({ path: '/', body: FEED });
  const target = w.document.querySelector('#c-aaaaaaaaaa1 h3');
  const touch = (type, x, y) => {
    const ev = new w.Event(type, { bubbles: true, cancelable: true });
    ev.touches = type === 'touchend' ? [] : [{ clientX: x, clientY: y }];
    target.dispatchEvent(ev);
  };
  touch('touchstart', 10, 10);
  touch('touchmove', 10, 60);   // scrolling
  await sleep(600);
  assert.equal(calls.filter((c) => c[0] === 'cardMenu').length, 0);
  touch('touchstart', 10, 10);
  await sleep(600);
  touch('touchend');
  const m = calls.filter((c) => c[0] === 'cardMenu');
  assert.equal(m.length, 1);
  assert.deepEqual(JSON.parse(m[0][1]), { id: 'aaaaaaaaaa1', title: 'Normal video', channel: 'Good Channel', key: '', wl: false });
  let reached = false;
  w.document.body.addEventListener('click', () => { reached = true; });
  target.click();
  assert.equal(reached, false, 'click after long press swallowed');
  let menu = 0;
  w.document.querySelector('#c-aaaaaaaaaa1 .menu-btn').addEventListener('click', () => { menu++; });
  await sleep(950);
  w.ytl.openCardMenu();
  assert.equal(menu, 1);
});

test('remote rules: own stylesheet, invalid / broad selectors dropped', async () => {
  const { w } = await load({ path: '/', remote: { schema: 1, version: 3, hide: ['ytm-new-ad-thing', 'video', 'div.promo-x', 'ytm-a{x}', '::::', 'body'] } });
  const st = w.document.getElementById('ytl-css-remote');
  assert.ok(st);
  assert.ok(st.textContent.includes('ytm-new-ad-thing{display:none!important}'));
  assert.ok(st.textContent.includes('div.promo-x{'));
  assert.ok(!/(^|\n)video\{/.test(st.textContent));
  assert.ok(!/(^|\n)body\{/.test(st.textContent));
  assert.ok(!st.textContent.includes('ytm-a{x}'));
  const none = await load({ path: '/' });
  assert.equal(none.w.document.getElementById('ytl-css-remote'), null);
});

test('back to a list returns to the saved scroll position', async () => {
  const env = await load({ path: '/', body: FEED });
  const { w } = env;
  let y = 1500;
  Object.defineProperty(w, 'scrollY', { configurable: true, get: () => y });
  Object.defineProperty(w.document.documentElement, 'scrollHeight', { configurable: true, get: () => 5000 });
  const to = [];
  w.scrollTo = (x, yy) => { to.push(yy); y = yy; };
  w.history.pushState({}, '', '/watch?v=aaaaaaaaaa1');
  assert.equal(w.ytl._.scrollMem()['/'], 1500);
  y = 0;
  w.history.replaceState({}, '', '/');
  w.dispatchEvent(new w.PopStateEvent('popstate'));
  await sleep(50);
  assert.deepEqual(to, [1500]);
});

test('system bar colour follows the page background', async () => {
  const env = await load({ path: '/', body: '<ytm-app></ytm-app>' });
  env.w.document.body.style.backgroundColor = 'rgb(255, 255, 255)';
  env.w.ytl._.reportTheme(true);
  env.w.document.body.style.backgroundColor = 'rgb(15, 15, 15)';
  env.w.ytl._.reportTheme(true);
  env.w.ytl.cfg({ amoled: true });
  env.w.ytl._.reportTheme(true);
  assert.deepEqual(env.calls.filter((c) => c[0] === 'pageTheme').map((c) => c[1]), ['#ffffff', '#0f0f0f', '#000000']);
});

test('look switches set their html attributes; bad accent ignored', async () => {
  const { w } = await load({ path: '/', cfg: { amoled: true, compact: true, cardStyle: true, dimThumbs: true, accent: '#2F6BFF', updateDot: true } });
  const h = w.document.documentElement;
  for (const a of ['data-ytl-amoled', 'data-ytl-compact', 'data-ytl-cards', 'data-ytl-dimthumb', 'data-ytl-accent', 'data-ytl-update']) assert.ok(h.hasAttribute(a), a);
  assert.equal(h.style.getPropertyValue('--ytl-accent-pick'), '#2F6BFF');
  assert.equal(h.getAttribute('data-ytl-page'), 'home');
  w.ytl.cfg({ accent: 'red;}body{display:none', amoled: false });
  assert.ok(!h.hasAttribute('data-ytl-accent'));
  assert.ok(!h.hasAttribute('data-ytl-amoled'));
  const css = w.document.getElementById('ytl-css').textContent;
  assert.ok(css.includes('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item{'));
  const def = await load({ path: '/' });
  for (const a of ['data-ytl-amoled', 'data-ytl-compact', 'data-ytl-cards', 'data-ytl-dimthumb', 'data-ytl-accent']) assert.ok(!def.w.document.documentElement.hasAttribute(a), 'default off: ' + a);
});

test('sleep mode forces the lowest quality and the calm rule; battery saver caps at 480p', async () => {
  const env = await load({ path: '/watch?v=abcdefghijk', cfg: { quality: '1080' } });
  assert.equal(env.w.ytl._.prefQuality(), 'hd1080');
  env.w.ytl.sleepMode(true);
  assert.equal(env.w.ytl._.prefQuality(), 'tiny');
  env.w.ytl.sleepState(true);
  assert.equal(env.w.ytl._.ruleMode(), 'channel');
  env.w.ytl.sleepMode(false);
  assert.equal(env.w.ytl._.ruleMode(), 'rec');
  env.w.ytl.cfg({ powerSave: true });
  assert.equal(env.w.ytl._.prefQuality(), 'large');
  env.w.ytl.cfg({ quality: '480' });
  assert.equal(env.w.ytl._.prefQuality(), 'large');
});

test('watch later: needs sign-in; signed in sends the same request YouTube does', async () => {
  const out = await load({ path: '/watch?v=abcdefghijk' });
  await out.w.ytl._.watchLater('abcdefghijk');
  assert.ok(out.calls.some((c) => c[0] === 'toast' && /登录/.test(c[1])));
  let req = null;
  const env = await load({
    path: '/watch?v=abcdefghijk', before: (w) => {
      w.ytcfg = { get: (k) => ({ INNERTUBE_CONTEXT: { client: { clientName: 'MWEB' } }, SESSION_INDEX: 0, INNERTUBE_CONTEXT_CLIENT_NAME: 2, INNERTUBE_CLIENT_VERSION: '2.2' })[k] };
      Object.defineProperty(w, 'crypto', { configurable: true, value: require('crypto').webcrypto });
      w.TextEncoder = require('util').TextEncoder;
      w.document.cookie = 'SAPISID=abc123; path=/';
      w.fetch = (u, init) => { req = { u, init }; return Promise.resolve({ ok: true, json: () => Promise.resolve({ status: 'STATUS_SUCCEEDED' }) }); };
    }
  });
  const ok = await env.w.ytl._.watchLater('abcdefghijk');
  assert.equal(ok, true);
  assert.ok(req.u.startsWith('/youtubei/v1/browse/edit_playlist'));
  const body = JSON.parse(req.init.body);
  assert.equal(body.playlistId, 'WL');
  assert.deepEqual(body.actions, [{ action: 'ACTION_ADD_VIDEO', addedVideoId: 'abcdefghijk' }]);
  assert.ok(/^SAPISIDHASH \d+_[0-9a-f]{40}$/.test(req.init.headers.Authorization));
  assert.ok(env.calls.some((c) => c[0] === 'toast' && c[1] === '已加入稍后观看'));
});

test('search box: focused with the keyboard when it opens empty, not when it has text', async () => {
  const env = await load({ path: '/', body: '<ytm-app><ytm-searchbox><input class="searchbox-input"></ytm-searchbox></ytm-app>', before: (w) => {
    Object.defineProperty(w.HTMLElement.prototype, 'offsetParent', { configurable: true, get() { return this.parentNode; } });
  } });
  assert.ok(env.calls.some((c) => c[0] === 'showKeyboard'));
  assert.equal(env.w.document.activeElement, env.w.document.querySelector('input'));
  const full = await load({ path: '/results?search_query=a', body: '<ytm-app><ytm-searchbox><input class="searchbox-input" value="a"></ytm-searchbox></ytm-app>', before: (w) => {
    Object.defineProperty(w.HTMLElement.prototype, 'offsetParent', { configurable: true, get() { return this.parentNode; } });
  } });
  assert.ok(!full.calls.some((c) => c[0] === 'showKeyboard'));
});

test('account switcher entries are never hidden (71)', async () => {
  const { w } = await load({ path: '/', body: '<ytm-app></ytm-app>' });
  const sheet = w.document.createElement('bottom-sheet-container');
  w.document.body.appendChild(sheet);
  await sleep(30);
  for (const t of ['切换账号', 'Switch account', '添加账号', '反馈']) {
    const it = w.document.createElement('yt-list-item-view-model');
    it.textContent = t;
    sheet.appendChild(it);
  }
  await sleep(600);
  const hidden = [...sheet.children].filter((x) => x.hasAttribute('data-ytl-hide')).map((x) => x.textContent);
  assert.deepEqual(hidden, ['反馈']);
});

test('pivot "create" entry and in-player watermark are in the stylesheet', async () => {
  const { w } = await load({ path: '/' });
  const css = w.document.getElementById('ytl-css').textContent;
  assert.ok(css.includes('.ytp-watermark'));
  assert.ok(css.includes('ytm-pivot-bar-item-renderer:has(.pivot-create)'));
});

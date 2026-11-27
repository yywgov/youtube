'use strict';
// v1.23+ UI redesign (redesign/PLAN.md): brand red tokens, line icons, update dot
const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./helpers');

test('v1.23 brand red tokens; a picked colour only fills', async () => {
  const { w } = await load({ path: '/', body: '<ytm-app><ytm-home-logo></ytm-home-logo></ytm-app>', cfg: { updateDot: true } });
  const css = w.document.getElementById('ytl-css').textContent;
  assert.match(css, /:root\{--ytl-accent:#c8102e;--ytl-accent-text:#ff5468;--ytl-dot:#ff5468/);
  assert.ok(!css.includes('#2f6bff'), 'old brand blue gone');
  assert.ok(css.includes('html[data-ytl-accent]{--ytl-accent:var(--ytl-accent-pick)}'), 'pick does not recolour text');
  assert.match(css, /#ytl-gear::after\{[^}]*top:11px;right:11px;width:10px;height:10px;[^}]*background:var\(--ytl-dot\)/);
});

test('v1.23 top-bar buttons use 2px line icons', async () => {
  const { w } = await load({ path: '/', body: '<ytm-app><ytm-home-logo></ytm-home-logo></ytm-app>' });
  const svg = w.document.querySelector('#ytl-gear svg');
  assert.ok(svg, 'gear drawn');
  assert.equal(svg.getAttribute('stroke-width'), '2');
  assert.equal(svg.getAttribute('fill'), 'none');
  assert.equal(svg.getAttribute('width'), '24');
  assert.equal(svg.querySelectorAll('path').length, 2);
});

test('v1.26 snack bar, empty note and press feedback follow the design', async () => {
  const { w } = await load({ path: '/' });
  const css = w.document.getElementById('ytl-css').textContent;
  assert.ok(css.includes('@starting-style{#ytl-sb{transform:translateY(8px);opacity:0}}'), 'bar enters with @starting-style');
  assert.match(css, /#ytl-sb\{[^}]*left:12px;right:12px;bottom:calc\(64px \+ env\(safe-area-inset-bottom\)\)/);
  assert.match(css, /#ytl-sb\{[^}]*background:var\(--ytl-snack\)/);
  assert.ok(css.includes('html[data-ytl-lowmotion] #ytl-sb'), 'no slide with animations removed');
  assert.match(css, /#ytl-gear:active[^{]*\{opacity:\.72;transform:scale\(\.96\)/);
  assert.ok(css.includes('user-select:none'));
  assert.ok(!/transition:all/.test(css), 'never transition: all');
});

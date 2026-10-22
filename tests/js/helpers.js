'use strict';
// Loads ytlite.js into a jsdom page with a fake Java bridge (window.YTL) that records calls.
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'ytlite.js'), 'utf8');

async function load(opts) {
  opts = opts || {};
  const url = 'https://m.youtube.com' + (opts.path || '/');
  const body = opts.body || '';
  const dom = new JSDOM(`<!doctype html><html><head></head><body>${body}</body></html>`, {
    url, runScripts: 'outside-only', pretendToBeVisual: true
  });
  const w = dom.window;
  const calls = [];
  const cfg = Object.assign({ sponsorBlock: true, lowQualityBg: true, hideWatched: true, perfCss: true }, opts.cfg || {});
  const rec = (name) => function () { calls.push([name].concat(Array.prototype.slice.call(arguments))); };
  w.YTL = {
    getConfig: () => JSON.stringify(cfg),
    log: rec('log'), logAd: rec('logAd'), toast: rec('toast'), setAtTop: rec('setAtTop'),
    openSettings: rec('openSettings'), mediaState: rec('mediaState'), mediaMeta: rec('mediaMeta'),
    fetchSegments: rec('fetchSegments'), setPortrait: rec('setPortrait'), hardReload: rec('hardReload'),
    sleepFired: rec('sleepFired'), saveRate: rec('saveRate'), savePos: rec('savePos'), videoEnded: rec('videoEnded'),
    openTools: rec('openTools'), isMetered: () => !!opts.metered
  };
  w.HTMLMediaElement.prototype.pause = function () {};
  w.HTMLMediaElement.prototype.play = function () { return Promise.resolve(); };
  if (opts.before) opts.before(w);
  w.eval(SRC);
  if (w.document.readyState === 'loading') {
    await new Promise((r) => w.document.addEventListener('DOMContentLoaded', () => setTimeout(r, 0)));
  }
  return { w, calls, dom };
}

/** Give a <video> controllable media properties (jsdom has no media pipeline). */
function fakeMedia(v, props) {
  const state = Object.assign({ duration: NaN, currentTime: 0, paused: false, readyState: 4, muted: false, playbackRate: 1, ended: false, error: null, volume: 1 }, props || {});
  Object.keys(state).forEach((k) => {
    Object.defineProperty(v, k, { configurable: true, get: () => state[k], set: (x) => { state[k] = x; } });
  });
  return state;
}

function fire(w, el, type) { el.dispatchEvent(new w.Event(type)); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { load, fakeMedia, fire, sleep, SRC };

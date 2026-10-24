#!/usr/bin/env node
'use strict';
// Build-time minify of the injected page script (259) + syntax level and size check (421).
//
//   node tools/minify-js.js [outDir]          (run from anywhere; esbuild comes from tests/js/node_modules)
//
// - parses app/src/main/assets/ytlite.js with target chrome90: syntax an older WebView cannot run fails the build
// - writes <outDir>/ytlite.min.js + ytlite.min.js.map (default build/js); CI runs the jsdom tests against the
//   minified copy and only then puts it into the APK. The source in git stays readable; the map is kept as a
//   CI artifact to translate line:column of a JS error in the diagnostics log.
// - prints sizes; warns (never fails) when the source grew more than 10% against the previous commit.
// Identifiers inside the IIFE are shortened; property names (window.ytl.*, cfg keys, bridge calls) are untouched.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const esbuild = require(path.join(root, 'tests', 'js', 'node_modules', 'esbuild'));
const srcPath = path.join(root, 'app', 'src', 'main', 'assets', 'ytlite.js');
const outDir = path.resolve(process.argv[2] || path.join(root, 'build', 'js'));
const src = fs.readFileSync(srcPath, 'utf8');

const r = esbuild.transformSync(src, {
  loader: 'js',
  target: 'chrome90',
  minify: true,
  keepNames: false,
  legalComments: 'none',
  charset: 'utf8',          // keep Chinese text as-is (smaller than \u escapes)
  sourcemap: 'external',
  sourcefile: 'ytlite.js'
});
(r.warnings || []).forEach((w) => console.log('::warning::esbuild: ' + w.text));

fs.mkdirSync(outDir, { recursive: true });
const out = path.join(outDir, 'ytlite.min.js');
fs.writeFileSync(out, r.code);
fs.writeFileSync(out + '.map', r.map);

const kb = (n) => (n / 1024).toFixed(1) + ' KB';
const a = Buffer.byteLength(src), b = Buffer.byteLength(r.code);
console.log(`ytlite.js ${kb(a)} -> minified ${kb(b)} (${Math.round((1 - b / a) * 100)}% smaller), target chrome90 ok`);

try {
  const prev = execFileSync('git', ['show', 'HEAD~1:app/src/main/assets/ytlite.js'], { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] });
  const p = prev.length;
  const growth = (a - p) / p;
  console.log(`source vs previous commit: ${kb(p)} -> ${kb(a)} (${(growth * 100).toFixed(1)}%)`);
  if (growth > 0.10) console.log(`::warning::ytlite.js grew ${(growth * 100).toFixed(1)}% in one commit (more than 10%)`);
} catch (e) {
  console.log('no previous commit to compare the size with');
}

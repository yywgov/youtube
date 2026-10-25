#!/usr/bin/env node
// Copy check (430): user-visible Chinese strings in the Java sources, the page script modules and
// strings.xml. Warnings only (CI prints them as ::warning, the build goes on).
//   - a space between Chinese and Latin letters / digits ("共 3 项", "Wi-Fi 最高")
//   - full-width punctuation inside Chinese text (，。：；！？ instead of , . : ; ! ?)
//   - "…" / "……" instead of "..."
'use strict';
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const files = [];
(function walk(d) {
  for (const f of fs.readdirSync(d)) {
    const p = path.join(d, f);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (/\.java$/.test(f)) files.push(p);
  }
})(path.join(root, 'app', 'src', 'main', 'java'));
const jsDir = path.join(root, 'src', 'js');
if (fs.existsSync(jsDir)) fs.readdirSync(jsDir).filter((f) => f.endsWith('.js')).forEach((f) => files.push(path.join(jsDir, f)));
files.push(path.join(root, 'app', 'src', 'main', 'res', 'values', 'strings.xml'));

const HAN = '\\u4e00-\\u9fff';
const CHECKS = [
  ['Chinese next to a Latin letter or digit without a space', new RegExp('[' + HAN + '][A-Za-z0-9]|[A-Za-z0-9][' + HAN + ']')],
  ['half-width punctuation in Chinese text', new RegExp('[' + HAN + '][,;!?](?![\\w])|[' + HAN + ']:(?!\\/)')],
  ['"..." instead of "…"', new RegExp('[' + HAN + ']\\.\\.\\.|\\.\\.\\.[' + HAN + ']')]
];
// Chinese string literals only: "…" in Java / JS, >…< in strings.xml; comments are skipped
const LIT = /"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|>([^<]*)</g;
let n = 0;
for (const f of files) {
  const rel = path.relative(root, f);
  fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
    const t = line.trim();
    if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') || t.startsWith('<!--')) return;
    let m;
    LIT.lastIndex = 0;
    while ((m = LIT.exec(line))) {
      const s = (m[1] || m[2] || m[3] || '').replace(/\\[nrt"'\\]|\\u[0-9a-fA-F]{4}/g, ' ').replace(/%[-\d.]*[sdf]/g, ' ');   // escapes and format specs are not text
      if (!new RegExp('[' + HAN + ']').test(s)) continue;
      for (const [what, re] of CHECKS) {
        if (re.test(s)) {
          n++;
          const msg = rel + ':' + (i + 1) + ': ' + what + ': ' + s.slice(0, 60);
          console.log(process.env.GITHUB_ACTIONS ? '::warning file=' + rel + ',line=' + (i + 1) + '::' + what : msg);
        }
      }
    }
  });
}
console.log('copy check: ' + n + ' warning(s) in ' + files.length + ' files');

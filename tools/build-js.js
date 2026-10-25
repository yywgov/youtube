#!/usr/bin/env node
// Page script build (435): src/js/NN-name.js are joined in file-name order, byte for byte, into
// app/src/main/assets/ytlite.js (one IIFE, as before). Edit the modules, then run this.
//   node tools/build-js.js          write the asset
//   node tools/build-js.js --check  fail when the asset is not the modules joined (CI)
'use strict';
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const dir = path.join(root, 'src', 'js');
const out = path.join(root, 'app', 'src', 'main', 'assets', 'ytlite.js');
const files = fs.readdirSync(dir).filter((f) => /^\d\d-[\w-]+\.js$/.test(f)).sort();
if (files.length < 2) { console.error('no modules in src/js'); process.exit(1); }
const joined = files.map((f) => fs.readFileSync(path.join(dir, f), 'utf8')).join('');
if (process.argv.includes('--check')) {
  const cur = fs.readFileSync(out, 'utf8');
  if (cur !== joined) {
    console.error('app/src/main/assets/ytlite.js is not src/js joined: edit src/js and run node tools/build-js.js');
    process.exit(1);
  }
  console.log('ytlite.js = ' + files.length + ' modules from src/js');
} else {
  fs.writeFileSync(out, joined);
  console.log('wrote ' + path.relative(root, out) + ' from ' + files.length + ' modules');
}

// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Bundles Tidepool Uploader's LibreView driver (third_party/uploader, not modified) with entry.js → out.cjs.
// DEPS = a directory with node_modules holding esbuild, babyparse, sundial, async, lodash (see README.md);
// UPLOADER = the uploader clone (default: the first third_party/uploader above this directory).
import fs from 'fs';
import path from 'path';
import { createRequire } from 'module';
const here = path.dirname(new URL(import.meta.url).pathname);
const deps = path.resolve(process.env.DEPS ?? '');
const esbuild = createRequire(path.join(deps, 'x.js'))('esbuild');
let up = process.env.UPLOADER;
for (let d = here; !up && d !== '/'; d = path.dirname(d)) {
  const p = path.join(d, 'third_party/uploader');
  if (fs.existsSync(p)) up = p;
}
const stub = { name: 'stub', setup(b) {
  b.onResolve({ filter: /commonFunctions/ }, () => ({ path: path.join(here, 'common-stub.js') }));
  b.onResolve({ filter: /^bows$/ }, () => ({ path: path.join(here, 'bows-stub.js') }));
  b.onResolve({ filter: /^uploader\// }, (a) => ({ path: path.join(up, a.path.slice('uploader/'.length)) }));
}};
await esbuild.build({ entryPoints: [path.join(here, 'entry.js')], bundle: true, platform: 'node', format: 'cjs',
  outfile: path.join(here, 'out.cjs'), nodePaths: [path.join(deps, 'node_modules')], plugins: [stub], logLevel: 'error' });

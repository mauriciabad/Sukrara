// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Second independent check (investigation 26): the per-bin grid samples (from check/independent.py --samples) through
// the code path of Tidepool's AGP print view (tidepool-org/viz, BSD-2): d3-array 3.2.4's quantile()/median() as in
// src/utils/bloodglucose.js calculateCbgStatsForBin, then the "Smooth all bin quantiles according to AGP spec" step
// of src/modules/print/utils/AGPUtils.js (1-4-1 weights, wrapping first/last bins), transcribed. Compared with the
// fixtures' smoothed profile.
//
//   node check/tidepool.mjs <samples.json> <path to node_modules/d3-array/src/index.js>
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [samplesPath, d3Path] = process.argv.slice(2);
const { quantile, median } = await import(pathToFileURL(d3Path).href);
const all = JSON.parse(readFileSync(samplesPath, 'utf8'));
const keys = ['lowerQuantile', 'firstQuartile', 'median', 'thirdQuartile', 'upperQuantile'];
let bad = 0;
for (const [name, { samples, expect }] of Object.entries(all)) {
  const chartData = samples.map((s) => (s.length ? {
    lowerQuantile: quantile(s, 0.05), firstQuartile: quantile(s, 0.25), median: median(s),
    thirdQuartile: quantile(s, 0.75), upperQuantile: quantile(s, 0.95),
  } : {}));
  // AGPUtils.js smoothDatum, as published
  const smoothDatum = (prev, curr, next) => {
    if ((!prev && !next) || !curr) return curr;
    if (!prev || !next) return ((prev || 0) + curr * 4 + (next || 0)) / 5;
    return (prev + curr * 4 + next) / 6;
  };
  const first = chartData[0];
  const last = chartData[chartData.length - 1];
  const smoothed = chartData.map((d, i) => keys.map((k) => smoothDatum(
    chartData[i - 1]?.[k] || last[k], d[k], chartData[i + 1]?.[k] || first[k])));
  let worst = 0;
  smoothed.forEach((row, i) => row.forEach((v, k) => {
    const e = expect[i][k + 1];
    if (v === undefined || e === null) { if (!(v === undefined && e === null)) bad++; return; }
    worst = Math.max(worst, Math.abs(v - e));
  }));
  if (worst > 1e-3) bad++;
  console.log(`${name}: ${worst <= 1e-3 ? 'ok' : 'DIFF'} · max |Δ| vs fixture ${worst.toFixed(4)} mg/dL over ${smoothed.length} bins × 5 percentiles`);
}
process.exit(bad ? 1 : 0);

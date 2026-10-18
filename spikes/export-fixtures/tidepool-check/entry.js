// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import fs from 'fs';
import driverFactory from 'uploader/lib/drivers/abbott/libreViewDriver.js';
import builder from 'uploader/lib/objectBuilder.js';
const [file, tz] = process.argv.slice(2);
const config = { filedata: fs.readFileSync(file, 'utf8'), timezone: tz, version: 'test', builder: builder() };
const d = driverFactory(config);
let payload = {};
const step = (name, ...args) => new Promise((res, rej) => d[name](...args, (err, p) => err ? rej(err) : res(p)));
(async () => {
  const p0 = { devices: {} };
  await step('getConfigInfo', () => {}, p0);
  let p = await step('fetchData', () => {}, p0);
  p = await step('processData', () => {}, p);
  for (const [k, dev] of Object.entries(p.devices)) {
    console.log('device', JSON.stringify(k), dev.info.deviceId, 'records', dev.postRecords.length);
    for (const r of dev.postRecords.slice(0, 3)) console.log(JSON.stringify({ type: r.type, value: r.value, units: r.units, time: r.time, deviceTime: r.deviceTime, tzo: r.timezoneOffset }));
  }
  console.log('units', config.units);
})().catch(e => { console.error('ERROR', e); process.exit(1); });

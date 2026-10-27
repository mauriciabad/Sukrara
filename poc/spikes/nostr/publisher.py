# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Desktop test uploader (the real one is the Android app). Publishes per PROTOCOL.md.

  uv run publisher.py --synthetic [--every 60]        fake readings (sine wave) — no phone needed
  uv run publisher.py --juggluco http://127.0.0.1:17580   real readings (e.g. via `adb forward tcp:17580 tcp:17580`)
Identity from secrets/stream.json (create with `uv run keys.py new`).
"""
import argparse, asyncio, json, math, time, urllib.request

from cgmnostr import Pool, Reading, hour_of, log
from keys import load

HISTORY_HOURS = 12


class Publisher:
    def __init__(self, stream):
        self.st = stream
        self.pool = Pool(stream.relays)
        self.readings: dict[int, Reading] = {}

    def add(self, r: Reading, live=True):
        new = r.t not in self.readings
        self.readings[r.t] = r
        if live and new:
            self.pool.publish(self.st.live_event(r))
            log(f"live {r.sgv} mg/dL rate {r.rate} t={time.strftime('%H:%M:%S', time.localtime(r.t))}")

    def publish_chunk(self, hour):
        rs = [r for r in self.readings.values() if hour_of(r.t) == hour]
        if rs:
            self.pool.publish(self.st.chunk_event(hour, rs))

    async def chunk_loop(self):
        last_hour = hour_of(int(time.time()))
        while True:
            await asyncio.sleep(300)
            now_hour = hour_of(int(time.time()))
            if now_hour != last_hour:          # close the previous hour
                self.publish_chunk(last_hour)
                last_hour = now_hour
            self.publish_chunk(now_hour)
            log("stats", json.dumps(self.pool.stats()))

    def backfill(self):
        hours = sorted({hour_of(t) for t in self.readings if t > time.time() - HISTORY_HOURS * 3600})
        for h in hours:                         # Relay writers pace these ≥ 2 s apart
            self.publish_chunk(h)
        log(f"backfill: {len(hours)} hourly chunks queued")


def synthetic(t):
    return Reading(t, int(130 + 60 * math.sin(t / 5400)), round(60 * math.cos(t / 5400) / 90, 2), "synthetic")


async def run_synthetic(p: Publisher, every: int):
    now = int(time.time())
    for t in range(now - HISTORY_HOURS * 3600, now, 60):
        p.add(synthetic(t), live=False)
    p.backfill()
    while True:
        p.add(synthetic(int(time.time())))
        await asyncio.sleep(every)


async def run_juggluco(p: Publisher, base: str):
    def get(path):
        with urllib.request.urlopen(base + path, timeout=10) as r:
            return r
    with urllib.request.urlopen(f"{base}/api/v1/entries.json?count={HISTORY_HOURS * 60}&interval=0", timeout=10) as r:
        for e in json.load(r):
            rate = None if not e.get("direction") else e["delta"] / 5
            p.add(Reading(e["date"] // 1000, int(e["sgv"]), rate, "juggluco"), live=False)
    p.backfill()
    while True:   # SSE /x/live (blocking reader in a thread)
        try:
            q: asyncio.Queue = asyncio.Queue()
            loop = asyncio.get_running_loop()

            def reader():
                with urllib.request.urlopen(f"{base}/x/live", timeout=60) as r:
                    event = ""
                    for raw in r:
                        line = raw.decode().strip()
                        if line.startswith("event:"):
                            event = line[6:].strip()
                        elif line.startswith("data:") and event == "glucose":
                            loop.call_soon_threadsafe(q.put_nowait, json.loads(line[5:]))
            fut = loop.run_in_executor(None, reader)
            while not fut.done():
                try:
                    d = await asyncio.wait_for(q.get(), 5)
                    p.add(Reading(d["timestamp"], int(d["glucoseMgDl"]), d.get("rateMgDlPerMinute"), "juggluco"))
                except asyncio.TimeoutError:
                    pass
            fut.result()
        except Exception as e:
            log("juggluco:", e)
        await asyncio.sleep(5)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--every", type=int, default=60)
    ap.add_argument("--juggluco")
    a = ap.parse_args()
    p = Publisher(load())
    p.pool.start()
    asyncio.ensure_future(p.chunk_loop())
    log("publishing as", p.st.source_pub.to_bech32(), "to", len(p.st.relays), "relays")
    await (run_juggluco(p, a.juggluco) if a.juggluco else run_synthetic(p, a.every))


if __name__ == "__main__":
    asyncio.run(main())

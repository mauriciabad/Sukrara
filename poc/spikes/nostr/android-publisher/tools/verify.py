# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""End-to-end check of the Android publisher from the Mac.

  adb forward tcp:27580 tcp:17580          # Juggluco on the phone, as ground truth
  cd spikes/nostr && uv run android-publisher/tools/verify.py [--link-file secrets/follower-link.txt] [--minutes 5]

1. chunks: REQ the last 12 hourly chunks on every relay, decrypt, compare row-by-row with Juggluco's history.
2. live: watch Juggluco's SSE (through adb forward) and the relays' kind-21078 stream at the same time; latency =
   relay delivery on the Mac − SSE arrival on the Mac (≈ phone receives reading → follower has it).
"""
import argparse, asyncio, json, os, sys, time, urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from cgmnostr import CHUNK_KIND, LIVE_KIND, Stream, hour_of  # noqa: E402
import websockets  # noqa: E402

J = "http://127.0.0.1:27580"


def juggluco_history():
    with urllib.request.urlopen(f"{J}/api/v1/entries.json?count=800&interval=0", timeout=10) as r:
        out = {}
        for e in json.load(r):
            rate = None if e.get("direction", "") == "" else e["delta"] / 5
            out[e["date"] // 1000] = (e["sgv"], None if rate is None else round(rate * 100))
        return out


async def fetch_chunks(st: Stream, url, ids):
    flt = {"authors": [st.source_pub.to_hex()], "kinds": [CHUNK_KIND], "#d": list(ids)}
    got = {}
    try:
        async with websockets.connect(url, open_timeout=10, max_size=2**22) as ws:
            await ws.send(json.dumps(["REQ", "c", flt]))
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), 15))
                if msg[0] == "EOSE":
                    break
                if msg[0] == "EVENT":
                    ev = msg[2]
                    body = st.decrypt(ev["content"])
                    tags = {t[0]: t[1] for t in ev["tags"]}
                    got[body["h"]] = (body, ev, tags)
                if msg[0] == "CLOSED":
                    print(url, "CLOSED", msg)
                    break
    except Exception as e:
        print(f"{url}: {type(e).__name__} {e}")
    return got


async def check_chunks(st: Stream, hist):
    now = int(time.time())
    hours = [hour_of(now) - 3600 * i for i in range(12)]
    ids = {st.chunk_id(h): h for h in hours}
    res = await asyncio.gather(*(fetch_chunks(st, u, ids) for u in st.relays))
    for url, got in zip(st.relays, res):
        good = bad = missing_rows = 0
        problems = []
        for h in hours:
            if h not in got:
                continue
            body, ev, tags = got[h]
            assert tags.get("d") == st.chunk_id(h), "d tag mismatch"
            assert int(tags.get("expiration")) == h + 7 * 86400, "expiration mismatch"
            rows = {h + dt: (sgv, r100) for dt, sgv, r100 in body["r"]}
            truth = {t: v for t, v in hist.items() if h <= t < h + 3600}
            for t, v in rows.items():
                if t in truth and truth[t][0] == v[0] and (truth[t][1] is None) == (v[1] is None) and \
                        (v[1] is None or abs(truth[t][1] - v[1]) <= 1):
                    good += 1
                elif t in truth:
                    bad += 1
                    problems.append((t, v, truth[t]))
            # rows Juggluco has but the chunk doesn't (the newest minutes of the current hour may lag ≤ 5 min)
            missing_rows += len([t for t in truth if t not in rows and now - t > 360])
        print(f"{url:26} chunks {len(got):2}/12  rows ok {good}  mismatched {bad}  missing {missing_rows}"
              f"  hours {[time.strftime('%H', time.localtime(h)) for h in sorted(got)]}")
        for p in problems[:3]:
            print("   mismatch", p)


async def watch_live(st: Stream, minutes):
    sse_at = {}                      # reading t → Mac time the SSE event arrived
    seen = defaultdict(dict)         # t → relay → Mac time
    stop = time.time() + minutes * 60

    async def sse():
        reader, writer = await asyncio.open_connection("127.0.0.1", 27580)
        writer.write(b"GET /x/live HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
        await writer.drain()
        while time.time() < stop:
            line = (await asyncio.wait_for(reader.readline(), 60)).decode().strip()
            if line.startswith("data:"):
                j = json.loads(line[5:])
                sse_at.setdefault(j["timestamp"], time.time())
                print(f"{time.strftime('%H:%M:%S')} SSE  {j['glucoseMgDl']} t={j['timestamp']}")

    async def relay(url):
        flt = {"authors": [st.source_pub.to_hex()], "kinds": [LIVE_KIND], "since": int(time.time()) - 120}
        try:
            async with websockets.connect(url, open_timeout=10) as ws:
                await ws.send(json.dumps(["REQ", "l", flt]))
                while time.time() < stop:
                    try:
                        msg = json.loads(await asyncio.wait_for(ws.recv(), max(1, stop - time.time())))
                    except asyncio.TimeoutError:
                        break
                    if msg[0] == "EVENT":
                        b = st.decrypt(msg[2]["content"])
                        tags = {t[0]: t[1] for t in msg[2]["tags"]}
                        assert int(tags["expiration"]) - msg[2]["created_at"] == 3600
                        seen[b["t"]].setdefault(url, time.time())
        except Exception as e:
            print(f"{url}: {type(e).__name__} {e}")

    await asyncio.gather(sse(), *(relay(u) for u in st.relays), return_exceptions=True)
    print("\nlive latency (relay → Mac minus SSE → Mac), seconds:")
    lat = defaultdict(list)
    for t, at in sorted(sse_at.items()):
        row = []
        for u in st.relays:
            if u in seen.get(t, {}):
                d = seen[t][u] - at
                lat[u].append(d)
                row.append(f"{d:5.2f}")
            else:
                row.append("  -  ")
        print(time.strftime("%H:%M:%S", time.localtime(t)), " ".join(row))
    for u in st.relays:
        v = sorted(lat[u])
        n = len(sse_at)
        print(f"{u:26} got {len(v)}/{n}" + (f"  p50 {v[len(v)//2]:.2f}s  max {v[-1]:.2f}s" if v else ""))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--link")
    ap.add_argument("--link-file", default=os.path.join(os.path.dirname(__file__), "..", "..", "secrets", "follower-link.txt"))
    ap.add_argument("--minutes", type=float, default=5)
    a = ap.parse_args()
    st = Stream.from_link((a.link or open(a.link_file).read()).strip())
    print("relays:", st.relays)
    hist = juggluco_history()
    print(f"Juggluco history: {len(hist)} readings")
    await check_chunks(st, hist)
    if a.minutes > 0:
        await watch_live(st, a.minutes)


asyncio.run(main())

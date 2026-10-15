# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Follower: syncs a CGM-over-Nostr stream into a local SQLite DB and serves a Nightscout-compatible read API.

  uv run follower.py --link 'cgmnostr:1?...' [--db follower.db] [--port 1337] [--hours 12] [--api-secret S]

1. fetches the last N hourly chunks from all relays (backfill), 2. follows live events, 3. re-fetches the current
hour every 5 min (gap repair). The DB keeps everything it has ever seen (all-time history on this device).
Nightscout API (read-only, tier 1): /api/v1/status(.json), /api/v1/entries(.json|/sgv(.json)|/current(.json)),
/api/v1/treatments(.json), /api/v1/devicestatus(.json), /api/v1/profile(.json), /api/v1/verifyauth, /pebble
"""
import argparse, asyncio, hashlib, json, sqlite3, time
from datetime import datetime, timezone

from aiohttp import web

from cgmnostr import CHUNK_KIND, LIVE_KIND, Pool, Reading, Stream, direction, hour_of, log


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute("""create table if not exists readings(
            t integer primary key, sgv integer not null, rate real, dev text, src text, received integer)""")

    def upsert(self, r: Reading, src: str) -> bool:
        cur = self.db.execute("select rate from readings where t=?", (r.t,)).fetchone()
        if cur is None:
            self.db.execute("insert into readings values(?,?,?,?,?,?)", (r.t, r.sgv, r.rate, r.dev, src, int(time.time())))
        elif cur[0] is None and r.rate is not None:        # keep the copy that carries a rate
            self.db.execute("update readings set rate=? where t=?", (r.rate, r.t))
        self.db.commit()
        return cur is None

    def query(self, count=10, gt=None, gte=None, lt=None, lte=None):
        sql, args = "select t,sgv,rate,dev from readings where 1=1", []
        for op, v in ((">", gt), (">=", gte), ("<", lt), ("<=", lte)):
            if v is not None:
                sql += f" and t*1000 {op} ?"
                args.append(v)
        sql += " order by t desc limit ?"
        args.append(count)
        return [Reading(*row) for row in self.db.execute(sql, args)]

    def count(self):
        return self.db.execute("select count(*), min(t), max(t) from readings").fetchone()


class Follower:
    def __init__(self, st: Stream, store: Store, hours: int):
        self.st, self.store, self.hours = st, store, hours
        self.pool = Pool(st.relays)
        self.latency: list[float] = []

    def on_event(self, ev, relay):
        for r in self.st.parse(ev):
            new = self.store.upsert(r, "live" if ev["kind"] == LIVE_KIND else "chunk")
            if new and ev["kind"] == LIVE_KIND:
                lat = time.time() - r.t
                self.latency.append(lat)
                log(f"live {r.sgv} mg/dL rate {r.rate} via {relay.split('//')[1]} latency {lat:.1f}s")

    def fetch_hours(self, hours):
        ids = [self.st.chunk_id(h) for h in hours]
        self.pool.subscribe({"authors": [self.st.source_pub.to_hex()], "kinds": [CHUNK_KIND], "#d": ids}, self.on_event)

    async def run(self):
        self.pool.start()
        now = int(time.time())
        hours = [hour_of(now) - 3600 * i for i in range(self.hours)]
        t0 = time.time()
        self.fetch_hours(hours)
        self.pool.subscribe({"authors": [self.st.source_pub.to_hex()], "kinds": [LIVE_KIND], "since": now - 120}, self.on_event)
        await asyncio.sleep(8)
        c, lo, hi = self.store.count()
        log(f"backfill after {time.time() - t0:.0f}s: {c} readings in DB "
            f"({time.strftime('%H:%M', time.localtime(lo)) if lo else '-'} → {time.strftime('%H:%M', time.localtime(hi)) if hi else '-'})")
        while True:
            await asyncio.sleep(300)
            self.fetch_hours([hour_of(int(time.time()))])
            lat = sorted(self.latency)
            log("relays", json.dumps({u.split('//')[1]: s["up"] for u, s in self.pool.stats().items()}),
                f"live latency p50={lat[len(lat)//2]:.1f}s max={lat[-1]:.1f}s n={len(lat)}" if lat else "")


# ---------------- Nightscout API ----------------

def iso(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


TREND = {"DoubleUp": 1, "SingleUp": 2, "FortyFiveUp": 3, "Flat": 4, "FortyFiveDown": 5, "SingleDown": 6,
         "DoubleDown": 7, "NONE": 8}   # 8 = NOT COMPUTABLE


def entry(r: Reading, src_hex: str, prev: Reading | None = None):
    # delta must always be present (py-nightscout 1.3.3 crashes on null): sensor rate×5, else 5-min difference, else 0
    if r.rate is not None:
        delta = round(r.rate * 5, 3)
    elif prev is not None and 0 < r.t - prev.t <= 900:
        delta = round((r.sgv - prev.sgv) * 300 / (r.t - prev.t), 3)
    else:
        delta = 0.0
    d = direction(r.rate)
    return {"_id": hashlib.sha1(f"{src_hex}:{r.t}".encode()).hexdigest()[:24], "type": "sgv", "sgv": r.sgv,
            "date": r.t * 1000, "dateString": iso(r.t), "sysTime": iso(r.t), "direction": d, "trend": TREND[d],
            "delta": delta, "device": r.dev or "cgmnostr", "noise": 1, "filtered": r.sgv * 1000,
            "unfiltered": r.sgv * 1000, "rssi": 100, "utcOffset": 0, "mills": r.t * 1000}


def entries_json(rows: list[Reading], src_hex: str):
    """rows newest first; each entry gets the following (older) row for delta fallback."""
    return [entry(r, src_hex, rows[i + 1] if i + 1 < len(rows) else None) for i, r in enumerate(rows)]


def make_app(store: Store, st: Stream, api_secret: str | None):
    src_hex = st.source_pub.to_hex()
    secret_sha1 = hashlib.sha1(api_secret.encode()).hexdigest() if api_secret else None

    @web.middleware
    async def auth(request, handler):
        log(f"HTTP {request.method} {request.path_qs} ua={request.headers.get('User-Agent', '')[:40]}")
        if api_secret and request.path != "/api/v3/version":
            h = request.headers.get("api-secret", "").removeprefix("token=")
            ok = (h.lower() in (secret_sha1, api_secret)
                  or request.query.get("token") == api_secret or request.query.get("secret") == secret_sha1
                  or request.headers.get("Authorization", "") == f"Bearer {api_secret}")
            if not ok:
                return web.json_response({"status": 401, "message": "Unauthorized"}, status=401)
        return await handler(request)

    def find(q, key):
        """find[date][$gt]=ms or find[dateString][$gte]=ISO → ms"""
        for field in ("date", "dateString"):
            v = q.get(f"find[{field}][{key}]")
            if v:
                try:
                    return int(float(v))
                except ValueError:
                    return int(datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000)
        return None

    async def entries(request):
        q = request.query
        if q.get("find[type][$eq]", q.get("find[type]", "sgv")) != "sgv" or q.get("find[type][$ne]") == "sgv":
            return web.json_response([])            # we only have sgv entries
        count = int(q.get("count", 10))
        rows = store.query(count + 1, find(q, "$gt"), find(q, "$gte"), find(q, "$lt"), find(q, "$lte"))
        return web.json_response(entries_json(rows, src_hex)[:count])

    async def current(request):
        return web.json_response(entries_json(store.query(2), src_hex)[:1])

    async def properties(request):
        rows = store.query(2)
        if not rows:
            return web.json_response({})
        e = entries_json(rows, src_hex)[0]
        prev = rows[1].sgv if len(rows) > 1 else rows[0].sgv
        return web.json_response({"delta": {"absolute": e["delta"], "elapsedMins": 5, "interpolated": False,
                                            "mean5MinsAgo": prev, "mgdl": e["delta"], "scaled": e["delta"],
                                            "display": f"{e['delta']:+.0f}"}})

    async def sgv_brief(request):   # xDrip/Juggluco local-server style (Garmin via 127.0.0.1, nightscout-clock)
        count = int(request.query.get("count", 24))
        out = [{k: e[k] for k in ("_id", "date", "dateString", "sgv", "delta", "direction", "noise")}
               for e in entries_json(store.query(count + 1), src_hex)[:count]]
        if out:
            out[0]["units_hint"] = "mgdl"
        return web.json_response(out)

    async def status(request):
        now = time.time()
        return web.json_response({
            "status": "ok", "name": "cgmnostr", "version": "15.0.8", "serverTime": iso(int(now)),
            "serverTimeEpoch": int(now * 1000), "apiEnabled": True, "careportalEnabled": False,
            "boluscalcEnabled": False, "head": "cgmnostr-poc3",
            "settings": {"units": "mg/dl", "timeFormat": 24, "customTitle": "CGM over Nostr", "theme": "default",
                         "alarmTypes": ["predict"], "language": "en", "showPlugins": "", "enable": ["careportal"],
                         "thresholds": {"bgHigh": 185, "bgTargetTop": 145, "bgTargetBottom": 65, "bgLow": 55},
                         "DEFAULT_FEATURES": [], "authDefaultRoles": "readable"},
            "extendedSettings": {"devicestatus": {"advanced": True}}, "authorized": None,
        })

    async def empty(request):
        return web.json_response([])

    async def verifyauth(request):
        return web.json_response({"status": 200, "message": {"canRead": True, "canWrite": False,
                                                             "isAdmin": False, "message": "OK", "rolefound": "FOUND"}})

    async def pebble(request):
        rs = store.query(2)
        bgs = [{"sgv": str(r.sgv), "trend": 0, "direction": direction(r.rate), "datetime": r.t * 1000,
                "bgdelta": "0" if r.rate is None else f"{r.rate * 5:.1f}"} for r in rs[:1]]
        return web.json_response({"status": [{"now": int(time.time() * 1000)}], "bgs": bgs, "cals": []})

    app = web.Application(middlewares=[auth])
    r = app.router
    for p in ("/api/v1/entries", "/api/v1/entries.json", "/api/v1/entries/sgv", "/api/v1/entries/sgv.json"):
        r.add_get(p, entries)
    for p in ("/api/v1/entries/current", "/api/v1/entries/current.json"):
        r.add_get(p, current)
    for p in ("/api/v1/status", "/api/v1/status.json"):
        r.add_get(p, status)
    for p in ("/api/v1/treatments", "/api/v1/treatments.json", "/api/v1/devicestatus", "/api/v1/devicestatus.json",
              "/api/v1/profile", "/api/v1/profile.json", "/api/v1/profiles", "/api/v1/profiles.json",
              "/api/v1/food.json"):
        r.add_get(p, empty)
    r.add_get("/api/v1/verifyauth", verifyauth)
    r.add_get("/api/v2/properties", properties)
    r.add_get("/api/v2/properties/{names}", properties)
    r.add_get("/sgv.json", sgv_brief)
    r.add_get("/pebble", pebble)
    # anything else (notably /api/v3/*) → aiohttp's 404, which makes Nightguard fall back to v1
    return app


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--link")
    ap.add_argument("--link-file")
    ap.add_argument("--db", default="follower.db")
    ap.add_argument("--port", type=int, default=1337)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--hours", type=int, default=12)
    ap.add_argument("--api-secret")
    a = ap.parse_args()
    st = Stream.from_link(a.link or open(a.link_file).read().strip())
    store = Store(a.db)
    runner = web.AppRunner(make_app(store, st, a.api_secret))
    await runner.setup()
    await web.TCPSite(runner, a.host, a.port).start()
    log(f"Nightscout API on http://{a.host}:{a.port} — following {st.source_pub.to_bech32()[:20]}… on {len(st.relays)} relays")
    await Follower(st, store, a.hours).run()


if __name__ == "__main__":
    asyncio.run(main())

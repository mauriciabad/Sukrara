# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Hit a Nightscout(-compatible) server the way real ecosystem clients do and check the fields they need.

  uv run api_check.py http://127.0.0.1:1337 [api-secret]
Request shapes and required fields come from reading each client's source (see docs/03-nostr.md).
"""
import hashlib, json, sys, time, urllib.error, urllib.request
from urllib.parse import urlencode

base = sys.argv[1].rstrip("/")
headers = {"api-secret": hashlib.sha1(sys.argv[2].encode()).hexdigest()} if len(sys.argv) > 2 else {}
fails = 0


def get(path, **params):
    url = base + path + ("?" + urlencode(params) if params else "")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


def check(name, cond, detail=""):
    global fails
    fails += not cond
    print(f"{'✅' if cond else '❌'} {name} {detail}")


now_ms = int(time.time() * 1000)
s, st = get("/api/v1/status.json")
check("status.json (HA, Nightguard, LoopFollow)", s == 200 and isinstance(st.get("settings"), dict)
      and "units" in st["settings"] and not str(st.get("version", "")).startswith(("0.6", "0.7", "0.8")),
      f"name={st.get('name') if st else None} version={st.get('version') if st else None}")

s, e = get("/api/v1/entries/sgv.json")              # HA / py-nightscout: no params, uses [0]
ok = s == 200 and e and all(k in e[0] for k in ("sgv", "delta", "direction", "device", "dateString", "date"))
check("entries/sgv.json (Home Assistant)", ok and e[0]["delta"] is not None,
      f"n={len(e or [])} newest={e[0]['sgv'] if e else None} {e[0]['direction'] if e else ''} delta={e[0]['delta'] if e else ''}")
check("  newest first", ok and all(e[i]["date"] >= e[i + 1]["date"] for i in range(len(e) - 1)))
check("  date is int ms (M5)", ok and isinstance(e[0]["date"], int) and e[0]["date"] > 1e12)
check("  trend int matches direction (nightscout-clock)", ok and isinstance(e[0].get("trend"), int))
check("  _id is 24 hex", ok and len(e[0]["_id"]) == 24)
age = (now_ms - e[0]["date"]) / 60000 if ok else 1e9
check("  newest reading fresh (< 3 min)", age < 3, f"age={age:.1f} min")

s, e = get("/api/v1/entries.json", **{"count": 2880, "find[date][$gt]": now_ms - 3600_000})   # xDrip+ follower
check("entries.json incremental (xDrip+)", s == 200 and e is not None and all(x["date"] > now_ms - 3600_000 for x in e),
      f"n={len(e or [])} in last hour")
s, e = get("/api/v1/entries.json", **{"count": 1440, "find[date][$gte]": now_ms - 12 * 3600_000,
                                         "find[date][$lt]": now_ms})                          # Nightguard history
check("entries.json 12 h window (Nightguard)", s == 200 and e is not None, f"n={len(e or [])}")
s, e = get("/api/v1/entries.json", **{"count": 10, "find[type][$ne]": "cal"})                 # LoopFollow
check("entries.json find[type][$ne]=cal (LoopFollow)", s == 200 and bool(e), f"n={len(e or [])}")
s, e = get("/api/v1/entries/current.json")
check("entries/current.json is a 1-element array", s == 200 and isinstance(e, list) and len(e) == 1)
for p in ("/api/v1/treatments.json", "/api/v1/devicestatus.json", "/api/v1/profiles.json"):
    s, e = get(p, count=1)
    check(f"{p} → []", s == 200 and e == [])
s, e = get("/api/v2/properties/iob,cob,delta")                                                # M5 NightscoutMon
check("v2/properties delta (M5, Nightguard)", s == 200 and "mgdl" in (e or {}).get("delta", {}))
s, _ = get("/api/v3/version")
check("v3/version → 404 (Nightguard falls back to v1)", s == 404, f"got {s}")
s, e = get("/sgv.json", brief_mode="Y", count=3)                                               # Garmin local / clock
check("/sgv.json brief (Garmin via 127.0.0.1, nightscout-clock)", s == 200 and bool(e) and "units_hint" in e[0])
print(f"\n{'all checks passed' if not fails else f'{fails} check(s) failed'}")
sys.exit(1 if fails else 0)

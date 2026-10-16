# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare a follower's database with the owner's (both pulled from the emulators, readings only — no keys).

  python3 -I compare_dbs.py owner.db follower.db [--hours 12]

Prints how many readings the follower has, how many match the owner exactly (sgv and rate), the `via` relays, the
epochs, and any mismatch. Also checks every row against the deterministic synthetic source (v2/nodes.py: synthetic).
"""
import argparse, math, sqlite3, time
from collections import Counter


def synthetic(t):
    return int(130 + 60 * math.sin(t / 5400)), round(60 * math.cos(t / 5400) / 90, 2)


ap = argparse.ArgumentParser()
ap.add_argument("owner")
ap.add_argument("follower")
ap.add_argument("--hours", type=float, default=12)
a = ap.parse_args()
since = int(time.time() - a.hours * 3600)

o = {t: (s, r) for t, s, r in sqlite3.connect(a.owner).execute("select t, sgv, rate from readings where t >= ?", (since,))}
f = list(sqlite3.connect(a.follower).execute(
    "select t, sgv, rate, epoch, via from readings where t >= ? order by t", (since,)))
match = sum(1 for t, s, r, *_ in f if t in o and o[t][0] == s and (o[t][1] is None) == (r is None)
            and (r is None or abs(o[t][1] - r) < 0.006))
missing = sorted(set(o) - {row[0] for row in f})
extra = [row for row in f if row[0] not in o]
synth_ok = sum(1 for t, s, r, *_ in f if synthetic(t)[0] == s)
print(f"window: last {a.hours} h")
print(f"owner readings: {len(o)}   follower readings: {len(f)}")
print(f"identical (t, sgv, rate): {match}/{len(f)}   follower rows not on owner: {len(extra)}")
print(f"owner readings the follower lacks: {len(missing)}" + (f" (newest missing t={missing[-1]}, {int(time.time()) - missing[-1]} s ago)" if missing else ""))
print(f"rows equal to the synthetic function: {synth_ok}/{len(f)}")
print("via:", dict(Counter(row[4] for row in f)))
print("epochs:", dict(Counter(row[3] for row in f)))
if f:
    print(f"span: {time.strftime('%H:%M', time.localtime(f[0][0]))} – {time.strftime('%H:%M', time.localtime(f[-1][0]))} "
          f"({(f[-1][0] - f[0][0]) / 3600:.1f} h), newest {int(time.time()) - f[-1][0]} s old")
for row in f[-3:]:
    print("  last:", row, "owner:", o.get(row[0]))

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Wire size of each event type and the resulting daily volume (uv run sizes.py [relays])."""
import json, sys, time

from cgmnostr import Reading, hour_of, new_stream

relays = int(sys.argv[1]) if len(sys.argv) > 1 else 4
st = new_stream()
now = int(time.time())
live = len(json.dumps(["EVENT", st.live_event(Reading(now, 123, -0.42, "juggluco"))]))
h = hour_of(now)
chunk = len(json.dumps(["EVENT", st.chunk_event(h, [Reading(h + 60 * i, 100 + i, -0.12, "juggluco") for i in range(60)])]))
live_day = 1440 * live
chunk_day = 288 * chunk          # republished every 5 min
stored = 24 * 7 * chunk          # 7-day expiry
print(f"live event  {live} B on the wire")
print(f"hour chunk  {chunk} B (60 readings)")
print(f"upload/day per relay: live {live_day/1e6:.2f} MB + chunks {chunk_day/1e6:.2f} MB = {(live_day+chunk_day)/1e6:.2f} MB")
print(f"upload/day with {relays} relays: {(live_day+chunk_day)*relays/1e6:.1f} MB  (~{(live_day+chunk_day)*relays*30/1e6:.0f} MB/month)")
print(f"follower download/day (live only, 1 relay delivering first): {live_day/1e6:.2f} MB")
print(f"stored on each relay at steady state: {stored/1e3:.0f} KB (24 chunks/day × 7 days) + live events ≤1 h")

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Minimal share round trip with diagnostics: uv run v2/debug_share.py"""
import asyncio, json, tempfile, time, traceback
from pathlib import Path

from cgm2 import FollowerState, OwnerState, keys   # (adds ../ to sys.path)
import cgmnostr
from nodes import FollowerNode, OwnerNode

# surface callback exceptions instead of letting them silently drop the relay connection
_orig = cgmnostr.Relay._reader


async def _reader(self, ws):
    try:
        await _orig(self, ws)
    except Exception:
        traceback.print_exc()
        raise
cgmnostr.Relay._reader = _reader


async def main():
    tmp = Path(tempfile.mkdtemp(prefix="cgm2dbg-"))
    o = OwnerNode(OwnerState.create(), tmp / "o.json")
    o.start()
    A = FollowerNode(FollowerState(keys(), "", []), tmp / "a.json", tmp / "a.db", "A")
    A.start()
    await asyncio.sleep(3)
    await A.request_access(o.invite(), "Mom")
    for _ in range(40):
        await asyncio.sleep(1)
        if A.st.status == "active":
            break
    print("A status", A.st.status, "owner events", o.events)
    print("owner relays", json.dumps(o.pool.stats(), indent=0))
    print("A relays", json.dumps(A.pool.stats(), indent=0))


asyncio.run(main())

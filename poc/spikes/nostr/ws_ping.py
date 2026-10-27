# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Connectivity check for one relay with a chosen User-Agent: uv run ws_ping.py wss://relay [user-agent]"""
import asyncio, json, sys
import websockets


async def main(url, ua):
    try:
        async with websockets.connect(url, open_timeout=8, user_agent_header=ua) as ws:
            await ws.send(json.dumps(["REQ", "x", {"kinds": [1], "limit": 1}]))
            print(f"{url} [{ua[:24]}] -> {(await asyncio.wait_for(ws.recv(), 8))[:60]}")
    except Exception as e:
        print(f"{url} [{ua[:24]}] -> {type(e).__name__}: {str(e)[:80]}")


asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "Python websockets"))

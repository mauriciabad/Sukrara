# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Does a relay keep taking events from one new key? A wearer's first start publishes ~25 hour chunks at once.

    uv run identity/relay_burst.py [n] [wss://relay …]     (from spikes/nostr; throwaway keys)"""
import asyncio, json, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nostr_sdk as n                                    # noqa: E402

N = int(sys.argv[1]) if len(sys.argv) > 1 else 30
RELAYS = sys.argv[2:] or ["wss://offchain.pub", "wss://nostr.oxtr.dev", "wss://nostr-pub.wellorder.net",
                          "wss://nostr.bitcoiner.social", "wss://relay.snort.social", "wss://nos.lol", "wss://nostr.mom"]


async def burst(url: str) -> str:
    import websockets
    d = n.Keys.generate()
    now = int(time.time())
    evs = [json.loads(n.EventBuilder(n.Kind(30078), "x" * 300).tags([n.Tag.identifier(f"probe{i}"),
           n.Tag.expiration(n.Timestamp.from_secs(now + 3600))]).finalize(d).as_json()) for i in range(N)]
    ok, rejects = 0, []
    try:
        async with websockets.connect(url, open_timeout=8) as ws:
            for e in evs:
                await ws.send(json.dumps(["EVENT", e]))
            end = time.time() + 15
            while time.time() < end and ok + len(rejects) < N:
                m = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                if m[0] == "OK":
                    if m[2]: ok += 1
                    else: rejects.append(m[3])
    except Exception as e:                                # noqa: BLE001
        rejects.append(f"{type(e).__name__}")
    return f"{url:32} {ok}/{N} accepted" + (f" · first reject: {rejects[0][:70]} (×{len(rejects)})" if rejects else "")


async def main():
    for line in await asyncio.gather(*[burst(u) for u in RELAYS]):
        print(line)

asyncio.run(main())

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Negative tests against a CGM Share follower (investigation 06). Run from spikes/nostr:

  uv run v2-tests/attack.py forge-delivery --to <follower npub/hex>
      An attacker key seals a fake *key delivery* (fake read key, fake device) to the follower, then publishes
      live readings (sgv 333) from the fake device under the fake key. A correct follower ignores the delivery
      (seal not signed by the identity I) and never shows 333.

  uv run v2-tests/attack.py forge-data --state secrets/<python follower dir>/follower.json
      An *insider* that knows the current read key G (an approved — or later revoked — follower) publishes
      (1) live readings (sgv 399) signed by a key that is NOT in the roster, encrypted to G with the right gk,
      (2) an hour chunk of 399s from that key, and (3) a forged roster listing that key, signed by the key (not I).
      A correct follower rejects all three ("author not in roster" / "roster not signed by identity").

Secrets are read from the state file and never printed.
"""
import argparse, asyncio, json, sys, time
from pathlib import Path

import websockets

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "v2"))
import nostr_sdk as n                                                    # noqa: E402
from cgm2 import (RELAYS, RUMOR_TO_FOLLOWER, Reading, chunk_event, gift, gk, hour_of, keys,  # noqa: E402
                  live_event, pub, roster_event)


async def publish(relays, evs):
    async def one(url):
        res = []
        try:
            async with websockets.connect(url, open_timeout=8) as ws:
                for ev in evs:
                    await ws.send(json.dumps(["EVENT", ev]))
                    while True:
                        m = json.loads(await asyncio.wait_for(ws.recv(), 8))
                        if m[0] == "OK" and m[1] == ev["id"]:
                            res.append(m[2])
                            break
                    await asyncio.sleep(2.1)          # pacing, like the apps
        except Exception as e:
            res.append(f"error {e.__class__.__name__}")
        return url, res
    for url, res in await asyncio.gather(*(one(u) for u in relays)):
        print(f"  {url}: {res}", flush=True)


def hexkey(s: str) -> str:
    return n.PublicKey.parse(s).to_hex()


async def forge_delivery(a):
    attacker, fake_g, fake_dev = keys(), keys(), keys()
    f = hexkey(a.to)
    body = {"v": 2, "type": "key", "sid": "00" * 32, "epoch": 7, "g": fake_g.secret_key().to_bech32(),
            "devices": [pub(fake_dev)], "relays": RELAYS}
    print(f"attacker {pub(attacker)[:12]} → forged key delivery to {f[:12]} (fake device {pub(fake_dev)[:12]}, gk {gk(pub(fake_g))})")
    await publish(RELAYS, [gift(attacker, f, RUMOR_TO_FOLLOWER, body)])
    for i in range(a.minutes):
        t = int(time.time())
        print(f"forged live sgv 333 t={t} from fake device")
        await publish(RELAYS, [live_event(fake_dev, pub(fake_g), Reading(t, 333, 0.0, "forged"))])
        await asyncio.sleep(60)


async def forge_data(a):
    st = json.loads(Path(a.state).read_text())
    sid = st["sid"]
    newest = max(st["epochs"], key=lambda e: e["n"])
    g = keys(newest["g"])
    x = keys()
    print(f"insider knows epoch {newest['n']} (gk {gk(pub(g))}); forging from non-roster key {pub(x)[:12]}")
    for i in range(a.minutes):
        t = int(time.time())
        evs = [live_event(x, pub(g), Reading(t, 399, 0.0, "forged"))]
        if i == 0:
            h = hour_of(t)
            evs.append(chunk_event(x, pub(g), sid, h, [Reading(tt, 399, 0.0, "forged") for tt in range(h, t, 60)]))
            evs.append(roster_event(x, pub(g), sid, newest["n"] + 1, [pub(x)], [], RELAYS))
        print(f"forged: {', '.join({21078: 'live 399', 30078: 'chunk/roster'}[e['kind']] for e in evs)}", flush=True)
        await publish(RELAYS, evs)
        await asyncio.sleep(60)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["forge-delivery", "forge-data"])
    ap.add_argument("--to")
    ap.add_argument("--state")
    ap.add_argument("--minutes", type=int, default=3)
    a = ap.parse_args()
    asyncio.run(forge_delivery(a) if a.mode == "forge-delivery" else forge_data(a))

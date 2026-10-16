# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Which relays accept NIP-59 gift wraps, and serve them back with / without NIP-42 AUTH? uv run v2/probe_giftwrap.py"""
import asyncio, json, secrets, sys, time

import nostr_sdk as n
import websockets

from cgm2 import RELAYS, RUMOR_TO_OWNER, gift, keys, pub

EXTRA = ["wss://relay.damus.io", "wss://offchain.pub", "wss://nostr.oxtr.dev", "wss://relay.snort.social",
         "wss://nostr-pub.wellorder.net", "wss://auth.nostr1.com", "wss://inbox.nostr.wine"]


def auth_event(k: n.Keys, url: str, challenge: str) -> dict:
    tags = [n.Tag.parse(["relay", url]), n.Tag.parse(["challenge", challenge])]
    return json.loads(n.EventBuilder(n.Kind(22242), "").tags(tags).finalize(k).as_json())


async def probe(url, sender, recipient):
    r = {"relay": url}
    try:
        ev = gift(sender, pub(recipient), RUMOR_TO_OWNER, {"v": 2, "type": "probe"})
        async with websockets.connect(url, open_timeout=8) as ws:
            await ws.send(json.dumps(["EVENT", ev]))
            end, challenge = time.time() + 8, None
            while time.time() < end:
                m = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                if m[0] == "AUTH":
                    challenge = m[1]
                if m[0] == "OK" and m[1] == ev["id"]:
                    r["publish"] = "ok" if m[2] else m[3][:50]
                    break
        for mode in ("noauth", "auth"):
            async with websockets.connect(url, open_timeout=8) as ws:
                sid = secrets.token_hex(3)
                flt = {"kinds": [1059], "#p": [pub(recipient)], "since": int(time.time()) - 3 * 86400}
                await ws.send(json.dumps(["REQ", sid, flt]))
                end, got, authed = time.time() + 8, 0, False
                while time.time() < end:
                    m = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                    if m[0] == "AUTH" and mode == "auth" and not authed:
                        await ws.send(json.dumps(["AUTH", auth_event(recipient, url, m[1])]))
                        authed = True
                    elif m[0] == "OK" and authed:
                        await ws.send(json.dumps(["REQ", sid, flt]))      # retry after auth
                    elif m[0] == "EVENT" and m[1] == sid and m[2]["id"] == ev["id"]:
                        got = 1
                    elif m[0] == "CLOSED" and m[1] == sid:
                        r[f"read_{mode}_closed"] = m[2][:40]
                        if mode == "noauth" or authed:
                            break
                    elif m[0] == "EOSE" and m[1] == sid and (mode == "noauth" or authed or got):
                        break
                r[f"read_{mode}"] = got
    except Exception as e:
        r["error"] = f"{type(e).__name__}"
    return r


async def main():
    sender, recipient = keys(), keys()
    urls = sys.argv[1:] or RELAYS + EXTRA
    for r in await asyncio.gather(*(probe(u, sender, recipient) for u in urls)):
        print(json.dumps(r))


asyncio.run(main())

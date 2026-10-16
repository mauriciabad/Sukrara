# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Which public relays take a gift wrap from a brand-new key AND serve it back to its recipient? Throwaway keys.

    uv run identity/relay_probe.py [wss://relay …]     (from spikes/nostr)

Every Sukrara control message (invite, request, key delivery) is a NIP-59 gift wrap signed by a one-time key, so a
relay that only accepts known keys ("web of trust") or can't serve wraps (NIP-42 AUTH broken) is no use."""
import asyncio, json, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ident as I                                        # noqa: E402
import nostr_sdk as n                                    # noqa: E402

CANDIDATES = sys.argv[1:] or ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://offchain.pub",
    "wss://relay.snort.social", "wss://nostr.oxtr.dev", "wss://relay.damus.io", "wss://relay.nostr.net",
    "wss://nostr-pub.wellorder.net", "wss://nostr.bitcoiner.social", "wss://nostr21.com", "wss://nostr.einundzwanzig.space"]


async def probe(url: str) -> str:
    sender, k = n.Keys.generate(), n.Keys.generate()
    rumor = n.EventBuilder(n.Kind(30502), json.dumps({"v": 3, "type": "probe"})).finalize_unsigned(sender.public_key())
    wrap = json.loads(n.nip59_make_gift_wrap(sender, k.public_key(), rumor, None,
                                             [n.Tag.expiration(n.Timestamp.from_secs(int(time.time()) + 3600))]).as_json())
    try:
        ok = await I.relay_publish(url, wrap, None)
    except Exception as e:                                # noqa: BLE001
        return f"{url:40} publish error {e!r:.60}"
    try:
        got = await I.relay_query(url, {"kinds": [1059], "#p": [k.public_key().to_hex()]}, k)
    except Exception as e:                                # noqa: BLE001
        got = f"query error {e!r:.60}"
    found = isinstance(got, list) and any(e.get("id") == wrap["id"] for e in got)
    # data events from a new key: an hour chunk (30078, addressable) and a live reading (21078, ephemeral)
    d = n.Keys.generate()
    exp = n.Tag.expiration(n.Timestamp.from_secs(int(time.time()) + 3600))
    chunk = json.loads(n.EventBuilder(n.Kind(30078), "x" * 120).tags([n.Tag.identifier(f"probe{time.time()}"), exp])
                       .finalize(d).as_json())
    live = json.loads(n.EventBuilder(n.Kind(21078), "x" * 120).tags([exp]).finalize(d).as_json())
    c_ok, l_ok = await I.relay_publish(url, chunk, None), await I.relay_publish(url, live, None)
    return (f"{url:32} wrap {str(ok)[:40]:42} served back: {'YES' if found else 'no '} · chunk {str(c_ok)[:50]:52} "
            f"· live {str(l_ok)[:50]}")


async def main():
    for line in await asyncio.gather(*[probe(u) for u in CANDIDATES]):
        print(line)

asyncio.run(main())

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Probe public Nostr relays for what a CGM stream needs (PoC 3).

Per relay:
  - NIP-11 info (limits, auth/payment requirements)
  - live:   ephemeral event (kind 21078, NIP-44 encrypted ~200 B) → accepted? delivered to a live subscriber? latency
  - chunk:  addressable event (kind 30078, ~1.4 KB, NIP-40 expiration) → accepted? stored? replaced on republish?
  - burst:  10 ephemeral events in ~2 s (a reconnect backlog) → how many accepted
  - expiry: (with --expiry-wait) addressable event expiring in 60 s → still served after it expired?

All test events are signed by a throwaway key, NIP-44 encrypted, and carry an expiration tag.
Usage: uv run relay_survey.py [--expiry-wait] [relay ...]  →  JSON lines on stdout + summary table on stderr
"""
import asyncio, json, os, sys, time, secrets
import urllib.request
import nostr_sdk as n
import websockets

DEFAULT_RELAYS = [
    "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.nostr.band",
    "wss://relay.snort.social", "wss://nostr.mom", "wss://offchain.pub", "wss://relay.nostr.bg",
    "wss://nostr.oxtr.dev", "wss://nostr.wine", "wss://relay.nostr.net", "wss://nostr-pub.wellorder.net",
]
LIVE_KIND, CHUNK_KIND = 21078, 30078

author = n.Keys.generate()
group = n.Keys.generate()


def enc(obj) -> str:
    return n.nip44_encrypt(author.secret_key(), group.public_key(), json.dumps(obj, separators=(",", ":")), n.Nip44Version.V2)


def event(kind, content, tags=()):
    return json.loads(n.EventBuilder(n.Kind(kind), content).tags(list(tags)).finalize(author).as_json())


def expiration(secs):
    return n.Tag.expiration(n.Timestamp.from_secs(int(time.time()) + secs))


def live_event():
    return event(LIVE_KIND, enc({"v": 1, "t": int(time.time()), "sgv": 120, "rate": -0.42}), [expiration(3600)])


def chunk_event(d, readings=60, exp=3600):
    t0 = int(time.time()) // 3600 * 3600
    body = {"v": 1, "h": t0, "r": [[i * 60, 100 + i, -12 + i] for i in range(readings)]}
    return event(CHUNK_KIND, enc(body), [n.Tag.identifier(d), expiration(exp)])


def nip11(url):
    try:
        req = urllib.request.Request(url.replace("wss://", "https://"), headers={"Accept": "application/nostr+json"})
        with urllib.request.urlopen(req, timeout=6) as r:
            info = json.load(r)
        lim = info.get("limitation", {}) or {}
        return {"software": (info.get("software") or "").split("/")[-1], "version": info.get("version"),
                "nips_40": 40 in (info.get("supported_nips") or []),
                **{k: lim.get(k) for k in ("max_message_length", "max_content_length", "auth_required",
                                           "payment_required", "restricted_writes", "created_at_lower_limit")}}
    except Exception as e:
        return {"error": f"nip11: {type(e).__name__}"}


async def send(ws, ev, timeout=8):
    """Publish and wait for the relay's OK. Returns (accepted, message)."""
    await ws.send(json.dumps(["EVENT", ev]))
    end = time.time() + timeout
    while time.time() < end:
        msg = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
        if msg[0] == "OK" and msg[1] == ev["id"]:
            return bool(msg[2]), msg[3]
        if msg[0] == "NOTICE":
            last_notice = msg[1]
    return False, "no OK"


async def query(url, flt, timeout=8):
    """One-shot REQ until EOSE; returns events."""
    out = []
    async with websockets.connect(url, open_timeout=8, max_size=2**22) as ws:
        sub = secrets.token_hex(4)
        await ws.send(json.dumps(["REQ", sub, flt]))
        end = time.time() + timeout
        while time.time() < end:
            msg = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
            if msg[0] == "EVENT" and msg[1] == sub:
                out.append(msg[2])
            elif msg[0] in ("EOSE", "CLOSED") and msg[1] == sub:
                break
    return out


async def probe(url):
    r = {"relay": url, **nip11(url)}
    pk = author.public_key().to_hex()
    try:
        async with websockets.connect(url, open_timeout=8, max_size=2**22) as sub_ws, \
                   websockets.connect(url, open_timeout=8, max_size=2**22) as pub_ws:
            # live subscriber first
            await sub_ws.send(json.dumps(["REQ", "live", {"authors": [pk], "kinds": [LIVE_KIND], "since": int(time.time()) - 5}]))
            ev = live_event()
            t_send = time.time()
            r["live_ok"], r["live_msg"] = await send(pub_ws, ev)
            r["live_delivered_s"] = None
            end = time.time() + 6
            try:
                while time.time() < end:
                    msg = json.loads(await asyncio.wait_for(sub_ws.recv(), end - time.time()))
                    if msg[0] == "EVENT" and msg[2]["id"] == ev["id"]:
                        r["live_delivered_s"] = round(time.time() - t_send, 3)
                        break
            except asyncio.TimeoutError:
                pass

            # addressable chunk: publish, republish (replace), then query
            d = "survey-" + secrets.token_hex(3)
            c1 = chunk_event(d, readings=59)
            r["chunk_ok"], r["chunk_msg"] = await send(pub_ws, c1)
            await asyncio.sleep(1.1)  # created_at must increase for replacement
            c2 = chunk_event(d, readings=60)
            r["chunk_bytes"] = len(json.dumps(["EVENT", c2]))
            r["replace_ok"], r["replace_msg"] = await send(pub_ws, c2)

            # burst of 10 live events
            ok = 0
            msgs = set()
            for _ in range(10):
                a, m = await send(pub_ws, live_event(), timeout=5)
                ok += a
                if not a:
                    msgs.add(m[:60])
                await asyncio.sleep(0.2)
            r["burst_ok"], r["burst_msgs"] = ok, sorted(msgs)
        await asyncio.sleep(1)
        stored = await query(url, {"authors": [pk], "kinds": [CHUNK_KIND], "#d": [d]})
        r["chunk_stored"] = len(stored)
        r["chunk_replaced"] = bool(stored) and all(e["id"] == c2["id"] for e in stored)
        stored_live = await query(url, {"authors": [pk], "kinds": [LIVE_KIND]})
        r["ephemeral_stored"] = len(stored_live)   # should be 0
    except Exception as e:
        r["error"] = f"{type(e).__name__}: {str(e)[:120]}"
    return r


async def expiry_probe(urls, wait=75):
    """Publish a chunk expiring in 30 s to each relay, wait, check whether it is still served."""
    pk = author.public_key().to_hex()
    sent = {}
    for url in urls:
        try:
            async with websockets.connect(url, open_timeout=8) as ws:
                ev = chunk_event("expiry-" + secrets.token_hex(3), readings=5, exp=30)
                ok, msg = await send(ws, ev)
                if ok:
                    sent[url] = ev
        except Exception:
            pass
    print(f"expiry: published to {len(sent)} relays, waiting {wait}s…", file=sys.stderr)
    await asyncio.sleep(wait)
    out = {}
    for url, ev in sent.items():
        try:
            got = await query(url, {"ids": [ev["id"]]})
            out[url] = "served after expiry" if got else "gone (NIP-40 honored)"
        except Exception as e:
            out[url] = f"query error {type(e).__name__}"
    return out


async def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    urls = args or DEFAULT_RELAYS
    results = await asyncio.gather(*(asyncio.wait_for(probe(u), 90) for u in urls), return_exceptions=True)
    rows = []
    for u, r in zip(urls, results):
        if isinstance(r, Exception):
            r = {"relay": u, "error": f"{type(r).__name__}"}
        rows.append(r)
        print(json.dumps(r))
    if "--expiry-wait" in sys.argv:
        exp = await expiry_probe([r["relay"] for r in rows if r.get("chunk_ok")])
        for r in rows:
            r["expiry"] = exp.get(r["relay"], "-")
            print(json.dumps({"relay": r["relay"], "expiry": r["expiry"]}))
    print(f"\n{'relay':32} {'live':5} {'deliv':6} {'chunk':5} {'stored':6} {'repl':5} {'burst':5} {'eph.st':6} expiry / error", file=sys.stderr)
    for r in rows:
        print(f"{r['relay']:32} {str(r.get('live_ok','-')):5} {str(r.get('live_delivered_s','-')):6} "
              f"{str(r.get('chunk_ok','-')):5} {str(r.get('chunk_stored','-')):6} {str(r.get('chunk_replaced','-')):5} "
              f"{str(r.get('burst_ok','-')):5} {str(r.get('ephemeral_stored','-')):6} "
              f"{r.get('expiry','')} {r.get('error','')} {'' if r.get('live_ok', True) else r.get('live_msg','')[:50]}"
              f"{'' if r.get('chunk_ok', True) else ' chunk: ' + r.get('chunk_msg','')[:50]}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())

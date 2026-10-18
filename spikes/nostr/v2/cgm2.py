# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""CGM-over-Nostr protocol v2 primitives (see ../PROTOCOL-v2.md): keys, tags, event builders, gift wraps.

The v2 CLI owner and follower built on these (and their removed `cgmshare:join?` links) are gone: the independent
implementation is spec/reference (OGS), and the identity spike, the attack harness and the OGS interop harness
(spec/reference/interop/sukrara_side.py) still use these primitives. Relay I/O reuses ../cgmnostr.Pool.
"""
from __future__ import annotations

import hashlib, json, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import nostr_sdk as n                                    # noqa: E402
from cgmnostr import Pool, Reading, hour_of, log         # noqa: E402,F401

LIVE, CHUNK, WRAP = 21078, 30078, 1059
RUMOR_TO_OWNER, RUMOR_TO_FOLLOWER = 30500, 30501
# Same set as Android Proto.RELAYS / Mac V2.relays (relay.nostr.net can't serve gift wraps; measured 2026-10-17)
RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social"]
DAY = 86400


def H(x: str) -> str:
    return hashlib.sha256(x.encode()).hexdigest()[:32]


def gk(g_pub_hex: str) -> str:
    return hashlib.sha256(bytes.fromhex(g_pub_hex)).hexdigest()[:8]


def keys(sec: str | None = None) -> n.Keys:
    return n.Keys.parse(sec) if sec else n.Keys.generate()


def pub(k: n.Keys) -> str:
    return k.public_key().to_hex()


def enc(sender: n.Keys, recipient_hex: str, obj) -> str:
    return n.nip44_encrypt(sender.secret_key(), n.PublicKey.parse(recipient_hex),
                           json.dumps(obj, separators=(",", ":")), n.Nip44Version.V2)


def dec(recipient: n.Keys, sender_hex: str, ct: str):
    return json.loads(n.nip44_decrypt(recipient.secret_key(), n.PublicKey.parse(sender_hex), ct))


def exp(at: int) -> n.Tag:
    return n.Tag.expiration(n.Timestamp.from_secs(int(at)))


_last_ts: dict[str, int] = {}


def signed(k: n.Keys, kind: int, content: str, tags, replace_key: str | None = None) -> dict:
    """replace_key: for addressable events, created_at must strictly increase per (author, kind, d) or relays answer
    "replaced: have newer event" when two versions land in the same second."""
    b = n.EventBuilder(n.Kind(kind), content).tags(list(tags))
    if replace_key:
        ts = max(int(time.time()), _last_ts.get(replace_key, 0) + 1)
        _last_ts[replace_key] = ts
        b = b.custom_created_at(n.Timestamp.from_secs(ts))
    return json.loads(b.finalize(k).as_json())


def tag(ev: dict, name: str) -> str | None:
    return next((t[1] for t in ev.get("tags", []) if t and t[0] == name and len(t) > 1), None)


def verify(ev: dict) -> bool:
    try:
        return n.Event.from_json(json.dumps(ev)).verify()
    except Exception:
        return False


# ---------------- events (PROTOCOL-v2 table) ----------------

def live_event(D: n.Keys, g_pub: str, r: Reading) -> dict:
    body = {"v": 2, "t": r.t, "sgv": r.sgv, "r": r.rate, "dev": r.dev}
    return signed(D, LIVE, enc(D, g_pub, body), [n.Tag.parse(["gk", gk(g_pub)]), exp(time.time() + 3600)])


def chunk_tags(sid: str, hour: int):
    return H(f"cgm2:h:{sid}:{hour}"), H(f"cgm2:glucose:{sid}")


def chunk_event(D: n.Keys, g_pub: str, sid: str, hour: int, readings: list[Reading]) -> dict:
    rows = [[r.t - hour, r.sgv, None if r.rate is None else round(r.rate * 100)]
            for r in sorted(readings, key=lambda r: r.t) if hour <= r.t < hour + 3600]
    body = {"v": 2, "h": hour, "dev": readings[-1].dev if readings else "", "r": rows}
    d, c = chunk_tags(sid, hour)
    return signed(D, CHUNK, enc(D, g_pub, body),
                  [n.Tag.identifier(d), n.Tag.parse(["c", c]), n.Tag.parse(["gk", gk(g_pub)]), exp(hour + 7 * DAY)],
                  pub(D) + d)


def roster_d(sid: str) -> str:
    return H(f"cgm2:roster:{sid}")


def roster_event(I: n.Keys, g_pub: str, sid: str, epoch: int, devices, writers, relays) -> dict:
    body = {"v": 2, "epoch": epoch, "devices": list(devices), "writers": list(writers), "relays": list(relays)}
    return signed(I, CHUNK, enc(I, g_pub, body),
                  [n.Tag.identifier(roster_d(sid)), n.Tag.parse(["gk", gk(g_pub)]), exp(time.time() + 30 * DAY)],
                  "roster" + sid)


def backup_d(i_pub: str) -> str:
    return H(f"cgm2:backup:{i_pub}")


def backup_event(I: n.Keys, state: dict) -> dict:
    return signed(I, CHUNK, enc(I, pub(I), state), [n.Tag.identifier(backup_d(pub(I))), exp(time.time() + 90 * DAY)],
                  "backup" + pub(I))


def gift(sender: n.Keys, recipient_hex: str, kind: int, obj) -> dict:
    rumor = n.EventBuilder(n.Kind(kind), json.dumps(obj, separators=(",", ":"))).finalize_unsigned(sender.public_key())
    return json.loads(n.nip59_make_gift_wrap(sender, n.PublicKey.parse(recipient_hex), rumor).as_json())


def unwrap(recipient: n.Keys, ev: dict):
    """→ (sender_hex, rumor_kind, content_obj) or None. The sender is the *seal* signer (authenticated)."""
    try:
        u = n.UnwrappedGift.from_gift_wrap(recipient, n.Event.from_json(json.dumps(ev)))
        r = u.rumor()
        return u.sender().to_hex(), r.kind().as_u16(), json.loads(r.content())
    except Exception:
        return None


# ---------------- access requests ----------------

def match_code(f_pub: str, token: str) -> str:
    return f"{int(hashlib.sha256((f_pub + token).encode()).hexdigest(), 16) % 10000:04d}"

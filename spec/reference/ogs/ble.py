# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS-BLE framing (spec/ogs-ble.md §4) and messages (§5). Transport-free: bytes in, bytes out."""
from __future__ import annotations

import json
import struct
import time

SERVICE = "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007"
RX = "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007"      # central → peripheral: write with response
TX = "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007"      # peripheral → central: notify
HEADER = 6
MAX_FRAME = 512                                  # ATT attribute value limit, whatever the MTU
MAX_MESSAGE = 256 * 1024                         # B§4.3: receivers MUST drop a bigger message
REASSEMBLY_TIMEOUT = 30.0                        # B§4.3: a buffer idle this long (s) is dropped
MAX_BUFFERS = 16                                 # B§4.3: open buffers per link


def payload_size(att_mtu: int) -> int:
    """Bytes of message per frame: min(ATT_MTU − 3, 512) − 6, at least 14 (MTU 23)."""
    return max(14, min(att_mtu - 3, MAX_FRAME) - HEADER)


def encode(msg: list) -> bytes:
    return json.dumps(msg, separators=(",", ":"), ensure_ascii=False).encode()


def split(msg_id: int, data: bytes, att_mtu: int) -> list[bytes]:
    size = payload_size(att_mtu)
    parts = [data[i:i + size] for i in range(0, len(data), size)] or [b""]
    if len(parts) > 0xFFFF:
        raise ValueError("message too large")
    return [struct.pack(">HHH", msg_id & 0xFFFF, i, len(parts)) + p for i, p in enumerate(parts)]


class Reassembler:
    """One per link direction. Frames of one message arrive in order (GATT guarantees it per characteristic);
    frames of different msgIds may interleave only if a sender breaks the one-message-at-a-time rule."""

    def __init__(self, clock=time.monotonic):
        self.buf: dict[int, tuple[int, float, list[bytes]]] = {}
        self.clock = clock

    def push(self, frame: bytes) -> list | None:
        if len(frame) < HEADER:
            return None
        mid, idx, count = struct.unpack(">HHH", frame[:HEADER])
        if count == 0 or idx >= count:
            return None
        now = self.clock()
        for k in [k for k, (_, t, _) in self.buf.items() if now - t > REASSEMBLY_TIMEOUT]:
            del self.buf[k]
        if idx == 0 or mid not in self.buf or self.buf[mid][0] != count:
            self.buf.pop(mid, None)
            if len(self.buf) >= MAX_BUFFERS:
                del self.buf[next(iter(self.buf))]
            self.buf[mid] = (count, now, [])
        cnt, _, parts = self.buf[mid]
        self.buf[mid] = (cnt, now, parts)               # idle time counts from the last frame
        if idx != len(parts):           # out of order or a gap: drop the message
            del self.buf[mid]
            return None
        parts.append(frame[HEADER:])
        if sum(map(len, parts)) > MAX_MESSAGE:
            del self.buf[mid]
            return None
        if len(parts) < cnt:
            return None
        del self.buf[mid]
        try:
            msg = json.loads(b"".join(parts).decode())
        except (UnicodeDecodeError, ValueError):
            return None
        return msg if isinstance(msg, list) and len(msg) >= 2 and isinstance(msg[0], str) else None


ID_KIND = 21080                                  # B§5: the identity proof's kind (never published)
DEVICES = ("phone", "mac")                       # B§5: what a follower device can be


def hello(nonce: str, id_sig: str | None = None) -> list:
    """B§5: a fresh 16-byte nonce (hex) per HELLO; the owner proves `sid` over it in INFO. A follower that knows `sid`
    adds `id`, its signed identity proof for this nonce."""
    body = {"v": 2, "nonce": nonce}
    if id_sig is not None:
        body["id"] = id_sig
    return ["HELLO", body]


def id_content(sid: str, nonce: str, dev: str) -> str:
    """B§5: the identity proof's content, H(NS + ":ble-id:" + sid + ":" + nonce + ":" + dev)."""
    from .model import H, NS
    return H(f"{NS}:ble-id:{sid}:{nonce}:{dev}")


def id_event(f_pub: str, sid: str, nonce: str, dev: str) -> dict:
    """B§5: the identity proof (unsigned): kind 21080 by the follower key F, created_at 0, no tags."""
    from .nostr import event_id
    ev = {"pubkey": f_pub, "created_at": 0, "kind": ID_KIND, "tags": [], "content": id_content(sid, nonce, dev)}
    ev["id"] = event_id(ev)
    return ev


def id_proof(f, sid: str, nonce: str, dev: str, aux: bytes | None = None) -> str:
    """B§5: HELLO `id` = the BIP-340 signature of the identity proof by F (64 bytes, hex)."""
    from .nostr import sign
    return sign(f, ID_KIND, id_content(sid, nonce, dev), [], 0, aux)["sig"]


def identify(id_sig: str, sid: str, nonce: str, followers) -> tuple[str, str] | None:
    """B§6 "Who's connected": the (F, dev) whose identity proof `id_sig` signs, among the roster's followers; None if
    none does. Without `sid` nobody can tell which key signed it."""
    from . import crypto as c
    for f_pub in followers:
        for dev in DEVICES:
            ev = id_event(f_pub, sid, nonce, dev)
            try:
                if c.schnorr_verify(bytes.fromhex(ev["id"]), bytes.fromhex(f_pub), bytes.fromhex(id_sig)):
                    return f_pub, dev
            except ValueError:
                return None
    return None


def info_m(sid: str, nonce: str) -> str:
    """B§5: m = H(NS + ":ble:" + sid + ":" + nonce) — only holders of `sid` can check it; a fresh nonce per HELLO makes
    sessions unlinkable and a recorded INFO useless."""
    from .model import H, NS
    return H(f"{NS}:ble:{sid}:{nonce}")


def info(sid: str, nonce: str, *, i_pub: str | None = None, relays: list[str] | None = None,
         pair: dict | None = None) -> list:
    """B§5: `nonce` + `m` always; `i`, `relays` and `pair` only while nearby pairing is open."""
    body = {"v": 2, "nonce": nonce, "m": info_m(sid, nonce)}
    if pair is not None:
        body.update({"i": i_pub, "relays": relays or [], "pair": pair})
    return ["INFO", body]


def owner_check(info_body: dict, i_pub: str | None, sid: str | None, nonces) -> str:
    """B§6 "Which owner": ours | other | unknown (our request is pending, no `sid` yet and the phone isn't pairing)."""
    i = info_body.get("i")
    if i_pub and i:
        return "ours" if i == i_pub else "other"
    if sid is None:
        return "unknown"
    n = info_body.get("nonce")
    return "ours" if n in nonces and info_body.get("m") == info_m(sid, n) else "other"


def backlog(events: list[dict], now: int) -> list[dict]:
    """B§6: the owner never re-sends a cached event whose NIP-40 expiration has passed."""
    from .nostr import expired
    return [e for e in events if not expired(e, now)]

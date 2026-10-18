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
MAX_MESSAGE = 256 * 1024                         # B§4.3 (proposal P13: receivers MUST cap)
REASSEMBLY_TIMEOUT = 30.0                        # B§4.3 (proposal P13)


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
            self.buf[mid] = (count, now, [])
        cnt, t0, parts = self.buf[mid]
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


def hello() -> list:
    return ["HELLO", {"v": 2}]


def info(i_pub: str, relays: list[str], pair: dict | None) -> list:
    return ["INFO", {"v": 2, "i": i_pub, "relays": relays, "pair": pair}]

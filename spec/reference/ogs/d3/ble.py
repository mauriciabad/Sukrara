# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS draft 3 over Bluetooth LE (spec/draft3/ogs3-ble.md): derived service UUIDs, the HELLO/INFO handshake.
The framing is draft 1's, unchanged (ogs.ble.split / Reassembler)."""
from __future__ import annotations

import uuid

from ..ble import Reassembler, encode, split  # noqa: F401  (re-exported: B§4 framing is unchanged)
from .core import DayKeys, hmac256

PAIRING_SERVICE = "abdb0001-9b12-494c-92fc-416bcdc4ac9a"   # B§3.2: advertised only while nearby pairing is open
PAIRING_RX = "abdb0002-9b12-494c-92fc-416bcdc4ac9a"
PAIRING_TX = "abdb0003-9b12-494c-92fc-416bcdc4ac9a"
WINDOW = 900                                                # B§3.1: the advertised UUID changes every 15 min


def as_uuid(b: bytes) -> str:
    """16 bytes → a UUID string with the version-4 and RFC 4122 variant bits set, like any random UUID."""
    return str(uuid.UUID(bytes=b[:16], version=4))


def gatt_uuids(dk: DayKeys) -> dict[str, str]:
    """B§3.1: the day's GATT service and characteristics."""
    return {n: as_uuid(hmac256(dk.ble_key, b"ogs3/ble/gatt/" + n.encode())) for n in ("service", "rx", "tx")}


def adv_uuid(dk: DayKeys, window: int) -> str:
    """B§3.1: the 128-bit service UUID advertised during window `window` (= unix time // 900) of day dk.day."""
    if window * WINDOW // 86400 != dk.day:
        raise ValueError("window outside the day")
    return as_uuid(hmac256(dk.ble_key, f"ogs3/ble/adv/{window}".encode()))


def hello(nonce: str, since: int) -> list:
    return ["HELLO", {"nonce": nonce, "since": since}]


def proof(dk: DayKeys, nonce: str) -> str:
    return hmac256(dk.ble_key, f"ogs3/ble/info/{nonce}".encode()).hex()[:32]


def info(dk: DayKeys, nonce: str) -> list:
    return ["INFO", {"nonce": nonce, "proof": proof(dk, nonce)}]


def pairing_info(nonce: str, invite: str) -> list:
    """B§6: while nearby pairing is open, INFO on the pairing service carries the invite text, nothing else."""
    return ["INFO", {"nonce": nonce, "invite": invite}]

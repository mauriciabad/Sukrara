# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""NIP-01 events, NIP-40 expiration, NIP-44 helpers and NIP-59 gift wraps on top of ogs.crypto.

Every function that would use randomness takes it as an optional argument so the test vectors are reproducible.
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import time
from dataclasses import dataclass

from . import crypto as c


@dataclass(frozen=True)
class Keys:
    sec: bytes

    @property
    def pub(self) -> str:
        return c.xonly(self.sec).hex()

    @staticmethod
    def generate() -> "Keys":
        while True:
            s = os.urandom(32)
            if c.seckey_ok(s):
                return Keys(s)

    @staticmethod
    def from_hex(h: str) -> "Keys":
        return Keys(bytes.fromhex(h))

    def nsec(self) -> str:
        return c.bech32_encode("nsec", self.sec)


def serialize(ev: dict) -> str:
    """NIP-01 canonical serialisation used for the event id."""
    return json.dumps([0, ev["pubkey"], ev["created_at"], ev["kind"], ev["tags"], ev["content"]],
                      separators=(",", ":"), ensure_ascii=False)


def event_id(ev: dict) -> str:
    return hashlib.sha256(serialize(ev).encode()).hexdigest()


def sign(keys: Keys, kind: int, content: str, tags: list, created_at: int | None = None,
         aux: bytes | None = None) -> dict:
    ev = {"pubkey": keys.pub, "created_at": int(time.time()) if created_at is None else created_at,
          "kind": kind, "tags": tags, "content": content}
    ev["id"] = event_id(ev)
    ev["sig"] = c.schnorr_sign(bytes.fromhex(ev["id"]), keys.sec, aux).hex()
    return ev


def verify(ev: dict) -> bool:
    try:
        if not isinstance(ev.get("tags"), list) or not isinstance(ev.get("content"), str):
            return False
        if event_id(ev) != ev["id"]:
            return False
        return c.schnorr_verify(bytes.fromhex(ev["id"]), bytes.fromhex(ev["pubkey"]), bytes.fromhex(ev["sig"]))
    except (KeyError, ValueError, TypeError):
        return False


def tag(ev: dict, name: str) -> str | None:
    for t in ev.get("tags", []):
        if len(t) >= 2 and t[0] == name:
            return t[1]
    return None


def expired(ev: dict, now: int) -> bool:
    x = tag(ev, "expiration")
    return x is not None and x.isdigit() and int(x) <= now


def compact(obj) -> str:
    """JSON as OGS producers emit it: compact, UTF-8, no escaped slashes. (Receivers MUST NOT depend on it.)"""
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def encrypt(sender: Keys, recipient_pub: str, obj, nonce: bytes | None = None) -> str:
    ck = c.nip44_conversation_key(sender.sec, bytes.fromhex(recipient_pub))
    return c.nip44_encrypt(obj if isinstance(obj, str) else compact(obj), ck, nonce)


def decrypt(recipient: Keys, sender_pub: str, payload: str) -> str:
    return c.nip44_decrypt(payload, c.nip44_conversation_key(recipient.sec, bytes.fromhex(sender_pub)))


# ---- NIP-59 ----------------------------------------------------------------------------------------------------

TWO_DAYS = 2 * 24 * 3600


def rumor(author_pub: str, kind: int, content: str, created_at: int, tags: list | None = None) -> dict:
    ev = {"pubkey": author_pub, "created_at": created_at, "kind": kind, "tags": tags or [], "content": content}
    ev["id"] = event_id(ev)
    return ev


def gift_wrap(sender: Keys, recipient_pub: str, rum: dict, *, expiration: int | None, now: int | None = None,
              wrap_key: Keys | None = None, seal_at: int | None = None, wrap_at: int | None = None,
              nonces: tuple[bytes, bytes] | None = None, aux: bytes | None = None) -> dict:
    """rumor → kind 13 seal (signed by sender) → kind 1059 wrap (signed by a one-time key, p = recipient).

    `expiration` is absolute (NIP-40); NIP-59 backdates created_at by up to 2 days, so an expiration derived from the
    wrap's created_at would arrive already expired.
    """
    now = int(time.time()) if now is None else now
    rng = random.SystemRandom()
    seal_at = now - rng.randrange(TWO_DAYS) if seal_at is None else seal_at
    wrap_at = now - rng.randrange(TWO_DAYS) if wrap_at is None else wrap_at
    n1, n2 = nonces or (None, None)
    seal = sign(sender, 13, encrypt(sender, recipient_pub, compact(rum), n1), [], seal_at, aux)
    wk = wrap_key or Keys.generate()
    tags = [["p", recipient_pub]] + ([["expiration", str(expiration)]] if expiration is not None else [])
    return sign(wk, 1059, encrypt(wk, recipient_pub, compact(seal), n2), tags, wrap_at, aux)


class UnwrapError(ValueError):
    pass


def unwrap(recipient: Keys, wrap: dict) -> tuple[dict, str]:
    """Returns (rumor, authenticated sender pubkey). The sender is the seal's signer; the rumor must claim the same
    pubkey and its id must match its content (NIP-59)."""
    if wrap.get("kind") != 1059 or not verify(wrap):
        raise UnwrapError("not a valid kind 1059 event")
    seal = json.loads(decrypt(recipient, wrap["pubkey"], wrap["content"]))
    if seal.get("kind") != 13 or not verify(seal):
        raise UnwrapError("seal is not a valid kind 13 event")
    if seal.get("tags"):
        raise UnwrapError("seal has tags")
    rum = json.loads(decrypt(recipient, seal["pubkey"], seal["content"]))
    if rum.get("pubkey") != seal["pubkey"]:
        raise UnwrapError("rumor author differs from seal signer")
    if "id" in rum and rum["id"] != event_id(rum):
        raise UnwrapError("rumor id mismatch")
    return rum, seal["pubkey"]

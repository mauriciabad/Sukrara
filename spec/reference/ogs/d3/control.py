# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS draft 3 control plane (ogs3-core.md C§8–C§9): invite links, the match code, and control
messages carried in NIP-59 gift wraps (rumor kind 4079)."""
from __future__ import annotations

import base64
import hashlib
import json
import re
import time
from dataclasses import dataclass

from ..nostr import Keys, compact, gift_wrap, rumor, unwrap
from .core import KIND_CONTROL, derive_scalar

INVITE_VERSION = 3
TTL = {"invite": None, "join": 7 * 86400, "grant": 30 * 86400, "ended": 30 * 86400, "rekey": 7 * 86400,
       "history": 86400, "profile": 7 * 86400, "inbox": 7 * 86400, "leave": 30 * 86400, "link": 7 * 86400,
       "manage": 7 * 86400, "invite-link": 7 * 86400}
TO_MEMBER = ("grant", "ended", "invite-link")
TO_ADMIN = ("join", "rekey", "history", "profile", "inbox", "leave", "link", "manage")


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def unb64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


@dataclass(frozen=True)
class Invite:
    fp: str                 # 16 hex chars: the first 8 bytes of the identity key I
    secret: bytes           # 16 bytes
    hints: tuple[str, ...]  # relay URLs from the link (may be empty)

    @property
    def token(self) -> str:
        return b64u(self.secret)

    @property
    def key(self) -> Keys:
        return invite_key(self.secret)


def invite_key(secret: bytes) -> Keys:
    """C§8.2: the key the invite gift wrap is addressed to; anyone holding the link can derive it."""
    return Keys(derive_scalar(secret, b"ogs3/invite", b"inbox"))


def invite_payload(i_pub: str, secret: bytes) -> str:
    """C§8.1: base64url(0x03 ‖ I.pub[0:8] ‖ secret[16]) = 34 characters."""
    if len(secret) != 16:
        raise ValueError("secret must be 16 bytes")
    return b64u(bytes([INVITE_VERSION]) + bytes.fromhex(i_pub)[:8] + secret)


def invite_text(i_pub: str, secret: bytes, hints: list[str] = (), web: str | None = None) -> str:
    """`ogs:<payload>[@host,host]`, or the same after `<web>#` (any https URL; the host authenticates nothing)."""
    s = "ogs:" + invite_payload(i_pub, secret)
    if hints:
        s += "@" + ",".join(h.removeprefix("wss://") for h in hints)
    return f"{web}#{s}" if web else s


_LINK = re.compile(r"(?:https://[^\s#]+#)?ogs:([A-Za-z0-9_-]{34})(?:@([a-z0-9.-]+(?::\d+)?(?:,[a-z0-9.-]+(?::\d+)?)*))?")


def parse_invite(text: str) -> Invite:
    m = _LINK.fullmatch(text.strip())
    if not m:
        raise ValueError("not an OGS invite")
    raw = unb64u(m.group(1))
    if len(raw) != 25 or raw[0] != INVITE_VERSION:
        raise ValueError("unsupported invite version")
    hints = tuple("wss://" + h for h in (m.group(2) or "").split(",") if h)
    return Invite(raw[1:9].hex(), raw[9:], hints)


def match_code(member_pub: str, token: str) -> str:
    """C§8.5: six digits the joining app shows and the wearer types."""
    return f"{int(hashlib.sha256(f'{member_pub}:{token}'.encode()).hexdigest(), 16) % 1_000_000:06d}"


def wrap(sender: Keys, recipient_pub: str, body: dict, *, now: int | None = None, expiration: int | None = None,
         **pins) -> dict:
    """C§9.1: rumor kind 4079 (content = the JSON body), sealed by the sender's real key, wrapped to the recipient's
    inbox key with an absolute NIP-40 expiration."""
    now = int(time.time()) if now is None else now
    if expiration is None:
        expiration = now + TTL[body["type"]]
    rum = rumor(sender.pub, KIND_CONTROL, compact(body), pins.pop("rumor_at", now))
    return gift_wrap(sender, recipient_pub, rum, expiration=expiration, now=now, **pins)


def open_wrap(recipient: Keys, ev: dict) -> tuple[dict, str, int]:
    """Unwrap and return (body, authenticated sender, rumor created_at)."""
    rum, sender = unwrap(recipient, ev)
    if rum.get("kind") != KIND_CONTROL:
        raise ValueError("not an OGS control rumor")
    body = json.loads(rum["content"])
    if not isinstance(body, dict) or not isinstance(body.get("type"), str):
        raise ValueError("bad control body")
    return body, sender, rum["created_at"]

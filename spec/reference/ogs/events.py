# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builders for every OGS draft 1 event (N§5, N§6). Owner side and follower side.

Optional `at`, `nonce`, `aux` arguments pin timestamps, NIP-44 nonces and BIP-340 aux randomness for test vectors;
leave them out in real use.
"""
from __future__ import annotations

import time

from . import model as m
from .nostr import Keys, compact, encrypt, gift_wrap, rumor, sign


def _now(at):
    return int(time.time()) if at is None else at


def _data(signer: Keys, kind: int, g_pub: str, body: dict, tags: list, at, nonce, aux) -> dict:
    return sign(signer, kind, encrypt(signer, g_pub, body, nonce), tags, at, aux)


# ---- owner → followers (signed by the device key D, or the identity I for the roster) ---------------------------

def live(D: Keys, g_pub: str, *, t: int, sgv: int, r: float | None, dev: str = "", sensor: dict | None = None,
         at=None, nonce=None, aux=None) -> dict:
    at = _now(at)
    body = {"v": m.WIRE_V, "t": t, "sgv": sgv, "r": None if r is None else round(r, 2), "dev": dev, **(sensor or {})}
    return _data(D, m.KIND_LIVE, g_pub, body, [["gk", m.gk(g_pub)], ["expiration", str(at + m.TTL_LIVE)]],
                 at, nonce, aux)


def sensor_update(D: Keys, g_pub: str, sensor: dict, *, at=None, nonce=None, aux=None) -> dict:
    """A reading-less live event: {"v":2,"ss","se","sw"} (D§5)."""
    at = _now(at)
    return _data(D, m.KIND_LIVE, g_pub, {"v": m.WIRE_V, **sensor},
                 [["gk", m.gk(g_pub)], ["expiration", str(at + m.TTL_LIVE)]], at, nonce, aux)


def chunk(D: Keys, g_pub: str, sid: str, hour: int, readings: list[m.Reading], *, at=None, nonce=None,
          aux=None) -> dict:
    rows = [[r.t - hour, r.sgv, None if r.rate is None else round(r.rate * 100)]
            for r in sorted(readings, key=lambda x: x.t) if hour <= r.t < hour + 3600]
    body = {"v": m.WIRE_V, "h": hour, "dev": readings[-1].dev if readings else "", "r": rows}
    tags = [["d", m.d_chunk(sid, hour)], ["c", m.c_glucose(sid)], ["gk", m.gk(g_pub)],
            ["expiration", str(hour + m.TTL_CHUNK_AFTER_HOUR)]]
    return _data(D, m.KIND_DATA, g_pub, body, tags, _now(at), nonce, aux)


def roster(I: Keys, g_pub: str, sid: str, *, epoch: int, devices: list[str], writers: list[str],
           relays: list[str], profile: dict | None, at=None, nonce=None, aux=None) -> dict:
    at = _now(at)
    body = {"v": m.WIRE_V, "epoch": epoch, "devices": devices, "writers": writers, "relays": relays,
            "profile": profile}
    tags = [["d", m.d_roster(sid)], ["gk", m.gk(g_pub)], ["expiration", str(at + m.TTL_ROSTER)]]
    return _data(I, m.KIND_DATA, g_pub, body, tags, at, nonce, aux)


def treatment(author: Keys, g_pub: str, sid: str, tr: dict, *, at=None, nonce=None, aux=None) -> dict:
    """tr = {"id","k","a","t","n","u","del","o"} (D§6). Published by the owner's D (merged log) or a writer W."""
    at = _now(at)
    body = {"v": m.TREATMENT_V, **tr}
    tags = [["d", m.d_treatment(sid, tr["id"])], ["c", m.c_treatments(sid)], ["gk", m.gk(g_pub)],
            ["expiration", str(at + m.TTL_TREATMENT)]]
    return _data(author, m.KIND_DATA, g_pub, body, tags, at, nonce, aux)


def alert(D: Keys, g_pub: str, body: dict, *, at=None, nonce=None, aux=None) -> dict:
    at = _now(at)
    return _data(D, m.KIND_ALERT, g_pub, {"v": m.WIRE_V, "type": "alert", **body},
                 [["gk", m.gk(g_pub)], ["expiration", str(at + m.TTL_LIVE)]], at, nonce, aux)


# ---- gift-wrapped messages (N§6) -----------------------------------------------------------------------------------

def wrapped(sender: Keys, recipient_pub: str, kind: int, body: dict, *, ttl: int, now=None, **pins) -> dict:
    now = _now(now)
    rum = rumor(sender.pub, kind, compact(body), pins.pop("rumor_at", now))
    return gift_wrap(sender, recipient_pub, rum, expiration=now + ttl, now=now, **pins)


def invite(I: Keys, secret: bytes, *, expiry: int, relays: list[str], profile: dict | None, now=None,
           **pins) -> dict:
    body = {"v": m.INVITE_V, "type": "invite", "x": expiry, "r": relays, "profile": profile}
    now = _now(now)
    rum = rumor(I.pub, m.KIND_INVITE, compact(body), pins.pop("rumor_at", now))
    return gift_wrap(I, m.invite_key(secret).pub, rum, expiration=max(now + 60, expiry), now=now, **pins)


def key_delivery(I: Keys, f_pub: str, *, sid: str, epoch: int, g: Keys, devices: list[str], relays: list[str],
                 now=None, **pins) -> dict:
    body = {"v": m.WIRE_V, "type": "key", "sid": sid, "epoch": epoch, "g": g.nsec(), "devices": devices,
            "relays": relays}
    return wrapped(I, f_pub, m.KIND_TO_FOLLOWER, body, ttl=30 * 86400, now=now, **pins)


def owner_notice(I: Keys, f_pub: str, kind: str, *, now=None, **pins) -> dict:
    """kind ∈ revoked | invite-used | declined."""
    ttl = 30 * 86400 if kind == "revoked" else 7 * 86400
    return wrapped(I, f_pub, m.KIND_TO_FOLLOWER, {"v": m.WIRE_V, "type": kind}, ttl=ttl, now=now, **pins)


# ---- follower → owner ----------------------------------------------------------------------------------------------

def access_request(F: Keys, i_pub: str, token: str, profile: dict | None, *, now=None, **pins) -> dict:
    body = {"v": m.WIRE_V, "type": "request", "token": token, "profile": profile}
    return wrapped(F, i_pub, m.KIND_TO_OWNER, body, ttl=7 * 86400, now=now, **pins)


def rekey_request(F: Keys, i_pub: str, have: str, *, now=None, **pins) -> dict:
    return wrapped(F, i_pub, m.KIND_TO_OWNER, {"v": m.WIRE_V, "type": "rekey", "have": have}, ttl=7 * 86400,
                   now=now, **pins)


def history_request(F: Keys, i_pub: str, since_hour: int, *, now=None, **pins) -> dict:
    return wrapped(F, i_pub, m.KIND_TO_OWNER, {"v": m.WIRE_V, "type": "history", "since": since_hour}, ttl=86400,
                   now=now, **pins)


def profile_update(F: Keys, i_pub: str, profile: dict, *, now=None, **pins) -> dict:
    return wrapped(F, i_pub, m.KIND_TO_OWNER, {"v": m.WIRE_V, "type": "profile", "profile": profile},
                   ttl=7 * 86400, now=now, **pins)


def auth(keys: Keys, relay: str, challenge: str, *, at=None, aux=None) -> dict:
    """NIP-42 AUTH as the key the wraps are addressed to (N§8)."""
    return sign(keys, 22242, "", [["relay", relay], ["challenge", challenge]], _now(at), aux)

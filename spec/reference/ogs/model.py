# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS draft 1 (wire version 2): constants, tag derivations, invites and payload validation.

Every rule here is cited by section number of spec/ogs-nostr.md (N§) or spec/ogs-data.md (D§).
"""
from __future__ import annotations

import base64
import hashlib
import re
from dataclasses import dataclass, field

from . import crypto as c
from .nostr import Keys

# ---- N§3 constants (draft 1 = what the reference implementation, Sukrara 0.11, sends today) ---------------------

KIND_LIVE = 21078          # ephemeral: one reading or a sensor update
KIND_ALERT = 21079         # ephemeral: alert fired / cleared
KIND_DATA = 30078          # addressable (NIP-78): hour chunk, roster, treatment, owner backup
KIND_TO_OWNER = 30500      # rumor kind inside a gift wrap: follower → owner
KIND_TO_FOLLOWER = 30501   # rumor kind inside a gift wrap: owner → follower
KIND_INVITE = 30502        # rumor kind inside a gift wrap: invite
KIND_WRAP = 1059

NS = "cgm2"                                    # domain-separation prefix of the opaque tags
INVITE_SALT = b"sukrara/invite/v3"             # HKDF salt of invite_key (draft 2 proposal: "ogs/invite/v1")
INVITE_INFO = b"wrap-recipient"
INVITE_PREFIXES = ("https://sukrara.mauri.app/join#", "sukrara:join#")   # N§7.1; see proposal P2
WIRE_V = 2                                     # payload "v" of data, roster, alerts and 30500/30501 rumors
INVITE_V = 3
TREATMENT_V = 1

TTL_LIVE = 3600
TTL_CHUNK_AFTER_HOUR = 7 * 86400
TTL_ROSTER = 30 * 86400
TTL_TREATMENT = 30 * 86400
ALERT_MAX_AGE = 600

TREATMENT_KINDS = ("rapid", "long", "carbs", "note")
ALERT_KINDS = ("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast", "no_data")


def H(x: str) -> str:
    """N§4: first 32 lowercase hex chars of sha256(UTF-8 x)."""
    return hashlib.sha256(x.encode()).hexdigest()[:32]


def gk(g_pub_hex: str) -> str:
    """N§4: epoch label = first 8 hex chars of sha256 over the 32 raw bytes of the read key's x-only pubkey."""
    return hashlib.sha256(bytes.fromhex(g_pub_hex)).hexdigest()[:8]


def hour_start(t: int) -> int:
    return t - t % 3600


def d_chunk(sid: str, hour: int) -> str:
    return H(f"{NS}:h:{sid}:{hour}")


def c_glucose(sid: str) -> str:
    return H(f"{NS}:glucose:{sid}")


def d_roster(sid: str) -> str:
    return H(f"{NS}:roster:{sid}")


def d_backup(i_pub: str) -> str:
    return H(f"{NS}:backup:{i_pub}")


def c_treatments(sid: str) -> str:
    return H(f"{NS}:treatments:{sid}")


def d_treatment(sid: str, tid: str) -> str:
    return H(f"{NS}:tr:{sid}:{tid}")


# ---- N§7 invites -----------------------------------------------------------------------------------------------

def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def unb64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def derive_scalar(ikm: bytes, salt: bytes, info: bytes) -> bytes:
    """N§7.2: HKDF-SHA256 → secp256k1 secret; on the ~2^-128 out-of-range case retry with info ‖ [ctr]."""
    for ctr in range(256):
        k = c.hkdf(ikm, salt, info + (bytes([ctr]) if ctr else b""))
        if c.seckey_ok(k):
            return k
    raise ValueError("unreachable")


def invite_key(secret: bytes) -> Keys:
    return Keys(derive_scalar(secret, INVITE_SALT, INVITE_INFO))


def invite_payload(i_pub: str, secret: bytes) -> str:
    """32 base64url chars: I.pub[0:8] ‖ 16-byte secret."""
    if len(secret) != 16:
        raise ValueError("secret must be 16 bytes")
    return b64u(bytes.fromhex(i_pub)[:8] + secret)


@dataclass(frozen=True)
class Invite:
    fp: str        # 16 hex chars: first 8 bytes of I
    secret: bytes  # 16 bytes
    token: str     # base64url(secret), 22 chars: the access request token

    @property
    def key(self) -> Keys:
        return invite_key(self.secret)


def parse_invite(link: str, any_host: bool = False) -> Invite:
    """N§7.1. Draft 1 accepts the two reference prefixes; `any_host=True` is proposal P2 (any https URL or the
    neutral scheme, the payload alone decides)."""
    link = link.strip()
    code = None
    for p in INVITE_PREFIXES:
        if link.startswith(p):
            code = link[len(p):]
    if code is None and any_host:
        m = re.fullmatch(r"(?:https://[^/#?\s]+/join|ogs:join)#(.+)", link)
        code = m.group(1) if m else None
    if code is None or not re.fullmatch(r"[A-Za-z0-9_-]{32}", code):
        raise ValueError("not an invite link")
    raw = unb64u(code)
    return Invite(raw[:8].hex(), raw[8:], b64u(raw[8:]))


def match_code(f_pub: str, token: str) -> str:
    """N§7.5: the whole sha256 as a big-endian integer, mod 10000, 4 digits."""
    return f"{int(hashlib.sha256((f_pub + token).encode()).hexdigest(), 16) % 10000:04d}"


# ---- D§ payloads -----------------------------------------------------------------------------------------------

class Invalid(ValueError):
    pass


def _int(o: dict, k: str, *, opt=False, null=False):
    if k not in o:
        if opt:
            return None
        raise Invalid(f"missing {k}")
    v = o[k]
    if v is None and null:
        return None
    # JSON has one number type; producers write integers without a fraction, but accept 5.0 as 5.
    if isinstance(v, bool) or not isinstance(v, (int, float)) or (isinstance(v, float) and not v.is_integer()):
        raise Invalid(f"{k} must be an integer")
    return int(v)


def _num(o: dict, k: str, *, opt=False, null=False):
    if k not in o:
        if opt:
            return None
        raise Invalid(f"missing {k}")
    v = o[k]
    if v is None and null:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise Invalid(f"{k} must be a number")
    return float(v)


def _v(o: dict, want: int):
    if not isinstance(o, dict):
        raise Invalid("payload is not an object")
    if _int(o, "v") != want:
        raise Invalid(f"unsupported v={o.get('v')}")


@dataclass
class Reading:
    t: int
    sgv: int
    rate: float | None = None
    dev: str = ""


@dataclass
class Sensor:
    ss: int
    se: int
    sw: int


def parse_live(o: dict) -> tuple[Reading | None, Sensor | None, bool]:
    """D§3 / D§5. Returns (reading or None, sensor or None, sensor_present). A payload with `t` and `sgv` is a
    reading; one without is a sensor update. `se` absent = the owner knows no sensor (clears it)."""
    _v(o, WIRE_V)
    reading = None
    if "t" in o or "sgv" in o:
        sgv = _int(o, "sgv")
        if not 1 <= sgv <= 1000:
            raise Invalid("sgv out of range")
        reading = Reading(_int(o, "t"), sgv, _num(o, "r", opt=True, null=True), str(o.get("dev") or ""))
    sensor = None
    if "se" in o:
        ss = _int(o, "ss", opt=True) or 0
        se = _int(o, "se")
        sw = _int(o, "sw", opt=True)
        sensor = Sensor(ss, se, ss + 3600 if sw is None else sw)
    return reading, sensor, sensor is not None


def parse_chunk(o: dict) -> tuple[int, list[Reading]]:
    """D§4: rows [dt, sgv, rate100|null], dt = t − h, 0 ≤ dt < 3600."""
    _v(o, WIRE_V)
    h = _int(o, "h")
    if h % 3600:
        raise Invalid("h is not an hour start")
    rows = o.get("r")
    if not isinstance(rows, list):
        raise Invalid("r must be an array")
    out = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 2:
            raise Invalid("bad row")
        dt, sgv = _int({"x": row[0]}, "x"), _int({"x": row[1]}, "x")
        if not 0 <= dt < 3600 or not 1 <= sgv <= 1000:
            raise Invalid("row out of range")
        r100 = row[2] if len(row) > 2 else None
        rate = None if r100 is None else _int({"x": r100}, "x") / 100
        out.append(Reading(h + dt, sgv, rate, str(o.get("dev") or "")))
    return h, out


@dataclass
class Roster:
    epoch: int
    devices: list[str]
    writers: list[str] = field(default_factory=list)
    relays: list[str] = field(default_factory=list)
    profile: dict | None = None


HEX64 = re.compile(r"[0-9a-f]{64}")


def parse_roster(o: dict) -> Roster:
    _v(o, WIRE_V)
    devs = o.get("devices")
    wr = o.get("writers", [])
    if not isinstance(devs, list) or not all(isinstance(d, str) and HEX64.fullmatch(d) for d in devs):
        raise Invalid("devices must be hex pubkeys")
    if not isinstance(wr, list) or not all(isinstance(d, str) and HEX64.fullmatch(d) for d in wr):
        raise Invalid("writers must be hex pubkeys")
    relays = [r for r in o.get("relays") or [] if isinstance(r, str) and r.startswith("wss://")]
    prof = o.get("profile") if isinstance(o.get("profile"), dict) else None
    return Roster(_int(o, "epoch"), devs, wr, relays, prof)


@dataclass
class Treatment:
    id: str
    k: str
    a: float
    t: int
    n: str
    u: int
    deleted: bool
    o: str


UUID4 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def parse_treatment(o: dict) -> Treatment:
    """D§6. Content v is 1 in draft 1 (proposal P7: align with the stream's v)."""
    _v(o, TREATMENT_V)
    tid = o.get("id")
    if not isinstance(tid, str) or not UUID4.fullmatch(tid):
        raise Invalid("id must be a lowercase UUID")
    k = o.get("k")
    if k not in TREATMENT_KINDS:
        raise Invalid(f"unknown treatment kind {k!r}")
    n = o.get("n", "")
    if not isinstance(n, str) or len(n.encode()) > 100:
        raise Invalid("n must be a string of ≤ 100 UTF-8 bytes")
    a = _num(o, "a", opt=True) or 0.0
    if a < 0:
        raise Invalid("negative amount")
    return Treatment(tid, k, a, _int(o, "t"), n, _int(o, "u"), bool(o.get("del", False)), str(o.get("o", "")))


def treatment_newer(a: Treatment, b: Treatment) -> bool:
    """D§6.2 merge: (u, o) compared lexicographically; the greater wins."""
    return (a.u, a.o) > (b.u, b.o)


@dataclass
class Alert:
    id: str
    k: str
    state: str
    at: int
    t: int | None
    sgv: int | None
    r: float | None
    th: float | None
    n: int
    tr: int


def parse_alert(o: dict) -> Alert:
    _v(o, WIRE_V)
    if o.get("type") != "alert":
        raise Invalid("type must be alert")
    if o.get("k") not in ALERT_KINDS:
        raise Invalid(f"unknown alert kind {o.get('k')!r}")
    if o.get("state") not in ("fire", "clear"):
        raise Invalid("state must be fire|clear")
    if not isinstance(o.get("id"), str) or not o["id"]:
        raise Invalid("missing id")
    return Alert(o["id"], o["k"], o["state"], _int(o, "at"), _int(o, "t", opt=True, null=True),
                 _int(o, "sgv", opt=True, null=True), _num(o, "r", opt=True, null=True),
                 _num(o, "th", opt=True, null=True), _int(o, "n", opt=True) or 1, _int(o, "tr", opt=True) or 0)


def parse_rumor(o: dict, kind: int) -> dict:
    """N§6: 30500/30501 rumors carry v=2 and a `type`; the invite (30502) carries v=3."""
    if not isinstance(o, dict) or not isinstance(o.get("type"), str):
        raise Invalid("rumor without type")
    _v(o, INVITE_V if kind == KIND_INVITE else WIRE_V)
    return o


# ---- D§2 trend: rate → Nightscout direction (informative mapping, normative thresholds) ------------------------

def direction(rate: float | None) -> str:
    if rate is None:
        return "NONE"
    for lim, name in ((3, "DoubleUp"), (2, "SingleUp"), (1, "FortyFiveUp")):
        if rate >= lim:
            return name
    for lim, name in ((-1, "Flat"), (-2, "FortyFiveDown"), (-3, "SingleDown")):
        if rate > lim:
            return name
    return "DoubleDown"

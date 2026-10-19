# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS draft 3 core (ogs3-core.md): the day-key tree, day material, blinded authors, records and
envelopes. Everything here is deterministic given its inputs; randomness is passed in (or drawn when omitted)."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from dataclasses import dataclass

from .. import crypto as c
from ..nostr import Keys, compact, sign, verify

# ---- C§3 constants ---------------------------------------------------------------------------------------------

KIND_ENVELOPE = 31078      # addressable: every stream object on relays and over BLE
KIND_RECORD = 4078         # the signed record inside an envelope; never published on its own
KIND_CONTROL = 4079        # the rumor inside a NIP-59 gift wrap; never published on its own
KIND_WRAP = 1059
DAY = 86400
DEPTH = 10                 # tree depth: an epoch spans 2^10 = 1024 days
SPAN = 1 << DEPTH
PAD = 256                  # C§7.2: record JSON is space-padded to a multiple of this before NIP-44
MAX_PLAINTEXT = 65535      # C§7.2: portable NIP-44 v2 limit (2-byte length prefix)
LANES = ("g", "r", "m")


def day_of(t: int) -> int:
    """Day number: whole UTC days since 1970-01-01."""
    return t // DAY


def hmac256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()


# ---- C§4 epochs and the day-key tree ---------------------------------------------------------------------------

def child(seed: bytes, bit: int) -> bytes:
    """C§4.2: node(l+1, 2i+b) = HMAC-SHA256(node(l, i), "ogs3/tree/" + b)."""
    return hmac256(seed, b"ogs3/tree/" + (b"1" if bit else b"0"))


@dataclass(frozen=True)
class Node:
    level: int          # 0 = root, DEPTH = a leaf (one day)
    index: int          # position within the level
    seed: bytes

    @property
    def first(self) -> int:
        return self.index << (DEPTH - self.level)

    @property
    def last(self) -> int:
        return ((self.index + 1) << (DEPTH - self.level)) - 1

    def to_json(self) -> dict:
        return {"l": self.level, "i": self.index, "k": self.seed.hex()}

    @staticmethod
    def from_json(o: dict) -> "Node":
        lv, ix, k = o["l"], o["i"], bytes.fromhex(o["k"])
        if not (isinstance(lv, int) and isinstance(ix, int) and 0 <= lv <= DEPTH and 0 <= ix < (1 << lv)
                and len(k) == 32):
            raise ValueError("bad cover node")
        return Node(lv, ix, k)


def descend(node: Node, rel: int) -> bytes:
    """Seed of leaf `rel` (relative day) below `node`; bits of rel taken most significant first."""
    if not node.first <= rel <= node.last:
        raise ValueError("leaf outside node")
    seed = node.seed
    for lv in range(node.level, DEPTH):
        seed = child(seed, (rel >> (DEPTH - lv - 1)) & 1)
    return seed


def cover(root: bytes, a: int, b: int) -> list[Node]:
    """C§4.3: the minimal set of nodes whose leaves are exactly the relative days a..b (inclusive), ordered by
    first leaf. Clipped to 0..SPAN-1."""
    a, b = max(a, 0), min(b, SPAN - 1)
    out: list[Node] = []

    def rec(n: Node):
        if n.last < a or n.first > b:
            return
        if a <= n.first and n.last <= b:
            out.append(n)
            return
        for bit in (0, 1):
            rec(Node(n.level + 1, 2 * n.index + bit, child(n.seed, bit)))

    if a <= b:
        rec(Node(0, 0, root))
    return out


@dataclass
class Epoch:
    """What a participant holds of one epoch: its id, start day, and the cover nodes it was given (C§4)."""
    id: str                 # 16 lowercase hex chars, random
    start: int              # day number of relative day 0
    nodes: list[Node]

    def leaf(self, day: int) -> bytes | None:
        rel = day - self.start
        for n in self.nodes:
            if n.first <= rel <= n.last:
                return descend(n, rel)
        return None

    def has(self, day: int) -> bool:
        rel = day - self.start
        return any(n.first <= rel <= n.last for n in self.nodes)

    def first_day(self) -> int | None:
        return min((self.start + n.first for n in self.nodes), default=None)

    def last_day(self) -> int | None:
        return max((self.start + n.last for n in self.nodes), default=None)

    def to_json(self) -> dict:
        return {"id": self.id, "start": self.start, "depth": DEPTH, "cover": [n.to_json() for n in self.nodes]}

    @staticmethod
    def from_json(o: dict) -> "Epoch":
        if o.get("depth") != DEPTH or not isinstance(o.get("start"), int) or \
                not (isinstance(o.get("id"), str) and len(o["id"]) == 16):
            raise ValueError("bad epoch")
        return Epoch(o["id"], o["start"], [Node.from_json(n) for n in o.get("cover", [])])


@dataclass
class EpochSecret:
    """The administrator's view of an epoch: the root seed (C§4.1)."""
    id: str
    start: int
    root: bytes

    def grant(self, from_day: int, to_day: int | None) -> Epoch:
        last = self.start + SPAN - 1 if to_day is None else to_day
        return Epoch(self.id, self.start, cover(self.root, from_day - self.start, last - self.start))

    def full(self) -> Epoch:
        return Epoch(self.id, self.start, [Node(0, 0, self.root)])

    @staticmethod
    def generate(start_day: int, rng=os.urandom) -> "EpochSecret":
        return EpochSecret(rng(8).hex(), start_day, rng(32))


# ---- C§5 day material ------------------------------------------------------------------------------------------

def blind_pub(pub_hex: str, tweak: int) -> str:
    """x(lift_x(P) + tweak·G)."""
    P = c.lift_x(int(pub_hex, 16))
    if P is None:
        raise ValueError("bad public key")
    B = c._add(P, c.mul(tweak))
    if B is None:
        raise ValueError("blinded key at infinity")
    return B[0].to_bytes(32, "big").hex()


def blind_keys(keys: Keys, tweak: int) -> Keys:
    """C§5.3: the secret of the blinded key: d' + tweak, where d' is the secret normalised to an even-y point."""
    d = int.from_bytes(keys.sec, "big")
    if c.mul(d)[1] % 2:
        d = c.N - d
    b = (d + tweak) % c.N
    if b == 0:
        raise ValueError("blinded secret is zero")
    return Keys(b.to_bytes(32, "big"))


@dataclass(frozen=True)
class DayKeys:
    """C§5: everything derived from one day's leaf."""
    epoch: str
    day: int
    leaf: bytes

    def _x(self, label: bytes) -> bytes:
        return c.hkdf_expand(self.leaf, label, 32)

    @property
    def content_key(self) -> bytes:          # C§5.1: the NIP-44 v2 conversation key
        return self._x(b"ogs3/content")

    @property
    def name_key(self) -> bytes:
        return self._x(b"ogs3/name")

    @property
    def blind_key(self) -> bytes:
        return self._x(b"ogs3/blind")

    @property
    def ble_key(self) -> bytes:
        return self._x(b"ogs3/ble")

    def dtag(self, name: str) -> str:
        """C§5.2: d tag of an object = first 32 hex chars of HMAC(name_key, name)."""
        return hmac256(self.name_key, name.encode()).hex()[:32]

    def tweak(self, pub_hex: str, lane: str) -> int:
        if lane not in LANES:
            raise ValueError("bad lane")
        t = int.from_bytes(hmac256(self.blind_key, f"ogs3/author/{lane}/{pub_hex}".encode()), "big") % c.N
        if t == 0:
            raise ValueError("zero tweak")
        return t

    def author(self, pub_hex: str, lane: str) -> str:
        """C§5.3: the blinded author public key of publisher `pub_hex` in `lane` on this day."""
        return blind_pub(pub_hex, self.tweak(pub_hex, lane))

    def author_keys(self, keys: Keys, lane: str) -> Keys:
        return blind_keys(keys, self.tweak(keys.pub, lane))


# ---- C§6 records -----------------------------------------------------------------------------------------------

ENTRY_TYPES = ("bg", "insulin", "carbs", "exercise", "note")
DEVICE_TYPES = ("cgm", "sensor", "device", "alert", "settings")
ADMIN_TYPES = ("membership", "epoch_end")


def lane_of(rtype: str) -> str:
    return "g" if rtype == "cgm" else "m" if rtype in ADMIN_TYPES else "r"


def record_day(body: dict, created_at: int) -> int:
    """C§6.3: the day whose keys carry a record."""
    t = body.get("type")
    if t == "cgm":
        return day_of(body["start"])
    if t in ENTRY_TYPES or (t not in DEVICE_TYPES + ADMIN_TYPES + ("ack",) and isinstance(body.get("t"), int)):
        return day_of(body["t"])
    return day_of(created_at)


def object_name(body: dict, author: str) -> str:
    """C§6.4: the name an object is addressed by (its d tag is dtag(name))."""
    t = body.get("type")
    if t == "cgm":
        return f"cgm/{body['sess']}/{body['start']}/{body['len']}"
    if t == "sensor":
        return f"sensor/{body['sess']}"
    if t in ("device", "settings", "membership"):
        return t
    if t == "epoch_end":
        return "epoch-end"
    if t == "alert":
        return f"alert/{body['id']}"
    if t == "ack":
        return f"ack/{body['alert']}"
    if t in ENTRY_TYPES:
        return f"rec/{body.get('by') or author}/{body['id']}"
    return f"x/{t}/{body.get('id', '')}"


def sign_record(author: Keys, body: dict, created_at: int, aux: bytes | None = None) -> dict:
    """C§6.1: a record is a NIP-01 event of kind 4078 with empty tags, content = compact JSON, signed by its
    author's own key (never published on its own)."""
    return sign(author, KIND_RECORD, compact(body), [], created_at, aux)


def check_record(rec: dict) -> dict:
    """C§6.1: verify a record and return its body."""
    if not isinstance(rec, dict) or rec.get("kind") != KIND_RECORD or rec.get("tags") != [] or not verify(rec):
        raise ValueError("bad record")
    body = json.loads(rec["content"])
    if not isinstance(body, dict) or not isinstance(body.get("type"), str):
        raise ValueError("bad record body")
    return body


# ---- C§7 envelopes ---------------------------------------------------------------------------------------------

def pad(s: str) -> str:
    """C§7.2: trailing ASCII spaces up to a multiple of PAD bytes (still valid JSON)."""
    n = len(s.encode())
    return s + " " * ((-n) % PAD)


def seal(blinded: Keys, dk: DayKeys, record: dict, *, name: str, expiration: int, created_at: int,
         nonce: bytes | None = None, aux: bytes | None = None, protected: bool = False) -> dict:
    """C§7.1: kind 31078 signed by the blinded author; tags d + expiration (+ NIP-70 "-" if asked)."""
    pt = pad(compact(record))
    if len(pt.encode()) > MAX_PLAINTEXT:
        raise ValueError("record too large for one envelope")
    tags = [["d", dk.dtag(name)], ["expiration", str(expiration)]] + ([["-"]] if protected else [])
    return sign(blinded, KIND_ENVELOPE, c.nip44_encrypt(pt, dk.content_key, nonce), tags, created_at, aux)


def open_envelope(ev: dict, dk: DayKeys) -> dict:
    return json.loads(c.nip44_decrypt(ev["content"], dk.content_key))


# ---- C§8.3 derived keys ----------------------------------------------------------------------------------------

def derive_scalar(ikm: bytes, salt: bytes, info: bytes) -> bytes:
    """HKDF-SHA256 → secp256k1 secret; retry with info ‖ [ctr] in the ~2^-128 out-of-range case."""
    for ctr in range(256):
        k = c.hkdf(ikm, salt, info + (bytes([ctr]) if ctr else b""))
        if c.seckey_ok(k):
            return k
    raise ValueError("unreachable")

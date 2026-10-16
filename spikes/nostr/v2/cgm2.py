# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""CGM-over-Nostr protocol v2 — reference implementation (see ../PROTOCOL-v2.md).

Pure protocol: keys, event builders, gift wraps, parsing/acceptance. Relay I/O reuses ../cgmnostr.Pool.
"""
from __future__ import annotations

import base64, hashlib, json, secrets, sys, time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import nostr_sdk as n                                    # noqa: E402
from cgmnostr import Pool, Reading, hour_of, log         # noqa: E402,F401

LIVE, CHUNK, WRAP = 21078, 30078, 1059
RUMOR_TO_OWNER, RUMOR_TO_FOLLOWER = 30500, 30501
RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.nostr.net"]
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


# ---------------- invites ----------------

def invite_link(i_pub: str, token: str, expiry: int, relays) -> str:
    npub = n.PublicKey.parse(i_pub).to_bech32()
    return f"cgmshare:join?i={npub}&t={token}&x={expiry}&r={quote(','.join(relays), safe='')}"


def parse_invite(link: str) -> dict:
    q = parse_qs(urlparse(link.replace("cgmshare:join?", "cgmshare://join/?")).query)
    return {"i": n.PublicKey.parse(q["i"][0]).to_hex(), "t": q["t"][0], "x": int(q["x"][0]), "r": q["r"][0].split(",")}


def new_token() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")


def match_code(f_pub: str, token: str) -> str:
    return f"{int(hashlib.sha256((f_pub + token).encode()).hexdigest(), 16) % 10000:04d}"


# ---------------- state files ----------------

@dataclass
class Epoch:
    n: int
    g: n.Keys

    @property
    def pub(self):
        return pub(self.g)

    @property
    def gk(self):
        return gk(self.pub)


@dataclass
class OwnerState:
    I: n.Keys
    D: n.Keys
    sid: str
    epochs: list[Epoch]
    followers: list[dict] = field(default_factory=list)    # {"f","name","since","revoked"?}
    invites: list[dict] = field(default_factory=list)      # {"t","x"}
    relays: list[str] = field(default_factory=lambda: list(RELAYS))

    @property
    def current(self) -> Epoch:
        return self.epochs[-1]

    def backup_body(self) -> dict:
        return {"v": 2, "sid": self.sid, "epochs": [{"n": e.n, "g": e.g.secret_key().to_bech32()} for e in self.epochs],
                "followers": self.followers, "invites": self.invites, "relays": self.relays}

    def save(self, path: Path):
        d = self.backup_body() | {"I": self.I.secret_key().to_bech32(), "D": self.D.secret_key().to_bech32()}
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(d, indent=1))

    @staticmethod
    def load(path: Path) -> "OwnerState":
        d = json.loads(path.read_text())
        return OwnerState(keys(d["I"]), keys(d["D"]), d["sid"], [Epoch(e["n"], keys(e["g"])) for e in d["epochs"]],
                          d["followers"], d["invites"], d["relays"])

    @staticmethod
    def from_backup(I: n.Keys, body: dict) -> "OwnerState":
        return OwnerState(I, keys(), body["sid"], [Epoch(e["n"], keys(e["g"])) for e in body["epochs"]],
                          body["followers"], body["invites"], body["relays"])

    @staticmethod
    def create() -> "OwnerState":
        return OwnerState(keys(), keys(), secrets.token_hex(32), [Epoch(1, keys())])

    def active_followers(self):
        return [f for f in self.followers if not f.get("revoked")]


@dataclass
class FollowerState:
    F: n.Keys
    i_pub: str
    relays: list[str]
    sid: str | None = None
    epochs: dict[str, tuple[int, n.Keys]] = field(default_factory=dict)   # gk → (n, G)
    devices: list[str] = field(default_factory=list)
    status: str = "requested"                                              # requested | active | revoked

    def save(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"F": self.F.secret_key().to_bech32(), "i": self.i_pub, "relays": self.relays,
                                    "sid": self.sid, "devices": self.devices, "status": self.status,
                                    "epochs": [{"n": e, "g": g.secret_key().to_bech32()} for e, g in self.epochs.values()]},
                                   indent=1))

    @staticmethod
    def load(path: Path) -> "FollowerState":
        d = json.loads(path.read_text())
        st = FollowerState(keys(d["F"]), d["i"], d["relays"], d["sid"], {}, d["devices"], d["status"])
        for e in d["epochs"]:
            g = keys(e["g"])
            st.epochs[gk(pub(g))] = (e["n"], g)
        return st

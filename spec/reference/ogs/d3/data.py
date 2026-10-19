# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""OGS draft 3 data model (ogs3-data.md): record validation, row flags, trend, units."""
from __future__ import annotations

import re

MGDL_PER_MMOLL = 18.01559          # D§2.2: Nightscout, Tidepool and xDrip+ use this factor
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
HEX64 = re.compile(r"[0-9a-f]{64}")
HEX16 = re.compile(r"[0-9a-f]{16}")
WORD = re.compile(r"[a-z0-9_]{1,32}")
SESS = re.compile(r"[A-Za-z0-9_-]{8,64}")

# D§4.2 row flags
LO, HI, WARMUP, UNRELIABLE, CALIBRATED, BACKFILLED, INTERPOLATED = 1, 2, 4, 8, 16, 32, 64
FLAG_NAMES = {LO: "lo", HI: "hi", WARMUP: "warmup", UNRELIABLE: "unreliable", CALIBRATED: "calibrated",
              BACKFILLED: "backfilled", INTERPOLATED: "interpolated"}


class Invalid(ValueError):
    pass


def _need(cond, msg):
    if not cond:
        raise Invalid(msg)


def _int(o, k, lo=None, hi=None, opt=False, null=False):
    v = o.get(k)
    if v is None:
        _need(opt or (null and k in o), f"{k} missing")
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    _need(isinstance(v, int) and not isinstance(v, bool), f"{k} not an integer")
    _need((lo is None or v >= lo) and (hi is None or v <= hi), f"{k} out of range")
    return v


def _num(o, k, lo=None, hi=None, opt=False):
    v = o.get(k)
    if v is None:
        _need(opt, f"{k} missing")
        return None
    _need(isinstance(v, (int, float)) and not isinstance(v, bool), f"{k} not a number")
    _need((lo is None or v >= lo) and (hi is None or v <= hi), f"{k} out of range")
    return v


def _str(o, k, maxbytes=None, pat=None, opt=False):
    v = o.get(k)
    if v is None:
        _need(opt, f"{k} missing")
        return None
    _need(isinstance(v, str), f"{k} not a string")
    _need(maxbytes is None or len(v.encode()) <= maxbytes, f"{k} too long")
    _need(pat is None or pat.fullmatch(v), f"{k} malformed")
    return v


def _bool(o, k):
    v = o.get(k)
    _need(v is None or isinstance(v, bool), f"{k} not a boolean")
    return bool(v)


def _common(o):
    _int(o, "tz", -840, 840, opt=True)


def _entry(o):
    _str(o, "id", pat=UUID)
    _int(o, "t", 0)
    _int(o, "rev", 0)
    _bool(o, "del")
    _str(o, "by", pat=HEX64, opt=True)
    _str(o, "note", 200, opt=True)


def validate(body: dict) -> dict:
    """D§3–D§9: raise Invalid unless `body` is a valid record of a known type; unknown types are returned as is
    (D§10: kept and forwarded, never interpreted)."""
    t = body.get("type")
    _need(isinstance(t, str) and WORD.fullmatch(t), "type")
    _common(body)
    if t == "cgm":
        _str(body, "sess", pat=SESS)
        ln = _int(body, "len", 60, 86400)
        _need(86400 % ln == 0, "len must divide a day")
        start = _int(body, "start", 0)
        _need(start % ln == 0, "start not aligned to len")
        rows = body.get("rows")
        _need(isinstance(rows, list) and len(rows) <= 2880, "rows")
        prev = -1
        for r in rows:
            _need(isinstance(r, list) and 2 <= len(r) <= 4, "row shape")
            dt, v = r[0], r[1]
            r100 = r[2] if len(r) > 2 else None
            fl = r[3] if len(r) > 3 else 0
            _need(isinstance(dt, int) and prev < dt < ln, "row dt")
            _need(isinstance(v, int) and 1 <= v <= 1000, "row value")
            _need(r100 is None or (isinstance(r100, int) and -3000 <= r100 <= 3000), "row rate")
            _need(isinstance(fl, int) and 0 <= fl < 1 << 16, "row flags")
            prev = dt
    elif t == "sensor":
        _str(body, "sess", pat=SESS)
        _str(body, "state", pat=WORD)
        for k in ("start", "warmup_end", "end", "ended", "interval"):
            _int(body, k, 0, opt=True)
        _str(body, "reason", pat=WORD, opt=True)
        _str(body, "model", 32, opt=True)
        _int(body, "rev", 0)
    elif t == "device":
        _str(body, "label", 40, opt=True)
        _int(body, "battery", 0, 100, opt=True)
        _bool(body, "charging")
        _str(body, "link", pat=WORD, opt=True)
        _int(body, "last", 0, opt=True)
        _int(body, "rev", 0)
    elif t == "settings":
        _need(body.get("unit") in ("mg/dL", "mmol/L"), "unit")
        tg = body.get("target")
        _need(tg is None or (isinstance(tg, list) and len(tg) == 2 and all(isinstance(x, int) for x in tg)
                             and 40 <= tg[0] < tg[1] <= 400), "target")
        _str(body, "zone", 64, opt=True)
        _int(body, "rev", 0)
    elif t == "alert":
        _str(body, "id", pat=HEX16)
        _str(body, "kind", pat=WORD)
        _str(body, "prio", pat=WORD)
        _need(body.get("state") in ("active", "cleared"), "state")
        _int(body, "start", 0)
        _int(body, "last", 0)
        _int(body, "n", 1)
        _int(body, "end", 0, opt=True)
        _int(body, "v", 1, 1000, opt=True)
        _num(body, "r", -30, 30, opt=True)
        _num(body, "th", opt=True)
        _str(body, "text", 200, opt=True)
        _int(body, "rev", 0)
    elif t == "ack":
        _str(body, "alert", pat=HEX16)
        _int(body, "t", 0)
        _str(body, "note", 200, opt=True)
    elif t == "bg":
        _entry(body)
        _int(body, "v", 1, 1000)
        _bool(body, "cal")
        _str(body, "sess", pat=SESS, opt=True)
        _str(body, "src", pat=WORD, opt=True)
    elif t == "insulin":
        _entry(body)
        _num(body, "u", 0, 250)
        _str(body, "kind", pat=WORD)
        _str(body, "how", pat=WORD, opt=True)
    elif t == "carbs":
        _entry(body)
        _num(body, "g", 0, 1000)
        _str(body, "meal", pat=WORD, opt=True)
        _num(body, "fat", 0, 1000, opt=True)
        _num(body, "protein", 0, 1000, opt=True)
    elif t == "exercise":
        _entry(body)
        _int(body, "dur", 1, 1440)
        _str(body, "level", pat=WORD, opt=True)
        _str(body, "what", 32, opt=True)
    elif t == "note":
        _entry(body)
        _str(body, "text", 500)
        _str(body, "tag", pat=WORD, opt=True)
    elif t == "membership":
        _str(body, "epoch", pat=HEX16)
        _int(body, "seq", 1)
        _str(body, "inbox", pat=HEX64)
        _need(isinstance(body.get("devices"), list) and body["devices"], "devices")
        for d in body["devices"]:
            _str(d, "k", pat=HEX64)
            _str(d, "name", 40, opt=True)
        _need(isinstance(body.get("members", []), list), "members")
        for mm in body.get("members", []):
            _str(mm, "k", pat=HEX64)
            _need(isinstance(mm.get("scopes"), list) and all(isinstance(s, str) for s in mm["scopes"]), "scopes")
            _int(mm, "from", 0)
            _int(mm, "to", 0, opt=True)
        _need(isinstance(body.get("relays", []), list) and all(
            isinstance(u, str) and u.startswith("wss://") for u in body.get("relays", [])), "relays")
        _need(isinstance(body.get("caps", []), list), "caps")
    elif t == "epoch_end":
        _int(body, "at", 0)
    return body


def rows_of(body: dict):
    """Expand a cgm record into (t, v, rate mg/dL/min or None, flags)."""
    for r in body["rows"]:
        r100 = r[2] if len(r) > 2 else None
        yield body["start"] + r[0], r[1], None if r100 is None else r100 / 100, r[3] if len(r) > 3 else 0


def direction(rate: float | None) -> str:
    """D§4.4: Nightscout direction names from the rate (mg/dL/min)."""
    if rate is None:
        return "NONE"
    for lim, name in ((3, "DoubleUp"), (2, "SingleUp"), (1, "FortyFiveUp")):
        if rate >= lim:
            return name
    for lim, name in ((-3, "DoubleDown"), (-2, "SingleDown"), (-1, "FortyFiveDown")):
        if rate <= lim:
            return name
    return "Flat"


def to_mmol(mgdl: float) -> float:
    """D§2.2: display conversion, one decimal."""
    return round(mgdl / MGDL_PER_MMOLL, 1)


def from_mmol(mmol: float) -> int:
    return round(mmol * MGDL_PER_MMOLL)

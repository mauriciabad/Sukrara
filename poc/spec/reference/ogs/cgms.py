# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Bluetooth SIG Continuous Glucose Monitoring Service (0x181F) encodings for the optional CGMS bridge
(spec/ogs-ble.md Annex B). Only what a read-only bridge needs: SFLOAT, CGM Measurement, CGM Status, Session Run
Time, E2E-CRC. Octet order and bit meanings follow the Nordic Android-BLE-Common-Library parser, which xDrip+'s
generic "GluPro" CGMS collector uses (third_party/xDrip/libglupro).
"""
from __future__ import annotations

import struct

SERVICE = 0x181F
MEASUREMENT, FEATURE, STATUS, SESSION_START, SESSION_RUN, RACP, SOCP = 0x2AA7, 0x2AA8, 0x2AA9, 0x2AAA, 0x2AAB, \
    0x2A52, 0x2AAC

SFLOAT_NAN, SFLOAT_NRES = 0x07FF, 0x0800

# first annunciation octet (flag 0x20)
SESSION_STOPPED, BATTERY_LOW, SENSOR_TYPE_INCORRECT, SENSOR_MALFUNCTION, DEVICE_SPECIFIC_ALERT, GENERAL_FAULT = \
    0x01, 0x02, 0x04, 0x08, 0x10, 0x20
# third annunciation octet (flag 0x80)
BELOW_PATIENT_LOW, ABOVE_PATIENT_HIGH, BELOW_HYPO, ABOVE_HYPER, RATE_DECREASE, RATE_INCREASE, BELOW_RANGE, \
    ABOVE_RANGE = 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80

# OGS alert kind → third-octet bit (Annex B.4)
ALERT_BITS = {"very_low": BELOW_HYPO, "low": BELOW_PATIENT_LOW, "high": ABOVE_PATIENT_HIGH,
              "very_high": ABOVE_HYPER, "extremely_high": ABOVE_HYPER, "falling_fast": RATE_DECREASE,
              "rising_fast": RATE_INCREASE}


def sfloat(v: float | None) -> int:
    """IEEE 11073-20601 16-bit SFLOAT: 4-bit signed exponent, 12-bit signed mantissa. None → NaN.
    Picks the smallest exponent whose mantissa fits (most precision), then drops trailing zeros."""
    if v is None:
        return SFLOAT_NAN
    for exp in range(-8, 8):
        mant = round(v * 10 ** -exp) if exp < 0 else round(v / 10 ** exp)
        if -2046 <= mant <= 2045:
            break
    else:
        raise ValueError("out of SFLOAT range")
    while exp < 0 and mant and mant % 10 == 0:
        mant //= 10
        exp += 1
    return ((exp & 0xF) << 12) | (mant & 0x0FFF)


def sfloat_value(raw: int) -> float | None:
    mant, exp = raw & 0x0FFF, raw >> 12
    if mant in (0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801):
        return None
    if mant >= 0x0800:
        mant -= 0x1000
    if exp >= 0x8:
        exp -= 0x10
    return mant * 10 ** exp


def crc16_mcrf4xx(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc


def measurement(sgv: int, offset_min: int, rate: float | None = None, status: int = 0, warning: int = 0,
                crc: bool = False) -> bytes:
    """CGM Measurement (0x2AA7): Size, Flags, Glucose (SFLOAT mg/dL), Time Offset (uint16 min since session start),
    [annunciation octets], [Trend SFLOAT mg/dL/min], [E2E-CRC]."""
    flags = (0x01 if rate is not None else 0) | (0x20 if status else 0) | (0x80 if warning else 0)
    body = struct.pack("<HH", sfloat(sgv), offset_min & 0xFFFF)
    body += bytes([status]) if status else b""
    body += bytes([warning]) if warning else b""
    body += struct.pack("<H", sfloat(round(rate, 2))) if rate is not None else b""
    size = 2 + len(body) + (2 if crc else 0)
    pkt = bytes([size, flags]) + body
    return pkt + (struct.pack("<H", crc16_mcrf4xx(pkt)) if crc else b"")


def parse_measurement(pkt: bytes) -> dict:
    size, flags = pkt[0], pkt[1]
    glucose, offset = struct.unpack_from("<HH", pkt, 2)
    i = 6
    out = {"sgv": sfloat_value(glucose), "offset": offset}
    for bit, name in ((0x20, "status"), (0x40, "caltemp"), (0x80, "warning")):
        if flags & bit:
            out[name] = pkt[i]
            i += 1
    if flags & 0x01:
        out["rate"] = sfloat_value(struct.unpack_from("<H", pkt, i)[0])
        i += 2
    if flags & 0x02:
        out["quality"] = sfloat_value(struct.unpack_from("<H", pkt, i)[0])
        i += 2
    if size == i + 2:
        out["crc_ok"] = struct.unpack_from("<H", pkt, i)[0] == crc16_mcrf4xx(pkt[:i])
    return out


def status_char(offset_min: int, status: int = 0, caltemp: int = 0, warning: int = 0) -> bytes:
    """CGM Status (0x2AA9): Time Offset uint16, then the three annunciation octets."""
    return struct.pack("<HBBB", offset_min & 0xFFFF, status, caltemp, warning)


def session_run_time(hours: int) -> bytes:
    return struct.pack("<H", hours)


def from_ogs(sgv: int, t: int, rate: float | None, ss: int, se: int, now: int, alert: str | None = None) -> bytes:
    """Annex B.3: one OGS reading → one CGM Measurement. Time offset counts from the sensor start `ss`."""
    status = SESSION_STOPPED if now >= se else 0
    warning = ALERT_BITS.get(alert, 0) if alert else 0
    if sgv < 40:
        warning |= BELOW_RANGE
    elif sgv > 500:
        warning |= ABOVE_RANGE
    return measurement(sgv, (t - ss) // 60, rate, status, warning)

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Informative mappings from OGS draft 3 records to other models (ogs3-data.md D§11). They leave the encrypted
channel, so an app runs them only with the user's consent."""
from __future__ import annotations

import time

from . import data as D

LOINC_ISF_MASS = "99504-3"      # HL7 CGM IG: Glucose [Mass/volume] in Interstitial fluid


def iso(t: int, tz: int | None) -> str:
    off = tz or 0
    s = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(t + off * 60))
    return s + ("Z" if tz is None else f"{'+' if off >= 0 else '-'}{abs(off) // 60:02d}:{abs(off) % 60:02d}")


def reading_id(sess: str, t: int) -> str:
    return f"ogs3:{sess}:{t}"


def nightscout_entry(sess: str, t: int, v: int, rate: float | None, flags: int, tz: int | None) -> dict:
    """Nightscout encodes LO/HI in the value: 39 = LOW, 401 = HIGH (lib/sandbox.js)."""
    sgv = 39 if flags & D.LO else 401 if flags & D.HI else v
    e = {"identifier": reading_id(sess, t), "type": "sgv", "sgv": sgv, "date": t * 1000,
         "dateString": iso(t, tz), "direction": D.direction(rate), "noise": 4 if flags & D.UNRELIABLE else 1,
         "device": "ogs3"}
    if rate is not None:
        e["delta"] = round(rate * 5, 1)
    if tz is not None:
        e["utcOffset"] = tz
    return e


NS_EVENT = {"insulin": "Correction Bolus", "carbs": "Carb Correction", "bg": "BG Check", "exercise": "Exercise",
            "note": "Note"}


def nightscout_treatment(body: dict) -> dict:
    t = body["type"]
    out = {"identifier": body["id"], "eventType": NS_EVENT[t], "created_at": iso(body["t"], body.get("tz")),
           "date": body["t"] * 1000, "enteredBy": "ogs3"}
    if t == "insulin":
        out["insulin"] = body["u"]
        if body["kind"] != "rapid":
            out["eventType"], out["notes"] = "Note", f"{body['kind']}-acting insulin"
    elif t == "carbs":
        out["carbs"] = body["g"]
    elif t == "bg":
        out.update(glucose=body["v"], glucoseType="Finger", units="mg/dl")
    elif t == "exercise":
        out["duration"] = body["dur"]
    if body.get("note") or body.get("text"):
        out["notes"] = body.get("text") or body["note"]
    return out


def fhir_observation(sess: str, t: int, v: int, flags: int, tz: int | None, patient: str = "Patient/example") -> dict:
    """HL7 CGM IG (hl7.fhir.uv.cgm 1.0.0) CGMSensorReadingMassPerVolume. LO/HI become a Quantity comparator."""
    q = {"value": v, "unit": "mg/dL", "system": "http://unitsofmeasure.org", "code": "mg/dL"}
    if flags & (D.LO | D.HI):
        q["comparator"] = "<" if flags & D.LO else ">"
    return {"resourceType": "Observation", "id": reading_id(sess, t).replace(":", "-"),
            "meta": {"profile": ["http://hl7.org/fhir/uv/cgm/StructureDefinition/cgm-sensor-reading-mass-per-volume"]},
            "status": "final",
            "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category",
                                      "code": "laboratory"}]}],
            "code": {"coding": [{"system": "http://loinc.org", "code": LOINC_ISF_MASS}]},
            "subject": {"reference": patient}, "effectiveDateTime": iso(t, tz), "valueQuantity": q}


def health_connect(sess: str, t: int, v: int, flags: int, tz: int | None, version: int) -> dict | None:
    """BloodGlucoseRecord fields. Health Connect has no way to say "below/above the sensor's range": skip those."""
    if flags & (D.LO | D.HI):
        return None
    return {"time": iso(t, None), "zoneOffset": None if tz is None else iso(0, tz)[-6:],
            "level": {"inMilligramsPerDeciliter": v}, "specimenSource": "INTERSTITIAL_FLUID",
            "metadata": {"clientRecordId": reading_id(sess, t), "clientRecordVersion": version,
                         "recordingMethod": "AUTOMATICALLY_RECORDED"}}


def tidepool_cbg(sess: str, t: int, v: int, rate: float | None, flags: int, tz: int | None, interval: int) -> dict:
    out = {"type": "cbg", "units": "mg/dL", "value": v, "time": iso(t, None), "sampleInterval": interval * 1000,
           "origin": {"id": reading_id(sess, t), "name": "ogs3"}}
    if tz is not None:
        out["timezoneOffset"] = tz
    if rate is not None:
        out.update(trendRate=rate, trendRateUnits="mg/dL/minute")
    if flags & D.BACKFILLED:
        out["backfilled"] = True
    if flags & (D.LO | D.HI):
        out["annotations"] = [{"code": "bg/out-of-range", "value": "low" if flags & D.LO else "high",
                               "threshold": v}]
    return out

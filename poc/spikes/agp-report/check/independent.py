# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent check of the AGP engine (investigation 26), written from the rules in docs/26 with pandas/numpy only
(no code shared with the Kotlin engine): every fixture's readings → metrics, the 5/25/50/75/95 % modal-day profile
(numpy.percentile, linear), 1-4-1 smoothing, daily profiles and the sufficiency flags, compared with the fixture's
`expect` (what the Kotlin engine computed). Exit 1 on any difference above 1e-3.

    uv run --with numpy,pandas python -I check/independent.py fixtures/*.json [--samples out.json]

--samples writes the per-bin grid samples, for check/tidepool.mjs (d3-array, Tidepool's quantile)."""
import json
import sys

import numpy as np
import pandas as pd

TOL = 1e-3
PCTS = [5, 25, 50, 75, 95]


def compute(fx):
    zone, gs, binm, maxgap = fx["zone"], fx["options"]["gridS"], fx["options"]["binMinutes"], fx["options"]["maxGapS"]
    b = fx["bands"]
    first = pd.Timestamp(fx["first"])
    days = fx["days"]
    t0 = int(first.tz_localize(zone).timestamp())
    t1 = int((first + pd.Timedelta(days=days)).tz_localize(zone).timestamp())

    df = pd.DataFrame(fx["readings"], columns=["t", "sgv"])
    df = df[(df.t >= t0) & (df.t < t1)].sort_values("t", kind="stable").reset_index(drop=True)
    # within 30 s of the previous kept reading → replaces it
    keep = []
    for t, v in zip(df.t, df.sgv):
        if keep and t - keep[-1][0] < 30:
            keep[-1] = (t, v)
        else:
            keep.append((t, v))
    df = pd.DataFrame(keep, columns=["t", "sgv"])
    nxt = df.t.shift(-1)
    w = (nxt - df.t).clip(upper=maxgap)
    if len(df) > 1:
        spacing = min(max(df.t.iloc[-1] - df.t.iloc[-2], 1), maxgap)
    else:
        spacing = 60
    w.iloc[-1] = max(1, min(spacing, t1 - df.t.iloc[-1]))
    df["w"] = w.astype(float)

    def rng(v):
        return np.select([v < b["veryLow"], v < b["low"], v <= b["tight"], v <= b["high"], v <= b["veryHigh"]],
                         ["veryLow", "low", "tight", "target", "high"], "veryHigh")
    df["range"] = rng(df.sgv.values)
    total = df.w.sum()
    share = df.groupby("range").w.sum() / total
    mean = np.average(df.sgv, weights=df.w)
    sd = np.sqrt(np.average((df.sgv - mean) ** 2, weights=df.w))
    metrics = {k: float(share.get(k, 0.0)) for k in ["veryLow", "low", "tight", "target", "high", "veryHigh"]}
    metrics.update(active=min(1.0, total / (t1 - t0)), mean=mean, sd=sd, cv=sd / mean * 100,
                   gmi=3.31 + 0.02392 * mean, readings=len(df))

    # regular grid, the reading in effect at each instant
    grid = pd.DataFrame({"g": np.arange(t0, t1, gs, dtype=np.int64)})
    m = pd.merge_asof(grid, df[["t", "sgv", "w"]].astype({"t": np.int64}), left_on="g", right_on="t", direction="backward")
    m = m[(m.g - m.t) < m.w]
    local = pd.to_datetime(m.g, unit="s", utc=True).dt.tz_convert(zone)
    m = m.assign(bin=((local.dt.hour * 60 + local.dt.minute) // binm).values)
    nb = 1440 // binm
    raw = []
    samples = []
    for i in range(nb):
        vals = m.sgv[m.bin == i].to_numpy(dtype=float)
        samples.append(sorted(vals.tolist()))
        raw.append([len(vals)] + (np.percentile(vals, PCTS).tolist() if len(vals) else [None] * 5))

    def smooth(raw):
        out = []
        for i in range(nb):
            row = [raw[i][0]]
            for k in range(1, 6):
                c, p, x = raw[i][k], raw[(i - 1) % nb][k], raw[(i + 1) % nb][k]
                if c is None or (p is None and x is None):
                    row.append(c)
                elif p is None:
                    row.append((4 * c + x) / 5)
                elif x is None:
                    row.append((p + 4 * c) / 5)
                else:
                    row.append((p + 4 * c + x) / 6)
            out.append(row)
        return out

    out_days = []
    with_data = []
    for k in range(days):
        d = first + pd.Timedelta(days=k)
        a = int(d.tz_localize(zone).timestamp())
        z = int((d + pd.Timedelta(days=1)).tz_localize(zone).timestamp())
        sub = df[(df.t >= a) & (df.t < z)]
        secs = np.minimum(sub.w, z - sub.t)
        inr = secs[(sub.sgv >= b["low"]) & (sub.sgv <= b["high"])].sum()
        segs = int(len(sub) > 0) + int((sub.t.diff() > maxgap).sum())
        noon = (int((d + pd.Timedelta(hours=12)).tz_localize(zone).timestamp()) - a) / (z - a)
        active = secs.sum() / (z - a)
        out_days.append({"date": d.strftime("%Y-%m-%d"), "hours": (z - a) / 3600, "active": active,
                         "inRange": (inr / secs.sum()) if secs.sum() > 0 else None,
                         "segments": segs, "points": len(sub), "noon": noon})
        if secs.sum() >= 3600:
            with_data.append(active)
    best7 = sorted(with_data, reverse=True)[:7]
    best7m = float(np.mean(best7)) if best7 else 0.0
    hours = total / 3600
    suff = {"hours": hours, "daysWithData": len(with_data), "best7Active": best7m, "report": bool(hours >= 24),
            "profile": bool(hours >= 24 and len(best7) >= 7 and best7m >= 0.70),
            "consensus": bool(days >= 14 and metrics["active"] >= 0.70)}
    return {"metrics": metrics, "sufficiency": suff, "rawProfile": raw, "profile": smooth(raw), "days": out_days}, samples


def diff(a, b, path=""):
    if isinstance(a, dict):
        return [x for k in set(a) | set(b) for x in (diff(a.get(k), b.get(k), f"{path}.{k}") if k in a and k in b else [f"{path}.{k}: missing"])]
    if isinstance(a, list):
        if len(a) != len(b):
            return [f"{path}: size {len(a)} != {len(b)}"]
        return [x for i in range(len(a)) for x in diff(a[i], b[i], f"{path}[{i}]")]
    if isinstance(a, bool) or isinstance(b, bool) or a is None or b is None or isinstance(a, str):
        return [] if a == b else [f"{path}: {a} != {b}"]
    return [] if abs(float(a) - float(b)) <= TOL else [f"{path}: {a} != {b}"]


def main(argv):
    files = [a for a in argv if a.endswith(".json") and not a.startswith("--")]
    sample_out = argv[argv.index("--samples") + 1] if "--samples" in argv else None
    bad = 0
    all_samples = {}
    for f in files:
        if sample_out and f == sample_out:
            continue
        with open(f) as fh:
            fx = json.load(fh)
        got, samples = compute(fx)
        all_samples[fx["name"]] = {"samples": samples, "expect": fx["expect"]["profile"]}
        d = diff(fx["expect"], got)
        worst = max((abs(e - g) for e_row, g_row in zip(fx["expect"]["profile"], got["profile"])
                     for e, g in zip(e_row[1:], g_row[1:]) if e is not None and g is not None), default=0.0)
        print(f"{fx['name']}: {'ok' if not d else 'DIFF'} · {sum(r[0] for r in got['rawProfile'])} grid samples · "
              f"max profile |Δ| {worst:.4f} mg/dL · mean {got['metrics']['mean']:.2f} · in range "
              f"{(got['metrics']['tight'] + got['metrics']['target']) * 100:.2f} % · active {got['metrics']['active'] * 100:.2f} %")
        for line in d[:10]:
            print("  " + line)
        bad += len(d)
    if sample_out:
        with open(sample_out, "w") as fh:
            json.dump(all_samples, fh)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

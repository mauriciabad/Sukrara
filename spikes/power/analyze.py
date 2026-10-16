# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarise a PowerLab run: energy per window from start/end snapshots, and each scenario vs its baselines.

usage: python analyze.py <run-dir>      (pip install perfetto; run-dir pulled from the phone)
The ODPM power rails are cumulative energy counters (µWs), so window energy = end − start, per rail.
"""
import re
import sys
from pathlib import Path

from perfetto.trace_processor import TraceProcessor

GROUPS = {  # rail name pattern → group shown in the table
    "wifi_bt": r"wifi\.bt|WLAN_BT", "cell": r"modem|radio\.frontend|RFFE|MODEM",
    "cpu": r"VDD_CPU", "ddr": r"ddr|MEM", "display": r"DISP", "aoc": r"AOC",
}
CPU_PROCS = {"app.mauri.sukrara.poc": "sukrara", "tk.glucodata": "juggluco", "dev.spike.glucobridge": "bridge", "dev.spike.cgmnostr": "nostr",
             "dev.spike.glucostatus": "status", "de.michelinside.glucodatahandler": "gdh",
             "com.android.systemui": "sysui", "system_server": "sysserv", "com.google.android.bluetooth": "bt",
             "dev.spike.powerlab": "lab"}
TICKS = 100  # USER_HZ


def rails(trace: Path) -> dict:
    tp = TraceProcessor(trace=str(trace))
    q = """select t.name name, c.value v from counter c join counter_track t on c.track_id = t.id
           where t.name like 'power.%' and c.ts = (select max(ts) from counter c2 where c2.track_id = c.track_id)"""
    out = {r.name: r.v for r in tp.query(q)}
    tp.close()
    return out


def meta(txt: Path) -> dict:
    d = {"cpu": {}}
    for line in txt.read_text().splitlines():
        k, _, v = line.partition(" ")
        if k == "cpu":
            name, ticks = v.split()
            d["cpu"][name] = int(ticks)
        else:
            d[k] = v
    return d


def window(run: Path, label: str) -> dict:
    s, e = rails(run / f"{label}-start.pftrace"), rails(run / f"{label}-end.pftrace")
    ms, me = meta(run / f"{label}-start.txt"), meta(run / f"{label}-end.txt")
    secs = float(me["uptime"]) - float(ms["uptime"])
    h = secs / 3600
    r = {"label": label, "min": secs / 60, "deep": me.get("deep", "?"), "wifi": me.get("wifi", "?")}
    total = 0.0
    for name in s.keys() & e.keys():
        mw = (e[name] - s[name]) / secs / 1000
        total += mw
        for g, pat in GROUPS.items():
            if re.search(pat, name):
                r[g] = r.get(g, 0) + mw
    r["rails_mW"] = total
    try:
        r["batt_mA"] = (int(ms["charge_uah"]) - int(me["charge_uah"])) / 1000 / h
    except (KeyError, ValueError):
        pass
    for proc, short in CPU_PROCS.items():
        if proc in ms["cpu"] and proc in me["cpu"]:
            r[f"{short}_s/h"] = (me["cpu"][proc] - ms["cpu"][proc]) / TICKS / h
    if ms.get("suspends", "").isdigit() and me.get("suspends", "").isdigit():
        r["suspends/h"] = (int(me["suspends"]) - int(ms["suspends"])) / h
    return r


def main():
    run = Path(sys.argv[1])
    labels = sorted({p.name[: -len("-start.txt")] for p in run.glob("*-start.txt") if (run / p.name.replace("-start", "-end")).exists()})
    rows = [window(run, l) for l in labels]
    cols = ["min", "rails_mW", "batt_mA", "wifi_bt", "cell", "cpu", "ddr", "display", "aoc", "suspends/h"] + \
           [f"{s}_s/h" for s in CPU_PROCS.values()]
    print("label".ljust(22) + "".join(c.rjust(11) for c in cols))
    for r in rows:
        print(r["label"].ljust(22) + "".join((f"{r[c]:11.1f}" if isinstance(r.get(c), float) else "-".rjust(11)) for c in cols))

    # Each scenario against the mean of the nearest baseline before and after it (cancels slow drift).
    print("\nscenario vs neighbouring baselines (Δ mW of all rails / Δ battery mA):")
    is_base = [re.sub(r"^\d+-", "", r["label"]).endswith("base") for r in rows]
    for i, r in enumerate(rows):
        if is_base[i]:
            continue
        family = "cell-base" if r["label"].split("-", 1)[1].startswith("cell") else "base"
        near = [rows[j] for j in (*range(i - 1, -1, -1), *range(i + 1, len(rows)))
                if re.sub(r"^\d+-", "", rows[j]["label"]) == family][:2]
        if not near:
            continue
        b = {k: sum(n.get(k, 0) for n in near) / len(near) for k in ("rails_mW", "batt_mA", "wifi_bt", "cell", "cpu")}
        d = {k: r.get(k, 0) - v for k, v in b.items()}
        print(f"  {r['label']:22s} rails {d['rails_mW']:+7.1f} mW  (wifi_bt {d['wifi_bt']:+6.1f}, cell {d['cell']:+6.1f}, "
              f"cpu {d['cpu']:+6.1f})   battery {d['batt_mA']:+6.2f} mA   vs {', '.join(n['label'] for n in near)}")
    # A/A noise: the spread between baseline windows.
    bases = [r["rails_mW"] for r, b in zip(rows, is_base) if b and "cell" not in r["label"]]
    if len(bases) > 1:
        print(f"\nbaseline windows: {len(bases)}, rails mean {sum(bases)/len(bases):.1f} mW, "
              f"min {min(bases):.1f}, max {max(bases):.1f} (spread = noise floor)")


if __name__ == "__main__":
    main()

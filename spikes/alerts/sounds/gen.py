# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Sukrara alert sound packs, synthesised from code (no samples, original melodies).

    uv run gen.py            # → out/<pack>_<kind>_<n>.wav  (+ .ogg for Android, .caf for the Mac, .m4a for the web)

4 packs × 7 kinds × 3 variants. Moods (spikes/alerts/SPEC.md §1): urgent low = "game over" (longer, unmistakable,
not harsh), low = "lost a life", high = "final lap" speed-up (short, it's frequent), very high = "boss incoming",
rising/falling fast = sweeps, no data = "connection lost".
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import lfilter

SR = 44100
LOUDER_DB = 5.0   # notification sounds sit well above music loudness
OUT = Path(__file__).parent / "out"
KINDS = ["urgent_low", "low", "high", "very_high", "falling_fast", "rising_fast", "no_data"]
rng = np.random.default_rng(15)


def hz(m: float) -> float:
    return 440.0 * 2 ** ((m - 69) / 12)


# --------------------------------------------------------------------------------------------- oscillators


def phase_of(f, n):
    """Phase in cycles for a constant or per-sample frequency."""
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    return np.cumsum(f) / SR, f / SR


def _blep(t, dt):
    y = np.zeros_like(t)
    a = t < dt
    x = t[a] / dt[a]
    y[a] = x + x - x * x - 1
    b = t > 1 - dt
    x = (t[b] - 1) / dt[b]
    y[b] = x * x + x + x + 1
    return y


def saw(f, n):
    ph, dt = phase_of(f, n)
    t = ph % 1.0
    return 2 * t - 1 - _blep(t, dt)


def pulse(f, n, duty=0.5):
    ph, dt = phase_of(f, n)
    t1 = ph % 1.0
    t2 = (ph + duty) % 1.0
    return (2 * t1 - 1 - _blep(t1, dt)) - (2 * t2 - 1 - _blep(t2, dt))


def tri(f, n, steps=0):
    ph, _ = phase_of(f, n)
    t = ph % 1.0
    y = 4 * np.abs(t - 0.5) - 1
    return np.round(y * steps) / steps if steps else y


def sine(f, n):
    ph, _ = phase_of(f, n)
    return np.sin(2 * np.pi * ph)


def additive(f, n, bright, k=12):
    """Brass-like: harmonics 1/k shaped by a per-sample brightness 0..1 (a 'filter' that opens with the envelope)."""
    ph, _ = phase_of(f, n)
    f0 = float(np.max(np.broadcast_to(np.asarray(f, dtype=float), (n,))))
    y = np.zeros(n)
    for h in range(1, k + 1):
        if h * f0 > SR * 0.45:
            break
        y += np.sin(2 * np.pi * h * ph) / h * np.power(bright, h - 1)
    return y


def noise(n):
    return rng.uniform(-1, 1, n)


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    return lfilter([1 - a], [1, -a], x)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def pluck(f, dur, decay=0.996, bright=0.5):
    """Karplus-Strong (harp / plucked string), vectorised one delay line at a time."""
    n = int(dur * SR)
    p = max(2, int(round(SR / f)))
    y = np.zeros(n + p)
    y[:p] = lowpass(noise(p), 2000 + 8000 * bright)
    i = p
    while i < n + p:
        j = min(i + p, n + p)
        src = y[i - p:j - p]
        nxt = y[i - p + 1:j - p + 1] if j - p + 1 <= i else np.append(y[i - p + 1:i], 0)
        if len(nxt) < len(src):
            nxt = np.append(nxt, src[-1:])
        y[i:j] = decay * 0.5 * (src + nxt[:len(src)])
        i = j
    return y[p:p + n]


# --------------------------------------------------------------------------------------------- envelopes & notes


def env(n, a=0.005, d=0.05, s=0.7, r=0.05, hold=None):
    """ADSR over n samples; release fits at the end."""
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    out = np.full(n, s, dtype=float)
    a_n = min(a_n, n)
    out[:a_n] = np.linspace(0, 1, a_n, endpoint=False)
    d_end = min(n, a_n + d_n)
    out[a_n:d_end] = np.linspace(1, s, d_end - a_n, endpoint=False)
    if r_n and n > r_n:
        out[n - r_n:] *= np.linspace(1, 0, r_n)
    return out


def expdecay(n, tau):
    return np.exp(-np.arange(n) / (tau * SR))


class Track:
    def __init__(self, dur: float):
        self.buf = np.zeros(int(dur * SR) + SR)

    def add(self, at: float, x: np.ndarray, gain=1.0, pan=None):
        i = int(at * SR)
        end = min(len(self.buf), i + len(x))
        self.buf[i:end] += gain * x[:end - i]

    def out(self):
        y = self.buf
        nz = np.nonzero(np.abs(y) > 1e-4)[0]
        return y[:nz[-1] + int(0.03 * SR)] if len(nz) else y


def freq_curve(n, m0, m1=None, vib=0.0, vib_rate=5.5, vib_delay=0.15, curve=1.0):
    """Per-sample frequency: slide m0→m1 (semitones, curve>1 = late slide) plus delayed vibrato (semitones)."""
    t = np.arange(n) / SR
    if m1 is None:
        m = np.full(n, float(m0))
    else:
        x = np.linspace(0, 1, n) ** curve
        m = m0 + (m1 - m0) * x
    if vib:
        ramp = np.clip((t - vib_delay) / 0.2, 0, 1)
        m = m + vib * ramp * np.sin(2 * np.pi * vib_rate * t)
    return 440.0 * 2 ** ((m - 69) / 12)


# --------------------------------------------------------------------------------------------- instruments
# each: (midi, dur, **kw) -> samples


def i_pulse(m, dur, duty=0.5, m1=None, vib=0.0, a=0.003, d=0.06, s=0.75, r=0.04, curve=1.0, **_):
    n = int(dur * SR)
    return pulse(freq_curve(n, m, m1, vib, curve=curve), n, duty) * env(n, a, d, s, r) * 0.5


def i_tri(m, dur, m1=None, vib=0.0, steps=8, r=0.03, **_):
    n = int(dur * SR)
    return tri(freq_curve(n, m, m1, vib), n, steps) * env(n, 0.002, 0.02, 0.9, r)


def i_brass(m, dur, m1=None, vib=0.0, a=0.03, **_):
    n = int(dur * SR)
    e = env(n, a, 0.12, 0.7, 0.06)
    f = freq_curve(n, m, m1, vib, vib_rate=6)
    y = sum(additive(f * (1 + det), n, np.clip(0.35 + 0.55 * e, 0, 0.95)) for det in (-0.004, 0, 0.005)) / 3
    return y * e * 0.7


def i_supersaw(m, dur, m1=None, vib=0.0, **_):
    n = int(dur * SR)
    f = freq_curve(n, m, m1, vib)
    y = sum(saw(f * (1 + det), n) for det in (-0.009, -0.003, 0.0, 0.004, 0.01)) / 5
    return lowpass(y, 5200) * env(n, 0.008, 0.1, 0.65, 0.05) * 0.8


def i_flute(m, dur, m1=None, vib=0.18, **_):
    n = int(dur * SR)
    f = freq_curve(n, m, m1, vib, vib_rate=5.2, vib_delay=0.18)
    ph, _ = phase_of(f, n)
    y = np.sin(2 * np.pi * ph) + 0.18 * np.sin(4 * np.pi * ph) + 0.06 * np.sin(6 * np.pi * ph)
    breath = lowpass(noise(n), 3000) * 0.05
    return (y + breath) * env(n, 0.04, 0.08, 0.8, 0.08) * 0.6


def i_bell(m, dur, ratio=3.5, index=2.2, tau=0.5, **_):
    n = int(dur * SR)
    fc = hz(m)
    t = np.arange(n) / SR
    idx = index * expdecay(n, tau * 0.6)
    y = np.sin(2 * np.pi * fc * t + idx * np.sin(2 * np.pi * fc * ratio * t))
    return y * expdecay(n, tau) * env(n, 0.002, 0.0, 1, 0.02) * 0.6


def i_marimba(m, dur, tau=0.35, **_):
    n = int(dur * SR)
    f = hz(m)
    t = np.arange(n) / SR
    y = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * f * 3.93 * t) * expdecay(n, tau * 0.25)
    y += 0.06 * np.sin(2 * np.pi * f * 9.2 * t) * expdecay(n, tau * 0.08)
    return y * expdecay(n, tau) * env(n, 0.002, 0, 1, 0.02) * 0.7


def i_glass(m, dur, tau=0.9, **_):
    n = int(dur * SR)
    f = hz(m)
    t = np.arange(n) / SR
    y = np.sin(2 * np.pi * f * t) + 0.3 * np.sin(2 * np.pi * f * 2.0 * t) * expdecay(n, tau * 0.5)
    y += 0.12 * np.sin(2 * np.pi * f * 5.4 * t) * expdecay(n, tau * 0.15)
    return y * expdecay(n, tau) * env(n, 0.01, 0, 1, 0.05) * 0.55


def i_harp(m, dur, **_):
    return pluck(hz(m), dur, decay=0.9975, bright=0.4) * env(int(dur * SR), 0.001, 0, 1, 0.05) * 0.9


def i_kick(m=0, dur=0.25, **_):
    n = int(dur * SR)
    f = 45 + 110 * expdecay(n, 0.03)
    return sine(f, n) * expdecay(n, 0.09) * 1.0


def i_snare(m=0, dur=0.18, tone=190, **_):
    n = int(dur * SR)
    return (highpass(noise(n), 1200) * 0.7 * expdecay(n, 0.06) + sine(tone, n) * 0.4 * expdecay(n, 0.04)) * 0.8


def i_hat(m=0, dur=0.06, **_):
    n = int(dur * SR)
    return highpass(noise(n), 7000) * expdecay(n, 0.015) * 0.5


def i_nes_noise(m=0, dur=0.12, period=8, **_):
    """NES-style noise: random steps held for `period` samples, decaying."""
    n = int(dur * SR)
    steps = rng.choice([-1.0, 1.0], n // period + 1)
    return np.repeat(steps, period)[:n] * expdecay(n, dur / 3) * 0.35


def i_timpani(m, dur, **_):
    n = int(dur * SR)
    f = freq_curve(n, m + 0.6, m, curve=0.3)
    return (sine(f, n) + 0.3 * sine(f * 1.5, n)) * expdecay(n, 0.45) * env(n, 0.004, 0, 1, 0.05) * 0.9


def i_sweep(m, dur, m1, wave="pulse", duty=0.25, curve=1.0, **_):
    n = int(dur * SR)
    f = freq_curve(n, m, m1, curve=curve)
    y = {"pulse": lambda: pulse(f, n, duty), "saw": lambda: lowpass(saw(f, n), 4000), "sine": lambda: sine(f, n),
         "tri": lambda: tri(f, n)}[wave]()
    return y * env(n, 0.005, 0.05, 0.8, 0.05) * 0.5


def i_whoosh(dur, up=True, **_):
    n = int(dur * SR)
    x = noise(n)
    cut = np.linspace(400, 6000, n) if up else np.linspace(6000, 400, n)
    # piecewise one-pole with a moving cutoff
    y = np.zeros(n)
    blk = 512
    for i in range(0, n, blk):
        y[i:i + blk] = lowpass(x[i:i + blk], float(cut[i]))
    return y * np.sin(np.linspace(0, np.pi, n)) * 0.6


# --------------------------------------------------------------------------------------------- mix helpers


def seq(tr: Track, inst, notes, at=0.0, gain=1.0, **kw):
    """notes: [(midi|None, dur)] played back to back from `at`; None = rest. Returns the end time."""
    t = at
    for item in notes:
        m, d = item[0], item[1]
        extra = item[2] if len(item) > 2 else {}
        if m is not None:
            tr.add(t, inst(m, d * (1.0 if extra.get("legato") is False else 0.98), **{**kw, **extra}), gain)
        t += d
    return t


def echo(y, delay=0.18, fb=0.3, mix=0.35):
    d = int(delay * SR)
    a = np.zeros(d + 1)
    a[0] = 1
    a[d] = -fb
    wet = lfilter([1], a, np.concatenate([y, np.zeros(int(delay * SR * 4))]))
    out = np.concatenate([y, np.zeros(len(wet) - len(y))])
    return out * (1 - mix) + wet * mix


def reverb(y, mix=0.25, size=1.0):
    pad = np.concatenate([y, np.zeros(int(1.2 * SR))])
    wet = np.zeros_like(pad)
    for d, g in ((0.0297, 0.77), (0.0371, 0.75), (0.0411, 0.73), (0.0437, 0.71)):
        D = int(d * size * SR)
        a = np.zeros(D + 1)
        a[0], a[D] = 1, -g
        wet += lfilter([1], a, pad)
    for d, g in ((0.005, 0.7), (0.0017, 0.7)):
        D = int(d * SR)
        b = np.zeros(D + 1)
        b[0], b[D] = -g, 1
        a = np.zeros(D + 1)
        a[0], a[D] = 1, -g
        wet = lfilter(b, a, wet)
    return pad * (1 - mix) + wet * mix * 0.25


def finish(y, target_db=-17.0, fade=0.03):
    """Trim, fade out, normalise loudness (RMS of the loud part) and keep peaks ≤ -1 dBFS."""
    nz = np.nonzero(np.abs(y) > 1e-3 * np.max(np.abs(y)))[0]
    y = y[:nz[-1] + 1] if len(nz) else y
    f = min(len(y), int(fade * SR))
    y[-f:] *= np.linspace(1, 0, f)
    y = highpass(y, 30)
    loud = y[np.abs(y) > 0.05 * np.max(np.abs(y))]
    rms = np.sqrt(np.mean(loud ** 2)) if len(loud) else 1
    y = y * (10 ** ((target_db + LOUDER_DB) / 20) / rms)
    peak = np.max(np.abs(y))
    if peak > 0.89:
        y = np.tanh(y / 0.89 * 1.2) / np.tanh(1.2) * 0.89 if peak < 2 else y / peak * 0.89
    return y


# ============================================================================================= ARCADE (8-bit)
# pulse leads (12.5/25/50 %), stepped triangle bass, NES-ish noise drums.


def arcade_urgent_low(v):
    tr = Track(5)
    if v == 0:   # descending minor, slowing down, long wobbling last note
        e = seq(tr, i_pulse, [(76, .11), (72, .11), (69, .11), (64, .22), (None, .12), (71, .22), (70, .22), (69, .3)], duty=.5)
        seq(tr, i_pulse, [(68, 1.5, {"vib": .35})], at=e, duty=.5, r=.6)
        seq(tr, i_tri, [(52, .66), (None, .12), (47, .74), (44, 1.5, {"vib": .2})], gain=.9, r=.5)
    elif v == 1:  # "wah wah wah wahhh"
        e = 0
        for i, m in enumerate((67, 66, 65)):
            e = seq(tr, i_pulse, [(m, .42, {"m1": m - .35, "curve": 2})], at=e, duty=.25)
        seq(tr, i_pulse, [(64, 1.6, {"vib": .45, "m1": 63.6})], at=e, duty=.25, r=.7)
        seq(tr, i_tri, [(43, .42), (42, .42), (41, .42), (40, 1.6)], gain=.8, r=.6)
    else:        # chromatic fall + "life lost" sweep
        e = seq(tr, i_pulse, [(79, .14), (78, .14), (77, .14), (76, .14), (None, .1)], duty=.125)
        tr.add(e, i_sweep(76, .55, 52, wave="pulse", duty=.5, curve=.7), .9)
        seq(tr, i_pulse, [(55, .3), (52, .3), (48, 1.4, {"vib": .3})], at=e + .65, duty=.5, r=.6)
        seq(tr, i_tri, [(45, .66), (None, .1), (41, .6), (36, 1.4)], at=0, gain=.9, r=.6)
    return finish(echo(tr.out(), .14, .2, .2), -16)


def arcade_low(v):
    tr = Track(3)
    if v == 0:
        tr.add(0, i_sweep(84, .32, 60, wave="pulse", duty=.25, curve=.6))
        seq(tr, i_pulse, [(64, .14), (60, .14), (55, .45, {"vib": .3})], at=.34, duty=.5, r=.2)
    elif v == 1:
        seq(tr, i_pulse, [(72, .1), (67, .1), (64, .1), (60, .1), (55, .1), (52, .5, {"vib": .3})], duty=.125, r=.2)
        seq(tr, i_tri, [(40, .5), (36, .5)], gain=.8)
    else:
        for k in range(3):
            tr.add(k * .2, i_sweep(76 - 4 * k, .17, 70 - 4 * k, wave="pulse", duty=.5))
        seq(tr, i_pulse, [(57, .5, {"vib": .35})], at=.62, duty=.25, r=.25)
    return finish(echo(tr.out(), .12, .2, .2))


def arcade_high(v):
    """Final-lap speed-up: a motif, then the same motif higher and faster."""
    tr = Track(2.5)
    motifs = [[0, 4, 7, 4], [0, 2, 4, 7], [0, 7, 5, 9]]
    base = [72, 74, 70][v]
    mo = motifs[v]
    e = seq(tr, i_pulse, [(base + x, .085) for x in mo], duty=.25)
    e = seq(tr, i_pulse, [(base + 5 + x, .065) for x in mo], at=e, duty=.25)
    seq(tr, i_pulse, [(base + 12, .28, {"vib": .15})], at=e, duty=.5, r=.12)
    seq(tr, i_tri, [(base - 24, .34), (base - 19, .26), (base - 12, .28)], gain=.7)
    for k in range(4):
        tr.add(e - .2 + k * .05, i_nes_noise(dur=.05, period=4), .7)
    return finish(tr.out(), -18)


def arcade_very_high(v):
    """Boss incoming: two-note alarm, bass pulse, rising."""
    tr = Track(3)
    a, b = [(76, 79), (74, 77), (77, 80)][v]
    t = 0
    for k in range(3):
        t = seq(tr, i_pulse, [(a + k, .12), (b + k, .12)], at=t, duty=.25)
        tr.add(k * .24, i_nes_noise(dur=.1, period=12), .8)
    seq(tr, i_pulse, [(b + 5, .5, {"vib": .25})], at=t, duty=.5, r=.2)
    seq(tr, i_tri, [(40, .24), (41, .24), (42, .24), (45, .5)], gain=.9)
    return finish(tr.out(), -17)


def arcade_falling_fast(v):
    tr = Track(2)
    m0, m1 = [(86, 62), (84, 58), (88, 64)][v]
    tr.add(0, i_sweep(m0, .45, m1, wave="pulse", duty=[.5, .25, .125][v], curve=.8))
    seq(tr, i_pulse, [(m1 - 2, .08), (m1 - 7, .2)], at=.46, duty=.5)
    return finish(tr.out(), -19)


def arcade_rising_fast(v):
    tr = Track(2)
    root = [60, 62, 57][v]
    notes = [root + x for x in ([0, 4, 7, 12, 16, 19, 24], [0, 3, 7, 10, 12, 15, 19], [0, 5, 7, 12, 17, 19, 24])[v]]
    seq(tr, i_pulse, [(m, .045) for m in notes] + [(notes[-1], .2)], duty=.25)
    return finish(tr.out(), -19)


def arcade_no_data(v):
    tr = Track(2)
    seq(tr, i_pulse, [(81, .09), (None, .05), (76, .09), (None, .05), ([69, 71, 66][v], .3)], duty=.5)
    tr.add(.5, i_nes_noise(dur=.25, period=[3, 6, 10][v]), .6)
    return finish(tr.out(), -19)


# ============================================================================================= KART (racing)
# brass stabs, supersaw leads, punchy drums, engine/turbo sweeps.


def drums_roll(tr, at, n=6, step=.06, crescendo=True):
    for k in range(n):
        tr.add(at + k * step, i_snare(dur=.12), (.4 + .6 * k / n) if crescendo else .7)


def kart_high(v):
    """Final lap: a 4-note riff, again faster and a step up, then a stab."""
    tr = Track(2.5)
    riffs = [[0, 0, 7, 5], [0, 3, 5, 7], [7, 5, 7, 12]]
    base = [67, 69, 65][v]
    r = riffs[v]
    e = seq(tr, i_supersaw, [(base + x, .1) for x in r])
    e = seq(tr, i_supersaw, [(base + 2 + x, .075) for x in r], at=e)
    for chord in ((0, 4, 7),):
        for x in chord:
            tr.add(e, i_brass(base + 12 + x - 12, .34), .45)
    tr.add(e, i_supersaw(base + 12, .34), .6)
    tr.add(0, i_kick(), .8)
    tr.add(e, i_kick(), .9)
    drums_roll(tr, e - .3, 5, .06)
    return finish(tr.out(), -17)


def kart_very_high(v):
    tr = Track(3.2)
    n = int(.9 * SR)
    rev = [(40, 52), (38, 50), (41, 55)][v]
    f = freq_curve(n, rev[0], rev[1], curve=.7)
    eng = lowpass(saw(f, n) + .5 * pulse(f * .5, n, .3), 1800) * env(n, .05, .1, .9, .1) * .6
    tr.add(0, eng)
    root = [62, 60, 63][v]
    t = .85
    for k, x in enumerate((0, 0, 3, 7)):
        tr.add(t, sum(i_brass(root + x + y, .16 if k < 3 else .55) for y in (0, 7, 12)) / 2)
        tr.add(t, i_kick(), .7)
        t += .17
    drums_roll(tr, .5, 6, .055)
    return finish(tr.out(), -16)


def kart_urgent_low(v):
    """Race over: descending brass, slowing down, timpani, long last chord."""
    tr = Track(5)
    lines = [[72, 70, 67, 63, 62], [71, 69, 66, 64, 59], [74, 72, 68, 65, 64]]
    durs = [.18, .2, .24, .34]
    line = lines[v]
    t = 0
    for m, d in zip(line[:-1], durs):
        tr.add(t, i_brass(m, d * .95), .8)
        tr.add(t, i_brass(m - 12, d * .95), .4)
        t += d
    for y in (0, 3, 7):
        tr.add(t + .05, i_brass(line[-1] - 12 + y, 1.7, vib=.12), .4)
    tr.add(t + .05, i_supersaw(line[-1], 1.6, vib=.18), .35)
    tr.add(t + .05, i_timpani(line[-1] - 24, 1.4), 1)
    tr.add(0, i_timpani(line[0] - 24, .8), .8)
    return finish(reverb(tr.out(), .3), -15.5)


def kart_low(v):
    """Spin out: wobbly descending sweep + two notes."""
    tr = Track(2.5)
    n = int(.55 * SR)
    t = np.arange(n) / SR
    m = np.linspace([79, 82, 77][v], [60, 62, 57][v], n) + 1.2 * np.sin(2 * np.pi * 9 * t)
    tr.add(0, lowpass(saw(440 * 2 ** ((m - 69) / 12), n), 3000) * env(n, .01, .05, .8, .05) * .5)
    seq(tr, i_brass, [([59, 61, 56][v], .16), ([55, 56, 52][v], .45, {"vib": .2})], at=.58)
    tr.add(.58, i_kick(), .6)
    return finish(tr.out(), -17)


def kart_falling_fast(v):
    tr = Track(2)
    n = int(.6 * SR)
    f = freq_curve(n, [60, 58, 62][v], [43, 41, 45][v], curve=.5)
    tr.add(0, lowpass(saw(f, n), 1500) * env(n, .01, .1, .8, .1) * .6)
    tr.add(0, i_whoosh(.6, up=False), .5)
    return finish(tr.out(), -19)


def kart_rising_fast(v):
    tr = Track(2)
    tr.add(0, i_whoosh(.55, up=True), .7)
    n = int(.5 * SR)
    f = freq_curve(n, [48, 50, 46][v], [72, 74, 70][v], curve=1.6)
    tr.add(0, lowpass(saw(f, n), 4000) * env(n, .01, .1, .8, .05) * .45)
    tr.add(.5, i_supersaw([72, 74, 70][v], .2), .5)
    return finish(tr.out(), -19)


def kart_no_data(v):
    tr = Track(2)
    tr.add(0, highpass(lowpass(noise(int(.35 * SR)), 5000), 800) * expdecay(int(.35 * SR), .15) * .4)
    seq(tr, i_sine_beep, [([88, 86, 90][v], .1), (None, .07), ([83, 81, 85][v], .22)], at=.3)
    return finish(tr.out(), -20)


def i_sine_beep(m, dur, **_):
    n = int(dur * SR)
    return sine(hz(m), n) * env(n, .003, .02, .8, .02) * .6


# ============================================================================================= QUEST (adventure / RPG)
# harp, flute, bells, timpani — gentle but clear.


def quest_urgent_low(v):
    """You have fallen: slow harp arpeggio downwards over a minor chord, bell toll, flute sigh."""
    tr = Track(5.5)
    roots = [57, 55, 59]
    r = roots[v]
    arp = [r + 24, r + 19, r + 15, r + 12, r + 7, r + 3, r]
    t = 0
    for k, m in enumerate(arp):
        tr.add(t, i_harp(m, 2.0), .8)
        t += .13 + k * .02
    tr.add(t + .1, i_bell(r + 12, 2.6, ratio=2.76, index=1.5, tau=1.0), .5)
    seq(tr, i_flute, [(r + 15, .45), (r + 14, .45), (r + 12, 1.3)], at=t + .15, gain=.55)
    tr.add(t + .1, i_timpani(r - 12, 1.6), .6)
    return finish(reverb(tr.out(), .35, 1.2), -15.5)


def quest_low(v):
    tr = Track(3)
    r = [76, 74, 77][v]
    for k, x in enumerate([0, -3, -7] if v != 1 else [0, -5, -8]):
        tr.add(k * .22, i_bell(r + x, 1.0, ratio=2.0, index=1.2, tau=.45), .8)
    tr.add(.66, i_harp(r - 19, 1.2), .6)
    return finish(reverb(tr.out(), .3), -17)


def quest_high(v):
    """Hurry: a flute run, then the same run faster and higher."""
    tr = Track(2.5)
    runs = [[0, 2, 4, 7], [0, 4, 5, 7], [0, 3, 7, 8]]
    base = [74, 72, 76][v]
    e = seq(tr, i_flute, [(base + x, .09, {"vib": 0}) for x in runs[v]])
    e = seq(tr, i_flute, [(base + 5 + x, .07, {"vib": 0}) for x in runs[v]], at=e)
    seq(tr, i_flute, [(base + 12, .32)], at=e)
    tr.add(e, i_harp(base - 12, .6), .6)
    tr.add(e, i_harp(base - 5, .6), .5)
    return finish(reverb(tr.out(), .2), -18)


def quest_very_high(v):
    """Danger: timpani hits and a minor flourish."""
    tr = Track(3)
    r = [50, 48, 52][v]
    for k in range(3):
        tr.add(k * .2, i_timpani(r, .5), .9)
    fl = [[0, 1, 0, 3, 7], [0, 3, 2, 3, 8], [0, 1, 3, 1, 6]][v]
    seq(tr, i_flute, [(r + 24 + x, .1, {"vib": 0}) for x in fl[:-1]] + [(r + 24 + fl[-1], .5)], at=.6)
    tr.add(.6 + .4, i_bell(r + 36, 1.2, ratio=3.1, tau=.6), .35)
    return finish(reverb(tr.out(), .25), -17)


def quest_falling_fast(v):
    tr = Track(2)
    scale = [0, 2, 3, 5, 7, 8, 10, 12, 14, 15]
    r = [60, 62, 57][v]
    for k, x in enumerate(reversed(scale)):
        tr.add(k * .04, i_harp(r + x, .8), .55)
    return finish(reverb(tr.out(), .25), -19)


def quest_rising_fast(v):
    tr = Track(2)
    scale = [0, 2, 4, 7, 9, 12, 14, 16, 19, 21]
    r = [60, 62, 58][v]
    for k, x in enumerate(scale):
        tr.add(k * .04, i_harp(r + x, .8), .55)
    return finish(reverb(tr.out(), .25), -19)


def quest_no_data(v):
    tr = Track(2.5)
    pair = [(79, 73), (77, 71), (80, 74)][v]
    tr.add(0, i_bell(pair[0], 1.2, ratio=1.41, index=1.0, tau=.5), .7)
    tr.add(.28, i_bell(pair[1], 1.4, ratio=1.41, index=1.0, tau=.6), .7)
    return finish(reverb(tr.out(), .35), -19)


# ============================================================================================= SOFT (calm, non-game)
# marimba and glass, no drums, slower attacks.


def soft_urgent_low(v):
    tr = Track(5)
    r = [64, 62, 65][v]
    pattern = [12, 7, 3, 0]
    t = 0
    for rep in range(2):
        for x in pattern:
            tr.add(t, i_marimba(r + x, .7, tau=.4), .9)
            t += .2
        t += .25
    tr.add(t, i_glass(r - 5, 2.2, tau=1.1), .8)
    tr.add(t, i_glass(r + 3 - 12, 2.2, tau=1.1), .4)
    return finish(reverb(tr.out(), .3), -16)


def soft_low(v):
    tr = Track(2.5)
    r = [72, 71, 74][v]
    for k, x in enumerate([0, -5, -9]):
        tr.add(k * .2, i_marimba(r + x, .8), .9)
    return finish(reverb(tr.out(), .25), -17.5)


def soft_high(v):
    tr = Track(2.5)
    r = [72, 70, 74][v]
    for k, x in enumerate(([0, 4, 7, 12], [0, 2, 7, 9], [0, 5, 7, 12])[v]):
        tr.add(k * .11, i_glass(r + x, .9, tau=.5), .7)
    return finish(reverb(tr.out(), .25), -19)


def soft_very_high(v):
    tr = Track(3)
    r = [70, 69, 72][v]
    for rep in range(2):
        for k, x in enumerate([0, 3, 7]):
            tr.add(rep * .55 + k * .12, i_marimba(r + x + rep * 2, .6), .9)
    tr.add(1.1, i_glass(r + 14, 1.2, tau=.6), .6)
    return finish(reverb(tr.out(), .25), -18)


def soft_falling_fast(v):
    tr = Track(2)
    r = [79, 77, 81][v]
    for k, x in enumerate([0, -2, -5, -7, -9, -12]):
        tr.add(k * .07, i_glass(r + x, .6, tau=.3), .6)
    return finish(reverb(tr.out(), .25), -20)


def soft_rising_fast(v):
    tr = Track(2)
    r = [67, 65, 69][v]
    for k, x in enumerate([0, 2, 5, 7, 9, 12]):
        tr.add(k * .07, i_glass(r + x, .6, tau=.3), .6)
    return finish(reverb(tr.out(), .25), -20)


def soft_no_data(v):
    tr = Track(2)
    tr.add(0, i_glass([76, 74, 77][v], 1.0, tau=.5), .7)
    tr.add(.35, i_glass([76, 74, 77][v] - 6, 1.2, tau=.6), .6)
    return finish(reverb(tr.out(), .3), -20)


PACKS = {
    "arcade": "Arcade — 8-bit",
    "kart": "Kart — racing",
    "quest": "Quest — adventure",
    "soft": "Soft — calm",
}


def render(pack: str, kind: str, v: int) -> np.ndarray:
    return globals()[f"{pack}_{kind}"](v)


def main(argv):
    OUT.mkdir(exist_ok=True)
    only = argv[1] if len(argv) > 1 else None
    # "None" in the app: the channel still points at the provider, which then serves this
    sf.write(OUT / "silence.ogg", np.zeros(int(0.2 * SR), dtype=np.float32), SR, format="OGG", subtype="VORBIS")
    for pack in PACKS:
        for kind in KINDS:
            for v in range(3):
                name = f"{pack}_{kind}_{v + 1}"
                if only and only not in name:
                    continue
                y = render(pack, kind, v).astype(np.float32)
                wav = OUT / f"{name}.wav"
                sf.write(wav, y, SR, subtype="PCM_16")
                sf.write(OUT / f"{name}.ogg", y, SR, format="OGG", subtype="VORBIS")
                subprocess.run(["afconvert", "-f", "caff", "-d", "ima4", str(wav), str(OUT / f"{name}.caf")], check=True)
                subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "64000", str(wav), str(OUT / f"{name}.m4a")],
                               check=True)
                print(f"{name:28s} {len(y) / SR:5.2f} s  peak {20 * np.log10(np.max(np.abs(y))):5.1f} dBFS")


if __name__ == "__main__":
    main(sys.argv)

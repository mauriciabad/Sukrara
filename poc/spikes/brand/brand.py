#!/usr/bin/env python3
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Name + icon + style candidates for the showcase app (investigation 09).

Every icon is defined once, as vector shapes on Android's 108-unit adaptive-icon canvas (visible area 18..90,
safe circle r=33 around 54,54). From that single definition this script writes SVG for the gallery and macOS,
VectorDrawable XML for Android, and PNGs (via render.swift) for everything else.

  python3 -I brand.py              build everything into build/ and open nothing
  python3 -I brand.py gallery      … then open build/gallery/index.html
  python3 -I brand.py export ID    platform-ready assets for one candidate → assets/ID/ (commit this one)
  python3 -I brand.py sheet        just the icon contact sheet (fast design loop) → build/sheet.png
"""
import html
import json
import math
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build")
RENDER = os.path.join(BUILD, "render")


# ---------------------------------------------------------------- geometry
class P:
    """Absolute-coordinate path that can be pushed through a similarity transform."""

    def __init__(self):
        self.seg = []

    def M(self, x, y): self.seg.append(("M", [(x, y)])); return self
    def L(self, x, y): self.seg.append(("L", [(x, y)])); return self
    def Q(self, x1, y1, x, y): self.seg.append(("Q", [(x1, y1), (x, y)])); return self
    def C(self, x1, y1, x2, y2, x, y): self.seg.append(("C", [(x1, y1), (x2, y2), (x, y)])); return self
    def A(self, r, large, sweep, x, y): self.seg.append(("A", [(x, y)], (r, large, sweep))); return self
    def Z(self): self.seg.append(("Z", [])); return self

    def map(self, f, k):
        out = P()
        for s in self.seg:
            pts = [f(p) for p in s[1]]
            out.seg.append((s[0], pts) if s[0] != "A" else ("A", pts, (s[2][0] * k, s[2][1], s[2][2])))
        return out

    def d(self):
        parts = []
        for s in self.seg:
            if s[0] == "A":
                r, large, sweep = s[2]
                (x, y), = s[1]
                parts.append(f"A{r:.2f},{r:.2f} 0 {large},{sweep} {x:.2f},{y:.2f}")
            else:
                parts.append(s[0] + " ".join(f"{x:.2f},{y:.2f}" for x, y in s[1]))
        return "".join(parts)


def sim(tx, ty, deg=0.0, s=1.0):
    """Similarity transform: scale by s, rotate by deg (clockwise on screen, y down), then translate."""
    c, n = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return (lambda p: (tx + s * (p[0] * c - p[1] * n), ty + s * (p[0] * n + p[1] * c))), s


def circle(cx, cy, r):
    return P().M(cx - r, cy).A(r, 1, 0, cx + r, cy).A(r, 1, 0, cx - r, cy).Z()


def poly(pts, r=0.0):
    """Closed polygon; r > 0 rounds every corner with a quadratic curve."""
    p = P()
    if r <= 0:
        p.M(*pts[0])
        for q in pts[1:]:
            p.L(*q)
        return p.Z()
    n = len(pts)
    for i in range(n):
        v, a, b = pts[i], pts[i - 1], pts[(i + 1) % n]
        da, db = math.dist(v, a), math.dist(v, b)
        ta, tb = min(r / da, 0.5), min(r / db, 0.5)
        pa = (v[0] + (a[0] - v[0]) * ta, v[1] + (a[1] - v[1]) * ta)
        pb = (v[0] + (b[0] - v[0]) * tb, v[1] + (b[1] - v[1]) * tb)
        (p.M if i == 0 else p.L)(*pa)
        p.Q(v[0], v[1], *pb)
    return p.Z()


def inset(pts, k):
    """Shrink a polygon towards its centroid (used for the gaps between the sugar cube's faces)."""
    cx, cy = sum(x for x, _ in pts) / len(pts), sum(y for _, y in pts) / len(pts)
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in pts]


def arc_points(p0, p1, r, large, sweep, n=24):
    """Points along an SVG circular arc (endpoint parameterisation), for bounding boxes."""
    (x0, y0), (x1, y1) = p0, p1
    mx, my, dx, dy = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    d = math.hypot(dx, dy)
    if d == 0:
        return [p0]
    h = math.sqrt(max(r * r - d * d, 0))
    sign = -1 if large == sweep else 1
    cx, cy = mx + sign * h * (-dy) / d, my + sign * h * dx / d
    a0, a1 = math.atan2(y0 - cy, x0 - cx), math.atan2(y1 - cy, x1 - cx)
    span = (a1 - a0) % (2 * math.pi) if sweep else -((a0 - a1) % (2 * math.pi))
    return [(cx + r * math.cos(a0 + span * i / n), cy + r * math.sin(a0 + span * i / n)) for i in range(n + 1)]


def glyph_box(shapes, pad=0.1):
    """Square crop (x, y, size) around the shapes, for small one-colour uses."""
    xs, ys = [], []
    for sh in shapes:
        h = (sh["w"] or 0) / 2
        cur = (0, 0)
        for seg in sh["p"].seg:
            pts = seg[1]
            if seg[0] == "A":
                r, large, sweep = seg[2]
                pts = arc_points(cur, seg[1][0], r, large, sweep)
            for x, y in pts:
                xs += [x - h, x + h]
                ys += [y - h, y + h]
            if seg[1]:
                cur = seg[1][-1]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    size = max(x1 - x0, y1 - y0) * (1 + 2 * pad)
    return (round((x0 + x1 - size) / 2, 2), round((y0 + y1 - size) / 2, 2), round(size, 2))


def shape(path, c="fg", a=1.0, w=None):
    """c: palette slot ('fg' | 'accent'); a: alpha; w: stroke width (None = filled)."""
    return {"p": path, "c": c, "a": a, "w": w}


# ---------------------------------------------------------------- icon concepts
def arrow_shapes(f, k):
    """GlucoseBar's trend arrow (spikes/menubar TrendArrow.swift), unit geometry pushed through f."""
    shaft = P().M(-0.42, 0).L(0.25, 0).map(f, k)
    head = poly([(0.46, 0), (0.06, 0.30), (0.16, 0), (0.06, -0.30)]).map(f, k)
    return [shape(shaft, w=0.14 * k), shape(head)]


def vane():
    f, k = sim(54.6, 54, -32, 40)
    shaft, head = arrow_shapes(f, k)
    shaft["w"] = 0.16 * k
    return [shape(circle(54, 54, 28), a=0.32, w=3.4), shaft, head]


def ebb():
    band = poly([(25, 48), (83, 48), (83, 64), (25, 64)], r=8)
    wave = P().M(28, 67).C(37, 67, 39, 39, 51, 39).C(62, 39, 63, 57, 76, 55)
    return [shape(band, a=0.22), shape(wave, w=6), shape(circle(76, 55, 7), c="accent")]


def cube(cx=54, cy=55, R=26, k=0.86, r=3.2):
    """Isometric sugar cube: three faces with gaps, lit from the top-left."""
    v = {a: (cx + R * math.cos(math.radians(a)), cy + R * math.sin(math.radians(a))) for a in (-90, -30, 30, 90, 150, 210)}
    c = (cx, cy)
    top = [v[-90], v[-30], c, v[210]]
    left = [v[210], c, v[90], v[150]]
    right = [c, v[-30], v[30], v[90]]
    return [shape(poly(inset(top, k), r=r)),
            shape(poly(inset(left, k), r=r), a=0.78),
            shape(poly(inset(right, k), r=r), a=0.55)]


def sucre():
    return cube()


def kite():
    f, k = sim(59, 50, 20, 0.86)
    top, right, bottom, left = (0, -26), (15, -6), (0, 20), (-15, -6)
    x0, y0 = f(bottom)
    tail = P().M(x0, y0).C(x0 - 1, y0 + 10, x0 - 14, y0 + 3, x0 - 17, y0 + 11).C(x0 - 19, y0 + 16, x0 - 23, y0 + 16, x0 - 26, y0 + 18)
    return [shape(poly([top, left, bottom], r=2.2).map(f, k)),
            shape(poly([top, right, bottom], r=2.2).map(f, k), a=0.72),
            shape(tail, c="accent", w=3.6)]


def slope():
    curve = P().M(28, 71).C(35, 71, 38, 60, 45, 61).C(52, 62, 57, 56, 65, 48)
    u = (1 / math.sqrt(2), -1 / math.sqrt(2))
    tangent = P().M(65 - 15 * u[0], 48 - 15 * u[1]).L(65 + 12 * u[0], 48 + 12 * u[1])
    return [shape(curve, a=0.45, w=5), shape(tangent, w=5), shape(circle(65, 48, 7), c="accent")]


def sparkle(x, y, r):
    return P().M(x, y - r).Q(x, y, x + r, y).Q(x, y, x, y + r).Q(x, y, x - r, y).Q(x, y, x, y - r).Z()


def nightjar():
    (ox, oy, r1), (ix, iy, r2) = (50, 57, 24), (61, 48, 20)
    d = math.dist((ox, oy), (ix, iy))
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(r1 * r1 - a * a)
    mx, my = ox + a * (ix - ox) / d, oy + a * (iy - oy) / d
    p1 = (mx + h * (iy - oy) / d, my - h * (ix - ox) / d)
    p2 = (mx - h * (iy - oy) / d, my + h * (ix - ox) / d)
    moon = P().M(*p1).A(r1, 1, 0, *p2).A(r2, 0, 1, *p1).Z()
    return [shape(moon), shape(sparkle(70, 38, 8), c="accent")]


def bezier(p0, p1, p2, p3, t):
    u = 1 - t
    return tuple(u ** 3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t ** 3 * d for a, b, c, d in zip(p0, p1, p2, p3))


def ellipse_halves(cx, cy, rx, ry, deg):
    """(back half, front half) of a tilted ellipse as open paths, so something can sit between them."""
    k = 0.5523
    f, s = sim(cx, cy, deg)
    back = P().M(-rx, 0).C(-rx, -k * ry, -k * rx, -ry, 0, -ry).C(k * rx, -ry, rx, -k * ry, rx, 0).map(f, s)
    front = P().M(rx, 0).C(rx, k * ry, k * rx, ry, 0, ry).C(-k * rx, ry, -rx, k * ry, -rx, 0).map(f, s)
    return back, front


def sine(x0, y, half, n, amp):
    """n half-periods of a smooth wave starting at (x0, y), first hump upwards."""
    p = P().M(x0, y)
    for i in range(n):
        a = -amp if i % 2 == 0 else amp
        x = x0 + i * half
        p.C(x + half * 0.36, y + a * 1.33, x + half * 0.64, y + a * 1.33, x + half, y)
    return p


def sucrepla():
    """A sugar cube standing on a perfectly flat line: sugar, flat, in control."""
    cy, R = 50, 21
    line_y = cy + 0.895 * R + 1.6  # inset bottom corner of the cube (less its rounding) + half the line width
    return cube(54, cy, R, r=2.6) + [shape(P().M(28, line_y).L(80, line_y), w=4.6, c="accent")]


def sucrecore():
    """A solid sugar cube at the core of a larger translucent one: the centre of a wider health ecosystem."""
    outer = cube(54, 54, 28, k=0.9, r=3)
    for s_, a in zip(outer, (0.3, 0.2, 0.12)):
        s_["a"] = a
    return outer + cube(54, 57, 12.5, k=0.84, r=1.6)


def sucreline():
    band = poly([(25, 47), (83, 47), (83, 61), (25, 61)], r=7)
    line = P().M(28, 62).C(34, 62, 35, 42, 42, 42).C(48, 42, 48, 54, 56, 54).L(75, 54)
    return [shape(band, a=0.2), shape(line, w=5.5), shape(circle(76, 54, 6.5), c="accent")]


def sucremap():
    """Day-by-hour heatmap (a 4×4 grid without its corners)."""
    alphas = [[0, .45, .8, 0], [.5, 1, "acc", .6], [.35, .75, 1, .45], [0, .3, .55, 0]]
    size, gap = 11.5, 3.4
    x0 = 54 - (4 * size + 3 * gap) / 2
    out = []
    for r, row in enumerate(alphas):
        for col, a in enumerate(row):
            if not a:
                continue
            x, y = x0 + col * (size + gap), x0 + r * (size + gap)
            tile = poly([(x, y), (x + size, y), (x + size, y + size), (x, y + size)], r=2.8)
            out.append(shape(tile, c="accent") if a == "acc" else shape(tile, a=a))
    return out


def sucretrack():
    """A trail of readings that turns into the trend arrow."""
    p0, p1, p2, p3 = (31, 74), (35, 62), (44, 55), (56, 52)
    dots = [shape(circle(*bezier(p0, p1, p2, p3, t), r), a=a) for t, r, a in ((0, 2.6, .45), (.36, 3.2, .65), (.72, 3.8, .85))]
    f, k = sim(70, 45, -30, 30)
    return dots + arrow_shapes(f, k)


def sucrescout():
    """Compass: a nod to Nightscout, the thing this replaces."""
    f, s = sim(54, 54, 35)
    north = poly([(0, -22), (6.5, 0), (-6.5, 0)], r=1).map(f, s)
    south = poly([(0, 22), (6.5, 0), (-6.5, 0)], r=1).map(f, s)
    return [shape(circle(54, 54, 27), w=3.2, a=0.45), shape(south, a=0.6), shape(north, c="accent"),
            shape(circle(54, 54, 2.6))]


def sucrewave():
    return [shape(sine(25, 46, 11.6, 5, 6.5), w=5.5), shape(sine(25, 64, 11.6, 5, 6.5), w=5.5, a=0.5)]


def sucreapp():
    """“Sucre up”: a sugar cube lifting off."""
    lines = [shape(P().M(x, y0).L(x, y1), w=3.6, a=0.6) for x, y0, y1 in ((45, 75, 81), (54, 76, 85), (63, 75, 81))]
    return cube(54, 48, 21, r=2.8) + lines


def sucreflow():
    top = P().M(25, 43).L(61, 43).C(70, 43, 73, 32, 65, 30).C(59, 29, 57, 35, 61, 37)
    mid = P().M(31, 55).L(80, 55)
    low = P().M(25, 67).L(57, 67).C(66, 67, 69, 78, 61, 80).C(55, 81, 53, 75, 57, 73)
    return [shape(top, w=4.8), shape(mid, w=4.8, a=0.6), shape(low, w=4.8, c="accent")]


def sucreplot():
    axes = P().M(32, 29).L(32, 76).L(79, 76)
    pts = [(41, 64), (48, 55), (55, 59), (63, 47)]
    return ([shape(axes, w=3.4, a=0.45), shape(P().M(37, 67).L(77, 37), w=2.6, a=0.35)]
            + [shape(circle(x, y, 3.8)) for x, y in pts] + [shape(circle(72, 41, 5.4), c="accent")])


def sucrehub():
    """Hub and spokes: the phone in the middle, Mac / watch / family / doctor / relays around it."""
    out, R = [], 23
    for i in range(5):
        a = math.radians(-90 + i * 72)
        x, y = 54 + R * math.cos(a), 54 + R * math.sin(a)
        out.append(shape(P().M(54 + 11 * math.cos(a), 54 + 11 * math.sin(a)).L(54 + (R - 7) * math.cos(a), 54 + (R - 7) * math.sin(a)), w=3, a=0.55))
        out.append(shape(circle(x, y, 5.6)))
    return out + [shape(circle(54, 54, 8.5), c="accent")]


def sucrehub_cube():
    """The sugar cube drawn as a network: edges are links, corners are devices."""
    cx, cy, R = 54, 54, 25
    v = {a: (cx + R * math.cos(math.radians(a)), cy + R * math.sin(math.radians(a))) for a in (-90, -30, 30, 90, 150, 210)}
    ring = [v[a] for a in (-90, -30, 30, 90, 150, 210)]
    edges = poly(ring)
    inner = P().M(cx, cy).L(*v[210]).M(cx, cy).L(*v[-30]).M(cx, cy).L(*v[90])
    return ([shape(edges, w=3.2, a=0.55), shape(inner, w=3.2, a=0.55)]
            + [shape(circle(x, y, 4.6)) for x, y in ring] + [shape(circle(cx, cy, 6.4), c="accent")])


def sucrehub_orbit():
    """A sugar cube with something orbiting it: data going round."""
    back, front = ellipse_halves(54, 56, 31, 11, -18)
    f, _ = sim(54, 56, -18)
    sat = f((31 * math.cos(math.radians(60)), 11 * math.sin(math.radians(60))))
    return [shape(back, w=3, a=0.5)] + cube(54, 54, 18, r=2.4) + [shape(front, w=3), shape(circle(*sat, 4.6), c="accent")]


def sucreguard():
    shield = (P().M(54, 27).C(62, 31, 70, 33, 78, 33).L(78, 52).C(78, 67, 68, 77, 54, 83)
              .C(40, 77, 30, 67, 30, 52).L(30, 33).C(38, 33, 46, 31, 54, 27).Z())
    line = P().M(38, 59).C(42, 59, 44, 49, 48, 49).C(52, 49, 52, 56, 58, 56).L(66, 56)
    return [shape(shield, a=0.2), shape(shield, w=3.6), shape(line, w=4.2), shape(circle(69, 56, 4.6), c="accent")]


def arc(cx, cy, r, a0, a1):
    """Open circular arc from angle a0 to a1 (degrees, clockwise on screen)."""
    pt = lambda a: (cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)))  # noqa: E731
    return P().M(*pt(a0)).A(r, 1 if (a1 - a0) % 360 > 180 else 0, 1, *pt(a1))


def solid_cube(cx, cy, R, k=0.78, r=2.2):
    """Small-size cube: opaque faces, wider gaps — reads as a cube in one colour at 16 px."""
    faces = cube(cx, cy, R, k=k, r=r)
    for f in faces:
        f["a"] = 1.0
    return faces


# Sukrara "live" variants: each has a colour drawing and a simplified one-colour drawing for small sizes.
def sukrara_live():
    return cube(49, 61, 19, r=2.4) + [shape(circle(70, 38, 10.5), w=2.6, a=0.45), shape(circle(70, 38, 5.6), c="accent")]


def sukrara_live_mono():
    return solid_cube(47, 61, 19) + [shape(circle(66, 42, 6.5))]  # just the "now" dot: an arc is noise at 16 px


def sukrara_live_themed():
    """Android themed icon: tinted by the system but alpha is kept, so faces can use opacity; dot tucked in close."""
    faces = cube(48, 59, 19, k=0.84, r=2.4)
    for f, a in zip(faces, (1.0, 0.72, 0.48)):
        f["a"] = a
    return faces + [shape(arc(67, 39, 10.5, -140, 50), w=3.2, a=0.6), shape(circle(67, 39, 5.6))]


def sukrara_signal():
    return (cube(46, 63, 17.5, r=2.2) + [shape(circle(63, 43, 5), c="accent")]
            + [shape(arc(63, 43, r, -115, 25), w=3.2, a=a) for r, a in ((10.5, 0.8), (17, 0.45))])


def sukrara_signal_mono():
    return (solid_cube(45, 64, 17.5) + [shape(circle(63, 43, 5.4))]
            + [shape(arc(63, 43, r, -115, 25), w=3.8) for r in (11, 18)])


def sukrara_beacon():
    return (cube(54, 65, 19, r=2.4) + [shape(circle(54, 33, 5.6), c="accent")]
            + [shape(arc(54, 33, 11, a0, a1), w=3, a=0.6) for a0, a1 in ((-50, 50), (130, 230))])


def sukrara_beacon_mono():
    return (solid_cube(54, 66, 19) + [shape(circle(54, 33, 6))]
            + [shape(arc(54, 33, 12, a0, a1), w=3.6) for a0, a1 in ((-50, 50), (130, 230))])


def sukrara_lit():
    faces = cube(54, 55, 25, r=3)
    faces[0]["c"], faces[1]["a"], faces[2]["a"] = "accent", 0.9, 0.62
    return faces


def sukrara_lit_mono():
    return solid_cube(54, 55, 25, k=0.8, r=2.8)


def sukrara_ring():
    return (cube(54, 56, 17, r=2.2) + [shape(arc(54, 54, 28, -12, 312), w=3.2, a=0.5),
                                       shape(circle(*sim(54, 54, -30, 28)[0]((1, 0)), 5.6), c="accent")])


def sukrara_ring_mono():
    return (solid_cube(54, 56, 17) + [shape(arc(54, 54, 28, -8, 308), w=3.8),
                                      shape(circle(*sim(54, 54, -30, 28)[0]((1, 0)), 6))])


def sukrara_s():
    """The S of Sukrara drawn as a glucose curve that settles on the current reading."""
    f, k = sim(54 - 0.8 * 54, 54 - 0.8 * 54, 0, 0.8)  # 80 % around the centre: clear of the circle mask
    s_ = P().M(70, 33).C(64, 27, 41, 27, 39, 39).C(37, 51, 70, 52, 69, 66).C(68, 79, 49, 81, 41, 76).map(f, k)
    return [shape(s_, w=7), shape(circle(*f((38, 74)), 5.6), c="accent")]


# name, domain = <id>.mauri.app, meaning, background gradient (top, bottom), glyph colours
SUCRE = [  # round 2 (2026-10-16): "Sucre" + a word about the app; check = what a web search found
    dict(id="sucrepla", name="SucrePla", draw=sucrepla, bg=("#2ED3B7", "#0B6B63"), fg="#FFFFFF", accent="#FFE08A",
         check="free", idea="<i>Pla</i> is Catalan for flat, and also plan and a film shot. A sugar cube standing "
                            "on a perfectly flat line: the curve you're aiming for."),
    dict(id="sucreline", name="SucreLine", draw=sucreline, bg=("#8B93FF", "#3B2FB0"), fg="#FFFFFF", accent="#FFE08A",
         check="taken: Béghin Say sells a pastry sugar called Sucreline; a French pastry blog too",
         idea="The line on the chart, going flat inside the target band and ending on the current reading."),
    dict(id="sucremap", name="SucreMap", draw=sucremap, bg=("#FFA24C", "#C2410C"), fg="#FFFFFF", accent="#7C2D12",
         check="free", idea="Your days as a heat-map: when you tend to run high or low. The analysis side."),
    dict(id="sucretrack", name="SucreTrack", draw=sucretrack, bg=("#4ADE80", "#13803B"), fg="#FFFFFF", accent="#FFFFFF",
         check="free", idea="A trail of readings that turns into the app's trend arrow: keeping track, and where it's going."),
    dict(id="sucrescout", name="SucreScout", draw=sucrescout, bg=("#3A4A61", "#0B1220"), fg="#FFFFFF", accent="#FFC53D",
         check="free (unrelated shops called SugarScout)",
         idea="A compass: finding your way. Says “Nightscout alternative” to anyone in the community."),
    dict(id="sucrewave", name="SucreWave", draw=sucrewave, bg=("#3CC3FA", "#0369A1"), fg="#FFFFFF", accent="#FFFFFF",
         check="free", idea="Glucose comes in waves; the second, fainter wave is the one you've already ridden out."),
    dict(id="sucreapp", name="SucreApp", draw=sucreapp, bg=("#FF5C7A", "#B0175E"), fg="#FFFFFF", accent="#FFFFFF",
         check="taken: “SucreApp” is an Argentine credit-card app (Sucredito, 50K+ installs)",
         idea="Sounds like <i>sucre up</i>. The sugar cube lifting off."),
    dict(id="sucreflow", name="SucreFlow", draw=sucreflow, bg=("#B197FC", "#6528D6"), fg="#FFFFFF", accent="#FFE08A",
         check="free", idea="Data flowing from the sensor to every screen you own, like wind lines."),
    dict(id="sucreplot", name="SucrePlot", draw=sucreplot, bg=("#F4F6FA", "#C9D2E0"), fg="#162033", accent="#F0443A",
         check="free", idea="A scatter plot with its trend line: the data-nerd option. The only light icon in the set."),
    dict(id="sucrehub", name="SucreHub", draw=sucrehub, bg=("#FFB23F", "#C2560C"), fg="#FFFFFF", accent="#FFFFFF",
         check="free as written, but “Sugar Hub” (its English translation) is an iOS diabetes app",
         idea="Hub and spokes: the phone in the middle, Mac, watch, family, doctor and relays around it."),
    dict(id="sucrehub2", name="SucreHub", variant="cube network", draw=sucrehub_cube, domain="sucrehub.mauri.app",
         bg=("#FF7A59", "#C2185B"), fg="#FFFFFF", accent="#FFE08A", check="see SucreHub",
         idea="The sugar cube drawn as a network: its edges are the links, its corners the devices. Sucre and hub in one shape."),
    dict(id="sucrehub3", name="SucreHub", variant="orbit", draw=sucrehub_orbit, domain="sucrehub.mauri.app",
         bg=("#7C83FF", "#2E2A86"), fg="#FFFFFF", accent="#FFE08A", check="see SucreHub",
         idea="A sugar cube with data orbiting it: everything revolves around your glucose."),
    dict(id="sucrecore", name="SucreCore", draw=sucrecore, bg=("#2FD0EE", "#125E78"), fg="#FFFFFF", accent="#FFFFFF",
         check="taken: an educational IoT board (Sucre4Stem), a crypto coin and a Java library are all called SucreCore", idea="The app as the core of a wider health ecosystem: a solid sugar cube inside a larger, "
                             "translucent one."),
    dict(id="sucreguard", name="SucreGuard", draw=sucreguard, bg=("#3B82F6", "#1E2A78"), fg="#FFFFFF", accent="#FFE08A",
         check="free, but “sugar guard” is a common name for blood-sugar supplements",
         idea="A shield with a calm line: keeping watch, alarms, peace of mind."),
]
for c in SUCRE:
    c["group"] = "sucre"
SUKRARA = [  # round 3: the user's own coinage, sukr- (sucre, sugar) + ara (Catalan: now)
    dict(id="sukrara", name="Sukrara", variant="live", draw=sukrara_live, mono=sukrara_live_mono, themed=sukrara_live_themed,
         bg=("#1BB5C9", "#0B2E5E"), fg="#FFFFFF", accent="#C6F432",
         check="free as written; near-misses: Sukraa (Indian healthcare software), Sukraraj hospital (Kathmandu)",
         idea="<i>Sukr-</i> (sucre, sugar) + <i>ara</i> (Catalan: now): your sugar, now. The cube with a live signal on its corner, in "
              "technical blues so it never reads as a pink “Sakura” wellness app."),
    dict(id="sukrara3", name="Sukrara", variant="live · signal", draw=sukrara_signal, mono=sukrara_signal_mono,
         domain="sukrara.mauri.app", bg=("#22C1D6", "#0A3266"), fg="#FFFFFF", accent="#C6F432", check="see Sukrara",
         idea="The live dot broadcasting: two signal waves, like a radio going out to the Mac, the watch and family."),
    dict(id="sukrara4", name="Sukrara", variant="live · beacon", draw=sukrara_beacon, mono=sukrara_beacon_mono,
         domain="sukrara.mauri.app", bg=("#1E9BF0", "#0B1F55"), fg="#FFFFFF", accent="#C6F432", check="see Sukrara",
         idea="A beacon on top of the cube: symmetric, centred, and the steadiest at 16 px."),
    dict(id="sukrara5", name="Sukrara", variant="live · lit top", draw=sukrara_lit, mono=sukrara_lit_mono,
         domain="sukrara.mauri.app", bg=("#14A9C4", "#0B2E5E"), fg="#FFFFFF", accent="#C6F432", check="see Sukrara",
         idea="The cube's top face lit in neon: <i>now</i> is the face you see. The simplest shape of the set."),
    dict(id="sukrara6", name="Sukrara", variant="live · ring", draw=sukrara_ring, mono=sukrara_ring_mono,
         domain="sukrara.mauri.app", bg=("#19B3B0", "#0B3A5E"), fg="#FFFFFF", accent="#C6F432", check="see Sukrara",
         idea="A live status ring around the cube with the dot riding on it, like a story ring or a clock's second hand."),
    dict(id="sukrara2", name="Sukrara", variant="S curve", draw=sukrara_s, domain="sukrara.mauri.app",
         bg=("#2F6BFF", "#0B1B4D"), fg="#FFFFFF", accent="#C6F432", check="see Sukrara",
         idea="The S of the name drawn as a glucose curve, ending on the current reading: the neon dot is <i>ara</i>, now."),
]
for c in SUKRARA:
    c["group"] = "sukrara"
FIRST = [
    dict(id="vane", name="Vane", draw=vane, bg=("#17B5A5", "#0B5D63"), fg="#FFFFFF", accent="#FFFFFF",
         idea="A weather vane points where things are heading. The icon <i>is</i> the app's signature: the "
              "continuous-angle trend arrow, inside a dial."),
    dict(id="ebb", name="Ebb", draw=ebb, bg=("#4C8DF6", "#1E3A8A"), fg="#FFFFFF", accent="#FFE08A",
         idea="Ebb and flow: glucose rises and falls like a tide. The wave drifts through the target band and "
              "ends on today's reading."),
    dict(id="sucre", name="Sucre", draw=sucre, bg=("#FF5C7A", "#B0175E"), fg="#FFFFFF", accent="#FFFFFF",
         idea="Catalan (and French) for <i>sugar</i>. Warm, personal, a bit playful. A sugar cube anyone "
              "recognises at 16 px."),
    dict(id="kite", name="Kite", draw=kite, bg=("#3DC2F8", "#5B5BEA"), fg="#FFFFFF", accent="#FFE08A",
         idea="A kite stays up inside a band of wind; its string angle shows where it's going. Light, "
              "outdoorsy, not medical."),
    dict(id="slope", name="Slope", draw=slope, bg=("#3A4556", "#0E1420"), fg="#FFFFFF", accent="#B6F24A",
         idea="The trend is literally the slope of the curve. The tangent at the last reading. Precise, a bit "
              "nerdy, dark-mode native."),
    dict(id="nightjar", name="Nightjar", draw=nightjar, bg=("#4B3FD6", "#1A1648"), fg="#FFE08A", accent="#FFFFFF",
         idea="A bird that keeps watch at night. Fits the remote-follow side (a parent watching a kid's "
              "glucose while they sleep) and the Nightscout heritage."),
]
for c in FIRST:
    c["group"] = "first"
CONCEPTS = SUKRARA + SUCRE + FIRST
for c in CONCEPTS:
    c.setdefault("domain", f"{c['id']}.mauri.app")
    c["label"] = c["name"] + (f" · {c['variant']}" if c.get("variant") else "")
    c["slug"] = c["domain"].split(".")[0]  # icon variants share their name's slug (ids, package names)
    c["shapes"] = c["draw"]()
    c["mono_shapes"] = c["mono"]() if c.get("mono") else c["shapes"]
    c["box"] = glyph_box(c["mono_shapes"])
    c["themed_shapes"] = c["themed"]() if c.get("themed") else c["mono_shapes"]  # themed icons keep alpha


# ---------------------------------------------------------------- SVG writers
def svg_shapes(shapes, colors):
    out = []
    for s in shapes:
        col = colors[s["c"]]
        if s["w"]:
            out.append(f'<path d="{s["p"].d()}" fill="none" stroke="{col}" stroke-opacity="{s["a"]}" '
                       f'stroke-width="{s["w"]:.2f}" stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            out.append(f'<path d="{s["p"].d()}" fill="{col}" fill-opacity="{s["a"]}"/>')
    return "".join(out)


def bg_defs(c, gid="bg"):
    return (f'<linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{c["bg"][0]}"/><stop offset="1" stop-color="{c["bg"][1]}"/></linearGradient>')


def colors(c, mono=None):
    return {"fg": mono or c["fg"], "accent": mono or c["accent"]}


def svg(view, body, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}">'
            + (f"<defs>{defs}</defs>" if defs else "") + body + "</svg>\n")


def squircle(x, y, size, n=5.0, steps=96):
    """Superellipse — close to Apple's continuous-corner icon shape."""
    a = size / 2
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        pts.append((x + a + a * math.copysign(abs(ct) ** (2 / n), ct), y + a + a * math.copysign(abs(st) ** (2 / n), st)))
    return poly(pts)




def write_svgs(c, d):
    os.makedirs(d, exist_ok=True)
    full = bg_defs(c)
    glyph = svg_shapes(c["shapes"], colors(c))
    bx, by, bs = c["box"]
    box = f"{bx} {by} {bs} {bs}"
    files = {
        # Android adaptive layers (full 108 canvas)
        "adaptive-full": svg("0 0 108 108", f'<rect width="108" height="108" fill="url(#bg)"/>{glyph}', full),
        "adaptive-fg": svg("0 0 108 108", glyph),
        "adaptive-mono": svg("0 0 108 108", svg_shapes(c["themed_shapes"], colors(c, "#000000"))),
        # Launcher masks, cropped to the 72-unit visible area
        "android-circle": svg("18 18 72 72", f'<circle cx="54" cy="54" r="36" fill="url(#bg)"/>{glyph}', full),
        "android-squircle": svg("18 18 72 72", f'<path d="{squircle(18, 18, 72, 4).d()}" fill="url(#bg)"/>{glyph}', full),
        "rounded": svg("18 18 72 72", f'<rect x="18" y="18" width="72" height="72" rx="16" fill="url(#bg)"/>{glyph}', full),
        # Small single-colour glyph
        "glyph": svg(box, svg_shapes(c["mono_shapes"], colors(c, "#000000"))),
        "glyph-white": svg(box, svg_shapes(c["mono_shapes"], colors(c, "#FFFFFF"))),
    }
    for mode, (bgc, fgc) in {"light": ("#D6E3FF", "#0B2E5C"), "dark": ("#20293A", "#B4CBFF")}.items():
        files[f"android-themed-{mode}"] = svg(
            "18 18 72 72", f'<circle cx="54" cy="54" r="36" fill="{bgc}"/>' + svg_shapes(c["themed_shapes"], colors(c, fgc)))
    # macOS: Big Sur+ grid — 1024 canvas, 824 squircle body at 100,100; the 72-unit visible area maps onto the body.
    s = 824 / 72
    g = f'<g transform="translate({100 - 18 * s:.3f},{100 - 18 * s:.3f}) scale({s:.4f})">{glyph}</g>'
    body = squircle(100, 100, 824).d()
    files["mac"] = svg("0 0 1024 1024",
                       f'<path d="{body}" fill="#000" fill-opacity="0.22" transform="translate(0,10)"/>'
                       f'<path d="{body}" fill="url(#bg)"/>{g}', full)
    for name, text in files.items():
        with open(os.path.join(d, name + ".svg"), "w") as fh:
            fh.write(text)


# ---------------------------------------------------------------- rendering
def build_renderer():
    src = [os.path.join(HERE, "render.swift"),
           os.path.join(HERE, "..", "menubar", "Sources", "GlucoseBar", "TrendArrow.swift")]
    if os.path.exists(RENDER) and all(os.path.getmtime(RENDER) > os.path.getmtime(s) for s in src):
        return
    os.makedirs(BUILD, exist_ok=True)
    subprocess.run(["swiftc", "-O", "-parse-as-library", "-o", RENDER] + src, check=True)


def render(jobs):
    build_renderer()
    path = os.path.join(BUILD, "jobs.json")
    with open(path, "w") as fh:
        json.dump(jobs, fh)
    subprocess.run([RENDER, path], check=True)


def sheet(prefix=""):
    """Every concept (or those whose id starts with prefix) at a few sizes, rasterized — the fast design loop.
    Columns: macOS, Android circle, themed, one-colour glyph large, colour 32 / 16, one-colour 24 / 16 on white and dark."""
    rows, defs = [], []
    W = 120
    todo = [c for c in CONCEPTS if c["id"].startswith(prefix)]
    width = 4 * W + 330
    for i, c in enumerate(todo):
        gid = f"bg{i}"
        defs.append(bg_defs(c, gid))
        y = i * (W + 20) + 10
        glyph = svg_shapes(c["shapes"], colors(c))
        mono, white = svg_shapes(c["mono_shapes"], colors(c, "#000")), svg_shapes(c["mono_shapes"], colors(c, "#fff"))
        bx, by, bs = c["box"]
        sq = squircle(18, 18, 72).d()
        at = lambda x, yy, scale, ox, oy, body: f'<g transform="translate({x},{yy}) scale({scale}) translate({-ox},{-oy})">{body}</g>'  # noqa: E731
        rows.append(at(10, y, W / 72, 18, 18, f'<path d="{sq}" fill="url(#{gid})"/>{glyph}'))
        rows.append(at(W + 30, y, W / 72, 18, 18, f'<circle cx="54" cy="54" r="36" fill="url(#{gid})"/>{glyph}'))
        rows.append(at(2 * W + 50, y, W / 72, 18, 18, '<circle cx="54" cy="54" r="36" fill="#D6E3FF"/>'
                       + svg_shapes(c["themed_shapes"], colors(c, "#0B2E5C"))))
        rows.append(at(3 * W + 70, y, W / bs, bx, by, mono))
        for j, px in enumerate((32, 16)):
            rows.append(at(4 * W + 90 + j * 44, y + 40, px / 72, 18, 18, f'<path d="{sq}" fill="url(#{gid})"/>{glyph}'))
        rows.append(at(4 * W + 180, y + 36, 24 / bs, bx, by, mono))
        rows.append(at(4 * W + 214, y + 40, 16 / bs, bx, by, mono))
        rows.append(f'<rect x="{4 * W + 240}" y="{y + 26}" width="80" height="44" rx="8" fill="#1d232c"/>')
        rows.append(at(4 * W + 250, y + 36, 24 / bs, bx, by, white))
        rows.append(at(4 * W + 286, y + 40, 16 / bs, bx, by, white))
    h = len(todo) * (W + 20) + 10
    text = svg(f"0 0 {width} {h}", f'<rect width="{width}" height="{h}" fill="#fff"/>' + "".join(rows), "".join(defs))
    p = os.path.join(BUILD, "sheet.svg")
    os.makedirs(BUILD, exist_ok=True)
    with open(p, "w") as fh:
        fh.write(text)
    render([{"kind": "svg", "in": p, "out": os.path.join(BUILD, "sheet.png"), "w": width * 2, "h": h * 2}])


# ---------------------------------------------------------------- style decisions
READINGS = [  # (mg/dL, rate mg/dL/min) — one of each band at the user's thresholds 65 / 145 / 185
    (58, -1.8), (102, 0.3), (163, 1.2), (214, 3.4)]
RANGE_KITS = [
    dict(id="a", name="Colour only off-target (today)", low="systemRed", inrange=None, above="systemOrange",
         high="systemRed", note="What GlucoseBar does now: plain in range, orange 145–185, red below 65 or above 185."),
    dict(id="b", name="Hypos stand out", low="systemRed", inrange=None, above=None, high="systemOrange",
         note="Plain up to 185, orange above, red only for lows. Calmest: red always means “act now”."),
    dict(id="c", name="Traffic light", low="systemRed", inrange="systemGreen", above="systemOrange",
         high="systemRed", note="Always coloured. Green is reassuring at a glance but makes the menubar busier."),
]
NUMBER_FONTS = [
    dict(id="pro", name="SF Pro, medium (today)"),
    dict(id="rounded", name="SF Pro Rounded, semibold"),
    dict(id="bold", name="SF Pro, bold"),
    dict(id="mono", name="SF Mono, medium"),
]
# Apple system colours (light, dark) — also used verbatim on Android so both apps match.
SYSTEM_HEX = {"systemRed": ("#FF3B30", "#FF453A"), "systemOrange": ("#FF9500", "#FF9F0A"),
              "systemGreen": ("#34C759", "#30D158"), None: ("#000000D9", "#FFFFFFD9")}


def band_color(kit, mgdl):
    if mgdl < 65: return kit["low"]
    if mgdl > 185: return kit["high"]
    if mgdl > 145: return kit["above"]
    return kit["inrange"]


def menubar_jobs(d):
    jobs = []
    for kit in RANGE_KITS:
        items = [dict(text=str(v), rate=r, color=band_color(kit, v)) for v, r in READINGS]
        items.append(dict(text="98", rate=None, color=None, stale=True))
        for dark in (False, True):
            jobs.append(dict(kind="menubar", out=f"{d}/kit-{kit['id']}-{'dark' if dark else 'light'}.png",
                             dark=dark, font="pro", items=items))
    for f in NUMBER_FONTS:
        items = [dict(text=str(v), rate=r, color=band_color(RANGE_KITS[0], v)) for v, r in READINGS[1:3]]
        for dark in (False, True):
            jobs.append(dict(kind="menubar", out=f"{d}/font-{f['id']}-{'dark' if dark else 'light'}.png",
                             dark=dark, font=f["id"], items=items))
    return jobs


# ---------------------------------------------------------------- Android VectorDrawable
def vector(shapes, cols, view=(0, 0, 108), size=108, bg=None):
    x, y, w = view
    out = ['<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by spikes/brand/brand.py — edit there, not here. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
           'xmlns:aapt="http://schemas.android.com/aapt"\n'
           f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{w}" android:viewportHeight="{w}">\n']
    if bg:
        out.append('    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
                   f'            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" '
                   f'android:endY="108" android:startColor="{bg[0]}" android:endColor="{bg[1]}"/>\n'
                   '        </aapt:attr>\n    </path>\n')
    out.append(f'    <group android:translateX="{-x}" android:translateY="{-y}">\n' if x or y else "    <group>\n")
    for s in shapes:
        col = cols[s["c"]]
        if s["w"]:
            out.append(f'        <path android:pathData="{s["p"].d()}" android:strokeColor="{col}" '
                       f'android:strokeAlpha="{s["a"]}" android:strokeWidth="{s["w"]:.2f}" '
                       'android:strokeLineCap="round" android:strokeLineJoin="round"/>\n')
        else:
            out.append(f'        <path android:pathData="{s["p"].d()}" android:fillColor="{col}" '
                       f'android:fillAlpha="{s["a"]}"/>\n')
    out.append("    </group>\n</vector>\n")
    return "".join(out)


def write_android_res(c, res, suffix=""):
    """Adaptive launcher icon (with themed-icon monochrome layer) + notification small icon."""
    sfx = f"_{suffix}" if suffix else ""
    files = {
        f"drawable/ic_launcher{sfx}_background.xml": vector([], {}, bg=c["bg"]),
        f"drawable/ic_launcher{sfx}_foreground.xml": vector(c["shapes"], colors(c)),
        f"drawable/ic_launcher{sfx}_monochrome.xml": vector(c["themed_shapes"], colors(c, "#FFFFFFFF")),
        f"drawable/ic_stat{sfx or '_' + c['slug']}.xml": vector(c["mono_shapes"], colors(c, "#FFFFFFFF"), view=c["box"], size=24),
        f"mipmap-anydpi/ic_launcher{sfx}.xml": (
            '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by spikes/brand/brand.py -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/ic_launcher{sfx}_background"/>\n'
            f'    <foreground android:drawable="@drawable/ic_launcher{sfx}_foreground"/>\n'
            f'    <monochrome android:drawable="@drawable/ic_launcher{sfx}_monochrome"/>\n'
            '</adaptive-icon>\n'),
    }
    for rel, text in files.items():
        p = os.path.join(res, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as fh:
            fh.write(text)


def write_preview_app():
    """Sources of the on-device preview APK (android/): one launcher alias per candidate."""
    app = os.path.join(HERE, "android", "app", "src", "main")
    res = os.path.join(app, "res")
    for sub in ("drawable", "mipmap-anydpi"):
        shutil.rmtree(os.path.join(res, sub), ignore_errors=True)
    for c in CONCEPTS:
        write_android_res(c, res, c["id"])
    aliases = "".join(
        f'        <activity-alias android:name=".Icon_{c["id"]}" android:targetActivity=".MainActivity" android:exported="true"\n'
        f'            android:label="{c["name"]}" android:icon="@mipmap/ic_launcher_{c["id"]}">\n'
        '            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n'
        '                <category android:name="android.intent.category.LAUNCHER"/>\n'
        '            </intent-filter>\n        </activity-alias>\n' for c in SUKRARA + SUCRE)
    manifest = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by spikes/brand/brand.py -->\n'
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n'
                f'    <application android:label="Brand preview" android:icon="@mipmap/ic_launcher_{CONCEPTS[0]["id"]}"\n'
                '        android:theme="@android:style/Theme.DeviceDefault.DayNight">\n'
                '        <activity android:name=".MainActivity" android:exported="false"/>\n'
                + aliases + "    </application>\n</manifest>\n")
    with open(os.path.join(app, "AndroidManifest.xml"), "w") as fh:
        fh.write(manifest)
    rows = ",\n".join(
        f'    Candidate("{c["id"]}", "{c["label"]}", "{c["domain"]}", R.mipmap.ic_launcher_{c["id"]}, R.drawable.ic_stat_{c["id"]}, '
        f'0xFF{c["bg"][0][1:]}.toInt(), 0xFF{c["bg"][1][1:]}.toInt())' for c in CONCEPTS)
    kt = ("// Generated by spikes/brand/brand.py — edit there, not here.\npackage dev.spike.brandpreview\n\n"
          "data class Candidate(val id: String, val name: String, val domain: String, val launcher: Int, val statIcon: Int, "
          "val top: Int, val bottom: Int)\n\n"
          f"val CANDIDATES = listOf(\n{rows},\n)\n")
    with open(os.path.join(app, "kotlin", "dev", "spike", "brandpreview", "Candidates.kt"), "w") as fh:
        fh.write(kt)


# ---------------------------------------------------------------- macOS
ICONSET = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]


def make_icns(svg_path, out_icns, work):
    iconset = os.path.join(work, "AppIcon.iconset")
    os.makedirs(iconset, exist_ok=True)
    render([dict(kind="svg", **{"in": svg_path}, out=os.path.join(iconset, f"icon_{p}x{p}{'@2x' if k == 2 else ''}.png"),
                 w=p * k) for p, k in ICONSET])
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", out_icns], check=True)


def mac_check(c, d):
    """Wrap the .icns in a stub .app and ask macOS which icon it shows (catches the legacy-icon squircle 'jail')."""
    app = os.path.join(d, f"{c['name']}.app")
    shutil.rmtree(app, ignore_errors=True)
    os.makedirs(os.path.join(app, "Contents", "Resources"))
    os.makedirs(os.path.join(app, "Contents", "MacOS"))
    shutil.copy("/usr/bin/true", os.path.join(app, "Contents", "MacOS", "stub"))  # without one macOS shows "can't open"
    make_icns(os.path.join(d, "mac.svg"), os.path.join(app, "Contents", "Resources", "AppIcon.icns"), d)
    with open(os.path.join(app, "Contents", "Info.plist"), "w") as fh:
        fh.write('<?xml version="1.0" encoding="UTF-8"?>\n<plist version="1.0"><dict>'
                 f"<key>CFBundleIdentifier</key><string>dev.spike.brand.{c['id']}</string>"
                 f"<key>CFBundleName</key><string>{c['name']}</string>"
                 "<key>CFBundlePackageType</key><string>APPL</string><key>CFBundleExecutable</key><string>stub</string>"
                 "<key>CFBundleIconFile</key><string>AppIcon</string></dict></plist>\n")
    return dict(kind="sysicon", app=app, out=os.path.join(d, "mac-system.png"), w=256)


# ---------------------------------------------------------------- web / social
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


def chrome_png(page, out, w, h):
    """Screenshot with headless Chrome — for SVG text, which AppKit's renderer draws without font weights."""
    if page.endswith(".svg"):
        wrapper = page[:-4] + ".html"
        with open(wrapper, "w") as fh:
            fh.write(f'<body style="margin:0"><img src="{os.path.basename(page)}" width={w} height={h}>')
        page = wrapper
    profile = os.path.join(BUILD, "chrome-profile")  # own profile: never touches (or waits on) the user's Chrome
    for attempt in range(3):  # headless Chrome occasionally exits 2 on start-up
        r = subprocess.run([CHROME, "--headless", "--hide-scrollbars", f"--user-data-dir={profile}",
                            f"--window-size={w},{h}", f"--screenshot={out}", "file://" + page], capture_output=True)
        if r.returncode == 0:
            return
    raise RuntimeError(f"Chrome screenshot failed: {r.stderr.decode()[-400:]}")

def og_svg(c):
    s = 300 / 72
    glyph = svg_shapes(c["shapes"], colors(c))
    return svg("0 0 1200 630",
               f'<rect width="1200" height="630" fill="url(#bg)"/>'
               f'<g transform="translate({110 - 18 * s:.2f},{165 - 18 * s:.2f}) scale({s:.4f})">{glyph}</g>'
               f'<text x="470" y="330" font-family="-apple-system, SF Pro Display, Helvetica Neue, sans-serif" font-weight="700" font-size="150" fill="{c["fg"]}">{c["name"]}</text>'
               f'<text x="476" y="410" font-family="-apple-system, SF Pro Text, Helvetica Neue, sans-serif" font-size="48" fill="{c["fg"]}" fill-opacity="0.75">{c["domain"]}</text>',
               bg_defs(c))


# ---------------------------------------------------------------- gallery
CSS = """
:root { font-family: -apple-system, system-ui, sans-serif; color: #1d1d1f; background: #ececf0; }
body { margin: 0 auto; max-width: 1180px; padding: 32px 24px 80px; }
h1 { font-size: 34px; margin: 0 0 6px; } h2 { margin: 0; font-size: 30px; } h3 { margin: 28px 0 10px; font-size: 19px; }
p { line-height: 1.5; } a { color: inherit; } code { font: 13px ui-monospace, monospace; background: #0000000d; padding: 1px 5px; border-radius: 5px; }
.lead { color: #555; max-width: 860px; }
.reco { background: #fff; border-left: 5px solid #17B5A5; border-radius: 12px; padding: 14px 20px; margin: 18px 0 8px; }
.lineup { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin: 22px 0; }
.lineup > a { background: #fff; border-radius: 16px; padding: 16px 8px 14px; text-align: center; text-decoration: none; }
.lineup img.big { width: 104px; } .lineup b { display: block; font-size: 18px; margin-top: 4px; } .lineup small { color: #777; }
.lineup .tiny { margin-top: 8px; display: flex; gap: 10px; justify-content: center; align-items: center; }
.card { background: #fff; border-radius: 22px; padding: 26px; margin: 22px 0; }
.hero { display: flex; gap: 26px; align-items: center; } .hero img { width: 150px; }
.hero p { margin: 6px 0 0; max-width: 700px; } .domain { color: #0a66c2; font-weight: 600; }
.ctx { display: grid; grid-template-columns: 1.25fr 1.3fr 1fr 1fr; gap: 14px; margin-top: 22px; }
.ctx > div { border-radius: 14px; padding: 14px; min-height: 150px; position: relative; overflow: hidden; }
.ctx .label { position: absolute; left: 12px; bottom: 8px; font-size: 11px; letter-spacing: .03em; text-transform: uppercase; opacity: .65; }
.mac { background: linear-gradient(135deg, #8aa4c8, #c9b6d9 60%, #f0c7a8); }
.droid { background: radial-gradient(circle at 30% 20%, #3b5068, #121a24 70%); color: #fff; }
.ink { background: #111; color: #fff; } .paper { background: #f4f4f6; }
.banner { background: #ffffffd0; border-radius: 16px; padding: 10px 12px; display: flex; gap: 10px; align-items: center; font-size: 13px; box-shadow: 0 4px 18px #0002; }
.banner img { width: 38px; } .banner .t { font-weight: 600; } .banner .s { color: #444; }
.row { display: flex; gap: 14px; align-items: flex-end; flex-wrap: wrap; }
.cap { font-size: 11px; text-align: center; margin-top: 4px; }
.status { display: flex; align-items: center; gap: 6px; font: 600 14px system-ui; padding: 2px 4px 10px; }
.status img { width: 16px; } .status .r { margin-left: auto; opacity: .9; }
.notif { background: #2a3442; border-radius: 18px; padding: 10px 12px; font-size: 13px; display: flex; gap: 10px; align-items: center; }
.notif .dot { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; flex: none; }
.notif .dot img { width: 18px; }
.watch { width: 124px; height: 124px; border-radius: 50%; background: #000; margin: 6px auto 0; border: 6px solid #333;
         display: flex; flex-direction: column; align-items: center; justify-content: center; color: #fff; }
.watch img { width: 30px; } .watch b { font: 700 34px ui-rounded, system-ui; line-height: 1.1; } .watch small { opacity: .7; font-size: 11px; }
.tab { background: #dfe1e5; border-radius: 10px 10px 0 0; display: inline-flex; gap: 8px; align-items: center; padding: 8px 14px; font-size: 13px; }
.tab img { width: 16px; } .url { background: #fff; border-radius: 0 10px 10px 10px; padding: 9px 12px; font-size: 13px; color: #333; }
.style-grid { display: grid; grid-template-columns: 1fr 1fr 1.2fr; gap: 10px 18px; align-items: center; }
.style-grid img { height: 30px; display: block; } .style-grid .n { font-weight: 600; } .style-grid .note { color: #555; font-size: 14px; }
.sw { display: inline-block; width: 22px; height: 22px; border-radius: 6px; vertical-align: middle; margin-right: 4px; }
table { border-collapse: collapse; } td, th { padding: 6px 12px 6px 0; text-align: left; vertical-align: top; }
"""


def gallery(sysicons_ok):
    g = os.path.join(BUILD, "gallery")
    os.makedirs(g, exist_ok=True)
    out = [f"<!doctype html><meta charset=utf-8><title>Pick a name — investigation 09</title><style>{CSS}</style>",
           "<h1>Pick a name, an icon and a style</h1>",
           "<p class=lead>Name + icon candidates for the showcase app, each shown where it will actually live: macOS, the "
           "Android launcher (incl. Material You themed icons), the status bar, a Garmin watch and the web. Names and "
           "icons are interchangeable. At the end: the range colours and the menubar number font, rendered with the "
           "real GlucoseBar arrow code.</p>",
           "<div class=reco><b>Chosen (2026-10-16): Sukrara · live</b>: <i>sukr-</i> (sugar) + <i>ara</i> (Catalan: now). "
           "Domain <span class=domain>sukrara.mauri.app</span>, ids <code>app.mauri.sukrara</code> (final) / "
           "<code>app.mauri.sukrara.poc</code> (showcase), range colours A, SF Pro medium. Assets: "
           "<code>spikes/brand/assets/sukrara/</code>. Everything below is kept for reference.</div>"]
    rounds = [("sukrara", "Round 3 · Sukrara", "<i>Sukr-</i> (sucre, sugar) + <i>ara</i> (Catalan: now). Five takes on the "
               "<i>live</i> idea plus the S curve. Each has its own simplified one-colour drawing for small sizes (the third "
               "small icon, also used for the status bar, themed icons and the watch)."),
              ("sucre", "Round 2 · Sucre + a word about the app",
               "Each badge says what a web search found for the exact name (2026-10-16). SucreHub has three icon takes."),
              ("first", "Round 1", "The first six ideas, kept for comparison.")]
    for group, title, sub in rounds:
        members = [c for c in CONCEPTS if c["group"] == group]
        out.append(f"<h2 style='margin-top:34px'>{title}</h2><p class=lead>{sub}</p><div class=lineup>")
        for c in members:
            out.append(f'<a href="#{c["id"]}"><img class=big src="../{c["id"]}/mac.svg"><b>{c["name"]}</b>'
                       f'<small>{c.get("variant") or c["domain"]}</small><div class=tiny><img src="../{c["id"]}/mac.svg" width=32>'
                       f'<img src="../{c["id"]}/mac.svg" width=16><img src="../{c["id"]}/glyph.svg" width=16></div>'
                       + (badge(c) if group != "first" else "") + "</a>")
        out.append("</div>")
        for c in members:
            out.append(card(c, sysicons_ok))
    out.append(style_html())
    with open(os.path.join(g, "index.html"), "w") as fh:
        fh.write("\n".join(out))
    return os.path.join(g, "index.html")


def badge(c):
    chk = c.get("check", "")
    color = "#1a7f37" if chk.startswith("free") and "but" not in chk else "#b42318" if chk.startswith("taken") else "#9a6700"
    return f'<div style="margin-top:8px;font-size:11px;color:{color}">● {html.escape(chk)}</div>'


def card(c, sysicons_ok):
    i = c["id"]
    sysimg = (f'<div><img src="../{i}/mac-system.png" width=64><div class=cap>as macOS shows it</div></div>'
              if sysicons_ok else "")
    return (f"""<section class=card id="{i}">
<div class=hero><img src="../{i}/mac.svg"><div><h2>{c["label"]}</h2>{badge(c) if c.get("check") else ""}<div class=domain>{c["domain"]}</div>
<p>{c["idea"]}</p><p><code>app.mauri.{c["slug"]}</code> &nbsp; colours <span class=sw style="background:{c["bg"][0]}"></span><code>{c["bg"][0]}</code>
<span class=sw style="background:{c["bg"][1]}"></span><code>{c["bg"][1]}</code></p></div></div>
<div class=ctx>
 <div class=mac><div class=banner><img src="../{i}/mac.svg"><div><div class=t>{c["name"]} <span style="font-weight:400;color:#888">· now</span></div>
   <div class=s>Low: 58 mg/dL ↘ falling</div></div></div>
   <div class=row style="margin-top:14px"><div><img src="../{i}/mac.svg" width=64><div class=cap>Dock / Finder</div></div>{sysimg}
   <div><img src="../{i}/mac.svg" width=32> <img src="../{i}/mac.svg" width=16><div class=cap>32 · 16</div></div></div>
   <span class=label>macOS</span></div>
 <div class=droid><div class=status>9:41 <img src="../{i}/glyph-white.svg"><span class=r>▾ ▴ ▮</span></div>
   <div class=row><div><img src="../{i}/android-circle.svg" width=56><div class=cap>{c["name"]}</div></div>
   <div><img src="../{i}/android-squircle.svg" width=56><div class=cap>squircle</div></div>
   <div><img src="../{i}/android-themed-light.svg" width=56><div class=cap>themed</div></div>
   <div><img src="../{i}/android-themed-dark.svg" width=56><div class=cap>themed dark</div></div></div>
   <span class=label>Android launcher + status bar</span></div>
 <div class=droid style="background:#18202b"><div class=notif><img src="../{i}/android-circle.svg" width=30>
   <div><div>{c["name"]} · now</div><div style="font-size:15px;font-weight:600">102 mg/dL →</div></div></div>
   <div class=watch><img src="../{i}/glyph-white.svg"><b>102</b><small>→ 2 min</small></div>
   <span class=label>Notification · watch</span></div>
 <div class=paper><div class=tab><img src="../{i}/rounded.svg">{c["name"]}</div><div class=url>🔒 {c["domain"]}</div>
   <img src="../{i}/og.svg" style="width:100%;border-radius:10px;margin-top:10px">
   <span class=label>Web · link preview</span></div>
</div></section>""")


def style_html():
    out = ["<section class=card><h2>Style</h2>",
           "<h3>Range colours <small style='font-weight:400;color:#777'>(58 ↘ · 102 → · 163 ↗ · 214 ⇈ · stale 98)</small></h3><div class=style-grid>"]
    for k in RANGE_KITS:
        out.append(f'<div><span class=n>{k["id"].upper()} · {k["name"]}</span><img src="../style/kit-{k["id"]}-light.png" style="margin-top:6px"></div>'
                   f'<div><img src="../style/kit-{k["id"]}-dark.png" style="margin-top:22px"></div><div class=note>{k["note"]}</div>')
    out.append("</div><h3>Menubar number font</h3><div class=style-grid>")
    for f in NUMBER_FONTS:
        out.append(f'<div><span class=n>{f["name"]}</span><img src="../style/font-{f["id"]}-light.png" style="margin-top:6px"></div>'
                   f'<div><img src="../style/font-{f["id"]}-dark.png" style="margin-top:22px"></div><div></div>')
    out.append("</div><h3>Already decided (carried over from the spikes)</h3><table>"
               "<tr><th>Unit</th><td>mg/dL, thresholds 65 / 145 / 185, stale after 5 min (strikethrough + grey)</td></tr>"
               "<tr><th>Trend arrow</th><td>continuous angle, ±3 mg/dL/min = vertical, double chevron beyond (GlucoseBar <code>TrendArrow</code>)</td></tr>"
               "<tr><th>Colours</th><td>Apple system colours on both platforms (red <code>#FF3B30</code>/<code>#FF453A</code>, "
               "orange <code>#FF9500</code>/<code>#FF9F0A</code>, green <code>#34C759</code>/<code>#30D158</code>), so the Mac and the phone match</td></tr>"
               "</table></section>")
    out.append("<section class=card><h2>How to answer</h2><p>Reply with a name, an icon, a range-colour kit and a font, e.g. "
               "<code>sucrepla, kit a, pro</code> or <code>name SucreHub with the sucrehub2 icon, kit b, rounded</code>. Then "
               "<code>python3 -I spikes/brand/brand.py export sucrepla</code> writes every platform asset to "
               "<code>spikes/brand/assets/sucrepla/</code> for the showcase app. <code>python3 -I brand.py exports</code> "
               "writes them for every round-2 candidate into <code>build/exports/</code>.</p></section>")
    return "\n".join(out)


# ---------------------------------------------------------------- export (one chosen candidate)
def export(cid, kit_id="a", root=None):
    c = next(x for x in CONCEPTS if x["id"] == cid)
    kit = next(k for k in RANGE_KITS if k["id"] == kit_id)
    src = os.path.join(BUILD, cid)
    write_svgs(c, src)
    a = os.path.join(root or os.path.join(HERE, "assets"), cid)
    shutil.rmtree(a, ignore_errors=True)
    for sub in ("macos", "android/res", "garmin", "web"):
        os.makedirs(os.path.join(a, sub), exist_ok=True)
    make_icns(os.path.join(src, "mac.svg"), os.path.join(a, "macos", "AppIcon.icns"), src)
    write_android_res(c, os.path.join(a, "android", "res"))
    with open(os.path.join(src, "og.svg"), "w") as fh:
        fh.write(og_svg(c))
    jobs = [dict(kind="svg", **{"in": os.path.join(src, "mac.svg")}, out=os.path.join(a, "macos", "AppIcon-1024.png"), w=1024),
            dict(kind="svg", **{"in": os.path.join(src, "glyph.svg")}, out=os.path.join(a, "macos", "MenubarGlyph.png"), w=18),
            dict(kind="svg", **{"in": os.path.join(src, "glyph.svg")}, out=os.path.join(a, "macos", "MenubarGlyph@2x.png"), w=36),
            dict(kind="svg", **{"in": os.path.join(src, "adaptive-full.svg")}, out=os.path.join(a, "android", "play-store-512.png"), w=512),
            dict(kind="svg", **{"in": os.path.join(src, "adaptive-full.svg")}, out=os.path.join(a, "web", "apple-touch-icon.png"), w=180),
            dict(kind="svg", **{"in": os.path.join(src, "rounded.svg")}, out=os.path.join(a, "web", "icon-512.png"), w=512),
            dict(kind="svg", **{"in": os.path.join(src, "rounded.svg")}, out=os.path.join(a, "web", "favicon-32.png"), w=32),
            ]
    for px in (40, 60, 70, 80):
        jobs.append(dict(kind="svg", **{"in": os.path.join(src, "android-circle.svg")},
                         out=os.path.join(a, "garmin", f"launcher_icon_{px}.png"), w=px))
        jobs.append(dict(kind="svg", **{"in": os.path.join(src, "glyph-white.svg")},
                         out=os.path.join(a, "garmin", f"launcher_icon_mono_{px}.png"), w=px))
    render(jobs)
    chrome_png(os.path.join(src, "og.svg"), os.path.join(a, "web", "og.png"), 1200, 630)
    shutil.copy(os.path.join(src, "rounded.svg"), os.path.join(a, "web", "favicon.svg"))
    shutil.copy(os.path.join(src, "mac.svg"), os.path.join(a, "macos", "AppIcon.svg"))
    shutil.copy(os.path.join(src, "glyph.svg"), os.path.join(a, "glyph.svg"))
    write_tokens(c, kit, a)
    print(f"wrote {os.path.relpath(a)}")


def write_tokens(c, kit, a):
    """Name, ids and colours as code for both apps, plus a README describing every file."""
    bands = [("low", kit["low"]), ("inRange", kit["inrange"]), ("above", kit["above"]), ("high", kit["high"])]
    tokens = dict(name=c["name"], id=c["slug"], icon=c["id"], domain=c["domain"], url=f"https://{c['domain']}",
                  applicationId=f"app.mauri.{c['slug']}", showcaseApplicationId=f"app.mauri.{c['slug']}.poc",
                  brand=dict(top=c["bg"][0], bottom=c["bg"][1], glyph=c["fg"], accent=c["accent"]),
                  thresholds=dict(low=65, slightlyHigh=145, high=185, staleAfterMinutes=5), rangeKit=kit["id"],
                  menubarFont="SF Pro, medium, monospaced digits (NSFont.monospacedDigitSystemFont)",
                  range={n: dict(zip(("light", "dark"), SYSTEM_HEX[v])) if v else None for n, v in bands})
    with open(os.path.join(a, "brand.json"), "w") as fh:
        json.dump(tokens, fh, indent=2)
        fh.write("\n")
    hx = lambda h: "0x" + (h[7:9] if len(h) == 9 else "FF") + h[1:7]  # noqa: E731  #RRGGBB(AA) → 0xAARRGGBB
    kt = [f"// Generated by spikes/brand/brand.py export {c['id']} {kit['id']}\n",
          "import androidx.compose.ui.graphics.Color\n\nobject Brand {\n",
          f'    const val NAME = "{c["name"]}"\n    const val URL = "https://{c["domain"]}"\n',
          f"    val Top = Color({hx(c['bg'][0])})\n    val Bottom = Color({hx(c['bg'][1])})\n",
          "    // Glucose bands (null = default text colour). Pick light/dark from the current theme.\n"]
    sw = [f"// Generated by spikes/brand/brand.py export {c['id']} {kit['id']}\nimport AppKit\n\nenum Brand {{\n",
          f'    static let name = "{c["name"]}"\n    static let url = URL(string: "https://{c["domain"]}")!\n',
          "    // Glucose bands (nil = labelColor). System colours adapt to light/dark by themselves.\n"]
    for n, v in bands:
        lt, dk = SYSTEM_HEX[v]
        kt.append(f"    val {n}: Pair<Color, Color>? = " + (f"Color({hx(lt)}) to Color({hx(dk)})\n" if v else "null\n"))
        sw.append(f"    static let {n}: NSColor? = " + (f".{v}\n" if v else "nil\n"))
    with open(os.path.join(a, "android", "Brand.kt"), "w") as fh:
        fh.write("".join(kt) + "}\n")
    with open(os.path.join(a, "macos", "Brand.swift"), "w") as fh:
        fh.write("".join(sw) + "}\n")
    with open(os.path.join(a, "README.md"), "w") as fh:
        fh.write(f"""# {c["name"]} — brand assets

Generated by `python3 -I spikes/brand/brand.py export {c["id"]} {kit["id"]}` (investigation 09). Don't edit by hand:
change the concept in `brand.py` and re-export.

| Path | Use |
|---|---|
| `brand.json` | name, domain, application ids, brand + range colours, thresholds |
| `glyph.svg` | one-colour glyph (source for any small/mono use) |
| `macos/AppIcon.icns` | `CFBundleIconFile` of the .app (Big Sur grid, 1024 master in `AppIcon-1024.png` / `AppIcon.svg`) |
| `macos/MenubarGlyph*.png` | template image (black + alpha, set `isTemplate`) for menus / "no data yet" |
| `macos/Brand.swift` | name, URL, range colours as `NSColor` |
| `android/res/` | adaptive launcher icon with a monochrome layer (themed icons): copy into `app/src/main/res`, then `android:icon="@mipmap/ic_launcher"`; `drawable/ic_stat_{c["slug"]}.xml` is the notification small icon |
| `android/Brand.kt` | name, URL, brand + range colours for Compose |
| `android/play-store-512.png` | store / about-screen icon |
| `garmin/launcher_icon_*.png` | Connect IQ `launcherIcon` (colour circle, or `_mono` for MIP screens); use the size the SDK's device profile asks for |
| `web/` | `favicon.svg`, `favicon-32.png`, `apple-touch-icon.png`, `icon-512.png` (PWA manifest), `og.png` (link previews) |

Ids: application / bundle id `app.mauri.{c["slug"]}` for the final app; the showcase PoC uses
`app.mauri.{c["slug"]}.poc` so both can be installed side by side.
""")


# ---------------------------------------------------------------- main
def build_all():
    jobs, checks = [], []
    for c in CONCEPTS:
        d = os.path.join(BUILD, c["id"])
        write_svgs(c, d)
        with open(os.path.join(d, "og.svg"), "w") as fh:
            fh.write(og_svg(c))
        checks.append(mac_check(c, d))
    jobs += menubar_jobs(os.path.join(BUILD, "style"))
    render(jobs + checks)
    write_preview_app()
    sheet()
    return gallery(sysicons_ok=True)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "sheet":
        sheet(sys.argv[2] if len(sys.argv) > 2 else "")
    elif cmd == "exports":  # every round-2 candidate, for looking at — not for committing
        for c in SUKRARA + SUCRE:
            export(c["id"], "a", os.path.join(BUILD, "exports"))
    elif cmd == "export":
        export(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "a")
    elif cmd in ("all", "gallery"):
        page = build_all()
        print(f"gallery: {page}")
        if cmd == "gallery":
            subprocess.run(["open", page])
    else:
        sys.exit(__doc__)

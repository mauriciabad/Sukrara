# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Sound-pack audition page.

    uv run preview.py inline <out.html>      # one self-contained page (sounds as data: URIs)
    uv run preview.py site <dir>             # <dir>/index.html + <dir>/*.m4a (docs/sounds on sukrara.mauri.app)

Rows = alert kinds, columns = packs, three variants each. The haptic strip under every kind is drawn from the
channel's VibrationEffect (alerts/AlertHaptics.kt) with the primitive durations the Pixel 10 Pro reports.
"""
import base64
import html
import shutil
import sys
from pathlib import Path

OUT = Path(__file__).parent / "out"
PACKS = [("arcade", "Arcade", "8-bit pulse waves"), ("kart", "Kart", "racing brass & engines"),
         ("quest", "Quest", "harp, flute, bells"), ("soft", "Soft", "marimba & glass")]
KINDS = [
    ("very_low", "Very low", "< 55 mg/dL", "every 5 min", "Game over: longer, slows down, ends low"),
    ("low", "Low", "< 70 mg/dL", "every 5 min", "Lost a life: short and falling"),
    ("high", "High", "≥ 180 mg/dL", "every 15 min", "Final lap: a riff, then faster and higher"),
    ("very_high", "Very high", "≥ 250 mg/dL", "every 10 min", "Boss incoming: two-note warning, rising"),
    ("extremely_high", "Extremely high", "≥ 300 mg/dL", "every 5 min", "Final boss: the warning, longer and climbing, then a held cry"),
    ("falling_fast", "Falling fast", "−2 mg/dL/min", "off by default", "A quick sweep down"),
    ("rising_fast", "Rising fast", "+2 mg/dL/min", "off by default", "A quick sweep up"),
    ("no_data", "No data", "20 min silent", "every 60 min", "Connection lost: two blips"),
]
# (primitive, scale, delay before ms) from AlertHaptics.rich; durations = `dumpsys vibrator_manager` on the Pixel
DUR = {"CLICK": 29, "THUD": 300, "SPIN": 133, "QUICK_RISE": 150, "SLOW_RISE": 500, "QUICK_FALL": 100, "TICK": 5, "LOW_TICK": 12}
HAPTICS = {
    "very_low": [("QUICK_FALL", 1, 0), ("QUICK_FALL", .7, 180), ("QUICK_FALL", .5, 260), ("THUD", 1, 360), ("THUD", 1, 500)],
    "low": [("QUICK_FALL", .9, 0), ("QUICK_FALL", .8, 200), ("THUD", .9, 280)],
    "high": [("CLICK", .7, 0), ("CLICK", .75, 140), ("CLICK", .8, 100), ("CLICK", .85, 70), ("CLICK", .9, 50), ("QUICK_RISE", 1, 60)],
    "very_high": [("THUD", .8, 0), ("CLICK", 1, 90), ("THUD", .8, 220), ("CLICK", 1, 90), ("SPIN", 1, 220)],
    "extremely_high": [("THUD", 1, 0), ("THUD", 1, 120), ("CLICK", 1, 90), ("THUD", 1, 200), ("THUD", 1, 120), ("CLICK", 1, 90),
                       ("SPIN", 1, 220), ("QUICK_RISE", 1, 100)],
    "falling_fast": [("QUICK_FALL", 1, 0), ("LOW_TICK", 1, 120)],
    "rising_fast": [("SLOW_RISE", 1, 0), ("TICK", 1, 60)],
    "no_data": [("TICK", 1, 0), ("TICK", .6, 160), ("LOW_TICK", .4, 160)],
}
MAX_MS = 3600


def haptic_svg(kind: str) -> str:
    """One strip: time → x (MAX_MS wide), scale → height. Shapes: rise = ramp up, fall = ramp down, others = blocks."""
    w, h = 720, 34
    x = 0
    parts = []
    for prim, scale, delay in HAPTICS[kind]:
        x += delay
        d = DUR[prim]
        x0, x1 = x / MAX_MS * w, (x + max(d, 14)) / MAX_MS * w
        top = h - 2 - scale * (h - 6)
        base = h - 2
        if prim in ("QUICK_RISE", "SLOW_RISE"):
            pts = f"{x0:.1f},{base} {x1:.1f},{top:.1f} {x1:.1f},{base}"
        elif prim == "QUICK_FALL":
            pts = f"{x0:.1f},{base} {x0:.1f},{top:.1f} {x1:.1f},{base}"
        else:
            pts = f"{x0:.1f},{base} {x0:.1f},{top:.1f} {x1:.1f},{top:.1f} {x1:.1f},{base}"
        parts.append(f'<polygon points="{pts}"><title>{prim.lower().replace("_", " ")} · {scale:g}</title></polygon>')
        x += d
    total = x
    return (f'<svg class="hap" viewBox="0 0 {w} {h}" preserveAspectRatio="none" role="img" '
            f'aria-label="Vibration pattern, {total} ms"><line x1="0" y1="{h - 1}" x2="{w}" y2="{h - 1}"/>'
            + "".join(parts) + f'</svg><span class="ms">{total} ms</span>')


def build(src) -> str:
    rows = []
    for k, label, th, rep, mood in KINDS:
        cells = []
        for p, pl, _ in PACKS:
            btns = "".join(
                f'<button class="v" data-src="{src(f"{p}_{k}_{n}")}" aria-label="{pl}, {label}, sound {n}">{n}</button>'
                for n in (1, 2, 3))
            cells.append(f'<td data-pack="{p}"><div class="vs">{btns}</div></td>')
        rows.append(f"""<tr data-kind="{k}">
  <th scope="row"><span class="k k-{k}">{label}</span><span class="th">{th} · {rep}</span>
    <span class="mood">{html.escape(mood)}</span>{haptic_svg(k)}</th>
  {''.join(cells)}
</tr>""")
    heads = "".join(f'<th scope="col"><span class="pk">{pl}</span><span class="pd">{pd}</span></th>' for _, pl, pd in PACKS)
    return TEMPLATE.replace("{{HEADS}}", heads).replace("{{ROWS}}", "\n".join(rows))


TEMPLATE = """<title>Sukrara Alert Sounds</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@500;700&family=Barlow:wght@400;500;600&family=JetBrains+Mono:wght@400;600&display=swap">
<style>
/* Layout: a sound board, alerts down the side, packs across the top, like a game's sound-test screen. */
:root {
  --ground: #f3f7f9;
  --panel: #ffffff;
  --ink: #0b2e5e;
  --muted: #4d6a83;
  --line: #cfdde6;
  --cyan: #1b8fa6;
  --live: #6c8a00;
  --live-bg: #eaf7b8;
  --low: #c62828;
  --high: #c25a00;
  --display: "Chakra Petch", "Avenir Next Condensed", "Arial Narrow", sans-serif;
  --body: "Barlow", "Helvetica Neue", Arial, sans-serif;
  --mono: "JetBrains Mono", ui-monospace, Menlo, monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --ground: #071a33; --panel: #0c2546; --ink: #e4f1f7; --muted: #8fb0c6; --line: #1d3d63;
    --cyan: #3fcbe0; --live: #c6f432; --live-bg: #2d3a08; --low: #ff6b5e; --high: #ffa94d;
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --ground: #071a33; --panel: #0c2546; --ink: #e4f1f7; --muted: #8fb0c6; --line: #1d3d63;
  --cyan: #3fcbe0; --live: #c6f432; --live-bg: #2d3a08; --low: #ff6b5e; --high: #ffa94d;
}
* { box-sizing: border-box; }
body {
  background: var(--ground); color: var(--ink); font: 400 16px/1.5 var(--body);
  padding-block: 32px 48px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px));
}
main { max-width: 1120px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }
header { display: flex; flex-direction: column; gap: 8px; max-width: 68ch; }
.eyebrow { font: 600 12px/1 var(--mono); letter-spacing: .12em; text-transform: uppercase; color: var(--cyan); }
h1 { font: 700 clamp(30px, 5vw, 46px)/1.05 var(--display); margin: 0; text-wrap: balance; letter-spacing: -.01em; }
header p { margin: 0; color: var(--muted); }
.bar { display: flex; flex-wrap: wrap; gap: 12px 20px; align-items: center; font-size: 14px; color: var(--muted); }
.bar button { font: 600 14px/1 var(--display); letter-spacing: .04em; color: var(--ink); background: var(--panel);
  border: 1px solid var(--line); border-radius: 6px; padding: 10px 14px; cursor: pointer; }
.bar button:hover { border-color: var(--cyan); }
.now { font-family: var(--mono); font-size: 13px; color: var(--ink); min-height: 1.5em; }
.board { overflow-x: auto; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; }
table { border-collapse: collapse; width: 100%; min-width: 860px; }
thead th { text-align: left; padding: 16px 12px 12px; border-bottom: 1px solid var(--line); vertical-align: bottom; }
.pk { display: block; font: 700 18px/1.1 var(--display); }
.pd { display: block; font-size: 13px; color: var(--muted); }
tbody th { text-align: left; font-weight: 400; padding: 14px 12px; width: 40%; border-bottom: 1px solid var(--line); vertical-align: top; }
tbody td { padding: 14px 12px; border-bottom: 1px solid var(--line); vertical-align: top; }
tbody tr:last-child > * { border-bottom: 0; }
.k { font: 700 17px/1.2 var(--display); display: block; }
.k-very_low, .k-low { color: var(--low); }
.k-high, .k-very_high, .k-extremely_high { color: var(--high); }
.th { display: block; font: 400 12.5px/1.6 var(--mono); color: var(--muted); font-variant-numeric: tabular-nums; }
.mood { display: block; font-size: 14px; margin: 2px 0 8px; }
.hap { width: calc(100% - 64px); height: 34px; display: inline-block; vertical-align: middle; }
.hap line { stroke: var(--line); stroke-width: 1; }
.hap polygon { fill: var(--cyan); }
.ms { font: 400 11px var(--mono); color: var(--muted); margin-left: 8px; vertical-align: middle; }
.vs { display: flex; gap: 6px; }
.v { width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--line); background: var(--ground);
  color: var(--ink); font: 600 14px var(--mono); cursor: pointer; position: relative; }
.v:hover { border-color: var(--cyan); }
.v:focus-visible, .bar button:focus-visible { outline: 2px solid var(--cyan); outline-offset: 2px; }
.v.playing { background: var(--live-bg); border-color: var(--live); color: var(--ink); }
.v.playing::after { content: ""; position: absolute; inset: -5px; border-radius: 50%;
  border: 2px solid var(--live); clip-path: inset(0 calc(100% - var(--p, 0%)) 0 0); }
footer { font-size: 14px; color: var(--muted); max-width: 72ch; display: flex; flex-direction: column; gap: 8px; }
footer p { margin: 0; }
footer code { font-family: var(--mono); font-size: 13px; }
@media (prefers-reduced-motion: reduce) { .v.playing::after { display: none; } }
</style>
<main>
  <header>
    <span class="eyebrow">Sukrara · glucose alerts · investigation 15</span>
    <h1>Alert sound test</h1>
    <p>Four packs, eight alerts, three sounds each. The app picks one of the three at random every time an alert
      goes off, so it doesn't get repetitive. Every sound is synthesised from code with original melodies.</p>
  </header>
  <div class="bar">
    <button type="button" id="all-pack">Play a random high from each pack</button>
    <button type="button" id="stop">Stop</button>
    <span class="now" id="now" aria-live="polite">Tap a number to listen.</span>
  </div>
  <div class="board">
    <table>
      <thead><tr><th scope="col"><span class="pk">Alert</span><span class="pd">default rule · vibration on Android</span></th>{{HEADS}}</tr></thead>
      <tbody>
{{ROWS}}
      </tbody>
    </table>
  </div>
  <footer>
    <p>The strip under each alert is its vibration on Android, drawn to scale from the haptic primitives (falls,
      thuds, clicks, rises; height = strength). It plays with the sound, and alone when the phone is on vibrate, so
      you can tell a low from a high in your pocket.</p>
    <p>Lows ring on the alarm volume. In the app: Settings → Alerts → Sound pack, or one pack for a single alert.
      Source: <code>poc/spikes/alerts/sounds/gen.py</code>.</p>
  </footer>
</main>
<script>
(() => {
  const audio = new Audio();
  let current = null;
  const now = document.getElementById("now");
  const label = b => b.getAttribute("aria-label");
  function stop() {
    audio.pause();
    if (current) { current.classList.remove("playing"); current.style.removeProperty("--p"); }
    current = null;
  }
  function play(b) {
    stop();
    current = b;
    b.classList.add("playing");
    audio.src = b.dataset.src;
    audio.currentTime = 0;
    audio.play().then(() => { now.textContent = "Playing: " + label(b); })
      .catch(() => { now.textContent = "This browser couldn't play the sound."; stop(); });
    return new Promise(res => { audio.onended = () => { stop(); res(); }; });
  }
  audio.ontimeupdate = () => {
    if (current && audio.duration) current.style.setProperty("--p", (100 * audio.currentTime / audio.duration).toFixed(1) + "%");
  };
  document.querySelectorAll(".v").forEach(b => b.addEventListener("click", () => current === b ? stop() : play(b)));
  document.getElementById("stop").addEventListener("click", () => { stop(); now.textContent = "Stopped."; });
  document.getElementById("all-pack").addEventListener("click", async () => {
    for (const td of document.querySelectorAll('tr[data-kind="high"] td')) {
      const bs = td.querySelectorAll(".v");
      await play(bs[Math.floor(Math.random() * bs.length)]);
      await new Promise(r => setTimeout(r, 350));
    }
  });
})();
</script>
"""


def main():
    mode, dest = sys.argv[1], Path(sys.argv[2])
    if mode == "inline":
        def src(name):
            return "data:audio/mp4;base64," + base64.b64encode((OUT / f"{name}.m4a").read_bytes()).decode()
        dest.write_text(build(src))
    else:
        dest.mkdir(parents=True, exist_ok=True)
        for f in OUT.glob("*.m4a"):
            shutil.copy(f, dest / f.name)
        page = build(lambda name: f"{name}.m4a")
        # the site is plain HTML served by GitHub Pages: give it a full document
        dest.joinpath("index.html").write_text('<!doctype html>\n<html lang="en"><head><meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n' + page + "</html>\n")
    print(dest, f"{dest.stat().st_size if dest.is_file() else sum(f.stat().st_size for f in dest.iterdir())} bytes")


if __name__ == "__main__":
    main()

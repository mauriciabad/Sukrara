# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Deterministic guards for the repo rules in CLAUDE.md. Python 3.9 stdlib only; run through tools/check/run.sh.

Modes
  --staged              the index (pre-commit): static rules fail, coupling rules only remind
  --commit-msg FILE     coupling rules with the message's trailers (commit-msg hook); strict for merge commits
  --range A..B          a branch / push / PR: everything strict (CI, tools/install.sh)
  --merge-of SHA        re-judge a past merge commit the way the commit-msg hook judges a merge
Checks
  parity      path groups that must change together (parity.json), skip: 'Parity-skip: <group>: <reason>'
  features    app code changes need docs/FEATURES.md, skip: 'Features-skip: <reason>'
  java        no .java source (Android is Kotlin only)
  header      new source files carry the GPL SPDX header
  secrets     nsec/private keys/tokens in added lines; secrets/, keys, APKs, build dirs as paths
  large       blobs over allow.json large_bytes
  images      new images only under docs/img, spikes/*/evidence, brand, res; full-phone captures flagged
  strings     new hardcoded UI text in Android Kotlin; values/ and values-es/ keys in sync
  debug       DebugReceiver fakes (sensor, readings, unlocks, test alerts, previews, treatments) only through the
              gated DebugFakes.run (debug builds)
  qa          prints the docs/QA.md sections the change touches
"""
import fnmatch
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=HERE, capture_output=True, text=True,
                      check=True).stdout.strip()


def git(*args, binary=False, ok=False):
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=not binary)
    if r.returncode and not ok:
        sys.exit("git %s failed: %s" % (" ".join(args), r.stderr if not binary else r.stderr.decode()))
    return r.stdout if r.returncode == 0 else ("" if not binary else b"")


def load(name):
    with open(os.path.join(HERE, name)) as f:
        return json.load(f)


def match(path, globs):
    return any(fnmatch.fnmatchcase(path, g) for g in globs)


class Change:
    """Files (path -> (status, new mode)) and added lines (path -> [(line, text)]) between two trees."""

    def __init__(self, diff_args, rev):
        self.rev = rev  # "" = the index, else a commit
        self.files = {}
        for line in git("diff", "--raw", "--no-renames", "--no-ext-diff", *diff_args).splitlines():
            meta, _, path = line.partition("\t")
            parts = meta.split()
            if len(parts) >= 5:
                self.files[path] = (parts[4][0], parts[1])
        self.added = {}
        path, n = None, 0
        for line in git("diff", "-U0", "--no-color", "--no-renames", "--no-ext-diff", *diff_args).splitlines():
            if line.startswith("+++ "):
                path = line[6:] if line.startswith("+++ b/") else None
            elif line.startswith("@@"):
                m = re.search(r"\+(\d+)", line)
                n = int(m.group(1)) if m else 0
            elif line.startswith("+") and path:
                self.added.setdefault(path, []).append((n, line[1:]))
                n += 1

    def live(self):
        """Added or modified regular files (no deletions, no symlinks, no submodules)."""
        return [p for p, (s, mode) in self.files.items() if s != "D" and mode.startswith("100")]

    def blob(self, path, binary=False):
        return git("cat-file", "blob", "%s:%s" % (self.rev, path), binary=binary, ok=True)

    def size(self, path):
        out = git("cat-file", "-s", "%s:%s" % (self.rev, path), ok=True).strip()
        return int(out) if out else 0


class Report:
    def __init__(self):
        self.items = []

    def add(self, level, check, text):
        self.items.append((level, check, text))

    def failed(self):
        return any(lv == "FAIL" for lv, _, _ in self.items)

    def show(self):
        for lv, check, text in self.items:
            print("%s [%s] %s" % (lv, check, text.replace("\n", "\n    ")))


def trailers(messages, key):
    out = []
    for m in messages:
        out += [v.strip() for v in re.findall(r"(?im)^%s:[ \t]*(.+)$" % re.escape(key), m)]
    return out


def skipped(values, group, groups):
    """A 'Parity-skip: <group>: reason' covers that group; one without a known group name covers all."""
    for v in values:
        m = re.match(r"([\w-]+)\s*:\s*(\S.*)", v)
        if m and m.group(1) in groups:
            if m.group(1) == group:
                return True
        elif v:
            return True
    return False


def check_parity(ch, messages, strict, rep):
    """Judged on the change alone: a counterpart merged earlier in the round doesn't count (it may be older work);
    a branch that only ports or only starts a change says so with a Parity-skip trailer."""
    cfg = load("parity.json")
    names = {g["name"] for g in cfg["groups"]}
    skips = trailers(messages, "Parity-skip")
    for g in cfg["groups"]:
        touched = {s: sorted(p for p in ch.files if match(p, globs)) for s, globs in g["sides"].items()}
        trig = [s for s in g.get("trigger", list(g["sides"])) if touched[s]]
        missing = [s for s in g["sides"] if not touched[s]]
        if not trig or not missing or skipped(skips, g["name"], names):
            continue
        lines = [g["why"] + "."]
        for s in g["sides"]:
            if touched[s]:
                lines.append("changed %s: %s" % (s, ", ".join(touched[s][:4]) + (" …" if len(touched[s]) > 4 else "")))
        for s in missing:
            lines.append("NOT changed %s: %s" % (s, ", ".join(g["sides"][s])))
        lines.append("Port the change, or add a commit-message trailer:  Parity-skip: %s: <reason>" % g["name"])
        level = "FAIL" if strict and g.get("level") == "fail" else "WARN"
        rep.add(level, "parity:" + g["name"], "\n".join(lines))


def check_features(ch, messages, strict, rep):
    """Judged on the change alone (a long integration branch touching FEATURES.md once must not cover everything)."""
    f = load("parity.json")["features"]
    code = sorted(p for p in ch.files if match(p, f["code"]))
    if not code:
        return
    every = set(ch.files)
    if not any(match(p, f["docs"]) for p in every) and not trailers(messages, "Features-skip"):
        rep.add("FAIL" if strict else "WARN", "features",
                "App code changed (%s%s) but docs/FEATURES.md did not.\n"
                "Every behaviour change updates the catalog in the same change; for refactors/tests/fixes that keep\n"
                "the behaviour add a trailer:  Features-skip: <reason>" % (", ".join(code[:3]), " …" if len(code) > 3 else ""))
    if not any(match(p, f["readme"]) for p in every):
        rep.add("NOTE", "features", "README.md not changed: if what Sukrara does changed, the README says so too.")


SECRET_PATHS = re.compile(r"(^|/)(secrets|build|\.build|\.gradle|\.kotlin|\.cxx)/|\.(jks|keystore|p12|pem|key|apk|aab|ipa|dmg)$"
                          r"|(^|/)(local\.properties|info\.dat|sensor\.json)$")
SECRET_TEXT = [
    (re.compile(r"\bnsec1[02-9ac-hj-np-z]{50,}"), "a Nostr private key (nsec1…)"),
    (re.compile(r"\bncryptsec1[02-9ac-hj-np-z]{50,}"), "an encrypted Nostr key (ncryptsec1…)"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "a PEM private key"),
    (re.compile(r"\b(ghp|gho|ghs)_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{30,}"), "a GitHub token"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "an AWS key"),
    (re.compile(r"\bxox[abp]-[A-Za-z0-9-]{10,}"), "a Slack token"),
]
IMAGE = re.compile(r"\.(png|jpe?g|webp|gif|heic)$", re.I)
UI_TEXT = re.compile(r'\bText\(\s*"((?:[^"\\]|\\.)*)"'
                     r'|setTextViewText\([^,]+,\s*"((?:[^"\\]|\\.)*)"'
                     r'|\b(?:title|text|contentDescription|placeholder|supportingText)\s*=\s*"((?:[^"\\]|\\.)*)"'
                     r'|makeText\([^,]+,\s*"((?:[^"\\]|\\.)*)"')
NOT_WORDS = re.compile(r"\$\{[^}]*\}|\$\w+|mg/dL|mmol/L|Sukrara|Libre 3|dBm|n?c?r?y?p?t?sec1|https?://\S+")
STRING_KEY = re.compile(r'<(string|plurals)\s+name="([^"]+)"([^>]*)>')
# Debug commands that replace the sensor or make up data run only in debug builds (2026-10-18: `cmd sensorEnd` on the
# user's release build faked a sensor over the real one). They live in DebugFakes.kt behind one gated entry point.
APP_KT = "spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara/"
DEBUG_RECEIVER = APP_KT + "features/DebugReceiver.kt"
DEBUG_FAKES = APP_KT + "features/DebugFakes.kt"
# ... and the release app never shows a value, an alert, a treatment or a sensor state that isn't real (2026-10-19):
# test alerts, status-bar previews, the lock screen test pattern, forced New sensor steps, adb treatments.
FAKE_CALL = re.compile(r"SensorLife\.update\(|SensorEnd\.(fake\w*|unlocked)\(|\bfake(Start|FirstReading)?\(|"
                       r"Live\.(reading|history)\(|\.onReading\(|RootStatusBar\.update\(|\b[Aa]lerts\.test\(|"
                       r"LockscreenWidget\.testPattern\s*=|NewSensorFlow\.preview\.value\s*=(?!\s*null\b)|"
                       r"Treat\.store\.add\(|\.addTreatment\(")
FAKE_DEF_OK = (DEBUG_FAKES, APP_KT + "sensorend/SensorEnd.kt")


def check_debug(ch, rep):
    """debug: no fake/SensorLife.update calls in DebugReceiver.kt; DebugFakes.kt has one public entry, gated first;
    SensorEnd.fake*() is called from DebugFakes.kt only."""
    def code(text):
        return [(n, l) for n, l in enumerate(text.splitlines(), 1) if not l.lstrip().startswith(("//", "*", "/*"))]
    live = ch.live()
    if DEBUG_RECEIVER in live:
        for n, l in code(ch.blob(DEBUG_RECEIVER)):
            if FAKE_CALL.search(l):
                rep.add("FAIL", "debug", "%s:%d replaces the sensor or makes up data outside the debug gate. Move it into\n"
                        "DebugFakes.run (debug builds only; a release build must change nothing)." % (DEBUG_RECEIVER, n))
    if DEBUG_FAKES in live:
        lines = code(ch.blob(DEBUG_FAKES))
        public = [(k, n, l) for k, (n, l) in enumerate(lines) if re.match(r"\s*fun\s", l)]
        gated = len(public) == 1 and re.match(r"\s*fun\s+run\(", public[0][2]) and \
            "if (!debug)" in next((l for _, l in lines[public[0][0] + 1:] if l.strip()), "")
        if not gated:
            rep.add("FAIL", "debug", "%s: needs exactly one public fun, run(…), whose first line is the\n"
                    "`if (!debug) { … return }` gate (everything else private)." % DEBUG_FAKES)
    for p, added in ch.added.items():
        if p in live and p.startswith(APP_KT) and p.endswith(".kt") and p not in FAKE_DEF_OK:
            for n, l in added:
                if re.search(r"SensorEnd\.fake\w*\(", l) and not l.lstrip().startswith("//"):
                    rep.add("FAIL", "debug", "%s:%d calls SensorEnd.fake*: only DebugFakes.kt may (debug builds only)." % (p, n))


def check_static(ch, rep):
    allow = load("allow.json")
    live = ch.live()
    for p in live:
        st = ch.files[p][0]
        if p.endswith(".java"):
            rep.add("FAIL", "java", "%s: Android is Kotlin only (JNI/C is fine). Rewrite it in Kotlin." % p)
        if SECRET_PATHS.search(p):
            rep.add("FAIL", "secrets", "%s: never commit secrets/, keys, APKs/dmgs, build dirs, local.properties." % p)
        if st == "A" and os.path.splitext(p)[1] in allow["header_exts"] and not match(p, allow["header_exempt"]):
            if "SPDX-License-Identifier: GPL-3.0-or-later" not in "\n".join(ch.blob(p).splitlines()[:8]):
                rep.add("FAIL", "header", "%s: new source file without the GPL header (copy the 3 lines from any file)." % p)
        size = ch.size(p)
        if size > allow["large_bytes"] and not match(p, allow["large_ok"]):
            rep.add("FAIL", "large", "%s: %d KB. Shrink it (crop/resize/pngquant) or list it in tools/check/allow.json large_ok." % (p, size // 1024))
        if IMAGE.search(p) and st == "A":
            if not match(p, allow["images_ok"]) and p not in allow["images_extra"]:
                rep.add("FAIL", "images", "%s: images go to docs/img/** or spikes/*/evidence/** (or allow.json images_extra)." % p)
            head = ch.blob(p, binary=True)[:24]
            if head[:8] == b"\x89PNG\r\n\x1a\n" and len(head) >= 24:
                w, h = int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")
                if w >= 900 and h >= 1.9 * w:
                    rep.add("WARN", "images", "%s: %dx%d looks like a full phone screenshot. Crop it (no other apps'\n"
                            "notifications) and make sure it shows synthetic/emulator data, never the user's real glucose." % (p, w, h))
    for p, lines in ch.added.items():
        if p not in live or match(p, allow["secret_ok"]):
            continue
        for n, text in lines:
            for rx, what in SECRET_TEXT:
                if rx.search(text):
                    rep.add("FAIL", "secrets", "%s:%d looks like %s. Remove it (and rotate it if it was real)." % (p, n, what))
    for p, lines in ch.added.items():
        if p not in live or not match(p, allow["strings_scope"]) or "/src/test/" in p:
            continue
        warn = match(p, allow["strings_warn_only"])
        for n, text in lines:
            if "i18n-ok" in text or text.lstrip().startswith("//"):
                continue
            for m in UI_TEXT.finditer(text):
                lit = next(g for g in m.groups() if g is not None)
                if re.search(r"[A-Za-z]{2,}", NOT_WORDS.sub("", lit)):
                    rep.add("WARN" if warn else "FAIL", "strings",
                            '%s:%d hardcoded UI text "%s". Put it in res/values/strings.xml + values-es (NEXT.md section 5),\n'
                            "or mark the line // i18n-ok if it is not user-visible text." % (p, n, lit[:60]))
    en = "spikes/showcase-android/app/src/main/res/values/strings.xml"
    es = "spikes/showcase-android/app/src/main/res/values-es/strings.xml"
    if en in ch.files or es in ch.files:
        def keys(path):
            return {m.group(2) for m in STRING_KEY.finditer(ch.blob(path)) if 'translatable="false"' not in m.group(3)}
        k_en, k_es = keys(en), keys(es)
        if k_en - k_es:
            rep.add("FAIL", "strings", "missing in values-es: %s" % ", ".join(sorted(k_en - k_es)[:12]))
        if k_es - k_en:
            rep.add("FAIL", "strings", "only in values-es (stale): %s" % ", ".join(sorted(k_es - k_en)[:12]))


def qa(ch):
    out = []
    for entry in load("qa.json")["map"]:
        if any(match(p, entry["paths"]) for p in ch.files):
            out += [q for q in entry["qa"] if q not in out]
    if out:
        print("QA reminder (docs/QA.md, check by hand before saying it works): " + "; ".join(sorted(out)))


def main(argv):
    mode, arg, only, strict = "range", None, None, os.environ.get("SUKRARA_STRICT") == "1"
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--staged":
            mode = "staged"
        elif a in ("--range", "--commit-msg", "--only", "--merge-of"):
            if i + 1 >= len(argv):
                sys.exit("%s needs a value" % a)
            if a == "--only":
                only = argv[i + 1]
            else:
                mode, arg = a[2:], argv[i + 1]
            i += 1
        elif a == "--strict":
            strict = True
        elif a in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            sys.exit("unknown argument %s (see --help)" % a)
        i += 1

    messages = []
    main_ref = git("rev-parse", "-q", "--verify", "origin/main", ok=True).strip()
    if mode == "staged":
        ch = Change(["--cached"], "")
    elif mode == "merge-of":  # re-judge a past merge commit as the commit-msg hook would have (calibration)
        p1, p2 = git("rev-parse", arg + "^1", arg + "^2").split()
        ch = Change([git("merge-base", p1, p2).strip(), p2], p2)
        messages = git("log", "--format=%B%x00", "%s..%s" % (p1, p2)).split("\0") + [git("log", "-1", "--format=%B", arg)]
        strict = True
    elif mode == "commit-msg":
        with open(arg) as f:
            messages = [f.read()]
        merge_head = git("rev-parse", "-q", "--verify", "MERGE_HEAD", ok=True).strip()
        if merge_head:  # a merge brings in a whole branch: judge the branch, strictly
            base = git("merge-base", "HEAD", merge_head).strip()
            ch = Change([base, merge_head], merge_head)
            messages += git("log", "--format=%B%x00", "HEAD.." + merge_head).split("\0")
            strict = True
        else:
            ch = Change(["--cached"], "")
    else:
        if arg is None:
            arg = "origin/main..HEAD" if main_ref else "HEAD~1..HEAD"
        a, _, b = arg.partition("..")
        b = b.lstrip(".") or "HEAD"
        base = git("merge-base", a, b).strip()
        ch = Change([base, b], b)
        messages = git("log", "--format=%B%x00", "%s..%s" % (a, b)).split("\0")
        strict = True

    rep = Report()
    if only in (None, "coupling"):
        check_parity(ch, messages, strict, rep)
        check_features(ch, messages, strict, rep)
    if only in (None, "static"):
        check_static(ch, rep)
        check_debug(ch, rep)
    rep.show()
    if only in (None, "coupling"):
        qa(ch)
    if rep.failed():
        print("\nChecks failed (tools/check/run.sh). Fix them; the rules and why are in CLAUDE.md.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

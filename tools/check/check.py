# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Deterministic guards for the repo rules in CLAUDE.md. Python 3.9 stdlib only; run through tools/check/run.sh.

Scope (V1): the per-app rules scan the V1 apps (apps/android, apps/macos) and contracts/; the frozen PoC (poc/) is
checked by java, secrets, large and images only. A file named by a registry (surfaces.json, allow.json) that doesn't
exist yet is a 'pending V1 file' NOTE (counted in one line; --pending lists them) until tools/check/v1-complete exists,
then it fails.

Modes
  --staged              the index (pre-commit): static rules fail, coupling rules only remind
  --commit-msg FILE     coupling rules with the message's trailers (commit-msg hook); strict for merge commits
  --range A..B          a branch / push / PR: everything strict (CI, tools/install.sh)
  --merge-of SHA        re-judge a past merge commit the way the commit-msg hook judges a merge
  --pending             list every pending V1 file (otherwise only counted)
Checks
  parity      path groups that must change together (parity.json), skip: 'Parity-skip: <group>: <reason>'
  features    app code changes need docs/FEATURES.md, skip: 'Features-skip: <reason>'
  java        no .java source (Android is Kotlin only)
  header      new source files carry the GPL SPDX header (a file moved out of poc/ counts as new)
  secrets     nsec/private keys/tokens in added lines; secrets/, keys, APKs, build dirs as paths
  large       blobs over allow.json large_bytes
  images      new images only under docs/img, poc/spikes/*/evidence, assets/brand, res, snapshot goldens; full-phone
              captures flagged
  strings     new hardcoded UI text in Android Kotlin; each module's values/ and values-es/ keys in sync
  cleartext   the app permits cleartext (network_security_config) so the home-network Nightscout works: every
              http:// / ws:// literal or "http"/"ws" scheme in Android Kotlin, and every place that opens a
              connection, must be on allow.json's cleartext_ok / network_ok lists (with the reason). Whole V1 tree.
  card-surface  one graph everywhere and the freshness steps everywhere (the user, 2026-10-21: a badge moved on one
              surface only; the status bar kept a 20-min-old value as if current; "things like this cannot happen
              again"). Whole V1 tree, Android + Mac, from THE registry contracts/graph/surfaces/surfaces.json:
              a file that calls the card renderer (GlucoseCard.draw / image / face) must be a registered file of the
              surface it names; the CardSurface enums list exactly the registry; a glyph surface takes its value from
              GlucoseCard.face; every surface is rendered by the cross-surface tests (the registry's 'tests').
  card-api    outside the renderer's files ('owners'), the renderer's namespaces (every top-level Kotlin object /
              Swift enum in them that paints, i.e. has a function taking a Canvas / CGContext, found automatically
              so a new painter is covered the moment it exists; plus the registry's 'api' keys, e.g. Freshness)
              may only be used through the members 'api' lists: a surface never lays out or draws a card element
              itself, nor judges a reading's age (Freshness.of / look) or uses an old staleness threshold.
  card-text   no file (outside the renderer and the tests) writes a glucose value itself (string templates /
              drawText / Text with sgv or mgdl, LO / HI literals): it shows the look's text (GlucoseCard.face), so a
              new surface that skips the renderer fails too. Data and event text (CSV, logs, an alert's reading) is
              listed in the registry's value_text_ok with the reason; a single line is marked '// card-ok: <why>'.
  clock       every clock time goes through Clock with the one 24-hour setting (Android :core:model Clock.kt, Mac
              SukraraCore Clock.swift; no own hour24 flag or time formatter elsewhere)
  debug       debug code is compiled out of release builds: Android DebugReceiver / DebugFakes / Synthetic* only in
              src/debug (never named in src/main or src/release, manifests included); Mac debugsend / DebugFakes /
              Synthetic* only inside #if DEBUG or in the snapshot / test targets
  settings    THE settings schema contracts/settings/settings.json (the user, 2026-10-21: settings must not diverge
              across platforms): generated SettingsSpec.kt/.swift up to date; every entry on both platforms or 'only'
              with the reason; alert 'path's are fields of contracts/alerts/SPEC.md §3; no stored key written by hand
              (Android *PreferencesKey("…"), Mac forKey: "…" / @AppStorage("…")): keys come from SettingsSpec.Keys;
              number settings only through the one input pattern (NumberDialog / NumberSettingRow; NumberSpec values
              only from the generated file). Whole V1 tree.
  states      complete flows (the user, 2026-10-21: "There's no way to unmute! On any platform"): every '## ' feature
              section of docs/FEATURES.md has a '**States and exits**' paragraph (each state a user can enter, where
              it shows, how to change and leave it, on every platform), unless allow.json 'states_pending' lists the
              section with the reason. A new section fails without one; a listed section that has one fails too
              (take it off the list). Whole file at the change's revision.
  durations   every user-visible duration goes through THE formatter (the user, 2026-10-21: "Showing 60 min and 120
              min is long, 1 h and 2 h is better. This applies everywhere in the UI; the mistake is repeated in many
              places"): Android :core:model Durations.kt, Mac SukraraCore Durations.swift, cases in contracts/settings/durations.json.
              Fails on a minute count written by hand: a string template / format with a number followed by min / m
              (Kotlin, Swift), a minutes placeholder in strings.xml outside the duration_* keys, the Words duration
              formats used outside Durations. Logs and data are exempt (log calls, tests, a line marked
              '// duration-ok: <why>', allow.json 'durations_ok' files with the reason). Whole tree.
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


ANDROID = "apps/android/"
MAC = "apps/macos/"
CONTRACTS = "contracts/"
POC = "poc/"
V1_COMPLETE = "tools/check/v1-complete"
ANDROID_MAIN = ANDROID + "*/src/main/*"  # fnmatch: '*' crosses '/'
ANDROID_KT = ANDROID + "*/src/main/kotlin/*.kt"
MAC_SOURCES = MAC + "Sources/"
KT_MODEL = ANDROID + "core/model/src/main/kotlin/app/mauri/sukrara/model/"


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


RENAMES = ("-M", "-l0")


class Change:
    """Files (path -> (status, new mode)) and added lines (path -> [(line, text)]) between two trees."""

    def __init__(self, diff_args, rev):
        self.rev = rev  # "" = the index, else a commit
        self.files = {}
        self.origin = {}  # renamed / copied path -> where it came from
        # Renames are detected (a moved file is R and its unchanged lines aren't "added"), so a tree move such as
        # spikes/ -> poc/spikes/ isn't re-checked as thousands of new files. -l0: no rename-detection limit.
        for line in git("diff", "--raw", *RENAMES, "--no-ext-diff", *diff_args).splitlines():
            meta, _, paths = line.partition("\t")
            path = paths.split("\t")[-1]
            parts = meta.split()
            if len(parts) >= 5:
                self.files[path] = (parts[4][0], parts[1])
                if parts[4][0] in "RC":
                    self.origin[path] = paths.split("\t")[0]
        self.added = {}
        path, n = None, 0
        for line in git("diff", "-U0", "--no-color", *RENAMES, "--no-ext-diff", *diff_args).splitlines():
            if line.startswith("+++ "):
                path = line[6:] if line.startswith("+++ b/") else None
            elif line.startswith("@@"):
                m = re.search(r"\+(\d+)", line)
                n = int(m.group(1)) if m else 0
            elif line.startswith("+") and path:
                self.added.setdefault(path, []).append((n, line[1:]))
                n += 1

    def new(self, path):
        """Added, or moved / copied out of the frozen PoC into V1 (a move doesn't make PoC code V1-clean)."""
        st = self.files[path][0]
        return st == "A" or (st in "RC" and self.origin.get(path, "").startswith(POC) and not path.startswith(POC))

    def live(self):
        """Added or modified regular files (no deletions, no symlinks, no submodules)."""
        return [p for p, (s, mode) in self.files.items() if s != "D" and mode.startswith("100")]

    def blob(self, path, binary=False):
        return git("cat-file", "blob", "%s:%s" % (self.rev, path), binary=binary, ok=True)

    def size(self, path):
        out = git("cat-file", "-s", "%s:%s" % (self.rev, path), ok=True).strip()
        return int(out) if out else 0


class Report:
    def __init__(self, list_pending=False):
        self.items = []
        self.pending_files = []
        self.list_pending = list_pending

    def pending(self, files, path, check, what):
        """True (and noted) when a file a registry names doesn't exist yet: a NOTE until tools/check/v1-complete exists,
        then a failure."""
        if path in files:
            return False
        if V1_COMPLETE in files:
            self.add("FAIL", check, "%s doesn't exist (%s). Fix the registry or write the file." % (path, what))
        elif path not in [p for p, _, _ in self.pending_files]:
            self.pending_files.append((path, check, what))
        return True

    def add(self, level, check, text):
        self.items.append((level, check, text))

    def failed(self):
        return any(lv == "FAIL" for lv, _, _ in self.items)

    def show(self):
        for lv, check, text in self.items:
            print("%s [%s] %s" % (lv, check, text.replace("\n", "\n    ")))
        if self.pending_files:
            print("NOTE [pending] %d V1 files named by the registries don't exist yet (fail once %s exists)%s" % (
                len(self.pending_files), V1_COMPLETE, ":" if self.list_pending else "; run.sh --pending lists them."))
            if self.list_pending:
                for path, check, what in self.pending_files:
                    print("    %s  (%s: %s)" % (path, check, what))


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
HEADER_HELP = ("    // Sukrara — https://github.com/mauriciabad/Sukrara\n    // Copyright (C) 2026 Maurici Abad\n"
               "    // SPDX-License-Identifier: GPL-3.0-or-later\n    (# for py / sh / yml, <!-- --> for xml)")
IMAGE = re.compile(r"\.(png|jpe?g|webp|gif|heic)$", re.I)
UI_TEXT = re.compile(r'\bText\(\s*"((?:[^"\\]|\\.)*)"'
                     r'|setTextViewText\([^,]+,\s*"((?:[^"\\]|\\.)*)"'
                     r'|\b(?:title|text|contentDescription|placeholder|supportingText)\s*=\s*"((?:[^"\\]|\\.)*)"'
                     r'|makeText\([^,]+,\s*"((?:[^"\\]|\\.)*)"')
NOT_WORDS = re.compile(r"\$\{[^}]*\}|\$\w+|mg/dL|mmol/L|Sukrara|Libre 3|dBm|n?c?r?y?p?t?sec1|https?://\S+")
STRING_KEY = re.compile(r'<(string|plurals)\s+name="([^"]+)"([^>]*)>')
# Debug commands that replace the sensor or make up data must not exist in a release build (2026-10-18: `cmd sensorEnd` on
# the user's release build faked a sensor over the real one; 2026-10-19: the release app never shows a value, an alert,
# a treatment or a sensor state that isn't real). V1 compiles them out: Android src/debug, Mac #if DEBUG / test targets.
DEBUG_NAMES = re.compile(r"\b(DebugReceiver|DebugFakes|Synthetic\w*)\b")
DEBUG_FILE = re.compile(r"(^|/)(DebugReceiver|DebugFakes|Synthetic\w*)\.kt$")
MAC_DEBUG_NAMES = re.compile(r"\b(debugsend|DebugFakes|Synthetic\w*)\b")
ANDROID_DEBUG_DIRS = ("/src/debug/", "/src/test/", "/src/androidTest/", "/src/testDebug/")
MAC_DEBUG_TARGETS = (MAC + "Tests/", MAC_SOURCES + "sukrara-snap/")


def outside_debug(text):
    """(line number, text) of Swift lines that a release build compiles: not inside an `#if DEBUG` branch."""
    out, stack = [], []  # one entry per open #if: is the current branch `DEBUG`?
    for n, l in enumerate(text.splitlines(), 1):
        s = l.strip()
        if s.startswith("#if"):
            stack.append(s[3:].strip() == "DEBUG")
            continue
        if s.startswith("#elseif"):
            if stack:
                stack[-1] = s[7:].strip() == "DEBUG"
            continue
        if s.startswith("#else"):
            if stack:
                stack[-1] = False
            continue
        if s.startswith("#endif"):
            if stack:
                stack.pop()
            continue
        if not any(stack) and not s.startswith("//"):
            out.append((n, l))
    return out


def check_debug(ch, rep):
    """debug: see the module doc. Whole V1 tree at the change's revision."""
    for p in tree_files(ch):
        if p.startswith(ANDROID) and not any(d in p for d in ANDROID_DEBUG_DIRS):
            if DEBUG_FILE.search(p):
                rep.add("FAIL", "debug", "%s: debug fakes live in the module's src/debug/ only (compiled out of release builds)." % p)
                continue
            if not (p.endswith(".kt") or p.endswith("AndroidManifest.xml")) or "/src/" not in p:
                continue
            for n, l in code_lines(ch.blob(p) or ""):
                m = DEBUG_NAMES.search(l)
                if m:
                    rep.add("FAIL", "debug", "%s:%d names %s outside src/debug: a release build must not contain debug fakes\n"
                            "(they replace the sensor or make up data). Register it from src/debug (its own manifest / code)." % (p, n, m.group(1)))
        elif p.startswith(MAC_SOURCES) and p.endswith(".swift") and not p.startswith(MAC_DEBUG_TARGETS):
            for n, l in outside_debug(ch.blob(p) or ""):
                m = MAC_DEBUG_NAMES.search(l)
                if m:
                    rep.add("FAIL", "debug", "%s:%d %s outside #if DEBUG: debug commands and fakes are compiled out of release\n"
                            "builds (wrap it in #if DEBUG, or move it to the sukrara-snap / test targets)." % (p, n, m.group(1)))


CLEARTEXT = re.compile(r"""["'](?:http|ws)(?:["':])""", re.I)
NETWORK = re.compile(r"openConnection\(|newWebSocket\(|Request\.Builder\(|\.toURL\(")


def check_cleartext(ch, rep):
    """cleartext: whole-tree scan of the V1 Android app's main Kotlin (not only the change): a cleartext URL or scheme, or a
    new place that opens connections, fails unless allow.json lists that exact spot with its reason."""
    allow = load("allow.json")
    clear_ok, net_ok = allow["cleartext_ok"], allow["network_ok"]
    where = ["--cached"] if not ch.rev else []
    out = git("grep", "-n", "-I", "-E", "-i", *where, "-e", r"(\"|')(http|ws)(\"|'|:)|openConnection\(|newWebSocket\(|Request\.Builder\(|\.toURL\(",
              *([ch.rev] if ch.rev else []), "--", ANDROID + "*.kt", ok=True)
    files = set(tree_files(ch))
    for e in clear_ok + net_ok:
        rep.pending(files, e["path"], "cleartext", "allow.json entry")
    for line in out.splitlines():
        if ch.rev and line.startswith(ch.rev + ":"):
            line = line[len(ch.rev) + 1:]
        path, _, rest = line.partition(":")
        n, _, text = rest.partition(":")
        if text.lstrip().startswith(("//", "*", "/*")) or not match(path, [ANDROID_MAIN]):
            continue
        for m in CLEARTEXT.finditer(text):
            if not any(e["path"] == path and e["contains"] in text for e in clear_ok):
                rep.add("FAIL", "cleartext", "%s:%s builds or accepts a cleartext URL (%s…). The app permits cleartext only for\n"
                        "the validated home-network Nightscout (NsSite.parse) and loopback; use https:// / wss://, or add this\n"
                        "exact spot to tools/check/allow.json cleartext_ok with the reason." % (path, n, text.strip()[:60]))
                break
        if NETWORK.search(text) and not any(e["path"] == path for e in net_ok):
            rep.add("FAIL", "cleartext", "%s:%s opens a connection in a file not on tools/check/allow.json network_ok. Make sure the\n"
                    "URL can only be https:// / wss:// (or loopback / NsSite-validated), then list the file with the reason." % (path, n))


SURFACES = CONTRACTS + "graph/surfaces/surfaces.json"
# old staleness thresholds (the freshness steps are Freshness.look's)
LEGACY_STALE = re.compile(r"\bSTALE_AFTER\w*|\bstaleAfter\b|\bisStale\b")
# a glucose value turned into text: Kotlin "${r.sgv}" / "$sgv" / sgv.toString(), Swift "\(x.mgdl)" / format(x.mgdl), LO/HI
# literals, and a value drawn as text (drawText / Text with an sgv)
VALUE_FORMAT = re.compile(r'"[^"\n]*\$\{?[\w.?!]*\bsgv\b[^"\n]*"|\bsgv\.toString\(\)|"[^"\n]*\\\([^)]*\b(?:sgv|mgdl)\b[^)]*\)[^"\n]*"'
                          r'|\bunit\.format\([^)]*\bmgdl\b|"(?:LO|HI)"|(?:drawText|\bText)\s*\(.*\bsgv\b')
# renderer calls a surface makes (Kotlin and Swift)
RENDER_CALL = re.compile(r"\bGlucoseCard\.(draw|image|face)\s*\(")


def tree_files(ch):
    """Every tracked file at the change's revision (the index for --staged)."""
    out = git("ls-files", "--cached", ok=True) if not ch.rev else git("ls-tree", "-r", "--name-only", ch.rev, ok=True)
    return out.splitlines()


def code_lines(text):
    """(line number, text) without comment lines and with trailing // comments cut (good enough for both languages)."""
    out = []
    for n, l in enumerate(text.splitlines(), 1):
        st = l.lstrip()
        if st.startswith(("//", "*", "/*", "///")):
            continue
        out.append((n, re.sub(r"\s//\s.*$", "", l)))
    return out


def call_text(code, k, start=0, limit=8):
    """The text of the call starting on code line index k at column start, up to its closing parenthesis (at most limit
    lines)."""
    joined, depth, started = "", 0, False
    for i, (_, l) in enumerate(code[k:k + limit]):
        if i == 0:
            l = l[start:]
        for ch_ in l:
            joined += ch_
            if ch_ == "(":
                depth += 1; started = True
            elif ch_ == ")":
                depth -= 1
                if started and depth == 0:
                    return joined
        joined += " "
    return joined


def governed(owner_texts, swift):
    """The renderer's namespaces, found automatically: every top-level Kotlin `object X` / Swift `enum X` in the
    owners that paints (has a function taking a Canvas / CGContext). New painters are governed the moment they exist."""
    names = set()
    head = re.compile(r"enum (\w+)\b[^{]*\{" if swift else r"(?:internal |private )?object (\w+)")
    painter = re.compile((r"static func \w+\((?:[^()]|\([^()]*\))*CGContext" if swift else
                          r"fun (?:<[^>]*> )?\w+\((?:[^()]|\([^()]*\))*\bCanvas\b"), re.S)
    for text in owner_texts:
        lines = text.splitlines()
        for k, l in enumerate(lines):
            m = head.match(l)
            if not m:
                continue
            body = []
            for b in lines[k + 1:]:
                if b.startswith("}"):
                    break
                body.append(b)
            if painter.search("\n".join(body)):
                names.add(m.group(1))
    return names


def check_card(ch, rep):
    """card-surface + card-api + card-text: see the module doc. Whole tree at the change's revision."""
    try:
        reg = json.loads(ch.blob(SURFACES) or "{}")
    except ValueError:
        rep.add("FAIL", "card-surface", "%s is not valid JSON." % SURFACES)
        return
    files = set(tree_files(ch))
    for plat, swift in (("android", False), ("mac", True)):
        cfg = reg.get(plat)
        if not cfg:
            rep.add("FAIL", "card-surface", "%s: no '%s' registry." % (SURFACES, plat))
            continue
        if rep.pending(files, cfg["enum"], "card-surface", "%s CardSurface enum" % plat):
            continue  # no renderer yet on this platform: nothing can call it
        owners = sorted(p for p in files if match(p, cfg["owners"]))
        tests = cfg["tests"]
        surfaces = cfg["surfaces"]
        ids = [s["id"] for s in surfaces]
        # the platform's enum lists exactly the registry (+ the tests' own surface)
        enum_text = ch.blob(cfg["enum"])
        if swift:
            body = re.search(r"enum CardSurface\b[^{]*\{(.*?)\n\}", enum_text, re.S)
            consts = dict(re.findall(r"case (\w+) = \"([\w-]+)\"", body.group(1))) if body else {}
        else:
            body = re.search(r"enum class CardSurface\b.*?\{(.*?);", enum_text, re.S)
            consts = dict(re.findall(r"(\w+)\(\"([\w-]+)\"", body.group(1))) if body else {}
        by_id = {v: k for k, v in consts.items()}
        if sorted(set(consts.values()) - {"test"}) != sorted(ids):
            rep.add("FAIL", "card-surface", "%s: CardSurface (%s) and %s %s (%s) list different surfaces. Every place that\n"
                    "shows the card or its value is registered in both." % (cfg["enum"], ", ".join(sorted(consts.values())), SURFACES,
                                                                         plat, ", ".join(sorted(ids))))
        const = (lambda c: "." + c) if swift else (lambda c: "CardSurface." + c)
        file_of = {}
        for s in surfaces:
            for f in s["files"]:
                rep.pending(files, f, "card-surface", "surface %s (%s)" % (s["id"], plat))
                file_of.setdefault(f, []).append(s)
        sources = sorted(p for p in files if match(p, cfg["sources"]) and p not in owners and not match(p, tests))
        texts = {p: ch.blob(p) for p in sources}
        api = cfg["api"]
        value_ok = {e["path"] for e in cfg.get("value_text_ok", [])}
        for e in cfg.get("value_text_ok", []):
            rep.pending(files, e["path"], "card-text", "value_text_ok (%s)" % plat)
        names = governed([ch.blob(o) for o in owners], swift) | set(api)
        ref = re.compile(r"(?<![\w])(?:[A-Za-z_][\w]*\.)*(%s)\.(\w+)" % "|".join(sorted(names))) if names else None
        for p, text in texts.items():
            code = [(n, l) for n, l in code_lines(text)]
            raw = text.splitlines()
            mine = file_of.get(p, [])
            mine_consts = {by_id.get(s["id"]) for s in mine}
            for k, (n, l) in enumerate(code):
                m = RENDER_CALL.search(l)
                if m:
                    if not mine:
                        rep.add("FAIL", "card-surface", "%s:%d calls the card renderer (GlucoseCard.%s) but isn't a registered surface. Every\n"
                                "place that shows the card or its value is in %s (and the CardSurface enum), with its sizes,\n"
                                "and is rendered by the cross-surface tests." % (p, n, m.group(1), SURFACES))
                    else:
                        call = call_text(code, k, m.start())
                        named = re.findall(r"CardSurface\.(\w+)" if not swift else r"surface:\s*\.(\w+)", call)
                        if not named and not re.search(r"\.surface\(\)|surface:\s*\w+|\bsurface\b", call):
                            rep.add("FAIL", "card-surface", "%s:%d asks the card renderer without naming its surface." % (p, n))
                        for c in named:
                            if c not in mine_consts:
                                rep.add("FAIL", "card-surface", "%s:%d asks the renderer as %s, which isn't registered for this file in %s." % (
                                    p, n, const(c), SURFACES))
            if ref:
                for n, l in code:
                    for m in ref.finditer(l):
                        obj, member = m.group(1), m.group(2)
                        if member not in api.get(obj, []):
                            rep.add("FAIL", "card-api", "%s:%d uses %s.%s, an internal of the card renderer. Surfaces only choose the size, their\n"
                                    "place's settings and the CardPart, and take the value from GlucoseCard.face; the card's layout,\n"
                                    "drawing and freshness stay in the renderer (%s 'api' lists what a surface may call; adding to\n"
                                    "it needs the same thinking as a new surface)." % (p, n, obj, member, SURFACES))
            for n, l in code:
                if LEGACY_STALE.search(l) and "card-ok" not in raw[n - 1]:
                    rep.add("FAIL", "card-api", "%s:%d uses an old staleness threshold. The freshness steps are GlucoseCard.face's." % (p, n))
            # no file formats a glucose value itself (a new surface that skips the renderer included), except data and
            # event text listed in the registry's value_text_ok
            if p not in value_ok:
                for n, l in code:
                    if VALUE_FORMAT.search(l) and "card-ok" not in raw[n - 1]:
                        rep.add("FAIL", "card-text", "%s:%d (%s) writes a glucose value itself. Show the look's text (GlucoseCard.face)\n"
                                "so it follows the card (LO / HI, struck at 5 min, gone at 20 min); a file that writes values as\n"
                                "data or event text goes on %s value_text_ok with the reason, a single line is marked\n"
                                "'// card-ok: <why>'." % (p, n, ("surface " + ", ".join(s["id"] for s in mine)) if mine else "not a registered surface", SURFACES))
            if not swift and "CardSurface.TEST" in text:
                rep.add("FAIL", "card-surface", "%s: CardSurface.TEST is for tests only." % p)
        # each surface names itself in its files; a glyph surface asks GlucoseCard.face
        for s in surfaces:
            c = by_id.get(s["id"])
            if any(f not in files for f in s["files"]):
                continue  # pending: noted above
            joined = "\n".join(ch.blob(f) for f in s["files"])
            if c and not re.search(re.escape(const(c)) + r"\b", joined):
                rep.add("FAIL", "card-surface", "surface '%s' (%s) never names %s in its files %s: move the entry to the files that\n"
                        "draw it, or remove the surface from %s and the enum." % (s["id"], plat, const(c), ", ".join(s["files"]), SURFACES))
            if s["kind"] == "glyph" and "GlucoseCard.face(" not in joined:
                rep.add("FAIL", "card-surface", "glyph surface '%s' (%s) must take the value / trend / freshness from GlucoseCard.face." % (s["id"], plat))
        # every surface is rendered by a cross-surface test
        harness = "\n".join(ch.blob(p) for p in sorted(files) if match(p, tests))
        for s in surfaces:
            c = by_id.get(s["id"])
            if any(f not in files for f in s["files"]):
                continue  # the surface isn't written yet
            if c and const(c) not in harness and ('"%s"' % s["id"]) not in harness:
                rep.add("FAIL", "card-surface", "surface '%s' (%s) isn't rendered by the %s cross-surface tests: add its real entry\n"
                        "path to the test's runner." % (s["id"], plat, plat))


# Android: the card renderer owns clock formatting (brief 08); the time *source* interface is :core:model Clock
CLOCK_KT = ANDROID + "core/graph/src/main/kotlin/app/mauri/sukrara/graph/Clock.kt"
CLOCK_SWIFT = MAC_SOURCES + "SukraraCore/Clock.swift"
# files that write times as data or log text (not the user's clock): path -> why. Each V1 chunk adds its own.
CLOCK_OK = {}


def v1_code(p):
    """'kt' / 'swift' for V1 app source (Android main Kotlin, Mac Sources outside the test / snapshot targets), else None."""
    if p.endswith(".kt") and match(p, [ANDROID_KT]):
        return "kt"
    if p.endswith(".swift") and p.startswith(MAC_SOURCES) and not p.startswith(MAC_DEBUG_TARGETS):
        return "swift"
    return None


LOCAL_HOUR24 = re.compile(r"(?<!Clock\.)\bhour24\b")
KT_TIME_FMT = re.compile(r"is24HourFormat\(|getTimeFormat\(|ofLocalizedTime\(|SimpleDateFormat\(\s*\"[^\"]*[Hhk]{1,2}:mm")
SWIFT_TIME_FMT = re.compile(r"time:\s*\.(shortened|standard|complete)|\.timeStyle|fromTemplate:\s*\"[^\"]*[Hhj]")


def check_clock(ch, rep):
    """clock: every clock time goes through Clock with the one 24-hour setting (the user, 2026-10-21: the value widget
    wrote "08:59" beside the chart cards' "8:59 AM"). No own hour24 flag, is24HourFormat or time formatter outside
    Clock (Android :core:graph Clock.kt, Mac SukraraCore Clock.swift; the app injects the system setting into Clock on a
    line that names Clock.hour24Source). Whole V1 tree; data / log formats are listed in CLOCK_OK."""
    for p in tree_files(ch):
        lang = v1_code(p)
        kt = lang == "kt"
        if not lang or p in (CLOCK_KT, CLOCK_SWIFT) or p in CLOCK_OK:
            continue
        for n, l in code_lines(ch.blob(p) or ""):
            if "Clock.hour24Source" in l:
                continue
            if "yyyy" in l:  # ISO / wire timestamps are data, not the user's clock
                continue
            bad = (KT_TIME_FMT if kt else SWIFT_TIME_FMT).search(l) or LOCAL_HOUR24.search(l)
            if bad:
                rep.add("FAIL", "clock", "%s:%d formats a clock time or reads the 24-hour setting itself (%s). Use Clock\n"
                        "(Clock.time / dateTime / minuteOfDay / hourLabel, Freshness.timeText) with its default setting."
                        % (p, n, bad.group(0)))


DURATIONS_KT = KT_MODEL + "Durations.kt"
DURATIONS_SWIFT = MAC_SOURCES + "SukraraCore/Durations.swift"
DURATIONS_LEVEL = "FAIL"
# a number (template, interpolation or format directive) followed by a minute unit inside a string literal
KT_MINUTES = re.compile(r"(\$\{[^}\"]*\}\s?|\$[A-Za-z_]\w*\s|%(\d\$)?d\s?)(min|mins|minutes|m)\b")
SWIFT_MINUTES = re.compile(r"(\\\([^\"]*?\)|%(\d\$)?d)\s?(min|mins|minutes|m)\b")
XML_MINUTES = re.compile(r"%(\d\$)?d\s?(min|mins|minutes|minutos|m)\b")
LOG_CALL = re.compile(r"\b(log|Log\.[a-z]|log\.[a-z]|logger|print|NSLog|EventLog\.[a-z]+|Logger\.[a-z]+)\s*\(|\bStrings\.log\b|\brec\.add\(")


def check_durations(ch, rep):
    """durations: see the module doc. Whole tree at the change's revision."""
    ok_files = load("allow.json").get("durations_ok", {})
    for p in tree_files(ch):
        lang = v1_code(p)
        kt = lang == "kt"
        xml = match(p, [ANDROID + "*/src/main/res/values*/strings.xml"])
        if not (lang or xml) or p in (DURATIONS_KT, DURATIONS_SWIFT) or p in ok_files:
            continue
        text = ch.blob(p) or ""
        if xml:
            for n, l in enumerate(text.splitlines(), 1):
                # the name is optional (an <item>), but when it's there the group must get it: a lazy optional group
                # matched nothing and let the duration_* formats themselves be flagged
                m = re.search(r'<(string|item)\b(?:[^>]*?\bname="([^"]+)")?[^>]*>(.*)', l)
                if not m or (m.group(2) or "").startswith("duration_"):
                    continue
                if XML_MINUTES.search(m.group(3)):
                    rep.add(DURATIONS_LEVEL, "durations", "%s:%d writes minutes by hand (%s). Take the duration as %%1$s and pass\n"
                            "Durations.text(min) (\"1 h 30 min\"), or Durations.compact on the card." % (p, n, m.group(3).strip()[:60]))
            continue
        for n, l in code_lines(text):
            if "duration-ok:" in l or LOG_CALL.search(l):
                continue
            for lit in re.findall(r'"(?:[^"\\]|\\.)*"', l):
                if (KT_MINUTES if kt else SWIFT_MINUTES).search(lit):
                    rep.add(DURATIONS_LEVEL, "durations", "%s:%d writes minutes by hand (%s): use Durations.text / compact (%s),\n"
                            "which writes 60 min as \"1 h\"." % (p, n, lit[:60], DURATIONS_KT if kt else DURATIONS_SWIFT))
                    break


SETTINGS_SCHEMA = CONTRACTS + "settings/settings.json"
SETTINGS_KT = ANDROID + "core/data/src/main/kotlin/app/mauri/sukrara/settings/SettingsSpec.kt"
SETTINGS_SWIFT = MAC_SOURCES + "SukraraCore/Settings/SettingsSpec.swift"
ALERT_SPEC = CONTRACTS + "alerts/SPEC.md"
KEY_NAME = re.compile(r"^[a-z][A-Za-z0-9]*$")
# a stored key written by hand (keys come from SettingsSpec.Keys)
KT_LITERAL_KEY = re.compile(r"\b(?:boolean|int|long|float|double|string|stringSet|byteArray)PreferencesKey\(\s*\"")
SWIFT_LITERAL_KEY = re.compile(r"\bforKey:\s*\"|@AppStorage\(\s*\"")
# a number setting's input outside the one pattern (NumberDialog / NumberSettingRow)
KT_NUMBER_INPUT = re.compile(r"\b(NumberRange|IntChoiceDialog|NumberChoiceDialog|Slider)\(|KeyboardType\.(Number|Decimal)\b"
                             r"|\blistOf\(\s*-?\d+(?:\.\d+)?\s*[,)]")
SWIFT_NUMBER_INPUT = re.compile(r"\bStepper\(|TextField\([^)]*\bvalue:|ForEach\(\s*(\d+\s*\.\.[.<]\s*\d|\[\s*-?\d)")
KT_SETTINGS_SCREENS = ANDROID + "feature/settings/src/main/*"
SWIFT_SETTINGS_SCREENS = MAC_SOURCES + "SukraraApp/Settings/*"


def alert_fields(spec):
    """(fields, kinds) of the alert settings JSON in SPEC.md §3: the dotted leaf paths with each alert kind written
    '{kind}' (an empty object, sounds.kinds, holds one entry per kind), and the kinds."""
    m = re.search(r"## 3\..*?```json\n(.*?)```", spec or "", re.S)
    if not m:
        return None, None
    obj = json.loads(m.group(1))
    kinds = set(obj.get("alerts", {}))
    out = set()

    def walk(v, prefix):
        if isinstance(v, dict):
            for k, x in v.items():
                walk(x, prefix + [k])
            if not v:
                out.add(".".join(prefix + ["{kind}"]))
        else:
            out.add(".".join(prefix))
    walk(obj, [])
    return {normal_field(f, kinds) for f in out}, kinds


def normal_field(path, kinds):
    """alerts.low.mgdl -> alerts.{kind}.mgdl"""
    parts = path.split(".")
    if len(parts) > 2 and parts[0] == "alerts" and parts[1] in kinds:
        parts[1] = "{kind}"
    return ".".join(parts)


def check_settings(ch, rep):
    """settings: THE schema (contracts/settings/settings.json; the user, 2026-10-21: "make something that makes sure
    settings don't diverge across platforms"). Whole V1 tree at the change's revision:
    - the generated SettingsSpec.kt / .swift match the schema (tools/settings/gen.py);
    - every entry is on both platforms, or on one with 'only': {platform, reason}; number refs and defaults are valid;
      keys are camelCase; an alert entry's 'path' is the same on both platforms and a field of SPEC.md §3, and every
      SPEC §3 field has an entry;
    - no stored key is written by hand: Android *PreferencesKey("…"), Mac forKey: "…" / @AppStorage("…") outside the
      generated file (keys come from SettingsSpec.Keys; SettingsSchemaTest on both apps covers the models);
    - number settings use the one input pattern: no NumberSpec made by hand; in the settings screens no Slider /
      number keyboard / literal number list (Android) and no Stepper / numeric TextField / literal number ForEach (Mac)
      outside NumberDialog / NumberSettingRow (a non-number line is marked // settings-ok: <why>)."""
    text = ch.blob(SETTINGS_SCHEMA)
    if not text:
        return
    try:
        schema = json.loads(text)
    except ValueError as e:
        return rep.add("FAIL", "settings", "%s is not valid JSON: %s" % (SETTINGS_SCHEMA, e))
    sys.path.insert(0, os.path.join(HERE, "..", "settings"))
    import gen  # noqa: E402  (tools/settings/gen.py, stdlib only)
    for path, want in ((SETTINGS_KT, gen.kotlin(schema)), (SETTINGS_SWIFT, gen.swift(schema))):
        if ch.blob(path) != want:
            rep.add("FAIL", "settings", "%s is stale or edited by hand: run python3 tools/settings/gen.py and commit it." % path)
    numbers = schema.get("numbers", {})
    for name, n in numbers.items():
        lo, hi, step = n.get("min"), n.get("max"), n.get("step")
        if lo is None or hi is None or not step or lo > hi:
            rep.add("FAIL", "settings", "number %s: needs min <= max and step > 0." % name)
            continue
        special = {float(k) for k in n.get("special", {})}

        def valid(v):
            on = abs((v - lo) / step - round((v - lo) / step)) < 1e-6
            return v in special or v in n.get("presets", []) or (n.get("custom", True) and lo <= v <= hi and on)
        if not valid(n.get("default")):
            rep.add("FAIL", "settings", "number %s: default %s isn't a valid value." % (name, n.get("default")))
    for name, o in schema.get("options", {}).items():
        if name.startswith("_"):
            continue
        vals = o.get("values") or []
        if not vals or len(set(vals)) != len(vals):
            rep.add("FAIL", "settings", "options %s: needs a non-empty list of distinct values." % name)
    fields, kinds = alert_fields(ch.blob(ALERT_SPEC))
    paths, seen = set(), set()
    for s in schema.get("settings", []):
        sid = s.get("id", "?")
        if sid in seen:
            rep.add("FAIL", "settings", "setting %s is listed twice." % sid)
        seen.add(sid)
        if s.get("number") and s["number"] not in numbers:
            rep.add("FAIL", "settings", "setting %s: number '%s' isn't in 'numbers'." % (sid, s["number"]))
        on = [p for p in ("android", "mac") if s.get(p)]
        only = s.get("only") or {}
        if not on:
            rep.add("FAIL", "settings", "setting %s is on neither platform." % sid)
        elif len(on) == 1 and (only.get("platform") != on[0] or len(only.get("reason", "").strip()) < 10):
            rep.add("FAIL", "settings", "setting %s exists on %s only: add it to the other platform, or give\n"
                    "'only': {\"platform\": \"%s\", \"reason\": \"why it can't be there\"}." % (sid, on[0], on[0]))
        elif len(on) == 2 and only:
            rep.add("FAIL", "settings", "setting %s is on both platforms but says 'only' %s." % (sid, only.get("platform")))
        if s.get("mirror") and not s.get("wire"):
            rep.add("FAIL", "settings", "setting %s is mirrored from the wearer but has no 'wire' key." % sid)
        for p in on:
            for k in s[p]["key"].split(","):
                if s[p]["store"] != "system" and not KEY_NAME.match(k):
                    rep.add("FAIL", "settings", "setting %s: %s key '%s' isn't a camelCase name." % (sid, p, k))
        ps = {s[p].get("path") for p in on}
        if len(ps) > 1:
            rep.add("FAIL", "settings", "setting %s: the platforms address different fields (%s)." % (sid, ", ".join(sorted(map(str, ps)))))
        path = next(iter(ps), None)
        if path:
            paths.add(normal_field(path, kinds or set()))
            if fields is not None and normal_field(path, kinds) not in fields:
                rep.add("FAIL", "settings", "setting %s: path '%s' isn't a field of the alert settings JSON (%s §3)." % (sid, path, ALERT_SPEC))
    if fields is None:
        rep.add("FAIL", "settings", "%s §3: can't find the alert settings JSON example." % ALERT_SPEC)
    else:
        for f in sorted(fields - paths):
            rep.add("FAIL", "settings", "%s §3 field '%s' has no entry in %s." % (ALERT_SPEC, f, SETTINGS_SCHEMA))
    for p in tree_files(ch):
        lang = v1_code(p)
        if not lang or p in (SETTINGS_KT, SETTINGS_SWIFT):
            continue
        src = ch.blob(p) or ""
        kt = lang == "kt"
        screen = match(p, [KT_SETTINGS_SCREENS]) and "fun NumberDialog(" not in src if kt else \
            match(p, [SWIFT_SETTINGS_SCREENS]) and "struct NumberSettingRow" not in src
        for n, l in code_lines(src):
            if re.search(r"\bNumberSpec\(", l) and "class NumberSpec" not in l and "struct NumberSpec" not in l:
                rep.add("FAIL", "settings", "%s:%d makes a NumberSpec by hand. Number formats come from %s (generated)."
                        % (p, n, SETTINGS_SCHEMA))
            if (KT_LITERAL_KEY if kt else SWIFT_LITERAL_KEY).search(l) and "settings-ok" not in l:
                rep.add("FAIL", "settings", "%s:%d writes a stored key by hand. Take it from SettingsSpec.Keys (add the setting to\n"
                        "%s and run tools/settings/gen.py), or mark a non-setting key // settings-ok: <why>." % (p, n, SETTINGS_SCHEMA))
            bad = screen and (KT_NUMBER_INPUT if kt else SWIFT_NUMBER_INPUT).search(l)
            if bad and "settings-ok" not in l:
                rep.add("FAIL", "settings", "%s:%d a number setting's input outside the one pattern (%s). Use %s with its\n"
                        "SettingsSpec number, or mark a non-number line // settings-ok: <why>."
                        % (p, n, bad.group(0).strip(), "NumberDialog" if kt else "NumberSettingRow"))


def check_static(ch, rep):
    allow = load("allow.json")
    live = ch.live()
    for p in live:
        st = ch.files[p][0]
        if p.endswith(".java"):
            rep.add("FAIL", "java", "%s: Android is Kotlin only (JNI/C is fine). Rewrite it in Kotlin." % p)
        if SECRET_PATHS.search(p):
            rep.add("FAIL", "secrets", "%s: never commit secrets/, keys, APKs/dmgs, build dirs, local.properties." % p)
        if ch.new(p) and not p.startswith(POC) and os.path.splitext(p)[1] in allow["header_exts"] \
                and not match(p, allow["header_exempt"]):
            if "SPDX-License-Identifier: GPL-3.0-or-later" not in "\n".join(ch.blob(p).splitlines()[:8]):
                rep.add("FAIL", "header", "%s: new source file without the GPL header:\n%s" % (p, HEADER_HELP))
        size = ch.size(p)
        if size > allow["large_bytes"] and not match(p, allow["large_ok"]):
            rep.add("FAIL", "large", "%s: %d KB. Shrink it (crop/resize/pngquant) or list it in tools/check/allow.json large_ok." % (p, size // 1024))
        if IMAGE.search(p) and st == "A":
            if not match(p, allow["images_ok"]) and p not in allow["images_extra"]:
                rep.add("FAIL", "images", "%s: images go to docs/img/**, assets/brand/**, res/ or the snapshot goldens\n"
                        "(allow.json images_ok; synthetic data only), or allow.json images_extra with the reason." % p)
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
                            '%s:%d hardcoded UI text "%s". Put it in the module res/values/strings.xml + values-es,\n'
                            "or mark the line // i18n-ok if it is not user-visible text." % (p, n, lit[:60]))
    # each module's English and Spanish keys match (checked when either file changes)
    def keys(path):
        return {m.group(2) for m in STRING_KEY.finditer(ch.blob(path) or "") if 'translatable="false"' not in m.group(3)}
    modules = {p.split("/src/main/res/")[0] for p in ch.files
               if match(p, [ANDROID + "*/src/main/res/values/strings.xml", ANDROID + "*/src/main/res/values-es/strings.xml"])}
    for mod in sorted(modules):
        en, es = mod + "/src/main/res/values/strings.xml", mod + "/src/main/res/values-es/strings.xml"
        k_en, k_es = keys(en), keys(es)
        if k_en - k_es:
            rep.add("FAIL", "strings", "%s: missing in values-es: %s" % (mod, ", ".join(sorted(k_en - k_es)[:12])))
        if k_es - k_en:
            rep.add("FAIL", "strings", "%s: only in values-es (stale): %s" % (mod, ", ".join(sorted(k_es - k_en)[:12])))


def check_states(ch, rep):
    """states: see the module doc."""
    text = ch.blob("docs/FEATURES.md")
    if text is None:
        return
    pending = {e["section"]: e.get("reason", "") for e in load("allow.json").get("states_pending", [])}
    sections = re.split(r"(?m)^## ", text)[1:]
    names = []
    for sec in sections:
        name = sec.split("\n", 1)[0].strip()
        names.append(name)
        has = "**States and exits**" in sec
        if not has and name not in pending:
            rep.add("FAIL", "states", "docs/FEATURES.md '## %s' has no **States and exits** paragraph.\n"
                    "List every state a user can enter, where it shows, and how to change it and leave it (undo, cancel,\n"
                    "turn off, unmute, go back) on every platform and surface (CLAUDE.md 'Complete flows')." % name)
        elif has and name in pending:
            rep.add("FAIL", "states", "docs/FEATURES.md '## %s' has its **States and exits** now: take it off\n"
                    "tools/check/allow.json states_pending." % name)
    for name in pending:
        if name not in names:
            rep.add("FAIL", "states", "tools/check/allow.json states_pending lists '## %s', which docs/FEATURES.md doesn't have." % name)


def qa(ch):
    out = []
    for entry in load("qa.json")["map"]:
        if any(match(p, entry["paths"]) for p in ch.files):
            out += [q for q in entry["qa"] if q not in out]
    if out:
        print("QA reminder (docs/QA.md, check by hand before saying it works): " + "; ".join(sorted(out)))


def main(argv):
    mode, arg, only, strict = "range", None, None, os.environ.get("SUKRARA_STRICT") == "1"
    list_pending = False
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
        elif a == "--pending":
            list_pending = True
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

    rep = Report(list_pending)
    if only in (None, "coupling"):
        check_parity(ch, messages, strict, rep)
        check_features(ch, messages, strict, rep)
    if only in (None, "static"):
        check_static(ch, rep)
        check_debug(ch, rep)
        check_cleartext(ch, rep)
        check_card(ch, rep)
        check_clock(ch, rep)
        check_settings(ch, rep)
        check_states(ch, rep)
        check_durations(ch, rep)
    rep.show()
    if only in (None, "coupling"):
        qa(ch)
    if rep.failed():
        print("\nChecks failed (tools/check/run.sh). Fix them; the rules and why are in CLAUDE.md.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

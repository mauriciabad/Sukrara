#!/usr/bin/env python3
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Copy docs/ to the website source and point every link that leaves docs/ at the file on GitHub.

The reports link the repository the GitHub way (`../poc/spikes/x/evidence/a.png`), which works on github.com but not on
the website: Pages publishes docs/ only. Rewriting at build time keeps one way of writing links and makes every new
report work on the site too: images → raw file at this commit, other files → their GitHub page, folders → the tree.

    python3 .github/site_links.py docs site OWNER/REPO SHA
"""
import os, re, shutil, sys

src, out, repo, sha = sys.argv[1:5]
root = os.path.dirname(os.path.abspath(src))  # the repository
IMAGES = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".m4a", ".mp4", ".webm")
LINK = re.compile(r'(\]\(|\b(?:src|href)=")(\.\./[^)"\s#]*)(#[^)"\s]*)?')

shutil.rmtree(out, ignore_errors=True)
shutil.copytree(src, out)
docs = os.path.abspath(src)
changed = 0
for dirpath, _, files in os.walk(out):
    for name in files:
        if not name.endswith((".md", ".html")):
            continue
        path = os.path.join(dirpath, name)
        here = os.path.join(docs, os.path.relpath(dirpath, out))  # where the original file sits in docs/

        def fix(m):
            global changed
            target = os.path.normpath(os.path.join(here, m.group(2)))
            if target == docs or target.startswith(docs + os.sep):
                return m.group(0)  # still inside docs/: the site has it
            rel = os.path.relpath(target, root).replace(os.sep, "/")
            if rel.startswith(".."):
                return m.group(0)
            changed += 1
            if rel.lower().endswith(IMAGES):
                url = f"https://raw.githubusercontent.com/{repo}/{sha}/{rel}"
            else:
                kind = "tree" if os.path.isdir(os.path.join(root, rel)) else "blob"
                url = f"https://github.com/{repo}/{kind}/{sha}/{rel}"
            return m.group(1) + url + (m.group(3) or "")

        with open(path, encoding="utf-8") as f:
            text = f.read()
        new = LINK.sub(fix, text)
        if new != text:
            with open(path, "w", encoding="utf-8") as f:
                f.write(new)
print(f"{changed} links outside docs/ now point at github.com/{repo} @ {sha[:7]}")

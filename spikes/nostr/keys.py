# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Create a stream identity (source key S + read key G) for the desktop test publisher.

  uv run keys.py new [--password PW]   → secrets/stream.json, prints the follower link, writes secrets/follower-qr.png,
                                         and (with --password) NIP-49 ncryptsec backups of both secrets
  uv run keys.py link                  → print the follower link of secrets/stream.json
"""
import json, sys
from pathlib import Path

import nostr_sdk as n
import qrcode

from cgmnostr import Stream, new_stream, DEFAULT_RELAYS

SECRETS = Path(__file__).parent / "secrets"


def load() -> Stream:
    d = json.loads((SECRETS / "stream.json").read_text())
    s = n.Keys.parse(d["source"])
    return Stream(s.public_key(), n.Keys.parse(d["read"]), d["relays"], s)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "link"
    if cmd == "new":
        SECRETS.mkdir(exist_ok=True)
        st = new_stream(DEFAULT_RELAYS)
        (SECRETS / "stream.json").write_text(json.dumps({
            "source": st.source.secret_key().to_bech32(), "read": st.read.secret_key().to_bech32(), "relays": st.relays}, indent=2))
        (SECRETS / "follower-link-desktop.txt").write_text(st.link())
        qrcode.make(st.link()).save(SECRETS / "follower-qr.png")
        print("source npub:", st.source_pub.to_bech32())
        print("follower link + QR written to secrets/ (contains the read key — treat as a password)")
        if "--password" in sys.argv:
            pw = sys.argv[sys.argv.index("--password") + 1]
            for name, k in (("source", st.source), ("read", st.read)):
                enc = k.secret_key().encrypt(pw)   # NIP-49
                (SECRETS / f"{name}.ncryptsec").write_text(enc.to_bech32())
                # round-trip check
                back = n.EncryptedSecretKey.from_bech32(enc.to_bech32()).decrypt(pw)
                assert back.to_hex() == k.secret_key().to_hex()
                print(f"{name}: NIP-49 ncryptsec ({len(enc.to_bech32())} chars) written, decrypt round-trip OK")
    else:
        print(load().link())


if __name__ == "__main__":
    main()

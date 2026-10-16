# Identity for CGM-over-Nostr: profiles, short invites, Nostr login (investigation 14)

Additions to [PROTOCOL-v2](PROTOCOL-v2.md). Nothing in v2 changes: same identity `I`, device key `D`, read keys,
roster, access request and key delivery. Old invite links keep working. Report and test results:
[docs/14](../../docs/14-nostr-identity.md). Reference code: [`identity/ident.py`](identity/ident.py) (Python) and
[`identity-android/…/Derive.kt`](identity-android/app/src/main/kotlin/dev/spike/nostrid/Derive.kt) (Kotlin). The two
are checked against each other with [`identity/vectors.json`](identity/vectors.json).

## Keys

```
 main Nostr key M (optional, "log in with Nostr") ──HKDF──┐      never signs anything for Sukrara
                                                           ▼
 app-generated random ─────────────────────────────▶ root R = identity I (v2)   no kind 0, ever
                                                           │ HKDF("profile")
                                                           ▼
                                                     profile key P              publishes only kind 0
```

| Key | Derivation | Publishes | Backup |
|---|---|---|---|
| **root `R` = identity `I`** | random (default), `derived_account(M)`, or a random root sealed to `M` | roster, key deliveries, owner backup (v2); invite wraps | password manager (default); nothing extra when derived or sealed |
| **profile `P`** | `subkey(R, "profile")` | **only** kind 0 (and the BUD-11 auth of its own picture uploads) | none needed |
| **main `M`** | the user's existing Nostr key | **nothing**. It is used once to derive `R` (nsec, ncryptsec or words), or only to open the sealed root (signer) | the user's own |

**Why the profile has its own key.** The protocol is public. Anyone who knows it can spot `I`'s events: weekly kind
30078 with a `gk` tag, plus gift wraps addressed to it. A public name and photo on `I` would put a real person next to
a recognisable glucose stream. `P` publishes nothing except kind 0, so it looks like any lurker account. Only people
holding an invite or a key delivery learn that `P` belongs to `I`.

### Derivation (RFC 5869 HKDF-SHA256)

```
scalar(ikm, salt, info) = first k = HKDF(ikm, salt, info ‖ [ctr if ctr>0]) with 1 ≤ k < n, ctr = 0,1,…
derived_account(M, app="app.mauri.sukrara", index=0) = scalar(M.sec, "nostr/derived-account/v1", app + "#" + index)
subkey(R, purpose)                                   = scalar(R.sec, "sukrara/subkey/v1", purpose)
invite_key(s)                                        = scalar(s, "sukrara/invite/v3", "wrap-recipient")
```
The derivation is one-way, so `R` reveals nothing about `M`. The same `M` gives the same account on every device.
Prior art: the open NIP-PNS proposal (nips#1893) derives private-storage keys from the nsec the same way, with
`hkdf_extract(nsec, "nip-pns")`. No merged NIP derives keys from an nsec. NIP-06 only covers mnemonics, and since
2026-05 it is `unrecommended`.

## Profiles (kind 0)

NIP-01 + NIP-24 kind 0 signed by `P`:
`{"name": n, "display_name": n, "picture": url}`, with nothing else. `about`, `website`, `nip05` and `lud16` are left
out, because any of them could hint at health or identity. Plus a **NIP-92 `imeta` tag** for the picture (the
"Profile metadata" section, merged 2026-10-05):

```json
["imeta", "url https://blossom.primal.net/<x>.png", "m image/png", "x <sha256>", "dim 512x512",
 "fallback https://nostr.download/<x>.png", "fallback https://24242.io/<x>.png"]
```

- **Pictures go on Blossom.** BUD-02 `PUT /upload` signed by `P` with a BUD-11 kind 24242 auth event (`t`, `x`,
  `expiration`, `server`). The first successful server becomes `picture`, the rest become `fallback`s. Clients check
  that the bytes hash to the `<x>` in the URL, so nobody can swap a picture.
  **Interop note:** BUD-11 says the header is base64url without padding. blossom.primal.net and nostr.download
  (route96) reject that and accept only standard padded base64, which every server we tried accepts.
- **Photos** are decoded and re-encoded from pixels: centre square, 512 px JPEG. That drops all EXIF (GPS, camera,
  time). blossom.band refuses uploads that still contain GPS data.
- **Generated avatars (the default):** `{palette, shape, angle, seed, initial}` is rendered to a 512 px PNG and
  uploaded like a photo, so every Nostr client shows it. The parameters stay local, for editing.
- `data:` URIs are accepted by most relays (7–15 KB events) but other clients don't render them. Don't use them.
- **Name first, picture later.** Image servers can be slow. If no upload succeeds, publish kind 0 without
  `picture`, retry the upload, then republish.
- **"Use my Nostr profile":** for Nostr-login users, `P := M`. No new kind 0 is published. Followers learn `M` from
  the invite and key delivery and read `M`'s existing profile. Nothing public links `M` to Sukrara.

## Short invites (v3)

```
https://sukrara.mauri.app/join#<base64url( I.pub[0:8] ‖ s )>        s = 16 random bytes → 32 chars, 63 in total
```

- The link holds an **8-byte fingerprint of `I`** and the **invite secret `s`**, which is also the v2 token
  (`t = base64url(s)`). The match code is unchanged: `sha256(F.pub_hex + t) mod 10000`.
- **The invite itself is a standard NIP-59 gift wrap** on the app's default relays:
  - rumor kind **30502** `{"v":3,"type":"invite","x":expiry,"r":[relays],"profile":P.pub,"name":…}`
  - sealed (signed) by `I`
  - wrapped to `invite_key(s)`
  - NIP-40 `expiration` = invite expiry, so relays drop it on their own
- **Follower:**
  1. Decode the link and derive `invite_key(s)`.
  2. `REQ {"kinds":[1059], "#p":[invite_key.pub]}`, answering NIP-42 AUTH as `invite_key` where needed.
  3. Unwrap. Accept the wrap only if the seal's signer starts with the fingerprint and `x` is still in the future.
  4. The result is exactly a v2 invite `{i, t, x, r}` plus the wearer's `profile`. Continue with the v2 access request.
- **Access request:** the v2 request gains `"profile": P.pub`. The wearer shows the follower's own name and picture
  from kind 0. The v2 `name` stays as a fallback.
- **Why the fingerprint:** anyone holding the link can also publish a wrap for `invite_key(s)`. Without the
  fingerprint they could point the follower at their own identity. Matching 8 bytes of a secp256k1 key takes about
  2⁶⁴ key generations, and the invite expires in 24 h.
- **Still parsed:** `https://sukrara.mauri.app/join#i=…&t=…&x=…&r=…` and `cgmshare:join?…`. A v3 link is a fragment
  of exactly 32 chars with no `=`.
- **Default relays for the lookup:** nos.lol, relay.primal.net, nostr.mom, offchain.pub. relay.damus.io and
  relay.nostr.net accept gift wraps but can't serve them (their AUTH answers "relay needs serviceUrl", 2026-10-16).

## Nostr login

| How the user brings their key | Sukrara root | What's stored | Public trace |
|---|---|---|---|
| **nsec / NIP-49 ncryptsec / NIP-06 words** pasted once | `derived_account(M)` | `R` only (Keystore), `M` is dropped | none |
| **NIP-55 signer (Amber)** | random `R`, **sealed to `M`** | `R` (Keystore), signer package + `M.pub` | one kind 1059 addressed to `M`, the same shape as a NIP-17 DM copy |
| NIP-46 bunker | same as NIP-55 (not built in the PoC) | | |
| Amber with a mnemonic | either of the above, or pick a NIP-06 account index in Amber | | none |

**Sealed root** (signers can't derive: no NIP-46/55 method returns anything both deterministic and secret, and
signatures use random aux data):

1. The rumor is kind **30503** `{"v":1,"type":"sukrara-root","app":…,"root":hex}`, authored by `M`.
2. The seal is kind 13. Its content is produced by the signer's `nip44_encrypt` to `M` itself, then signed with the
   signer's `sign_event`.
3. The wrap is kind 1059, with a one-time key and `p = M`.
4. **Restore:** query `{"kinds":[1059], "#p":[M]}` and open each wrap with the signer's `nip44_decrypt`. Accept only a
   seal signed by `M` that wraps a 30503 rumor for this app.

`get_public_key` pre-authorises `sign_event` (kind 13), `nip44_encrypt` and `nip44_decrypt`. With Amber, every later
call then goes through the content resolver without a prompt.

**Never:** sign with `M` directly, publish a kind 0 on `M`, or AUTH to relays as `M` from Sukrara. Each of those would
link the main account to the app.

## Leaving

"Delete account everywhere", in this order:
1. A BUD-12 `DELETE /<sha256>` on every picture server.
2. An **empty kind 0** on `P`. Kind 0 is replaceable, and this was the most effective step: the old name stayed on
   1 of 7 relays.
3. A **NIP-62** kind 62 request to vanish (`relay` tags) for `P` and `I`. Relays accept it, but only relay.damus.io
   erased everything. NIP-09 alone left the profile on 3 of 7 relays.

This is best effort. blossom.band and nostr.build also keep serving deleted blobs. The UI says "asks relays and image
servers to erase it" and never promises erasure.

# Juggluco: two problems in the Dexcom G7 EC-J-PAKE — draft report

**Status: draft, not sent.** Nothing has been filed, posted or emailed. The user decides whether and how to send it.

- Upstream: [`j-kaltes/Juggluco`](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b), default branch `primary`. Checked at **`75399d7`** (upstream HEAD,
  2026-10-16); `ecJPake.cpp` is byte-identical to the local clone at `a983788`, so the lines below hold for both.
- How Juggluco takes reports: no `.github/ISSUE_TEMPLATE`, no `CONTRIBUTING`, no `SECURITY.md`, and GitHub private
  vulnerability reporting is off (`gh api repos/j-kaltes/Juggluco/private-vulnerability-reporting` →
  `enabled: false`). Issues are open and the maintainer answers them (for example #488, about G7-family pairing). The
  maintainer's email is in the GPL header of every source file, including `ecJPake.cpp`.
- Duplicates: none found (searched open and closed issues for J-PAKE, `ecJPake`, `EC_POINT_cmp`, nonce, validate3,
  zero knowledge, proof, security, vulnerability).

## Recommendation: report privately first

Problem 2 lets anyone who records a G7's pairing over Bluetooth derive the key that Juggluco later uses to
authenticate to that sensor. The fix is a few lines and the maintainer is responsive, so **send Part A by email
first**. Then file Part B as a public issue once a fixed release is out, or sooner if the maintainer asks for it.
Problem 1 on its own would be fine to file publicly, but both fixes are in the same function and have to ship
together (see "Fix both at once"), so one report is simpler.

---

## Part A — private email

**To:** the address in the header of `Common/src/main/cpp/dexcom/ecJPake.cpp`
**Subject:** Juggluco Dexcom G7 pairing: proof check never fails, and a fixed round-3 nonce

> Hello Jaap,
>
> While reading `Common/src/main/cpp/dexcom/ecJPake.cpp` (checked at 75399d7) for an interoperability project, I
> found two problems in the G7 EC-J-PAKE. I am reporting them to you privately because the second one has a
> security side. Details, a reproduction and a suggested patch are below; it is the same text as the public issue I
> would file once you have had a chance to fix it, or whenever you prefer.
>
> **1. The zero-knowledge-proof check never fails.** The sensor's party id is hashed as `b_bytes = 37 56 27 67 56 27`
> (line 334), the nibble-swapped form of ASCII `"server"` (`73 65 72 76 65 72`). And `validateZeroKnowledgeProof`
> returns `EC_POINT_cmp(...)==1` (line 564), but `EC_POINT_cmp` returns 0 when the points are equal. The two cancel:
> with the wrong id a genuine packet gives "not equal", and the inverted test reports it as valid. Pairing works,
> but no proof is actually checked, so the protection the proofs give J-PAKE against an active attacker during
> pairing is missing.
>
> **2. Round 3 always uses the same nonce.** `makeRound3Cert` sets `ran3` from a constant (line 489). Because that
> value is in the published source, anyone who records the phone's round-3 packet during pairing can compute
> `x2·s` from it. With the round-2 packet and the 4-digit code space, that is enough to compute the shared key that
> Juggluco saves and later uses to answer the sensor's `0x02`/`0x03` challenge.
>
> Suggested patch (three hunks, one file) and a reproduction script are in the issue text below. Note that fixing
> only the id or only the comparison would break G7 pairing; they need to change together.
>
> Thank you for Juggluco.

---

## Part B — public issue

**Title:** Dexcom G7 J-PAKE: proof check never fails (wrong party id + inverted compare), and round 3 uses a fixed nonce

### Summary

Two problems in [`Common/src/main/cpp/dexcom/ecJPake.cpp`](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp), found while reading the code for an
interoperability project. Both are small to fix. I have no Dexcom sensor, so nothing here was tried on hardware.

### 1. The zero-knowledge-proof check never rejects a packet

- [Line 334](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L334): the sensor's party id is `b_bytes = {0x37,0x56,0x27,0x67,0x56,0x27}`, the nibble-swapped
  form of ASCII `"server"` (`73 65 72 76 65 72`).
- [Line 564](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L564): `validateZeroKnowledgeProof` returns `EC_POINT_cmp(group, res1, gv, bn_ctx)==1`, but
  `EC_POINT_cmp` returns **0** when the points are equal, 1 when they differ and -1 on error.
- Used by `validate12` ([line 568](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L568)) for the sensor's round-1 and round-2 packets, and by `validate3`
  ([line 581](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L581)), whose result decides whether `mkSharedKey` runs (`dexcom/java.cpp` line 453).

**What happens in practice:** the two mistakes cancel. With the wrong party id, the point computed from a genuine
sensor packet never equals `gv`, so `EC_POINT_cmp` returns 1 and the check reports "valid". That is why G7 pairing
works and why logs show `dexPutPubKey 0 valid` and `validate3()=1` (for example in #488). But the check accepts any
well-formed 160-byte packet, so no proof is really verified.

**Reproduce:** the two sensor packets embedded in the same file's `#ifdef TEST` main (`packby1`, `packby2`) verify
under `"server"` and not under `b_bytes`. A standard-library Python check using the same hash as `mkhash()`:

```
$ python3 verify_packby.py
b_bytes 37 56 27 67 56 27    packby1: False  packby2: False
"server"                     packby1: True   packby2: True
```

(Script attached: `verify_packby.py`, about 80 lines, no dependencies. It accepts a packet iff `G·r + X·c == V`.)

### 2. Round 3 always uses the same nonce

- [Line 489](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L489): `makeRound3Cert` sets the proof's random exponent `ran3` with
  `BN_hex2bn(&ran3.data,"fbc971b8…dc393")`, the same value in every pairing. Rounds 1 and 2 use `getrandom()`.

**Consequence:** a Schnorr proof is only zero-knowledge if its nonce is secret and fresh. Here the nonce is
published, so anyone who records the phone's round-3 packet during pairing can compute `x2·s` from it. Together
with the round-2 packet and the 4-digit code space, that is enough to compute the shared key that Juggluco saves
(`sharedKey`) and later uses to answer the sensor's authentication challenge (`dex8AES`). This needs someone in
Bluetooth range at the moment a sensor is paired.

### Suggested fix

Fix both at once: correcting only the party id or only the comparison makes every genuine packet fail and breaks
G7 pairing.

```diff
--- a/Common/src/main/cpp/dexcom/ecJPake.cpp
+++ b/Common/src/main/cpp/dexcom/ecJPake.cpp
@@ -331,7 +331,7 @@
    }
 
 constexpr const std::array<const uchar,6> a_bytes{0x63,0x6c,0x69,0x65,0x6e,0x74};
-constexpr const std::array<const uchar,6> b_bytes{0x37,0x56,0x27,0x67,0x56,0x27};
+constexpr const std::array<const uchar,6> b_bytes{0x73,0x65,0x72,0x76,0x65,0x72}; // "server"
 static  BIGNUM* mkhashBigInt(const EC_POINT *p1,const EC_POINT * pub_key,const EC_POINT * gv,  const BIGNUM*order,  const std::array<const uchar,6> bytes) {
    const auto hash=mkhash(p1,gv, pub_key,bytes);
    showhex("digest: ",hash);
@@ -449,6 +449,7 @@
    ERR_print_errors_cb(logcallback,(void*)format);
    }
 
+static BIGNUM *getrandom();
 bool makeRound3Cert(PCert &cert,const EC_POINT * pub1,const EC_POINT * pub2,const EC_POINT *  pubA,const BIGNUM * privB,const BIGNUM * pass) {
    ERR_clear_error();
    const auto ec_group=getgroup();
@@ -485,8 +486,7 @@
   printPoint("A=",A);
 
 
-   Number ran3;
-   BN_hex2bn(&ran3.data,"fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393");
+   Number ran3(getrandom());
    printNum("Exponent",ran3);
 
 
@@ -561,7 +561,7 @@
    EC_POINT_mul(group, res1, nullptr, g, r, bn_ctx);
    EC_POINT_mul(group, res2, nullptr, A, c, bn_ctx);
    EC_POINT_add(group, res1,res1,res2, bn_ctx);
-   return EC_POINT_cmp(group, res1, gv, bn_ctx)==1;
+   return EC_POINT_cmp(group, res1, gv, bn_ctx)==0;
     }
 bool validate12(const PCert &cert) {
    const auto g=EC_GROUP_get0_generator(getgroup());
```

`getrandom()` is the helper rounds 1 and 2 already use; it is defined further down, hence the forward declaration.
With this patch, the file's own `#ifdef TEST` main should print `pack1 is valid` / `pack2 is valid`. I could not
compile it here (no OpenSSL headers), but the same check passes in the Python script above and in an independent
Kotlin/BouncyCastle implementation.

### Not verified

- Nothing was run against a sensor.
- Round 3 after the fix: there is no recorded sensor round-3 packet to test `validate3` against, so please check
  pairing with a real G7 (and a ONE+ / Stelo) before releasing.
- Whether the sensor checks the phone's proofs, and so whether `a_bytes = "client"` is exactly what it expects.
- Whether the shared key alone is enough to get glucose from a sensor, or whether the sensor also needs the phone's
  Bluetooth bond.

### Also noticed (minor, related)

`tobytes()` ([line 227](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L227), used by `PCert::byteify()`) and `mkSharedKey()` ([line 544](https://github.com/j-kaltes/Juggluco/blob/75399d704e718d9210b73a494fdec469b708ba7b/Common/src/main/cpp/dexcom/ecJPake.cpp#L544)) use
`BN_bn2bin`, which drops leading zero bytes. A coordinate or x-value that starts with `0x00` (about 1 in 256 per
value) then shifts the 160-byte packet or the hashed key buffer. `BN_bn2binpad(…, 32)` would avoid it; its pointer
is declared in `cryptodecl.hpp`, but its `symtest` at line 69 is commented out, so it would need loading first.

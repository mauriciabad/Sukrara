# Recorded traces and test vectors

Fixtures for the drivers in `spikes/sensors-android`. **Every byte here comes from an open-source project's own
source or test data, never from a sensor of the user's** (investigation 23 touched no hardware).

| File | What | Origin |
|---|---|---|
| `g7-jpake-rounds.txt` | two 160-byte EC-J-PAKE round packets from a sensor; they verify under the party id "server" | Juggluco `Common/src/main/cpp/dexcom/ecJPake.cpp` `packby1` / `packby2` (GPL-3, `#ifdef TEST`) |
| `g7-session.txt` | a G7 session: EGV, backfill reply, backfill records, calibration bounds | DiaBLE `DiaBLE/DexcomG7.swift:243-383` comments (MIT) |
| `g6-session.txt` | G6 / ONE frames: version, backfill reply, calibration bounds | DiaBLE `DiaBLE/Dexcom.swift:333-434` comments (MIT) |
| `smartguide.txt` | Accu-Chek SmartGuide CGM Measurement / Status / control-point frames | Juggluco `Common/src/main/cpp/accu/java.cpp:130-182,:292` comments (GPL-3) |
| `aidex-x.txt` | Aidex X key exchange, encrypted commands and replies, and one advertisement | Juggluco `Common/src/main/cpp/aidexx/decrypt.cpp:215-240`, `crc.cpp` `TEST2`, `glucose.h` (GPL-3) |


Format: `# comment`, then `<label> <hex>` lines. `HarnessKt` replays a file through a driver; the tests in
`core/src/test` assert the decoded values.

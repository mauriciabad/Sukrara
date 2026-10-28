# Recorded traces and test vectors (investigation 23)

Copied from [`spikes/sensors-android`](../../../../../../sensors-android/core/src/main/resources/traces) for the app's
own driver tests (`app/src/test/kotlin/app/mauri/sukrara/direct/…`). **Every byte here comes from an open-source
project's own source or test data, never from a sensor of the user's**: no sensor of these families was ever touched.

| File | What | Origin |
|---|---|---|
| `g7-session.txt` | Dexcom G7 EGVs (a whole sensor lifecycle), backfill records | LoopKit G7SensorKit `G7GlucoseMessageTests.swift` (MIT), DiaBLE `DexcomG7.swift` comments (MIT) |
| `g6-session.txt` | Dexcom G6 / ONE frames: version, backfill reply, calibration bounds | DiaBLE `Dexcom.swift:333-434` comments (MIT) |
| `smartguide.txt` | Accu-Chek SmartGuide CGM Measurement / Status frames | Juggluco `Common/src/main/cpp/accu/java.cpp:130-182,:292` comments (GPL-3) |
| `aidex-x.txt` | Aidex X key exchange, encrypted commands and replies, one advertisement | Juggluco `Common/src/main/cpp/aidexx/decrypt.cpp:215-240`, `crc.cpp`, `glucose.h` (GPL-3) |

Format: `# comment`, then `<label> <hex>` lines (spaces inside the hex are ignored; a non-hex value is ASCII).

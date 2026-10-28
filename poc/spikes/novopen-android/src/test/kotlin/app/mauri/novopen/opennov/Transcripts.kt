// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Recorded NovoPen 20601 APDUs from xDrip's tests (insulin/opennov/MessageTest + ll/PHDllTest, GPL-3.0, JamOrHam).
package app.mauri.novopen.opennov

object Transcripts {
    /** Manager → pen: GET all MDS attributes. */
    const val A_INFO = "e7 00 00 0e 00 0c 00 00 01 03 00 06 00 00 00 00 00 00"

    /** Manager → pen: trigger segment 16's transfer (invoke id 2). */
    const val T_XFER = "e7 00 00 10 00 0e 00 02 01 07 00 08 01 00 0c 1c 00 02 00 10"

    /** Pen → manager: the transfer was accepted (segment 16, result 0). */
    const val T_XFER_OK = "e7 00 00 12 00 10 00 02 02 07 00 0a 01 00 0c 1c 00 04 00 10 00 00"

    /** Pen → manager: MDS attributes: serial EGDO2U, relative time 116243 s, model "Novo Nordisk A/S NovoPen". */
    const val D_INFO =
        "e7 00 00 d3 00 d1 00 00 02 03 00 cb 00 00 00 08 " +
            "00 c5 09 84 00 0a 00 08 00 14 65 00 45 07 d7 21 " +
            "09 8f 00 04 00 01 c6 13 0a 45 00 10 20 00 1f 00 " +
            "ff ff ff ff 00 00 1f 40 00 00 00 00 09 2d 00 4b " +
            "00 04 00 47 00 01 00 01 00 06 45 47 44 4f 32 55 " +
            "00 02 00 01 00 20 44 32 30 31 33 30 32 36 33 32 " +
            "30 30 30 30 30 20 44 32 30 31 33 30 32 36 33 32 " +
            "30 30 30 30 30 20 00 03 00 01 00 01 00 00 04 00 " +
            "01 00 08 30 31 2e 30 38 2e 30 30 0a 5a 00 08 00 " +
            "01 00 04 10 48 00 01 09 28 00 1c 00 10 4e 6f 76 " +
            "6f 20 4e 6f 72 64 69 73 6b 20 41 2f 53 00 08 4e " +
            "6f 76 6f 50 65 6e 00 0a 44 00 02 40 0a 0a 4b 00 " +
            "16 00 02 00 12 02 01 00 08 04 00 00 01 00 02 a0 " +
            "48 02 02 00 02 00 00 "

    /** Pen → manager: PM-store segment list: one segment (16) of the typical shape, usage 3. */
    const val S_INFO =
        "e7 00 00 7c 00 7a 00 01 02 07 00 74 01 00 0c 0d " +
            "00 6e 00 01 00 6a 00 10 00 06 00 64 09 22 00 02 " +
            "00 10 0a 4e 00 36 40 00 00 03 00 30 00 06 00 82 " +
            "34 01 00 02 00 01 00 04 0a 56 00 04 00 05 00 82 " +
            "34 02 00 03 00 01 00 04 0a 66 00 02 00 06 00 82 " +
            "f0 00 00 04 00 01 00 04 0a 66 00 02 09 53 00 02 " +
            "00 00 0a 58 00 0a 00 08 44 6f 73 65 20 4c 6f 67 " +
            "09 7b 00 04 00 00 00 03 0a 64 00 04 00 02 71 00 "

    /** Pen → manager: association request (IEEE 20601 APOEP, 8-byte system id). */
    const val A_REQUEST =
        "e2 00 00 32 80 00 00 00 00 01 00 2a 50 79 00 26 " +
            "80 00 00 00 80 00 80 00 00 00 00 00 00 00 00 80 " +
            "00 00 00 08 00 14 65 00 45 07 b9 51 40 0a 00 01 " +
            "01 00 00 00 00 00 "

    /** Manager → pen: association accepted. */
    const val A_RESPONSE =
        "e3 00 00 2c 00 03 50 79 00 26 80 00 00 00 80 00 " +
            "80 00 00 00 00 00 00 00 80 00 00 00 00 08 00 14 " +
            "65 00 45 07 b9 51 00 00 00 00 00 00 00 00 00 00 "

    /** Manager → pen: list all PM-store segments (invoke id 1). */
    const val CA_SEG = "e7 00 00 14 00 12 00 01 01 07 00 0c 01 00 0c 0d 00 06 00 01 00 02 00 00"

    /** Pen → manager: configuration report (insulin units, 1 segment, capacity 800). */
    const val C_REPORT =
        "e7 00 00 c4 00 c2 00 00 01 01 00 bc 00 00 00 00  " +
            "00 00 0d 1c 00 b2 40 0a 00 04 00 ac 00 3d 01 00  " +
            "00 08 00 38 0a 4d 00 02 08 00 09 43 00 02 00 00 " +
            "09 41 00 04 00 00 03 20 09 53 00 02 00 00 0a 57  " +
            "00 04 00 02 50 4d 09 51 00 02 00 01 0a 63 00 04  " +
            "00 00 00 00 09 44 00 04 00 00 00 02 00 06 00 02  " +
            "00 04 00 20 09 2f 00 04 00 82 34 01 0a 46 00 02  " +
            "f0 40 09 96 00 02 15 60 0a 55 00 08 00 01 00 04  " +
            "0a 56 00 04 00 05 00 03 00 03 00 1a 09 2f 00 04  " +
            "00 82 34 02 0a 46 00 02 f0 40 0a 55 00 08 00 01  " +
            "00 04 0a 66 00 02 00 06 00 04 00 03 00 1a 09 2f  " +
            "00 04 00 82 f0 00 0a 46 00 02 f0 40 0a 55 00 08  " +
            "00 01 00 04 0a 66 00 02  "

    /** Manager → pen: configuration accepted. */
    const val C_ACCEPT = "e7 00 00 16 00 14 00 00 02 01 00 0e 00 00 00 00 00 00 0d 1c 00 04 40 0a 00 00  "

    /**
     * Pen → manager: segment 16 data, index 0, 3 records, report rel time 116243: 3.0 u at rel 116177 (66 s ago),
     * 2.0 u at rel 331, and a non-dose record at rel 0 (units `00 7f ff ff`, flags `04 00 00 08`).
     */
    const val SEG_DATA_3 =
        "e7 00 00 44 00 42 80 01 01 01 00 3c 01 00 00 01 " +
            "c6 13 0d 21 00 32 00 10 00 00 00 00 00 00 00 03 " +
            "c0 00 00 24 00 01 c5 d1 ff 00 00 1e 08 00 00 00 " +
            "00 00 01 4b ff 00 00 14 08 00 00 00 00 00 00 00 " +
            "00 7f ff ff 04 00 00 08 "

    /** Pen → manager: segment 16 data, index 0, 18 records (1.0 u and 3.0 u doses), report rel time 0x00090452. */
    const val SEG_DATA_18 =
        "E7 00 00 F8 00 F6 80 01 01 01 00 F0 01 00 00 09 " +
            "04 52 0D 21 00 E6 00 10 00 00 00 00 00 00 00 12 " +
            "80 00 00 D8 00 08 88 F2 FF 00 00 0A 08 00 00 00 " +
            "00 08 83 E3 FF 00 00 0A 08 00 00 00 00 08 83 E1 " +
            "FF 00 00 0A 08 00 00 00 00 08 83 DE FF 00 00 0A " +
            "08 00 00 00 00 08 83 DC FF 00 00 0A 08 00 00 00 " +
            "00 08 83 DB FF 00 00 0A 08 00 00 00 00 08 78 15 " +
            "FF 00 00 0A 08 00 00 00 00 08 78 13 FF 00 00 0A " +
            "08 00 00 00 00 08 78 10 FF 00 00 0A 08 00 00 00 " +
            "00 08 78 0E FF 00 00 0A 08 00 00 00 00 08 78 0C " +
            "FF 00 00 0A 08 00 00 00 00 08 74 D5 FF 00 00 0A " +
            "08 00 00 00 00 08 74 D3 FF 00 00 0A 08 00 00 00 " +
            "00 08 74 D0 FF 00 00 0A 08 00 00 00 00 08 74 CE " +
            "FF 00 00 0A 08 00 00 00 00 08 73 14 FF 00 00 1E " +
            "08 00 00 00 00 08 73 10 FF 00 00 1E 08 00 00 00 " +
            "00 08 73 0D FF 00 00 1E 08 00 00 00 "

    /** The NDEF PHD record (seq 14) that carried [SEG_DATA_18]. */
    const val SEG_DATA_18_RECORD =
        "D1 03 FD 50 48 44 8E E7 00 00 F8 00 F6 80 01 01 " +
            "01 00 F0 01 00 00 09 04 52 0D 21 00 E6 00 10 00 " +
            "00 00 00 00 00 00 12 80 00 00 D8 00 08 88 F2 FF" +
            "00 00 0A 08 00 00 00 00 08 83 E3 FF 00 00 0A 08 " +
            "00 00 00 00 08 83 E1 FF 00 00 0A 08 00 00 00 00 " +
            "08 83 DE FF 00 00 0A 08 00 00 00 00 08 83 DC FF " +
            "00 00 0A 08 00 00 00 00 08 83 DB FF 00 00 0A 08 " +
            "00 00 00 00 08 78 15 FF 00 00 0A 08 00 00 00 00 " +
            "08 78 13 FF 00 00 0A 08 00 00 00 00 08 78 10 FF " +
            "00 00 0A 08 00 00 00 00 08 78 0E FF 00 00 0A 08 " +
            "00 00 00 00 08 78 0C FF 00 00 0A 08 00 00 00 00 " +
            "08 74 D5 FF 00 00 0A 08 00 00 00 00 08 74 D3 FF " +
            "00 00 0A 08 00 00 00 00 08 74 D0 FF 00 00 0A 08 " +
            "00 00 00 00 08 74 CE FF 00 00 0A 08 00 00 00 00 " +
            "08 73 14 FF 00 00 1E 08 00 00 00 00 08 73 10 FF " +
            "00 00 1E 08 00 00 00 00 08 73 0D FF 00 00 1E 08 " +
            "00 00 00 "

    /** Pen → manager: release request / release response (standard 20601, not from xDrip's recordings). */
    const val RLRQ = "e4 00 00 02 00 00"
    const val RLRE = "e5 00 00 02 00 00"
}

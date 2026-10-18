// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Driver for gen.sh: prints the vectors Libre2Test checks, using DiaBLE's own functions.
import Foundation
extension Data { var hex: String { map { String(format: "%02x", $0) }.joined() } }
func bytes(_ s: String) -> Data { var d = Data(); var i = s.startIndex; while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }; return d }
let uid = bytes("2fe7b10000a407e0")
let info = bytes("9d083001712b")
let infoPlus = bytes("c60931013f52")
print("serial", serialNumber(uid: uid, family: .libre2))
print("useful_activate", Data(Libre2.usefulFunction(id: uid, x: 0x1b, y: 0x1b6a)).hex)
// enable streaming, as DiaBLE Libre2.swift:190-198 + NFC.swift:112-122
let code: UInt32 = 527_000_123
let p = [UInt8(code & 0xFF), UInt8((code >> 8) & 0xFF), UInt8((code >> 16) & 0xFF), UInt8((code >> 24) & 0xFF)]
let secret = UInt16(infoPlus[4...5]) ^ UInt16(p[1], p[0])
print("enable_params", (Data([0x1e]) + Data(p) + Libre2.usefulFunction(id: uid, x: 0x1e, y: secret)).hex)
print("unlock", Libre2.streamingUnlockPayload(id: uid, info: infoPlus, enableTime: code, unlockCount: 7).hex)
print("crc", String(format: "%04x", crc16(bytes("0123456789abcdef55aa"))))
// FRAM: deterministic input
let fin = Data((0..<344).map { UInt8(truncatingIfNeeded: $0 * 7 + 3) })
print("fram", try! Libre2.decryptFRAM(type: .libre2, id: uid, info: infoPlus, data: fin).hex)
// BLE: keystream from a zero body, then a plaintext with a valid CRC
let header = Data([0x5a, 0xc3])
var plain = Data((0..<42).map { UInt8(truncatingIfNeeded: $0 * 13 + 1) })
plain[40] = 0x9a; plain[41] = 0x05   // age 1434 min
let c = crc16(plain); plain.append(UInt8(c & 0xFF)); plain.append(UInt8(c >> 8))
// keystream: replicate decryptBLE without the CRC guard
let d = Libre2.usefulFunction(id: uid, x: 0x1b, y: 0x1b6a)
let x = UInt16(d[1], d[0]) ^ UInt16(d[3], d[2]) | 0x63
let y = UInt16(header[1], header[0]) ^ 0x63
var key = [UInt8](); var k = Libre2.processCrypto(input: Libre2.prepareVariables(id: uid, x: x, y: y))
for _ in 0..<8 { for w in k { key.append(UInt8(truncatingIfNeeded: w)); key.append(UInt8(truncatingIfNeeded: w >> 8)) }; k = Libre2.processCrypto(input: k) }
let cipher = header + Data(plain.enumerated().map { $0.1 ^ key[$0.0] })
let back = try! Libre2.decryptBLE(id: uid, data: cipher)
precondition(back == plain)
print("ble_cipher", cipher.hex)
print("ble_plain", plain.hex)
for i in 0..<10 {
  let raw = readBits(plain, i*4, 0, 0xe); let t = readBits(plain, i*4, 0xe, 0xc) << 2
  var a = readBits(plain, i*4, 0x1a, 0x5) << 2; if readBits(plain, i*4, 0x1f, 1) != 0 { a = -a }
  print("ble_val", i, raw, t, a)
}
let cal = CalibrationInfo(i1: 2, i2: 300, i3: 0, i4: 12000, i5: 0, i6: 6900)
for (raw, t, a) in [(1500, 7000, 0), (2500, 7100, -40), (900, 6800, 12), (3200, 7300, 0)] {
  let g = factoryGlucose(rawGlucose: Glucose(rawValue: raw, rawTemperature: t, temperatureAdjustment: a, id: 100), calibrationInfo: cal)
  print("glucose", raw, t, a, g.value, String(format: "%.3f", g.temperature))
}

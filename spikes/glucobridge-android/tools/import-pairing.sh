#!/bin/sh
# Spike helper: copy a sensor's pairing data from Juggluco into GlucoBridge, so the direct
# flavor can resume the sensor's BLE session without NFC.
#
# Needs: USB debugging, root on the phone (Juggluco's data dir is private), GlucoBridge installed
# and launched once. The real app will get this from its own NFC activation instead.
#
# The data (BLE PIN + saved authorization record) is a per-sensor secret: it is piped straight
# into the app's private directory, never written to disk on the Mac and never printed.
set -e
PKG=dev.spike.glucobridge
JUG=/data/data/tk.glucodata/files/sensors

DIR=$(adb shell su -c "ls $JUG" 2>/dev/null | tr -d '\r' | grep -E '^E07A-' | head -1)
[ -n "$DIR" ] || { echo "No Libre 3 sensor directory under $JUG (is Juggluco paired, is root granted?)" >&2; exit 1; }
SERIAL=${DIR#E07A-}
echo "Found sensor $SERIAL"

adb exec-out su -c "cat $JUG/$DIR/info.dat" |
python3 -I -c '
import json, re, struct, sys
b = sys.stdin.buffer.read()
if len(b) < 300: sys.exit("info.dat too short (%d bytes)" % len(b))
start = struct.unpack_from("<I", b, 0)[0]
pin = b[28:32]
security = struct.unpack_from("<H", b, 40)[0]
m = re.search(rb"([0-9A-F]{2}:){5}[0-9A-F]{2}", b)
if not m: sys.exit("no BLE address in info.dat")
k = b.find(b"L3S9")
if k < 0 or k + 149 > len(b): sys.exit("no saved authorization (L3S9) in info.dat")
json.dump({
    "serial": sys.argv[1] if len(sys.argv) > 1 else "",
    "address": m.group().decode(),
    "startEpoch": start,
    "securityVersion": security,
    "pinHex": pin.hex(),
    "kAuthHex": b[k:k+149].hex(),
}, sys.stdout)
' "$SERIAL" |
adb shell "run-as $PKG sh -c 'cat > files/sensor.json && chmod 600 files/sensor.json'"

adb shell "run-as $PKG sh -c 'ls -l files/sensor.json'" | sed 's/^/  /'
echo "Imported pairing data for $SERIAL into $PKG"

# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# One snapshot, run as root by PowerLab:  sh snap.sh <outdir> <label>
# The power rails are cumulative energy counters, so a 3 s Perfetto trace at the start and at the end of a
# window gives the window's energy per rail with no sampling during the window itself.
out=$1; label=$2
cfg=/data/local/tmp/powerlab.cfg
cat > $cfg <<'CFG'
buffers { size_kb: 4096 }
data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 1000 collect_power_rails: true
  collect_entity_state_residency: true battery_counters: BATTERY_COUNTER_CHARGE battery_counters: BATTERY_COUNTER_VOLTAGE } } }
duration_ms: 3000
CFG
cat $cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/powerlab.pftrace >/dev/null 2>&1  # SELinux: perfetto can't open files in /data/local/tmp
cp /data/misc/perfetto-traces/powerlab.pftrace "$out/$label.pftrace"
{
  echo "time $(date +%s)"
  echo "uptime $(cut -d' ' -f1 /proc/uptime)"
  echo "charge_uah $(cat /sys/class/power_supply/battery/charge_counter)"
  echo "capacity $(cat /sys/class/power_supply/battery/capacity)"
  echo "status $(cat /sys/class/power_supply/battery/status)"
  echo "wifi $(settings get global wifi_on)"
  echo "deep $(dumpsys deviceidle get deep)"
  echo "suspends $(cat /sys/power/suspend_stats/success 2>/dev/null)"
  # CPU time (clock ticks, utime+stime) of every process whose command line starts with the pattern.
  for name in app.mauri.sukrara.poc tk.glucodata dev.spike.glucobridge dev.spike.cgmnostr dev.spike.glucostatus de.michelinside.glucodatahandler \
              com.android.systemui system_server com.google.android.bluetooth com.android.bluetooth dev.spike.powerlab; do
    t=0
    for pid in $(pgrep -f "^$name"); do
      v=$(sed 's/^.*) //' /proc/$pid/stat 2>/dev/null | awk '{print $12 + $13}')
      t=$((t + ${v:-0}))
    done
    echo "cpu $name $t"
  done
} > "$out/$label.txt"

#!/system/bin/sh
# Astrion boot hook: start the input bridge and enable adb-over-TCP.
#
# Launched by /system/etc/init/astrion-boot.rc on sys.boot_completed, as root
# via init. Lives out-of-tree in /data so /system carries only the tiny .rc.
#
# Why this exists: this build has NO init trigger consuming persist.adb.tcp.port,
# so adbd starts USB-only, and the input bridge needs a root/privileged start
# that nothing performs at boot. Both are solved here.
#
# Order matters ONLY for manual testing over adb: the bridge is started first,
# because the adbd restart below tears down an adb-shell test session. Under
# init (the real path) the order is immaterial -- init owns this process and the
# adbd restart cannot touch it.
LOG=/data/local/tmp/astrion-boot.log
echo "=== astrion-boot run $(date) uid=$(id -u) ===" >> "$LOG"

# --- input bridge: single clean instance owned by root -----------------
pkill -f 'com.custom.astrion.bridge.InputBridge' 2>/dev/null
sleep 1
APK=$(pm path com.custom.astrion 2>/dev/null | sed 's/package://' | head -1)
if [ -n "$APK" ]; then
  CLASSPATH="$APK" setsid app_process /system/bin com.custom.astrion.bridge.InputBridge \
    --stop=com.aiks.HaRemote >> "$LOG" 2>&1 &
  echo "bridge started, apk=$APK pid=$!" >> "$LOG"
else
  echo "bridge: could not resolve apk path" >> "$LOG"
fi

# --- adb over TCP: set the port adbd reads on start, then restart it ----
# LAST, because ctl.restart adbd drops any adb-shell session running this.
setprop service.adb.tcp.port 5555
setprop ctl.restart adbd
echo "adb tcp: service.adb.tcp.port=$(getprop service.adb.tcp.port)" >> "$LOG"

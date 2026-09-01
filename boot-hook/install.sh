#!/usr/bin/env bash
# Install the Astrion boot hook on an HA100 over adb.
#
# Enables adb-over-TCP and starts the input bridge automatically at every boot,
# so the unit never again needs a USB `adb tcpip 5555` or a manual bridge start.
# See README.md for what this does and why it is safe.
#
# Usage:
#   ./install.sh <ip>            # install, then prompt before rebooting
#   ./install.sh <ip> --reboot   # install and reboot without prompting
#   ./install.sh <ip> --no-reboot# install only; changes take effect next boot
#
# Requires: adb on PATH, and the device already reachable over adb (USB, or an
# existing `adb tcpip 5555` session). Run from this directory so the two payload
# files sit alongside.
set -euo pipefail

IP="${1:-}"
MODE="${2:-ask}"
PKG="com.custom.astrion"
DIR="$(cd "$(dirname "$0")" && pwd)"
SH="$DIR/astrion-boot.sh"
RC="$DIR/astrion-boot.rc"

die() { echo "error: $*" >&2; exit 1; }

[ -n "$IP" ] || die "usage: $0 <ip> [--reboot|--no-reboot]"
[ -f "$SH" ] && [ -f "$RC" ] || die "astrion-boot.sh / astrion-boot.rc not found next to this script"
command -v adb >/dev/null || die "adb not found on PATH"

TARGET="$IP:5555"
adb connect "$TARGET" >/dev/null 2>&1 || true
adb -s "$TARGET" shell true >/dev/null 2>&1 || die "cannot reach $TARGET over adb (need USB or an existing adb tcpip session)"

# Refuse a device that is not actually an Astrion running the custom app -- this
# modifies /system, so hitting the wrong device would be bad.
adb -s "$TARGET" shell "pm path $PKG" 2>/dev/null | grep -q base.apk \
  || die "$PKG not installed on $TARGET -- refusing to modify /system on an unexpected device"

# Root + writable /system are prerequisites; both hold on the HA100 userdebug
# build, but check rather than assume.
[ "$(adb -s "$TARGET" shell id -u | tr -d '\r')" = "0" ] \
  || die "adb is not root on $TARGET (expected on this userdebug build)"

echo ">> staging payload in /data/local/tmp"
adb -s "$TARGET" push "$SH" /data/local/tmp/astrion-boot.sh
adb -s "$TARGET" push "$RC" /data/local/tmp/astrion-boot.rc
adb -s "$TARGET" shell chmod 755 /data/local/tmp/astrion-boot.sh

echo ">> installing the init service into /system/etc/init (rw window, then ro)"
adb -s "$TARGET" shell '
  set -e
  mount -o rw,remount /system
  cp /data/local/tmp/astrion-boot.rc /system/etc/init/astrion-boot.rc
  chmod 644 /system/etc/init/astrion-boot.rc
  chown root:root /system/etc/init/astrion-boot.rc
  chcon u:object_r:system_file:s0 /system/etc/init/astrion-boot.rc 2>/dev/null || true
  mount -o ro,remount /system
'

echo ">> verifying"
adb -s "$TARGET" shell 'ls -la /system/etc/init/astrion-boot.rc; head -1 /system/etc/init/astrion-boot.rc' | tr -d '\r'
echo "   installed. Takes effect on next boot."

do_reboot() {
  echo ">> rebooting $TARGET to activate + verify"
  adb -s "$TARGET" reboot
  echo "   waiting for the hook to bring TCP back (no USB needed)..."
  sleep 55
  for i in $(seq 1 20); do
    adb disconnect "$TARGET" >/dev/null 2>&1 || true
    adb connect "$TARGET" >/dev/null 2>&1 || true
    if [ "$(adb -s "$TARGET" shell echo up 2>/dev/null | tr -d '\r')" = "up" ]; then
      echo ">> back up. state:"
      adb -s "$TARGET" shell 'echo "  adb tcp port: $(getprop service.adb.tcp.port)"; echo "  bridge listening: $(netstat -an 2>/dev/null | grep -c 8098.*LISTEN)"; echo "  boot log:"; sed "s/^/    /" /data/local/tmp/astrion-boot.log' | tr -d '\r'
      return 0
    fi
    sleep 8
  done
  echo "   !! TCP did not return within ~3 min. If the unit is bootlooping, power-cycle it;" >&2
  echo "      a bad NEW .rc is skipped by init and is not fatal, so USB adb + rm the .rc recovers." >&2
  return 1
}

case "$MODE" in
  --reboot)    do_reboot ;;
  --no-reboot) echo ">> skipping reboot; the hook activates on the next boot." ;;
  *)
    printf ">> reboot %s now to activate and verify? [y/N] " "$TARGET"
    read -r ans
    case "$ans" in [yY]*) do_reboot ;; *) echo "   left for next boot." ;; esac
    ;;
esac

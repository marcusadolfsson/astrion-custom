# Astrion boot hook — adb-over-TCP and the input bridge at boot

Makes an Astrion HA100 come back with **adb-over-TCP enabled and the input
bridge running** after a reboot, with no USB `adb tcpip 5555` and no manual
bridge start. Proven on the HA100 across a real reboot.

This overturns the older "network adb does NOT survive a reboot — settled" note
in `.claude/skills/astrion-remote/reference/`. That note was right that the
persist props are inert, but wrong that nothing could be done. See below.

## Why the persist props are inert (the real reason)

`persist.adb.tcp.port=5555` is set and ignored. The cause is
not that it fails to persist — it persists fine — it is that **this build has no
init trigger that consumes it**. `grep`-ing every `.rc` under `/init*.rc`,
`/system/etc/init/`, `/vendor/etc/init/` finds no `on property:service.adb.tcp.port=*`
and no `persist.adb.tcp.port` reader. So adbd starts USB-only and nothing ever
restarts it on the TCP port.

## Why a boot hook is possible at all

The build is wide open: `ro.build.type=userdebug`, SELinux **Permissive**,
`ro.secure=0`, adbd runs as **root**, `su` present. So a root process at boot
can do everything needed. The only question was how to get root to run at boot,
since:

- The app is an ordinary uid (10062) and **cannot self-elevate** — the AOSP
  `su` here gates on the caller's uid (shell/root only), which permissive
  SELinux does not override. `/data` is `nosuid`, so a setuid helper there is
  out too.
- Nothing else runs root at boot without Magisk.

The answer: **add one file to `/system/etc/init/`** (init auto-parses all 58
`.rc` files there) declaring a `oneshot` service that runs a script as root in
the `su` domain on `sys.boot_completed`.

## The two files

- **`astrion-boot.rc`** → installed at `/system/etc/init/astrion-boot.rc`
  (root:root, 0644, `u:object_r:system_file:s0`). Declares the service and the
  `on property:sys.boot_completed=1` trigger. `seclabel u:r:su:s0` is what gives
  the script the permissive root context adbd-root uses, so `app_process` can
  open `/dev/input` for the bridge.

- **`astrion-boot.sh`** → installed at `/data/local/tmp/astrion-boot.sh`
  (root, 0755). Starts the bridge (resolving the APK path at runtime via
  `pm path`, so it survives app updates — unlike the app's own
  `start-bridge.sh`, which hardcodes the versioned APK hash), then sets
  `service.adb.tcp.port 5555` and `ctl.restart adbd`.

**Comments in the `.rc` MUST use `#`, not `//`.** init parses `//` lines as
commands and errors. (Cost one reinstall here.)

**The bridge is started before the adb-tcp restart, on purpose.** Only matters
for manual testing: `ctl.restart adbd` tears down an adb-shell test session, so
the bridge must start first to be observable. Under init the order is
immaterial — init owns the process and the adbd restart cannot touch it. This is
also why the payload can only be truly tested by a reboot, not through an adb
shell: an adb-launched run dies at the `ctl.restart` line.

## Install

From this directory, with the device reachable over adb (USB, or an existing
`adb tcpip 5555` session):

```sh
./install.sh <remote-ip>          # install, prompt before rebooting
./install.sh <remote-ip> --reboot # install and reboot + verify unattended
```

`install.sh` stages the two files, adds the `.rc` to `/system/etc/init/` (brief
rw window, back to ro), and refuses to touch `/system` unless the target is
actually an Astrion running the custom app and adb is root. With `--reboot` it
waits for the hook to bring TCP back and prints the resulting state.

Manual equivalent, if you would rather run the steps yourself:

```sh
IP=<remote-ip>
adb -s $IP:5555 push astrion-boot.sh /data/local/tmp/astrion-boot.sh
adb -s $IP:5555 shell chmod 755 /data/local/tmp/astrion-boot.sh
adb -s $IP:5555 push astrion-boot.rc /data/local/tmp/astrion-boot.rc
adb -s $IP:5555 shell '
  mount -o rw,remount /system &&
  cp /data/local/tmp/astrion-boot.rc /system/etc/init/astrion-boot.rc &&
  chmod 644 /system/etc/init/astrion-boot.rc &&
  chown root:root /system/etc/init/astrion-boot.rc &&
  chcon u:object_r:system_file:s0 /system/etc/init/astrion-boot.rc &&
  mount -o ro,remount /system'
adb -s $IP:5555 reboot
# wait ~60s, then: adb connect $IP:5555 should answer with no USB.
```

## Verify

```sh
adb -s $IP:5555 shell 'getprop service.adb.tcp.port; \
  netstat -an | grep -c "8098.*LISTEN"; cat /data/local/tmp/astrion-boot.log'
```
`init.svc.astrion_boot` reads `stopped` after a good run — that is a completed
`oneshot`, not a failure.

## Risk and recovery

Adding a NEW `.rc` touches no existing service, so a parse error is skipped by
init, never fatal — this is NOT in the `pm disable-user` brick class. Recovery if
the hook ever misbehaves: USB adb (always available — `persist.sys.usb.config=adb`),
`mount -o rw,remount /system`, `rm /system/etc/init/astrion-boot.rc`.

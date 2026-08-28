# Hardware notes

Faults and quirks found in HA100 units themselves, as opposed to anything this
app does. Recorded here because each one cost real debugging time and none is
visible from the UI.

---

## Accelerometer: constant Z-axis bias on one unit

One of our three HA100s reports gravity at nearly **double** its true magnitude
while sitting perfectly still. An identical unit — same variant, same firmware,
consecutive serial numbers, bought together — reads correctly.

Raw driver counts from `/sys/bus/platform/drivers/gsensor/axis_data`, where
1024 counts = 1 g, both remotes resting untouched:

| Serial | Variant | raw x | raw y | raw z | \|raw\| | |
|---|---|---|---|---|---|---|
| 0127B260701T0177 | HA100B | −47 | −756 | **686** | 1021.9 | **1.00 g** ✅ |
| 0127B260701T0121 | HA100B | −50 | −772 | **1786** | 1946.4 | **1.90 g** ❌ |

Both on `Ruby/Ruby/Ruby:8.1.0/O11019/1536230651:user/release-keys`. The sensor
HAL then reports |a| = 9.75 and 18.61 m/s² respectively.

**X and Y agree between the units; the entire error is Z — +1100 counts, or
+1.07 g.** That is a constant per-axis bias, not a range/scale error: a scale
error would inflate all three axes. `primary_offset` from the same driver is
nearly identical on both (z = 756 vs 790), so the offset the driver applies is
not the difference. `/data/misc/sensor/` is **empty on all three of our units**,
so no calibration has ever been stored on any of them.

Ruled out: firmware (identical fingerprint), variant (both HA100B; our older
HA100A also reads 1.00 g), orientation (vector magnitude is
orientation-independent — a device at rest reads 1 g at any angle), and motion
(stable across minutes, max sample-to-sample Δ|a| ≈ 0.27 m/s² on both — the
sensor is stable, it is just stably wrong).

### Check your own unit

    adb shell cat /sys/bus/platform/drivers/gsensor/axis_data
    adb shell dumpsys sensorservice | grep -A3 "ACCELEROMETER: last"

A resting remote must read ~1024 counts / ~9.81 m/s² of total magnitude at any
orientation. Anything far from that is biased.

### Why it matters

Any logic with a threshold in m/s² behaves differently on an affected unit, and
does so silently. It cost us two rounds of motion-wake tuning that could never
have converged, because the same config number meant a different amount of real
movement on two supposedly identical remotes.

Worse, the obvious fix does not work: **normalising the vector cancels a scale
error but not a bias.** A bias drags the computed gravity direction toward its
own axis and compresses every angle measured from it, so a normalised tilt
detector is *also* wrong on that unit — quietly, and in the same direction as the
bug it was meant to fix.

### How this app handles it

Motion wake measures the **difference** between the current gravity estimate and
the resting one, converted to an angle with `2·asin(chord / 2g)` against the
physical constant rather than against the sensor's own reported magnitude. A
constant bias is present in both terms and cancels. See
[FORK_ADDITIONS.md → Motion wake](FORK_ADDITIONS.md#motion-wake-per-remote).

The app also logs a warning once at startup when resting |a| is more than
2 m/s² away from 1 g:

    W AstrionKeys: accelerometer rests at 18.85 m/s^2, expected ~9.81 (device=…)
      -- biased sensor, tilt uses differences so this is compensated

A device that lies about gravity should not be able to do it silently.

### Status

Open with the vendor. Unresolved questions: whether gsensor calibration is
performed in production and can be re-run in the field (`/data/misc/sensor/`
being empty on every unit suggests it may not be); whether `primary_offset` is
the intended calibration mechanism and is field-writable; and whether the stock
app compensates in software, which would explain how a unit ships this way.

---

## Accelerometer: intermittent implausible samples on one unit

A different fault from the Z-axis bias above, on a different remote, and it does
not show up at rest — the sensor reads a clean 9.82 m/s² when you go looking.

Instead it **intermittently returns z ≈ 1.45 m/s² instead of ≈ 9.81**, about
0.15 g. The bad reading is **always exactly 1.46**, never a scatter, which is
what marks it as a driver artefact rather than noise.

Interleaved with good samples it drags the low-passed gravity estimate down in
steps, and the motion detector fires on a device nobody is touching:

```
mag=9.83  prevMag=1.46  ->  jerk = |9.83-1.46|/9.81 = 0.85     (threshold 0.25)
mag=1.46  prevMag=1.46  ->  tilt = 18.4°                        (threshold 10°)
ref=[0.19 -0.09 9.82]   <- resting reference CORRECT, not stale
g z:  9.16 -> 8.26 -> 6.92 -> 6.77 -> 9.33 -> 6.69 -> 9.09
```

The visible symptom was the screen lighting roughly every 30 seconds on a remote
lying flat — `screen_off_timeout` plus `settle_seconds`, re-firing the moment the
settle window reopened.

**Checking your own unit.** The fix logs the first discard, so the cheapest test
is to look for it:

```
adb shell "logcat -d -s AstrionKeys" | grep implausible
W AstrionKeys: discarding implausible accelerometer samples
               (first was 1.46 m/s^2, expected ~9.81) (device=master_1)
```

Two traps if you go hunting yourself. **`dumpsys sensorservice` will not show
it** — it keeps only the last 50 events, about 3 seconds, and you will sample a
clean stretch. And **adb suppresses the symptom entirely**: with a session
attached the device never suspends and the bursts stop, so `suspend_stats`
`success` sits still and nothing fires. Detach, wait, then reconnect and read the
log buffer, which holds hours.

Two plausible explanations were wrong before the sample values were logged: the
resting reference was never stale, and sample delivery never stopped (`gap=0ms`
throughout). Neither is visible from `tilt` and `jerk` alone.

## Dock: with the USB-C cover removed, the pins only meet when seated flush

Getting at the USB-C port means undoing two screws and lifting the plastic strip
off the bottom edge (see the README's install steps). Leave that strip off — as
you will, if you ever want a cable again — and the remote no longer seats the
same way in its cradle. The pogo pins then make **partial contact**: enough for
the charger to be detected, not enough to deliver current.

The failure is silent and looks like a software problem:

    dumpsys battery
      AC powered: true        <- a charger IS detected
      status: 3               <- BATTERY_STATUS_DISCHARGING
      level: 61               <- and falling

A remote here went from 93% to flat overnight in this state — about 12%/hour —
reporting `AC powered: true` the entire way down, because an app was holding its
screen on and "plugged in" was taken to mean "charging".

**Two things follow, and both are worth copying.**

`EXTRA_PLUGGED` answers "is a cable attached". `EXTRA_STATUS` answers "is the
battery gaining". They are not the same question, and on a dock like this they
give opposite answers. Anything that decides to spend power — holding the screen
on, running a bright screensaver — must gate on the second:

    val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val gaining = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                  status == BatteryManager.BATTERY_STATUS_FULL

And re-check it periodically. No broadcast fires when a charger merely stops
keeping up — `ACTION_POWER_CONNECTED` already fired, correctly, when the pins
touched — so a poll is the only way to notice the state you are actually in.

If a docked remote is losing charge, seat it fully home before suspecting the
cradle, the cable or the PSU. On a plain USB-C cable the same remote charges
normally, which is a quick way to tell the two apart.

---

## The dock and a USB-C charger are indistinguishable to software

Worth knowing before designing anything around "is it parked in its cradle":
you cannot tell. Measured on two remotes, one seated in its dock and one on a
USB-C charger, both charging:

    dumpsys battery      ->  AC powered: true      (both)
    /sys/class/power_supply/ac/online   -> 1       (both)
    /sys/class/power_supply/usb/online  -> 0       (both)

`BATTERY_PLUGGED_AC` reflects the CHARGER type, not the connector: the cradle's
pogo pins feed the same charging path as the port, and any dumb charger
enumerates as Mains. A remote plugged into a data-capable host does report USB,
but that distinguishes a computer from a charger — not a dock from a cable.

The practical consequence is that "suppress this behaviour only while docked"
is not implementable here. Where the intent is to avoid acting on a parked
remote, gate on something you can actually observe instead — whether the screen
is already on, or whether the device has been still.

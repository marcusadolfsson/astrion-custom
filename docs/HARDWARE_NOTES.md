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

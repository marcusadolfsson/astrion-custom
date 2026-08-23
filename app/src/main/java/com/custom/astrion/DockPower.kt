package com.custom.astrion

import android.content.Intent
import android.os.BatteryManager

/**
 * The one definition of "mains is carrying this device".
 *
 * It lived in three copies -- dock display, the status bar and the battery
 * reporter -- and that is precisely how the Pixel Tablet ended up docked, awake
 * and reporting itself as running on battery. One copy was fixed and the other
 * two were not, because nothing connected them.
 *
 * THE RULE, and why it is not simply EXTRA_PLUGGED:
 *
 *  - Plugged is not enough. A dock that supplies less than a permanently-lit
 *    screen draws still reports plugged, and holding the display on against it
 *    took a remote from 93% to flat overnight while it reported "AC powered:
 *    true" the whole way down. That device reports DISCHARGING, which is false
 *    here.
 *
 *  - CHARGING/FULL is not enough either. A Pixel Tablet on its own dock reports
 *    NOT_CHARGING at 90%, because it deliberately HOLDS the level for battery
 *    health rather than topping up. It is plugged, mains-powered and in no
 *    danger, and reading that as "on battery" cost the tablet both its idle
 *    clock and its backlight.
 *
 * So: gaining, or holding steady with a charger attached. Losing ground is not
 * carried, whatever the cable says.
 */
object DockPower {

    /** True when mains is carrying the device, from an ACTION_BATTERY_CHANGED intent. */
    fun isCarried(intent: Intent?): Boolean {
        if (intent == null) return false
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            // DISCHARGING, UNKNOWN, or no reading at all.
            else -> false
        }
    }

    /** Battery percentage, or -1 when it cannot be read. */
    fun percent(intent: Intent?): Int {
        if (intent == null) return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return if (level < 0) -1 else level * 100 / scale
    }
}

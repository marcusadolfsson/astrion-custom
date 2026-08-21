package com.custom.astrion.input

import android.app.admin.DeviceAdminReceiver

/**
 * Empty device-admin receiver. Being an ACTIVE admin holding the force-lock
 * policy is the only sanctioned way an app can turn the screen off, via
 * DevicePolicyManager.lockNow().
 *
 * WHY WE NEED THAT. Two reasons, both specific to this remote:
 *
 *  - The physical Off button reports keycode 132, a generic code rather than a
 *    real KEYCODE_POWER, so Android will not sleep the device on it.
 *  - More usefully: with the input bridge running, a keypress made while the
 *    screen is off does its job AND wakes the display, because Android handles
 *    its own copy of that event independently of our read. In a dark room that
 *    means adjusting the volume lights a 3" screen in your lap for the whole
 *    screen timeout. lockNow() lets us put it straight back down, so the press
 *    does its work invisibly.
 *
 * Only force-lock is declared (see res/xml/device_admin.xml) -- no wipe, no
 * password policy. Granted once and it persists across reboots, unlike the
 * bridge itself:
 *
 *   adb shell dpm set-active-admin com.custom.astrion/.input.SleepAdminReceiver
 *
 * To revoke: adb shell dpm remove-active-admin com.custom.astrion/.input.SleepAdminReceiver
 */
class SleepAdminReceiver : DeviceAdminReceiver()

package com.custom.astrion.ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes the remote's own battery level to Home Assistant, so it can be
 * charted and alerted on like any other sensor.
 *
 * WHY THIS EXISTS. The battery is the one thing about this device that is
 * invisible from Home Assistant: it is not a HA-managed device, it has no
 * integration, and reading it over adb only ever produces spot values taken
 * while someone happened to be looking. A discharge CURVE is what answers
 * questions like "did stopping the OEM launcher actually help", and that needs
 * a recorded series, not a reading.
 *
 * WHY A SERVICE CALL AND NOT `POST /api/states`. Publishing a state that way
 * needs an ADMIN token (a non-admin gets 401), and each remote now authenticates
 * as its own scoped user rather than sharing the owner's credentials. Writing an
 * `input_number` instead is a plain service call, which any user may make.
 *
 * It also fixes the flaw the REST version documented and lived with: states
 * created over REST vanish on every Home Assistant restart, so the curve was
 * full of holes. An input_number is a real, restored entity.
 *
 * The helper is per REMOTE (`entityId` comes from the runtime device name), so
 * three remotes no longer overwrite one shared sensor -- which is what they did
 * the moment the app was installed on more than one.
 */
class BatteryReporter(
    private val context: Context,
    private val client: HaClient,
    private val scope: CoroutineScope,
    /** input_number this remote owns, e.g. input_number.astrion_living_battery. */
    private val entityId: String,
) {
    private var level: Int = -1
    private var charging: Boolean = false
    private var lastPosted: Int = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) level = (raw * 100f / scale).toInt()
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            // Publish immediately on a level change so the curve keeps its
            // shape; the timer below is only a heartbeat for when nothing moves.
            if (level != lastPosted) post()
        }
    }

    fun start() {
        // ACTION_BATTERY_CHANGED is sticky and cannot be declared in a manifest,
        // so it is registered at runtime; registerReceiver returns the last
        // sticky value, which seeds level/charging without waiting for a change.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(context, sticky)

        scope.launch(Dispatchers.IO) {
            while (isActive) {
                post()
                delay(HEARTBEAT_MS)
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun post() {
        if (level < 0) return
        // No device name configured -> no helper to write to. Publishing into a
        // shared entity is what this change exists to stop.
        if (!entityId.contains(Regex("astrion_[a-z0-9_]+_battery"))) return
        lastPosted = level
        // device_class + state_class are what make HA treat this as a real
        // battery sensor: correct icon and units in the UI, and long-term
        // statistics rather than raw history that the recorder purges.
        // Only the value: an input_number carries its own name, unit, icon and
        // statistics settings from its HA definition, so there is nothing else
        // to publish and nothing here to keep in sync with the helper.
        client.callService(
            ServiceCall.of("input_number", "set_value", entityId, "value" to level)
        )
        Log.d(TAG, "posted $level% to $entityId (charging=$charging)")
    }

    companion object {
        private const val TAG = "AstrionBattery"

        /**
         * Five minutes. Short enough that a Home Assistant restart leaves a blip
         * rather than a hole, long enough that a device which spends its life
         * asleep is not woken to report a number that has not moved -- the
         * measurement should not distort what it measures.
         */
        private const val HEARTBEAT_MS = 5 * 60 * 1000L
    }
}

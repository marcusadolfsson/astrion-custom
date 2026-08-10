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
 * WHY REST AND NOT THE WEBSOCKET. The app already holds an authenticated
 * WebSocket, but that API calls services -- and setting a state is not a
 * service. `POST /api/states/<entity>` is the only way to publish a value HA
 * did not already know about, so this is the one place the app talks HTTP.
 *
 * ONE CONSEQUENCE WORTH KNOWING: states created this way do NOT survive a Home
 * Assistant restart. The entity disappears until the next post, which is why
 * the interval is short enough to make that a blip rather than a gap.
 */
class BatteryReporter(
    private val context: Context,
    private val client: HaClient,
    private val scope: CoroutineScope,
    private val entityId: String = "sensor.astrion_remote_battery",
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
        lastPosted = level
        // device_class + state_class are what make HA treat this as a real
        // battery sensor: correct icon and units in the UI, and long-term
        // statistics rather than raw history that the recorder purges.
        val json = """
            {"state":"$level",
             "attributes":{
               "friendly_name":"Astrion Remote Battery",
               "unit_of_measurement":"%",
               "device_class":"battery",
               "state_class":"measurement",
               "icon":"${if (charging) "mdi:battery-charging" else "mdi:battery"}",
               "charging":$charging
             }}
        """.trimIndent().replace("\n", "")
        client.postJson("/api/states/$entityId", json)
        Log.d(TAG, "posted $level% (charging=$charging)")
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

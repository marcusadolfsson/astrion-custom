package com.custom.astrion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Thin always-on strip: clock left, battery right.
 *
 * Both come from the DEVICE, not from Home Assistant. That matters more than it
 * looks: this is a battery-powered remote that spends most of its life asleep
 * and off the network, and the two things you most want at a glance are exactly
 * the two that must not depend on the connection being up. A clock that stops
 * when Wi-Fi drops is worse than no clock.
 *
 * Sized to disappear. On a 480x800 screen every row costs something the cards
 * below could have used, so this is one small line in a muted colour -- readable
 * when looked at, invisible when not.
 */
@Composable
fun StatusBar(is24Hour: Boolean = false) {
    val context = LocalContext.current

    // Tick every 10s rather than every second: the display only shows minutes,
    // and this composable is alive for as long as the app is on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val timeFmt = remember(is24Hour) {
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    }

    var batteryPct by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }

    // ACTION_BATTERY_CHANGED is sticky and cannot be declared in the manifest --
    // it must be registered at runtime, which is why this is a DisposableEffect
    // rather than anything simpler. registerReceiver returns the last sticky
    // value immediately, so the bar is populated on the first frame instead of
    // waiting for the battery to happen to change.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPct = (level * 100f / scale).toInt()
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(context, sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Asymmetric on purpose: a little air above so the readouts clear
            // the screen edge, and less below so they sit close to the content
            // they label rather than floating between the two.
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeFmt.format(now),
            color = Color(0xFF93AFB6),
            fontSize = 15.sp,
        )
        Text(
            text = when {
                batteryPct < 0 -> ""
                charging -> "⚡ $batteryPct%"
                else -> "$batteryPct%"
            },
            // Only the low-battery case earns a colour. A remote that is merely
            // discharging is doing its job; one below 15% is about to stop.
            color = if (batteryPct in 0..14) Color(0xFFE08A8A) else Color(0xFF93AFB6),
            fontSize = 15.sp,
        )
    }
}

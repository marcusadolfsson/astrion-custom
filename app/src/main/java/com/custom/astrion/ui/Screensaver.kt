package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.config.ScreensaverConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen idle clock for a docked remote.
 *
 * Pure black background, not a dark grey: on this panel black pixels are the
 * cheapest thing to show and the least light thrown into a dark room, and any
 * lift in the background is what makes a screen visible as a glowing rectangle
 * from across a bedroom.
 *
 * Ticks once a second, but only recomposes the two Texts -- the whole point of
 * keeping this a leaf composable rather than hoisting the time into the
 * dashboard's state.
 */
@Composable
fun Screensaver(cfg: ScreensaverConfig) {
    val timeFmt = remember(cfg.clock24h) {
        SimpleDateFormat(if (cfg.clock24h) "HH:mm" else "h:mm", Locale.getDefault())
    }
    val amPmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val dateFmt = remember(cfg.dateFormat) {
        // Falls back rather than throwing: a typo'd pattern in a config file
        // should cost a wrong-looking date, not a blank screensaver.
        runCatching { SimpleDateFormat(cfg.dateFormat, Locale.getDefault()) }
            .getOrElse { SimpleDateFormat("EEEE MMMM d", Locale.getDefault()) }
    }

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            // Land just after the next minute boundary rather than polling per
            // second: the display only shows minutes, so a second-resolution
            // tick would be 59 recompositions an hour for nothing.
            val ms = System.currentTimeMillis()
            delay(60_000 - (ms % 60_000) + 50)
        }
    }

    val tint = remember(cfg.color) { parseHex(cfg.color) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    timeFmt.format(now),
                    color = tint,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!cfg.clock24h) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        amPmFmt.format(now),
                        color = tint.copy(alpha = 0.7f),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                }
            }
            if (cfg.showDate) {
                Spacer(Modifier.height(6.dp))
                Text(
                    dateFmt.format(now),
                    color = tint.copy(alpha = 0.6f),
                    fontSize = 17.sp,
                )
            }
        }
    }
}

/** "#RRGGBB" -> Color, falling back to a mid grey rather than throwing. */
private fun parseHex(s: String): Color = runCatching {
    val hex = s.removePrefix("#")
    Color(
        red = hex.substring(0, 2).toInt(16) / 255f,
        green = hex.substring(2, 4).toInt(16) / 255f,
        blue = hex.substring(4, 6).toInt(16) / 255f,
    )
}.getOrElse { Color(0xFF6E6E6E) }

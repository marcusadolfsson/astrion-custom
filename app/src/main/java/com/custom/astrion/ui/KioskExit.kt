package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardContext
import kotlinx.coroutines.delay

/**
 * The way out of kiosk mode.
 *
 * Reached by tapping the clock a set number of times (see MainActivity), which
 * is deliberately undiscoverable: this is not a control, it is a service hatch,
 * and a visible "exit" button on a wall tablet is an invitation.
 *
 * The PIN is compared LOCALLY against the live state of the configured entity
 * (`input_text.tv_wall_lock_pin`, the same one the living-room screen unlock
 * uses) rather than by writing to HA's scratchpad. Two reasons:
 *  - writing the scratchpad would trip automation.tv_wall_validate_unlock_pin
 *    and unlock the TV wall as a side effect of leaving the launcher; and
 *  - a device that has lost its websocket must still be recoverable, and the
 *    last-known PIN is already in the entity cache.
 *
 * As with the wall lock, this is accidental-exit protection, not a security
 * boundary: the PIN is a plain-text helper value and anyone with adb can walk
 * straight past it.
 */
@Composable
fun KioskExitDialog(
    pinEntity: String,
    ctx: CardContext,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    OpenOverlays.Track(true)

    // Blank/unset means the tablet would be locked with no way out, so an unset
    // PIN falls back the same way the wall lock's automation does.
    val expected = (ctx.entities[pinEntity]?.state ?: "")
        .takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" } ?: "0000"

    // Checked on every keystroke rather than behind an "enter" key: the length
    // is known, so an extra confirm press is pure ceremony.
    LaunchedEffect(entered) {
        if (entered.length < expected.length) return@LaunchedEffect
        if (entered == expected) {
            onUnlock()
        } else {
            wrong = true
            delay(600)
            entered = ""
            wrong = false
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF14262E))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (wrong) "Wrong PIN" else "Exit kiosk",
                color = if (wrong) Color(0xFFE06C6C) else Color(0xFF93AFB6),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            // Dots, not digits -- a wall tablet is read over your shoulder.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(maxOf(expected.length, entered.length)) { i ->
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
                            when {
                                wrong -> Color(0xFFE06C6C)
                                i < entered.length -> Color(0xFF4C8DFF)
                                else -> Color(0xFF1E3841)
                            }
                        )
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { d -> PadKey(d) { if (!wrong) entered += d } }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadKey("✕", onClick = onDismiss)
                PadKey("0") { if (!wrong) entered += "0" }
                PadKey("⌫") { if (entered.isNotEmpty()) entered = entered.dropLast(1) }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit) {
    val (press, click) = rememberPressFeedback(onClick)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(ackColor(Color(0xFF1E3841), press))
            .pressFeedback(press, click),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

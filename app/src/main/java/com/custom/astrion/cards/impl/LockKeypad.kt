package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.cards.CardContext
import kotlinx.coroutines.delay
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.OpenOverlays
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback

/**
 * PIN keypad for unlocking the AV lock.
 *
 * Deliberately holds NO opinion about the PIN. Each digit is appended and the
 * whole entry written to `pinEntry`; Home Assistant already watches that helper,
 * compares it against the configured PIN and clears the lock. So the remote
 * never reads, stores or compares the secret, there is one implementation of
 * "is this the right PIN" in the house, and this keypad behaves exactly like the
 * wall dashboard's — including that a wrong entry simply sits there rather than
 * being cleared, which is what lets you fix a mistyped digit.
 *
 * It closes when the lock actually opens, not when the fourth digit is typed:
 * the only proof the PIN was right is the lock state changing.
 */
@Composable
fun LockKeypad(
    lockEntity: String,
    pinEntry: String,
    ctx: CardContext,
    /**
     * The configured PIN, read only to say "wrong" locally. HA still does the
     * real validation -- automation.tv_wall_validate_unlock_pin watches the
     * scratchpad and flips the lock. Blank disables the message rather than
     * guessing, because a keypad that calls a correct PIN wrong is worse than
     * one that says nothing.
     */
    pinEntity: String = "",
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // Composed only while the keypad is up, so this is unconditionally open.
    OpenOverlays.Track(true)

    // The lock opening IS the success signal.
    val locked = ctx.entities[lockEntity]?.state == "on"
    LaunchedEffect(locked) { if (!locked) onDismiss() }

    fun write(value: String) {
        entered = value
        ctx.client.callService(
            ServiceCall.of("input_text", "set_value", pinEntry, "value" to value)
        )
    }

    // A wrong PIN used to do nothing at all: the automation only acts on a
    // match, so the dots just sat there and the only way to tell a mis-tap from
    // a mis-remembered PIN was to wait and see if the lock opened. Say so, and
    // clear -- HA is still the one that decides, this is only the message.
    val expected = (ctx.entities[pinEntity]?.state ?: "")
        .takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" }
    LaunchedEffect(entered, expected) {
        if (expected == null || entered.length < expected.length) return@LaunchedEffect
        if (entered != expected) {
            wrong = true
            delay(600)
            write("")
            wrong = false
        }
    }

    Dialog(onDismissRequest = {
        // Leave the scratchpad clean for the next attempt.
        ctx.client.callService(ServiceCall.of("input_text", "set_value", pinEntry, "value" to ""))
        onDismiss()
    }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF14262E))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (wrong) "Wrong PIN" else "Enter PIN",
                color = if (wrong) Color(0xFFE06C6C) else Color(0xFF93AFB6),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            // Dots, not digits: the PIN is shoulder-surfable on a screen held
            // at chest height in a room with other people in it.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(maxOf(expected?.length ?: 4, entered.length)) { i ->
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
                    row.forEach { Key(it) { write(entered + it) } }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.size(64.dp))
                Key("0") { write(entered + "0") }
                Key("⌫") { if (entered.isNotEmpty()) write(entered.dropLast(1)) }
            }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
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

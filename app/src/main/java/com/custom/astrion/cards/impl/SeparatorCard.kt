package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback

/**
 * Bubble-Card-style section separator: a small icon + label followed by a
 * divider line filling the rest of the row. Purely visual — groups the bubble
 * selectors into sections like the wall dashboards' `separator` cards.
 *
 * Config: { "type": "separator", "options": { "name": "Display", "icon": "screen" } }
 *
 * A separator may also carry a `lock`, drawn at the RIGHT END OF THE DIVIDER:
 *
 *   "lock": { "entity": "input_boolean.tv_wall_locked",
 *             "pin_entry": "input_text.tv_wall_lock_pin_entry" }
 *
 * The divider is dead space that already spans to the edge, so a control there
 * costs no vertical room and lands under the thumb — which is why the section
 * header is a better home for it than a card of its own.
 *
 * Locking is one tap. UNLOCKING opens a PIN keypad, because the two directions
 * are not equally consequential: locking something that was already off is
 * harmless, while unlocking is what lets a stray press or a voice command power
 * the room back up.
 */
class SeparatorCard : CardRenderer {
    /** Drives both the lock button and every separator's minimum height. */
    private val LOCK_SIZE = 34.dp

    override val type = "separator"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val name = config.string("name") ?: ""
        val icon = CardIcons.forName(config.string("icon"))
        Row(
            // Every separator gets the LOCK's height whether or not it has one.
            // Otherwise a section headed by a lockable separator starts ~12dp
            // lower than its neighbour, and in a multi-column layout the two
            // columns' first cards visibly fail to line up.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp)
                .heightIn(min = LOCK_SIZE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF7FB3C4), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            if (name.isNotBlank()) {
                Text(name, color = Color(0xFF9FC0CB), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x33FFFFFF)))

            @Suppress("UNCHECKED_CAST")
            val lock = config.options["lock"] as? Map<String, Any?>
            val lockEntity = lock?.get("entity") as? String
            val pinEntry = lock?.get("pin_entry") as? String
            if (lockEntity != null) {
                val locked = ctx.entities[lockEntity]?.state == "on"
                var keypad by remember { mutableStateOf(false) }
                val (press, click) = rememberPressFeedback {
                    if (locked) keypad = true
                    else ctx.client.callService(
                        ServiceCall("input_boolean", "turn_on", lockEntity)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(LOCK_SIZE)
                        .clip(CircleShape)
                        .background(ackColor(if (locked) Color(0xFFB4472F) else Color(0xFF1E3841), press))
                        .pressFeedback(press, click),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (locked) "Unlock" else "Lock",
                        tint = if (locked) Color.White else Color(0xFF6C8A94),
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (keypad && pinEntry != null) {
                    LockKeypad(
                        lockEntity,
                        pinEntry,
                        ctx,
                        pinEntity = lock["pin_entity"] as? String ?: "",
                    ) { keypad = false }
                }
            }
        }
    }
}

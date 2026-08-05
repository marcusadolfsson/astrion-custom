package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import kotlin.math.roundToInt

/**
 * Bubble-Card-style thermostat pill, matching the wall dashboard's climate card
 * but sized for the remote: icon + name + setpoint/current on the LEFT, − / +
 * on the RIGHT. Tapping the icon cycles nothing (deliberately) — this is a
 * setpoint nudger, mode changes stay on the wall dashboards.
 *
 * The icon tints by what the system is actually doing (hvac_action), so a glance
 * shows cooling vs idle.
 *
 * Config:
 *   { "type": "bubble_climate", "options": {
 *       "entity_id": "climate.living_room", "name": "Common Areas AC",
 *       "step": 1 } }
 */
class BubbleClimateCard : CardRenderer {
    override val type = "bubble_climate"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val step = (config.options["step"] as? Number)?.toDouble() ?: 1.0

        val target = e?.attr("temperature")?.let { num(it) }
        val current = e?.attr("current_temperature")?.let { num(it) }
        val mode = e?.state ?: "—"
        val action = e?.attrString("hvac_action")

        // Tint by what it's actively doing, falling back to the mode.
        val accent = when {
            action == "cooling" || (action == null && mode == "cool") -> Color(0xFF3E8FD0)
            action == "heating" || (action == null && mode == "heat") -> Color(0xFFD98032)
            mode == "off" -> Color(0xFF41606B)
            else -> Color(0xFF2E7D95)
        }

        fun setTemp(t: Double) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to t)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF1E3841))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No icon circle — with three controls on a 320dp row it crowded the
            // text. The hvac_action cue moves to the setpoint colour instead.
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color(0xFF93AFB6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(target?.let { "${fmt(it)}°" } ?: "—")
                        current?.let { append("  ·  now ${fmt(it)}°") }
                    },
                    // Tinted by hvac_action so "is it actually cooling?" is still
                    // visible now that the coloured icon is gone.
                    color = if (action == "cooling" || action == "heating") accent else Color(0xFFF1F4FA),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundBtn(Icons.Filled.Remove) { target?.let { setTemp(it - step) } }
                RoundBtn(Icons.Filled.Add) { target?.let { setTemp(it + step) } }
            }
        }
    }

    /** Whole degrees render without a trailing .0; halves keep one decimal. */
    private fun fmt(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format("%.1f", v)

    private fun num(el: kotlinx.serialization.json.JsonElement): Double? =
        (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    @Composable
    private fun RoundBtn(icon: ImageVector, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E7D95))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }
}

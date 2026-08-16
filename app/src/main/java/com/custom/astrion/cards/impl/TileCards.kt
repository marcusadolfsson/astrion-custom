package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Cover / curtain card: open / stop / close buttons plus a position readout.
 *
 * Uses cover.open_cover / cover.close_cover / cover.stop_cover.
 *
 * Config: CardConfig("cover", mapOf("entity_id" to "cover.living_room", "name" to "Curtains"))
 */
class CoverCard : CardRenderer {
    override val type = "cover"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val position = e?.attrInt("current_position") // 0..100
        val stateLabel = position?.let { "$it% open" } ?: (e?.state ?: "—")

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "cover", service = service, entityId = entityId))
        }

        // Mushroom horizontal: icon + name/state on the left, controls on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1E3841))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A4954)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Blinds, contentDescription = null, tint = Color(0xFFB6C9CE))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color(0xFFE6F0F1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(stateLabel, color = Color(0xFF93AFB6), fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleBtn(Icons.Filled.KeyboardArrowUp) { call("open_cover") }
                CircleBtn(Icons.Filled.Stop) { call("stop_cover") }
                CircleBtn(Icons.Filled.KeyboardArrowDown) { call("close_cover") }
            }
        }
    }
}

/**
 * Fan card: toggle tile with a percentage readout and up/down speed steppers.
 *
 * Uses fan.toggle and fan.set_percentage.
 *
 * Config: CardConfig("fan", mapOf("entity_id" to "fan.bedroom", "step" to 20))
 */
class FanCard : CardRenderer {
    override val type = "fan"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val pct = e?.attrInt("percentage") ?: 0
        val step = config.int("step", 20).coerceAtLeast(1)

        fun setPct(p: Int) {
            ctx.client.callService(
                ServiceCall.of("fan", "set_percentage", entityId, "percentage" to p.coerceIn(0, 100))
            )
        }

        // Shaped to match the other control pills (shade_control / bubble_climate):
        // same height, corner radius, label-over-value typography and buttons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(if (on) Color(0xFF2B3A67) else Color(0xFF1E3841))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { ctx.client.toggle(entityId) }
            ) {
                Text(name, color = Color(0xFF93AFB6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (on) "$pct%" else "Off",
                    color = Color(0xFFF1F4FA),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircleBtn(Icons.Filled.KeyboardArrowUp) { setPct(pct + step) }
                CircleBtn(Icons.Filled.KeyboardArrowDown) { setPct(pct - step) }
            }
        }
    }
}

/**
 * Switch tile: simple toggle. Works for switch.* (and anything toggleable).
 *
 * Config: CardConfig("switch", mapOf("entity_id" to "switch.porch", "name" to "Porch"))
 */
class SwitchCard : CardRenderer {
    override val type = "switch"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val icon = switchIcon(config.string("icon"))
        // On-state background (e.g. a semi-transparent dark red for a heater).
        val onColor = parseColor(config.options["on_color"]) ?: Color(0xFF2E5A46)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) onColor else Color(0xFF1E3841))
                .clickable { ctx.client.toggle(entityId) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading icon box, matching the cover tiles below it.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A4954)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (on) Color(0xFFE79A9A) else Color(0xFFB6C9CE))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color(0xFFE6F0F1), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (on) "On" else "Off", color = Color(0xFF93AFB6), fontSize = 13.sp)
            }
        }
    }
}

/** Map a switch card's `icon` option name to a Material icon. */
private fun switchIcon(name: String?): ImageVector = when (name) {
    "heater", "heat" -> Icons.Filled.Whatshot
    "fan" -> Icons.Filled.Air
    "bulb", "light" -> Icons.Filled.Lightbulb
    else -> Icons.Filled.PowerSettingsNew
}

/** Parse an `on_color` option: a hex string ("#AARRGGBB") or an ARGB number. */
private fun parseColor(v: Any?): Color? = when (v) {
    is Number -> Color(v.toLong())
    is String -> v.removePrefix("#").toLongOrNull(16)?.let { Color(it) }
    else -> null
}

/** Shared small circular icon button used by the tile cards above. */
@Composable
private fun CircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    // Shared by the cover / fan / switch tiles. The fan is the case that earns
    // it: a speed step goes out over LocalTuya and the percentage on the tile
    // only moves once the fan reports back, so the button sits there looking
    // untouched for about a second.
    val (press, click) = rememberPressFeedback(onClick)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(ackColor(Color(0xFF2E7D95), press))
            .pressFeedback(press, click),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

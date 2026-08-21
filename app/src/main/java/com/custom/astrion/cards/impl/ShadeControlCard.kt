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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.OpenOverlays
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shade controller mirroring the Living Room wall dashboard's Bubble cover card:
 * a target selector on the LEFT (which shade the buttons act on) and open / stop
 * / close controls on the RIGHT, in one pill.
 *
 * The selector is backed by an `input_select` (e.g. input_select.lr_shade_target
 * = All Shades / North / Left / Center / Right) whose options are read live from
 * the entity, so adding a shade in HA needs no app change. Picking one calls
 * `input_select.select_option`; the buttons then fire the configured services,
 * which on the HA side already route to whatever the target is.
 *
 * Config:
 *   { "type": "shade_control", "options": {
 *       "name": "Shades",
 *       "target_entity": "input_select.lr_shade_target",
 *       "open":  { "service": "script.lr_shade_target_open" },
 *       "stop":  { "service": "script.lr_shade_target_stop" },
 *       "close": { "service": "script.lr_shade_target_close" } } }
 */
class ShadeControlCard : CardRenderer {
    override val type = "shade_control"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val name = config.string("name") ?: "Shades"
        val targetEntity = config.string("target_entity")
        val openBtn = config.options["open"] as? Map<String, Any?>
        val stopBtn = config.options["stop"] as? Map<String, Any?>
        val closeBtn = config.options["close"] as? Map<String, Any?>

        val e = targetEntity?.let { ctx.entities[it] }
        val current = e?.state ?: "—"
        // Options come straight from the input_select, so HA stays the source of truth.
        // Keyed on the raw attribute so the list is rebuilt only when HA actually
        // changes it — not on every recomposition (and not while it's closed).
        val optionsAttr = e?.attr("options")
        val options: List<String> = remember(optionsAttr) {
            (optionsAttr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        }

        var expanded by remember { mutableStateOf(false) }
        OpenOverlays.Track(expanded)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF1E3841))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- left: target selector ----
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(enabled = options.isNotEmpty()) { expanded = true }
                        // Inset the text: the other bubble cards get this from
                        // their icon circle, which doesn't fit here alongside
                        // three buttons on a 320dp-wide screen.
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color(0xFF93AFB6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            current,
                            color = Color(0xFFF1F4FA),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFF93AFB6),
                        modifier = Modifier.size(20.dp))
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        val selected = opt == current
                        DropdownMenuItem(
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            text = {
                                Text(
                                    opt,
                                    fontSize = 28.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Color(0xFF2E7D95) else Color.Unspecified,
                                    textAlign = TextAlign.Start,
                                )
                            },
                            onClick = {
                                expanded = false
                                if (targetEntity != null) {
                                    ctx.client.callService(
                                        ServiceCall.of(
                                            "input_select", "select_option", targetEntity,
                                            "option" to opt,
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // ---- right: open / stop / close ----
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundButton(Icons.Filled.KeyboardArrowUp) { fire(ctx, openBtn) }
                RoundButton(Icons.Filled.Stop) { fire(ctx, stopBtn) }
                RoundButton(Icons.Filled.KeyboardArrowDown) { fire(ctx, closeBtn) }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
        // Latched tap feedback rather than a ripple. These three buttons are the
        // clearest case for it in the whole dashboard: Bond is RF one-way, so
        // nothing on screen changes for 5-10 s after a press, and a 200 ms
        // ripple on a 3" panel is why the same button gets pressed three times.
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

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>?) {
        val service = b?.get("service") as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }
}

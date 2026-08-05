package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall

/**
 * Bubble-Card-style selector pill, matching the look of the HACS Bubble Card
 * `select` cards on the wall dashboards but touch-sized for the remote's small
 * screen: a circular icon + name + current value, tap to open a dropdown of
 * options. Each option fires a HA service (same option shape as button_grid's
 * buttons), so it drives script-based selectors (Activity, Brightness, Screen
 * size, Light Scenes) while `active_entity` supplies the current value.
 *
 * Config:
 *   { "type": "bubble_select", "options": {
 *       "name": "Activity", "icon": "tv",
 *       "active_entity": "input_select.lr_av_activity",
 *       "options": [
 *         { "name": "Apple TV", "service": "script.lr_av_watch_apple_tv",
 *           "active_value": "Watch Apple TV" }, ... ] } }
 */
class BubbleSelectCard : CardRenderer {
    override val type = "bubble_select"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val name = config.string("name") ?: ""
        val icon = CardIcons.forName(config.string("icon"))
        val activeEntity = config.string("active_entity")
        val options = (config.options["options"] as? List<Map<String, Any?>>) ?: emptyList()

        val currentState = activeEntity?.let { ctx.entities[it]?.state }
        val currentName = (options.firstOrNull { it["active_value"] == currentState }?.get("name") as? String)
            ?: currentState ?: "—"

        var expanded by remember { mutableStateOf(false) }

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFF1E3841))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2E7D95)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (name.isNotBlank()) {
                        Text(name, color = Color(0xFF93AFB6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(currentName, color = Color(0xFFF1F4FA), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFF93AFB6))
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    val label = opt["name"] as? String ?: return@forEach
                    val selected = opt["active_value"] == currentState
                    DropdownMenuItem(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                        text = {
                            Text(
                                label,
                                fontSize = 24.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(0xFF2E7D95) else Color.Unspecified,
                            )
                        },
                        onClick = { expanded = false; fire(ctx, opt) },
                    )
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }
}

/** Maps a small set of config icon names to Material icons (shared by the bubble
 *  select + separator cards). Extend as needed. */
object CardIcons {
    fun forName(n: String?): ImageVector = when (n?.lowercase()) {
        "tv", "activity", "source", "watch" -> Icons.Filled.Tv
        "brightness", "sun", "light-level" -> Icons.Filled.BrightnessMedium
        "screen", "aspect", "layout", "display" -> Icons.Filled.AspectRatio
        "movie", "theater", "scene" -> Icons.Filled.Movie
        "light", "bulb", "lights" -> Icons.Filled.Lightbulb
        "remote" -> Icons.Filled.SettingsRemote
        else -> Icons.Filled.Tune
    }
}

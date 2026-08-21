package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.OpenOverlays

/**
 * Source picker for a media_player: a compact row showing the current source,
 * tapping it opens a dropdown of the entity's live `source_list`; picking one
 * fires `media_player.select_source` immediately.
 *
 * Handles players whose source_list vanishes while off (e.g. Android TV) by
 * showing a hint instead of an empty menu.
 *
 * Config shape:
 *   { "type": "source_select", "options": {
 *       "entity_id": "media_player.living_room_tv",
 *       "name": "TV source"
 *   } }
 */
class SourceSelectCard : CardRenderer {
    override val type = "source_select"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        // Keyed on the raw attribute: this re-walked the JsonArray and allocated a
        // 50-150 string list on EVERY recomposition.
        val sourceListAttr = e?.attr("source_list")
        val sources = remember(sourceListAttr) { e?.attrStringList("source_list") ?: emptyList() }
        val current = e?.attrString("source")

        var expanded by remember { mutableStateOf(false) }
        OpenOverlays.Track(expanded)

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E3841))
                    .clickable(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color(0xFF93AFB6), fontSize = 11.sp)
                    Text(
                        current ?: if (sources.isEmpty()) "No sources (device off?)" else "Select source…",
                        color = Color(0xFFE6F0F1),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) Color(0xFF5A7783) else Color(0xFFCBDCE0),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xFF1E3841))
                    .widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) Color(0xFF6EA8FE) else Color(0xFFE6F0F1),
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            ctx.client.callService(
                                ServiceCall.of("media_player", "select_source", entityId, "source" to s)
                            )
                        },
                    )
                }
            }
        }
    }
}

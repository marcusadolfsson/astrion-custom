package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall

/**
 * Full-screen modal picker over a media_player's live `source_list`.
 *
 * This is `source_select`'s big sibling. Where source_select is a compact row
 * plus a dropdown -- right for "which HDMI input?", where the list is short,
 * stable, and you went looking for it -- this one is for lists that arrive
 * UNANNOUNCED and want reading from across a room: a voice search comes back
 * with matches and the remote should just show them, in type you can hit with a
 * thumb without aiming.
 *
 * Two behaviours make it work as a response surface rather than a control:
 *
 *  - `open_when` auto-opens the modal, but on the state TRANSITION into that
 *    value, not while it equals it. Opening on the value would make the modal
 *    unclosable: dismissing it leaves the entity still `on`, so the next
 *    recomposition immediately reopens it. Tracking the previous state means
 *    dismiss sticks until genuinely new results arrive.
 *  - It closes itself when the list empties, so a cleared search doesn't leave
 *    a stale modal sitting over the dashboard.
 *
 * Picking an item fires `media_player.select_source`, exactly as source_select
 * does -- so any entity that works with one works with the other.
 *
 * Config shape:
 *   { "type": "source_modal", "options": {
 *       "entity_id": "media_player.kaleidescape_voice_search",
 *       "name": "Search results",
 *       "subtitle_attr": "media_title",
 *       "open_when": "on",
 *       "trigger_label": "Show results",
 *       "show_trigger": true,
 *       "item_font_size": 20,
 *       "item_height": 62,
 *       "max_items": 0
 *   } }
 */
class SourceModalCard : CardRenderer {
    override val type = "source_modal"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]

        // Keyed on the raw attribute so the list is only rebuilt when it really
        // changes -- attrStringList walks a JsonArray and allocates.
        val sourceListAttr = e?.attr("source_list")
        val all = remember(sourceListAttr) { e?.attrStringList("source_list") ?: emptyList() }
        val maxItems = config.int("max_items", 0)
        val sources = if (maxItems > 0) all.take(maxItems) else all

        val title = config.string("name") ?: e?.friendlyName ?: entityId
        val subtitle = config.string("subtitle_attr")?.let { e?.attrString(it) }

        var open by remember { mutableStateOf(false) }

        // Auto-open when a NEW result set arrives (see class doc).
        //
        // Keying this on the state alone does not work, and both failures are
        // easy to hit:
        //  - Wrapped in a `conditional` the card is only composed while results
        //    exist, so it never observes an off->on edge at all -- it is simply
        //    born with the state already matching.
        //  - Two searches in a row both leave the entity `on`, so the state
        //    never changes between them and a state-keyed effect stays silent
        //    for every search after the first.
        // Keying on the RESULT IDENTITY instead handles both: a new search
        // changes the list and/or the query subtitle, while merely dismissing
        // the modal changes neither -- so dismiss sticks until real new results.
        val openWhen = config.string("open_when")
        val state = e?.state
        LaunchedEffect(state, sourceListAttr, subtitle) {
            if (openWhen != null && state == openWhen && sources.isNotEmpty()) open = true
        }
        // A cleared list should never leave the modal stranded.
        if (open && sources.isEmpty()) open = false

        if (config.bool("show_trigger", true)) {
            val label = config.string("trigger_label") ?: "Show results"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E3841))
                    .clickable(enabled = sources.isNotEmpty()) { open = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color(0xFF93AFB6), fontSize = 11.sp)
                    Text(
                        if (sources.isEmpty()) "No results" else "$label (${sources.size})",
                        color = Color(0xFFE6F0F1),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (!open) return

        Dialog(
            onDismissRequest = { open = false },
            // The stock dialog width is far too narrow on a 480x800 remote; the
            // whole point here is large targets, so take the screen.
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF12262C)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = Color(0xFFE6F0F1),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                color = Color(0xFF93AFB6),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { open = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFCBDCE0),
                        )
                    }
                }

                val itemHeight = config.int("item_height", 62).dp
                val itemFontSize = config.int("item_font_size", 20).sp

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources) { s ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = itemHeight)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1E3841))
                                .clickable {
                                    open = false
                                    ctx.client.callService(
                                        ServiceCall.of(
                                            "media_player", "select_source", entityId, "source" to s
                                        )
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                s,
                                color = Color(0xFFE6F0F1),
                                fontSize = itemFontSize,
                                fontWeight = FontWeight.Medium,
                                // Long titles wrap rather than truncate: the
                                // whole point is being readable at a glance.
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

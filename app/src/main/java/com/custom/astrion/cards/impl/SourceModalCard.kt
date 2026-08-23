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
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import com.custom.astrion.cards.CuratedItems
import com.custom.astrion.ui.PickerModal
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.OpenOverlays

/**
 * Full-screen modal picker over a live list: a media_player's `source_list` or
 * an input_select's `options`.
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
 *  - `open_when` auto-opens the modal ONCE PER RESULT SET, not while the state
 *    equals that value. Opening on the value would make it unclosable --
 *    dismissing leaves the entity still `on`, so the next recomposition reopens
 *    it. And tracking it inside the card is not enough either, because swiping
 *    to another page disposes the card and takes "already dismissed" with it.
 *    Identity is tracked outside composition; see SourceModalShown.
 *  - It closes itself when the list empties, so a cleared search doesn't leave
 *    a stale modal sitting over the dashboard.
 *
 * Picking an item fires `media_player.select_source` or
 * `input_select.select_option`, chosen from the entity's domain.
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

    /**
     * A curated entry. `items:` replaces the entity's live list entirely: the
     * Apple TV reports 32 apps in whatever order it likes and the Spectrum
     * select carries 149 channels, neither of which is a thing you want to read
     * through. A hand-picked list with logos is, and it is also what the TV Wall
     * dashboard has always shown -- this keeps the two the same.
     */
    private data class Item(
        val name: String,
        val image: String?,
        val service: String?,
        val entityId: String?,
        val data: Map<String, Any?>,
    )

    /**
     * One logo tile. Falls back to the entry's NAME when it has no image or the
     * fetch fails -- a blank square is indistinguishable from a broken app.
     */
    @Composable
    private fun Tile(
        ctx: CardContext,
        name: String,
        image: String?,
        height: Dp,
        fontSize: TextUnit,
        onClick: () -> Unit,
    ) {
        var bmp by remember(image) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(image) {
            // Bounded decode: these are ~250px PNGs drawn into a ~120dp tile,
            // and the HA100 has a ~6 MB heap.
            bmp = image?.let { ctx.client.fetchBitmap(it, maxPx = 160) }
        }
        val b = bmp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // FIXED height, not a minimum. ContentScale.Fit scales to the
                // SMALLER of the two ratios, so with an unbounded height the
                // limit was the image's own 52px and these 70x52 guide logos
                // drew at natural size in a tile five times that wide.
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                // Logo tiles go light. Spectrum's guide art and most brand marks
                // are drawn for white backgrounds -- abc, CBS, FOX, TNT, bravo
                // and A&E are all near-black, and on the dark tile they were
                // invisible rather than subtle. Text tiles keep the dark chip.
                .background(if (b != null) Color(0xFFF2F5F7) else Color(0xFF1E3841))
                .clickable(onClick = onClick)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    name,
                    color = Color(0xFFE6F0F1),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /**
     * Fire whatever the picked entry means. A curated entry carries its own
     * service (so the same modal can select an Apple TV app and launch a
     * Spectrum channel); anything else falls back to the domain default.
     */
    private fun pick(
        ctx: CardContext,
        name: String,
        curated: List<Item>,
        isSelect: Boolean,
        entityId: String,
    ) {
        val it = curated.firstOrNull { c -> c.name == name }
        if (it?.service != null) {
            val domain = it.service.substringBefore('.')
            val svc = it.service.substringAfter('.')
            ctx.client.callService(
                ServiceCall.of(domain, svc, it.entityId, *it.data.entries.map { e -> e.key to e.value }.toTypedArray())
            )
            return
        }
        ctx.client.callService(
            ServiceCall.of(
                if (isSelect) "input_select" else "media_player",
                if (isSelect) "select_option" else "select_source",
                entityId,
                (if (isSelect) "option" else "source") to name,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseItems(config: CardConfig): List<Item> =
        (config.options["items"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            Item(
                name = name,
                image = m["image"] as? String,
                service = m["service"] as? String,
                entityId = m["entity_id"] as? String,
                data = (m["data"] as? Map<String, Any?>).orEmpty(),
            )
        }

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val curated = remember(config) { CuratedItems.parse(config.options["items"], ctx, config.string("target_from")) }
        // A curated `items:` list is self-contained -- each entry names its own
        // service -- so entity_id is only required for a LIVE list. Returning
        // early on a missing entity_id made a valid curated card draw nothing.
        val entityId = config.string("entity_id").orEmpty()
        if (entityId.isBlank() && curated.isEmpty()) return
        val e = ctx.entities[entityId]

        // Where a live list lives and how a pick is applied both follow the
        // entity's DOMAIN: a media_player carries `source_list` and takes
        // select_source, an input_select carries `options` and takes
        // select_option.
        val isSelect = entityId.startsWith("input_select.")
        val listAttr = if (isSelect) "options" else "source_list"
        val sourceListAttr = e?.attr(listAttr)
        val excluded = config.stringList("exclude").toSet()
        val live = remember(sourceListAttr, excluded, entityId) {
            (e?.attrStringList(listAttr) ?: emptyList())
                .filter { it !in excluded }
                .map { name ->
                    PickerModal.Entry(name) {
                        ctx.client.callService(
                            ServiceCall.of(
                                if (isSelect) "input_select" else "media_player",
                                if (isSelect) "select_option" else "select_source",
                                entityId,
                                (if (isSelect) "option" else "source") to name,
                            )
                        )
                    }
                }
        }
        val maxItems = config.int("max_items", 0)
        val entries = when {
            curated.isNotEmpty() -> curated
            maxItems > 0 -> live.take(maxItems)
            else -> live
        }

        val title = config.string("name") ?: "Sources"
        var open by remember { mutableStateOf(false) }

        // Auto-open ONCE PER RESULT SET, not while the state equals the value:
        // opening on the value would make it unclosable, since dismissing leaves
        // the entity still `on` and the next recomposition reopens it. Identity
        // is tracked outside composition -- see SourceModalShown.
        val openWhen = config.string("open_when")
        if (openWhen != null && e?.state == openWhen && entries.isNotEmpty()) {
            val stamp = e.lastChanged ?: e.state
            LaunchedEffect(stamp) {
                if (SourceModalShown.claim(entityId, stamp)) open = true
            }
        }
        // Closes itself when the list empties, so a cleared search does not
        // leave a stale modal sitting over the dashboard.
        LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) open = false }

        if (config.bool("show_trigger", true)) {
            val label = config.string("trigger_label") ?: title
            if (config.bool("compact", false)) {
                // A small pill carrying just its label. The full-width row below
                // belongs to a result set that ARRIVED and wants announcing; a
                // launcher you go looking for should not take a whole row.
                val (press, click) = rememberPressFeedback { open = true }
                Box(
                    modifier = Modifier
                        // Full width, like every other row on the page. A pill
                        // that hugs its text reads as an afterthought squeezed
                        // under the Activity row rather than a control.
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ackColor(Color(0xFF1E3841), press))
                        .pressFeedback(press, click)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color(0xFFE6F0F1), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E3841))
                        .clickable(enabled = entries.isNotEmpty()) { open = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color(0xFF93AFB6), fontSize = 11.sp)
                        Text(
                            if (entries.isEmpty()) "No results" else "$label (${entries.size})",
                            color = Color(0xFFE6F0F1),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (open && entries.isNotEmpty()) {
            PickerModal.Show(
                title = title,
                entries = entries,
                client = ctx.client,
                columns = config.int("columns", 1).coerceAtLeast(1),
                tileHeight = config.int("item_height", 62).dp,
                fontSize = config.int("item_font_size", 20).sp,
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * Remembers which result set has already auto-opened, OUTSIDE composition.
 *
 * Tracking it inside the card is not enough: swiping to another page disposes
 * the card and takes "already dismissed" with it, so the modal would spring
 * back the moment you swiped home.
 */
private object SourceModalShown {
    private val shown = mutableMapOf<String, String>()

    /** True the FIRST time an identity is seen for this entity; false after. */
    @Synchronized
    fun claim(entityId: String, identity: String): Boolean {
        if (shown[entityId] == identity) return false
        shown[entityId] = identity
        return true
    }
}

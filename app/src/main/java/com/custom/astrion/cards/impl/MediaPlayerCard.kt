package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall

/**
 * Media player card with two layouts:
 *  - "compact" (default): a single row — round album art, title/artist, and
 *    prev / play-pause / next / vol- / vol+ controls. Blurred art background.
 *  - "full": a big square album art on top, title/artist, then transport and
 *    volume rows. For the dedicated Media page.
 *
 * Album art is loaded from the entity's `entity_picture` (auth'd fetch). Since
 * Modifier.blur is a no-op on this API 26 device, the blurred background is a
 * heavily downscaled copy upscaled to fill the card.
 *
 * Config:
 *   { "type": "media_player", "options": { "entity_id": "media_player.club",
 *       "variant": "full" } }   // omit variant for compact
 */
class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val full = config.string("variant") == "full"
        val topButtons = (config.options["top_buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        // Optional reverse/forward transport buttons, each an action map
        // {service, entity_id, data}. Shown only when set.
        //
        // `scan: false` makes the pair CHAPTER skip rather than fast-forward /
        // rewind: skip icons instead of scan icons, and no scanning state --
        // a chapter jump lands you back in normal playback, so flipping the
        // centre button to Play (which is right for scanning, where a tap must
        // resume) would be a lie.
        val reverseBtn = config.options["reverse"] as? Map<String, Any?>
        val forwardBtn = config.options["forward"] as? Map<String, Any?>
        val e = ctx.entities[entityId]
        val playing = e?.state == "playing"
        val realTitle = e?.attrString("media_title")?.takeIf { it.isNotBlank() }
        val title = realTitle ?: e?.friendlyName ?: entityId
        val artist = e?.attrString("media_artist")
            ?: e?.attrString("media_series_title")
            ?: e?.attrString("app_name")
        val artPath = e?.attrString("entity_picture")?.takeIf { it.isNotBlank() }

        // Collapse the full card entirely when nothing is actually playing.
        //
        // A missing media title is the reliable signal. Requiring the artwork to
        // be missing too does not work: the Kaleidescape keeps serving a
        // placeholder cover while idle, so `entity_picture` is never null and
        // the card stayed on screen as a purple square captioned with the
        // device's own name — the friendly-name fallback below standing in for
        // a title that was never there.
        if (full && realTitle == null) return

        var art by remember(artPath) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(artPath) { art = artPath?.let { ctx.client.fetchBitmap(it) } }

        // Downscale off the main thread: this ran inside remember{}, i.e. during
        // composition on the UI thread — a filtered scale of full-size album art.
        var blurredBg by remember(art) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(art) {
            blurredBg = art?.let { img ->
                withContext(Dispatchers.Default) {
                    val src = img.asAndroidBitmap()
                    if (src.width <= 0) return@withContext null
                    val w = 32
                    val h = (w * src.height / src.width).coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
                }
            }
        }

        val mp: (String, Array<out Pair<String, Any?>>) -> Unit =
            remember(entityId) {
                { service, data ->
                    ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
                }
            }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B343D)),
        ) {
            // Blurred album art background + scrim for legibility.
            // Alpha instead of a separate full-size scrim Box — same look, one
            // fewer full-card layer to composite on a Mali-400 with no fillrate
            // headroom (was art + scrim + content = 3 layers).
            blurredBg?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.30f,
                )
            }

            if (full) {
                FullContent(ctx, title, artist, playing, art, mp, topButtons, reverseBtn, forwardBtn)
            } else {
                CompactContent(title, artist, art, mp)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fireService(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    // ---- compact (main page): one row, volume only, buttons right-justified --
    @Composable
    private fun CompactContent(
        title: String,
        artist: String?,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tap the card body to toggle play/pause (volume buttons still
                // handle their own taps).
                .clickable { mp("media_play_pause", emptyArray()) }
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artMod = Modifier.size(44.dp).clip(CircleShape)
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(Color(0xFF3A2E5A)))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFF1F4FA), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!artist.isNullOrBlank()) {
                    Text(artist, color = Color(0xFFB6BECC), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Only volume, sitting at the right after the weighted text.
            CircleControl(Icons.Filled.VolumeDown, 40.dp) { mp("volume_down", emptyArray()) }
            CircleControl(Icons.Filled.VolumeUp, 40.dp) { mp("volume_up", emptyArray()) }
        }
    }

    // ---- full (media page) --------------------------------------------------
    @Composable
    private fun FullContent(
        ctx: CardContext,
        title: String,
        artist: String?,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        topButtons: List<Map<String, Any?>>,
        reverseBtn: Map<String, Any?>?,
        forwardBtn: Map<String, Any?>?,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top action buttons (e.g. Group / Ungroup).
            if (topButtons.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    topButtons.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x662C4C58)) // semi-transparent
                                .clickable { fireService(ctx, b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "",
                                color = Color(0xFFE6F0F1),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            // Big album art.
            val artMod = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(16.dp))
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(Color(0xFF3A2E5A)))
            }
            // Centered now-playing text.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = Color(0xFFF1F4FA), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                if (!artist.isNullOrBlank()) {
                    Text(artist, color = Color(0xFFB6BECC), fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
            // Transport row: [reverse] play/pause [forward] — all one size. Chapter
            // skip removed; volume is on the hardware keys. Reverse/forward show
            // only when configured (e.g. Kaleidescape scan). While scanning, the
            // centre button shows Play (not Pause) so a tap resumes normal play.
            var scanning by remember(title) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                reverseBtn?.let { b ->
                    val isScan = b["scan"] as? Boolean ?: true
                    CircleControl(
                        if (isScan) Icons.Filled.FastRewind else Icons.Filled.SkipPrevious,
                        70.dp,
                    ) { if (isScan) scanning = true; fireService(ctx, b) }
                }
                CircleControl(
                    if (playing && !scanning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    70.dp,
                    accent = true,
                ) {
                    if (scanning) {
                        scanning = false
                        mp("media_play", emptyArray())
                    } else {
                        mp("media_play_pause", emptyArray())
                    }
                }
                forwardBtn?.let { b ->
                    val isScan = b["scan"] as? Boolean ?: true
                    CircleControl(
                        if (isScan) Icons.Filled.FastForward else Icons.Filled.SkipNext,
                        70.dp,
                    ) { if (isScan) scanning = true; fireService(ctx, b) }
                }
            }
        }
    }

    @Composable
    private fun CircleControl(
        icon: ImageVector,
        size: androidx.compose.ui.unit.Dp,
        accent: Boolean = false,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (accent) Color(0xFF4C6EF5) else Color(0x552C4C58))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }
}

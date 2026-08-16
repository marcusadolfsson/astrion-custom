package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
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
        // `title` is resolved after the staleness check below, which needs to
        // be able to discard it.
        // Resolved below too, for the same reason: artist and series title come
        // out of the same now-playing record as the title and go stale with it.
        // `app_name` does NOT — it reports the foreground app and stays correct —
        // so it survives as the subtitle when the rest is discarded.
        val artPath = e?.attrString("entity_picture")?.takeIf { it.isNotBlank() }

        // Hide a title we can no longer vouch for.
        //
        // An Apple TV serves two independent things: which app is in the
        // foreground (current) and the tvOS "now playing" record (whatever an app
        // last registered). Disney+ in particular often never re-registers, so
        // the card confidently captioned a live show with the episode watched two
        // days earlier — right app, wrong programme, and no way to tell from the
        // card that it was reading a stale record.
        //
        // `media_position_updated_at` is the honest witness: it is stamped when
        // the player last reported a position, so an ancient value means nothing
        // has reported in for that long. The threshold is HOURS, not minutes, on
        // purpose — a genuinely paused film legitimately holds its position for a
        // long evening, and hiding a correct title would be the worse error. Six
        // hours clears that while still catching the two-day case.
        //
        // Deliberately not treating `state` as the signal: this same entity read
        // `paused` while audio was demonstrably playing, so state is exactly as
        // stale as the record it comes from.
        val staleAfterMs = config.int("stale_after_minutes", 360).coerceAtLeast(1) * 60_000L
        val reportedAt = e?.attrString("media_position_updated_at")
        val stale = reportedAt?.let { ts ->
            runCatching {
                // OffsetDateTime, NOT Instant.parse. Instant.parse uses
                // ISO_INSTANT, which insists on a trailing `Z` and throws on the
                // `+00:00` offset Home Assistant actually emits
                // ("2026-08-14T20:39:36.729036+00:00"). That threw on every
                // single call, runCatching swallowed it, and the card went on
                // showing two-day-old titles while looking like the check worked.
                val t = ts.trim().replace(" ", "T")
                val instant = runCatching { java.time.OffsetDateTime.parse(t).toInstant() }
                    .getOrElse { java.time.Instant.parse(t) }
                System.currentTimeMillis() - instant.toEpochMilli() > staleAfterMs
            }.getOrDefault(false) // fail OPEN: a parse we cannot do must not hide a good card
        } ?: false

        // Collapse the full card entirely when nothing is actually playing.
        //
        // A missing media title is the reliable signal. Requiring the artwork to
        // be missing too does not work: the Kaleidescape keeps serving a
        // placeholder cover while idle, so `entity_picture` is never null and
        // the card stayed on screen as a purple square captioned with the
        // device's own name — the friendly-name fallback below standing in for
        // a title that was never there.
        if (full && (realTitle == null || stale)) return

        // The compact variant has no collapse path -- it is a persistent row --
        // so it degrades to the device's own name instead of vanishing. Showing
        // "Master Bedroom" is honest; showing last week's episode is not.
        val title = (if (stale) null else realTitle) ?: e?.friendlyName ?: entityId
        val artist = if (stale) e?.attrString("app_name") else (
            e?.attrString("media_artist")
                ?: e?.attrString("media_series_title")
                ?: e?.attrString("app_name")
        )

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
                        // Named action buttons fire scripts, so nothing on this
                        // card necessarily changes when they land -- the same
                        // reason the shade buttons needed acknowledging.
                        val (press, click) = rememberPressFeedback { fireService(ctx, b) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ackColor(Color(0x662C4C58), press)) // semi-transparent
                                .pressFeedback(press, click),
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
        // Transport goes out to an Apple TV or the Kaleidescape and the card only
        // repaints once that device reports back, which is a beat later than the
        // press. It is the same "did that register" gap the shades have, just
        // shorter -- and pressing play twice is worse than pressing it late.
        val (press, click) = rememberPressFeedback(onClick)
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(ackColor(if (accent) Color(0xFF4C6EF5) else Color(0x552C4C58), press))
                .pressFeedback(press, click),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }
}

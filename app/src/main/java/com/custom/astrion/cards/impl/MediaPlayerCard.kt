package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.graphics.Brush
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
import com.custom.astrion.cards.LauncherButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import java.time.Instant
import kotlinx.coroutines.delay
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
/**
 * Process-wide album art cache, keyed on `entity_picture`.
 *
 * The art used to live only in the card's `remember`, which is discarded the
 * moment the card leaves composition -- so every return to the dock index
 * re-fetched the poster over the network and re-ran the downscale, and you
 * watched it arrive. Nothing was wrong with the fetch; the result simply had
 * nowhere to survive.
 *
 * Both halves are cached. The downscaled copy matters as much as the original:
 * it is a filtered scale of a full-size poster, and redoing it on every swipe
 * is work this device has no headroom for.
 *
 * Bounded and access-ordered (a real LRU) rather than a plain map. A poster at
 * full size is a couple of MB, the key changes whenever the artwork does, and an
 * unbounded map would grow for as long as the app runs -- which on a remote that
 * is never restarted means forever. Four is enough for what you actually swipe
 * between; the blurred copies are 32px wide and cost nothing.
 */
private object ArtCache {
    private const val MAX_FULL = 4
    private const val MAX_BLUR = 8

    private fun lru(max: Int) = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) =
            size > max
    }

    private val full = lru(MAX_FULL)
    private val blur = lru(MAX_BLUR)

    @Synchronized fun getFull(key: String): ImageBitmap? = full[key]
    @Synchronized fun putFull(key: String, v: ImageBitmap) { full[key] = v }
    @Synchronized fun getBlur(key: String): ImageBitmap? = blur[key]
    @Synchronized fun putBlur(key: String, v: ImageBitmap) { blur[key] = v }
}

class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val full = config.string("variant") == "full"
        // A one-line strip: art, what's on, and the app launcher. No transport --
        // the remote has real buttons for that, and on the tablet the D-pad
        // does it. This is a STATUS row you glance at, not a control surface,
        // which is also why it does not hide itself when nothing is playing.
        val strip = config.string("variant") == "strip"
        // "header" is the strip re-cut to BE the page header rather than to sit
        // under one. Same resolution logic above -- the staleness check in
        // particular is subtle enough that a second copy of it would drift --
        // only the arrangement below differs.
        val header = config.string("variant") == "header"
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
        // Only the FULL card hides when idle -- a big now-playing panel with
        // nothing playing is dead space. The strip is the opposite: it is the
        // row that tells you the source is up at all, so it must survive an
        // idle player and say so.
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

        // Seeded from the cache, so a cached poster is on screen in the FIRST
        // frame rather than after a round trip that happens to be quick.
        var art by remember(artPath) {
            mutableStateOf(artPath?.let { ArtCache.getFull(it) })
        }
        LaunchedEffect(artPath) {
            val path = artPath ?: return@LaunchedEffect
            if (art != null) return@LaunchedEffect
            art = ctx.client.fetchBitmap(path)?.also { ArtCache.putFull(path, it) }
        }

        // Downscale off the main thread: this ran inside remember{}, i.e. during
        // composition on the UI thread — a filtered scale of full-size album art.
        var blurredBg by remember(art) {
            mutableStateOf(artPath?.let { ArtCache.getBlur(it) })
        }
        LaunchedEffect(art) {
            if (blurredBg != null) return@LaunchedEffect
            val path = artPath
            blurredBg = art?.let { img ->
                withContext(Dispatchers.Default) {
                    val src = img.asAndroidBitmap()
                    if (src.width <= 0) return@withContext null
                    val w = 32
                    val h = (w * src.height / src.width).coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
                }
            }?.also { if (path != null) ArtCache.putBlur(path, it) }
        }

        val mp: (String, Array<out Pair<String, Any?>>) -> Unit =
            remember(entityId) {
                { service, data ->
                    ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
                }
            }

        // The card, then the bar UNDER it. Inside the card the bar sat over the
        // blurred artwork and read as part of the poster; it is a separate piece
        // of information about the card, so it lives below it.
        Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The header is full-bleed: it runs to the screen edges and
                // carries the clock and battery on top of it, so a rounded card
                // with a margin would just reintroduce the frame it replaces.
                .then(if (header) Modifier else Modifier.clip(RoundedCornerShape(20.dp)))
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

            when {
                full -> FullContent(ctx, title, artist, playing, art, mp, topButtons, reverseBtn, forwardBtn)
                strip -> StripContent(config, ctx, realTitle, title, artist, art)
                header -> HeaderContent(config, ctx, entityId, realTitle, title, artist, art)
                else -> CompactContent(title, artist, art, mp)
            }
        }
        // NOT the header's: that one is drawn inside the card, so the blurred
        // artwork runs behind it instead of stopping at a hard edge above it.
        if (strip) ProgressBar(ctx, entityId)
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

    /**
     * The compact status strip: cover, what is on, and the launcher.
     *
     * Roughly a third the height of the full card, which is the point -- on a
     * 480x800 remote the full panel pushed the Watch section off the screen for
     * something you only glance at.
     */
    /**
     * The now-playing row AS the page header, with the clock and battery drawn
     * over it rather than on a bar of their own.
     *
     * The point is vertical space. The old arrangement stacked three things --
     * status bar, then a now-playing card, then the buttons -- on a 800px screen
     * where the buttons are the reason the view exists. Folding the first into
     * the second gives that height back to the grid.
     *
     * The clock and battery genuinely overlap this row -- they are not given a
     * reserved strip -- which is only safe because of the scrim below. The row
     * can be backed by album art of any brightness, and the first cut put the
     * clock straight over a film poster: readable against that one, a coin flip
     * against the next. A gradient across the top few dp costs nothing and makes
     * the overlap unconditional, which is what lets `top_inset` be small instead
     * of being a reserved bar wearing a different name.
     */
    @Composable
    @Suppress("UNCHECKED_CAST")
    private fun HeaderContent(
        config: CardConfig,
        ctx: CardContext,
        entityId: String?,
        realTitle: String?,
        title: String,
        artist: String?,
        art: ImageBitmap?,
    ) {
        val artSize = config.int("art_size", 48).dp
        // Row and timeline in ONE column, so the blurred backdrop behind them
        // covers both. They used to be siblings either side of the card's edge,
        // which put a hard horizontal seam across the header exactly where the
        // artwork was still going.
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    // 14dp was too greedy and the screenshot showed it: the
                    // clock landed on the poster and the battery sat on the
                    // launcher button. The clock is ~38dp tall, so anything less
                    // than that puts content in both corners. The height this
                    // costs back is taken off the progress row instead, which
                    // was spending 33dp on two timestamps.
                    //
                    // The extra 6dp on top comes straight off the bottom, so the
                    // row keeps its height and the band, the grid and everything
                    // below stay exactly where they are -- the CONTENT moves
                    // down toward the timeline, the background does not move at
                    // all. Artwork and title now group with the bar they belong
                    // to instead of floating midway between it and the clock.
                    top = config.int("top_inset", 46).dp,
                    bottom = config.int("bottom_inset", 4).dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artMod = Modifier.size(artSize).clip(RoundedCornerShape(8.dp))
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(Color(0xFF24404A)))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (realTitle != null) title else "Nothing playing",
                    color = if (realTitle != null) Color(0xFFF1F4FA) else Color(0xFF93AFB6),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    // ONE line, always, and it SCROLLS when it does not fit.
                    //
                    // The header has to be the same height in every room,
                    // because the buttons below are positioned from its bottom
                    // edge -- and it was not. The Kaleidescape publishes a title
                    // and nothing else; an Apple TV publishes a long title AND
                    // an app name that becomes the subtitle. Three lines of text
                    // beat the artwork for height and pushed that room's grid
                    // 33px down.
                    //
                    // Capping at one line fixes the height for free: with a
                    // subtitle the text is two lines, without one it is one, and
                    // either way the 48dp artwork is at least as tall -- so the
                    // artwork sets the height in every case. Marquee then gives
                    // the long title back rather than ellipsing it, which is the
                    // better trade in a row you look at repeatedly.
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee(
                        // Enough passes to read a film title, then it settles.
                        // Endless motion beside a grid of buttons is a
                        // distraction, not information.
                        iterations = 3,
                        initialDelayMillis = 1200,
                        repeatDelayMillis = 2000,
                    ),
                )
                if (!artist.isNullOrBlank()) {
                    Text(artist, color = Color(0xFFB6BECC), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LauncherButton(
                config.options["launcher"] as? Map<String, Any?>,
                ctx,
                size = config.int("button_size", 48).dp,
            )
        }
        // The artwork fades to the page colour across the timeline rather than
        // being cut off above it. Two jobs at once: the header stops having a
        // visible bottom edge, and the two timestamps get a background that is
        // solid where they sit instead of whatever the poster happened to be.
        //
        // A gradient, not a flat scrim -- flat would reinstate the same seam a
        // few dp lower.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x000E2229), Color(0xE60E2229), Color(0xFF0E2229)),
                    )
                )
        ) {
            ProgressBar(ctx, entityId, inlineLabels = true)
        }
        }
        // AFTER the row, deliberately: the thing the clock most often lands on
        // is the album art thumbnail at the left, so a scrim drawn UNDER the row
        // dims the blurred backdrop and leaves the bright poster corner exactly
        // where the problem is. Drawn last, it covers everything the clock and
        // battery can overlap. It is only the top 40dp, so the artwork below is
        // untouched.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(config.int("scrim_height", 40).dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xB3000000), Color(0x00000000)),
                    )
                )
        )
    }

    @Composable
    @Suppress("UNCHECKED_CAST")
    private fun StripContent(
        config: CardConfig,
        ctx: CardContext,
        realTitle: String?,
        title: String,
        artist: String?,
        art: ImageBitmap?,
    ) {
        val artSize = config.int("art_size", 68).dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Square, not the compact row's circle: this is cover art and a
            // channel logo, both of which get cropped into nonsense by a circle.
            val artMod = Modifier.size(artSize).clip(RoundedCornerShape(10.dp))
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(Color(0xFF24404A)))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    // Idle still says something useful -- the source is on, it
                    // just has nothing playing. Blank here would read as broken.
                    if (realTitle != null) title else "Nothing playing",
                    color = if (realTitle != null) Color(0xFFF1F4FA) else Color(0xFF93AFB6),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Text(artist, color = Color(0xFFB6BECC), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Sizeable so the dock can run a bigger target than the in-hand
            // page: same row, but read and pressed from across a room.
            LauncherButton(
                config.options["launcher"] as? Map<String, Any?>,
                ctx,
                size = config.int("button_size", 44).dp,
            )
        }
    }

    /**
     * Elapsed / remaining, when the source publishes it.
     *
     * Drawn only when there is a real duration -- an Apple TV app that reports
     * nothing gets no bar rather than a permanently empty one, which would read
     * as "stuck at zero" instead of "not reported".
     *
     * The position is INTERPOLATED. HA updates media_position only every few
     * seconds (and the Kaleidescape only on state changes), so a bar drawn
     * straight from the attribute jumps in visible steps; adding the time since
     * media_position_updated_at makes it move like a progress bar should.
     */
    @Composable
    private fun ProgressBar(
        ctx: CardContext,
        entityId: String?,
        /**
         * Put the timestamps BESIDE the bar instead of under it.
         *
         * Same information, one row instead of two. Under the bar they cost the
         * header 33dp on the screen whose entire problem is height; beside it
         * they cost nothing vertical and only shorten a bar whose precision was
         * never the point. The strip keeps them underneath, where the card has
         * the room and the full-width bar lines up with the card edges.
         */
        inlineLabels: Boolean = false,
    ) {
        val e = entityId?.let { ctx.entities[it] } ?: return
        val duration = e.attrDouble("media_duration")?.takeIf { it > 0 } ?: return
        val reported = e.attrDouble("media_position") ?: return
        val playing = e.state == "playing"

        // `now` is state, ticked once a second while playing -- reading it is
        // what re-renders the bar between HA's sparse position updates.
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(playing, reported) {
            while (playing) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        val updatedAt = e.attrString("media_position_updated_at")
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val drift = if (playing && updatedAt != null) (now - updatedAt).coerceAtLeast(0L) / 1000.0 else 0.0
        val pos = (reported + drift).coerceIn(0.0, duration)

        // Top padding only. A bottom pad here double-counts with the page's own
        // card spacing, which made the gap under the bar 6dp wider than every
        // other card-to-section gap -- so the divider after it sat visibly
        // lower than the ones further down the page.
        val bar = @Composable { mod: Modifier ->
            Box(
                mod.height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    Modifier.fillMaxWidth((pos / duration).toFloat()).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4C8DFF)),
                )
            }
        }
        if (inlineLabels) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(clock(pos), color = Color(0xFF93AFB6), fontSize = 11.sp)
                // The bar gives up exactly the width the two timestamps need.
                bar(Modifier.weight(1f))
                Text("-" + clock(duration - pos), color = Color(0xFF93AFB6), fontSize = 11.sp)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp)) {
                bar(Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(clock(pos), color = Color(0xFF93AFB6), fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("-" + clock(duration - pos), color = Color(0xFF93AFB6), fontSize = 11.sp)
                }
            }
        }
    }

    /** h:mm:ss, dropping the hour when there isn't one. */
    private fun clock(seconds: Double): String {
        val t = seconds.toLong().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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
            // Uncapped keeps the 1.2 aspect ratio (the remotes). Capped fixes the
            // height instead and lets ContentScale.Crop take the difference,
            // which is what keeps the transport keys above the fold in a wide
            // lane -- an aspect-ratio'd art there is taller than the card.
            val cap = ctx.mediaArtMaxHeight
            val artMod = Modifier
                .fillMaxWidth()
                .then(if (cap > 0) Modifier.height(cap.dp) else Modifier.aspectRatio(1.2f))
                .clip(RoundedCornerShape(16.dp))
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
        val (press, click) = rememberPressFeedback(onClick = onClick)
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

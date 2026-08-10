package com.custom.astrion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.custom.astrion.BuildConfig
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.impl.ConditionalCard
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.OverlayConfig
import com.custom.astrion.voice.VoiceOverlay
import com.custom.astrion.voice.VoiceState
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import androidx.compose.runtime.snapshotFlow
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.launch

/**
 * Swipeable, paginated dashboard. Each config page is a horizontally-swipeable
 * screen; a row of dots at the bottom shows how many pages there are and which
 * one you're on. Swipe left/right to move between them (Lights ← Main → TV by
 * default), or jump via a physical shortcut button (see MainActivity hotkeys).
 *
 * Sized for the HA100 panel (480x800, portrait). Each page scrolls vertically
 * on its own; the pager stays light for the 1GB / MT6580 hardware.
 */
@Composable
fun Dashboard(
    client: HaClient,
    entitiesState: State<EntityMap>,
    connectionState: State<ConnectionState>,
    config: AppConfig,
    configNotice: String? = null,
    /** Page index requested by a hardware button; consumed via onNavHandled. */
    navTarget: Int? = null,
    onNavHandled: () -> Unit = {},
    /** Section (separator name) to scroll the current page to; consumed via onScrollHandled. */
    scrollTarget: String? = null,
    onScrollHandled: () -> Unit = {},
    /** Section a hardware key asked to "open" (auto-pops a sole selector); consumed via onOpenHandled. */
    openTarget: String? = null,
    onOpenHandled: () -> Unit = {},
    /** Voice indicator state; the VOICE key drives it via VoiceSession. */
    voiceState: VoiceState = VoiceState.Idle,
    onVoiceDismiss: () -> Unit = {},
    /** Invoked when the user taps Sync in the swipe-up info panel. */
    onSync: () -> Unit = {},
    /** Live HA base URL (from ConnectionConfig, not BuildConfig) for the info panel. */
    haUrl: String = "",
    /** Non-null while the setup web server is listening (its browsable URL). */
    setupUrl: String? = null,
    /** Toggles the setup web server from the info panel. */
    onSetup: () -> Unit = {},
) {
    val connection by connectionState
    val scope = rememberCoroutineScope()
    val gestureContext = LocalContext.current

    // PERFORMANCE: deliberately do NOT read the entity map here. Reading it in
    // this scope would recompose the entire dashboard (every card, both pages)
    // on every publish tick. Instead the flow is drained OUTSIDE composition into
    // a SnapshotStateMap, which Compose tracks per key — so an entity change
    // recomposes only the cards that actually read that entity.
    val entityMap = remember { mutableStateMapOf<String, EntityState>() }
    LaunchedEffect(entitiesState) {
        snapshotFlow { entitiesState.value }.collect { snapshot ->
            // Touch only what actually changed; EntityState is a data class, so
            // equality is by value and unchanged entities never notify readers.
            for ((id, state) in snapshot) {
                if (entityMap[id] != state) entityMap[id] = state
            }
            if (entityMap.size != snapshot.size) {
                entityMap.keys.retainAll(snapshot.keys)
            }
        }
    }

    // Stable identity: allocating a new context per update was what stopped any
    // card from skipping. openTarget rides in a State so it can change without
    // changing this instance.
    val openTargetState = rememberUpdatedState(openTarget)
    val ctx = remember(client) { CardContext(entityMap, client, openTargetState) }

    // Give the sole-selector-in-section bubble a moment to see the open request,
    // then clear it so the next keypress re-triggers.
    LaunchedEffect(openTarget) {
        if (openTarget != null) {
            kotlinx.coroutines.delay(500)
            onOpenHandled()
        }
    }

    val pageCount = config.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = config.startPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )

    // Hardware-button navigation: animate to the requested page, then clear it.
    LaunchedEffect(navTarget) {
        val t = navTarget ?: return@LaunchedEffect
        if (t in 0 until pageCount) pagerState.animateScrollToPage(t)
        onNavHandled()
    }

    var showInfo by remember { mutableStateOf(false) }

    // Nothing to show until the app knows which HA to talk to: on a fresh
    // install, put the setup server's address + port on screen so it can be
    // provisioned from any browser on the LAN (no adb).
    if (haUrl.isBlank()) {
        SetupScreen(setupUrl)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E2229)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Above the banners on purpose: the clock and battery should hold
            // the same spot whether or not a connection banner is showing.
            StatusBar(onSwipeDown = config.gestures?.swipeDown?.let { g ->
                {
                    // Named actions only -- the layout arrives over the network,
                    // so "launch any intent" would be a far wider door than this
                    // needs. Wrapped because a missing Settings activity should
                    // do nothing, not take the dashboard down.
                    runCatching {
                        val intent = when (g.action) {
                            "app_info" -> Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${g.packageName}"),
                            )
                            else -> Intent(Settings.ACTION_SETTINGS)
                        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        gestureContext.startActivity(intent)
                    }
                    Unit
                }
            })
            ConnectionBanner(connection)
            if (configNotice != null) ConfigNoticeBanner(configNotice)

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { pageIndex ->
                // Only the settled/current page acts on a scroll request; other
                // (pre-composed) pages get null so they don't consume it early.
                PageContent(
                    config.pages[pageIndex],
                    ctx,
                    if (pageIndex == pagerState.currentPage) scrollTarget else null,
                    onScrollHandled,
                )
            }

            PageIndicator(
                pages = config.pages,
                current = pagerState.currentPage,
                onDotClick = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                onSwipeUp = { showInfo = true },
            )
        }

        config.overlay?.let { VolumeOverlay(it, ctx) }

        // Prompts are gated on an entity so they can be specific to what is on
        // (movie searches in front of the Kaleidescape, nothing elsewhere). No
        // gate entity configured means always show them. Reading the one key
        // here keeps the per-key observation intact -- see CardContext.
        val voiceCfg = config.voice
        val prompts = when {
            voiceCfg == null || voiceCfg.suggestions.isEmpty() -> emptyList()
            voiceCfg.suggestEntity == null -> voiceCfg.suggestions
            ctx.entities[voiceCfg.suggestEntity]?.state == voiceCfg.suggestState ->
                voiceCfg.suggestions
            else -> emptyList()
        }
        VoiceOverlay(
            voiceState,
            onVoiceDismiss,
            prompts = prompts,
            promptTitle = voiceCfg?.suggestTitle ?: "Try saying",
        )

        if (showInfo) {
            InfoSheet(
                haUrl = haUrl,
                setupUrl = setupUrl,
                onSetup = onSetup,
                onSync = { onSync(); showInfo = false },
                onDismiss = { showInfo = false },
            )
        }
    }
}

/**
 * Transient volume / mute readout — the remote's own OSD.
 *
 * Driven by whatever entity the volume buttons step IMMEDIATELY (an input_number
 * the scripts write), not the media player's own volume_level: the speaker's
 * state feedback lags by enough to make the overlay feel broken.
 *
 * Deliberately does not appear on first composition — only on an actual change —
 * so opening the app doesn't flash it.
 */
@Composable
private fun BoxScope.VolumeOverlay(cfg: OverlayConfig, ctx: CardContext) {
    // Per-key reads: only this overlay recomposes when these change.
    val vol = cfg.volumeEntity?.let { ctx.entities[it]?.state?.toFloatOrNull() }
    val muted = cfg.muteEntity?.let { ctx.entities[it]?.attr("is_volume_muted") }
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() }

    var visible by remember { mutableStateOf(false) }
    var lastVol by remember { mutableStateOf<Float?>(null) }
    var lastMuted by remember { mutableStateOf<Boolean?>(null) }
    var seeded by remember { mutableStateOf(false) }

    // Re-read every recomposition so the gate tracks its entity live. Not a
    // LaunchedEffect key: the OSD should never appear merely because the gate
    // opened -- only because the volume or mute actually moved.
    val allowed = cfg.condition?.let { ConditionalCard.matches(it, ctx) } ?: true

    // The gate closing must also take down anything already on screen. Switching
    // the system off mutes the speaker and then settles the volume a moment
    // later, so the sequence is: mute arrives while still allowed and shows the
    // overlay, THEN the activity goes Off, THEN the volume lands -- restarting
    // the effect below and cancelling the coroutine that owed us a hide.
    LaunchedEffect(allowed) { if (!allowed) visible = false }

    LaunchedEffect(vol, muted) {
        val prevV = lastVol
        val prevM = lastMuted
        lastVol = vol
        lastMuted = muted
        if (!seeded) { seeded = true; return@LaunchedEffect }   // first values: don't flash
        if (vol == prevV && muted == prevM) return@LaunchedEffect
        // Bail AFTER recording the new values, never before: a suppressed change
        // must be absorbed, or it would read as a fresh change and flash the
        // moment the gate reopens -- showing "Muted" when you switch back on,
        // which is the exact thing this suppresses.
        //
        // And clear on the way out. Returning here without hiding is what left
        // the overlay stuck on: the previous invocation was cancelled mid-delay
        // (so its hide never ran) and this one returned before reaching one.
        if (!allowed) { visible = false; return@LaunchedEffect }
        // try/finally because this coroutine is cancelled every time the volume
        // moves again -- without it, each cancellation drops the hide on the
        // floor and only the LAST change in a burst is ever cleaned up.
        try {
            visible = true
            kotlinx.coroutines.delay(1500)
        } finally {
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(140), initialScale = 0.88f),
        exit = fadeOut(tween(220)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val pct = (vol ?: 0f).coerceIn(0f, 100f)
        val isMuted = muted == true
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE60E2229))
                .padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = if (isMuted) Color(0xFFE08A8A) else Color(0xFF7FD8F0),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (isMuted) "Muted" else "${pct.toInt()}%",
                color = Color(0xFFF1F4FA),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            // Level bar — dimmed while muted so the value is still readable.
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF2A4954)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((pct / 100f).coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isMuted) Color(0xFF41606B) else Color(0xFF2E7D95)),
                )
            }
        }
    }
}

/** First-run screen: tells you where to point a browser to configure the remote. */
@Composable
private fun SetupScreen(setupUrl: String?) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0E2229)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Astrion Custom", color = Color(0xFFF1F4FA), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Not connected to Home Assistant yet.\nOpen this address in a browser on your network:",
                color = Color(0xFF93AFB6),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16303A))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    setupUrl ?: "starting…",
                    color = Color(0xFF7FD8F0),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Enter the HA URL and a long-lived access token.",
                color = Color(0xFF93AFB6),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Color(0xFF5E7C86), fontSize = 12.sp)
        }
    }
}

/**
 * Swipe-up info panel: current build, the HA endpoint, and a manual Sync button.
 * Dismisses on scrim tap. Replaces the previous sync-on-every-resume behaviour.
 */
@Composable
private fun InfoSheet(
    haUrl: String,
    setupUrl: String?,
    onSetup: () -> Unit,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF16303A))
                // Absorb taps so they don't fall through to the dismiss scrim.
                .clickable(enabled = false) {}
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF33525E)),
            )
            Text("Astrion Custom", color = Color(0xFFF1F4FA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InfoRow("Build", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Home Assistant", haUrl.ifBlank { "not configured" })
            if (setupUrl != null) InfoRow("Setup page", setupUrl)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2E7D95))
                    .clickable(onClick = onSync),
                contentAlignment = Alignment.Center,
            ) {
                Text("Sync dashboard", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            // Opens a LAN web form for the HA URL + token (see ConfigServer), so
            // credentials never need to be compiled in or pushed over adb.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2A4954))
                    .clickable(onClick = onSetup),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (setupUrl == null) "Open connection setup" else "Close setup",
                    color = Color(0xFFE6F0F1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF93AFB6), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFE6F0F1), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PageContent(
    page: PageConfig,
    ctx: CardContext,
    scrollTarget: String? = null,
    onScrollHandled: () -> Unit = {},
) {
    // Cards with options["pin"] == "bottom" float at the bottom, always visible;
    // the rest scroll above them.
    val pinned = remember(page) { page.cards.filter { it.options["pin"] == "bottom" } }
    val scrolling = remember(page) { page.cards.filter { it.options["pin"] != "bottom" } }

    val listState = rememberLazyListState()
    // Hardware "scroll to section": scroll so the matching separator sits at the
    // top. Only the page that actually has the section consumes the request.
    LaunchedEffect(scrollTarget) {
        val name = scrollTarget ?: return@LaunchedEffect
        val idx = scrolling.indexOfFirst {
            it.type == "separator" &&
                (it.options["name"] as? String)?.equals(name, ignoreCase = true) == true
        }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            onScrollHandled()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Index keys: the card list only changes when the layout syncs, and
            // they keep LazyColumn reusing slots instead of re-composing on the
            // list's identity.
            // Only lay out cards that will actually draw something. A hidden
            // `conditional` still occupies a slot, and spacedBy(10.dp) puts a
            // gap around every slot -- so the three conditionals stacked above
            // the first separator on Main left ~30dp of hole between the status
            // bar and "Watch" whenever nothing was playing. Filtering here (not
            // inside the card) is what removes the gap as well as the content.
            val visible = scrolling.filter {
                it.type != "conditional" || ConditionalCard.matches(it, ctx)
            }
            items(visible.size, key = { it }) { i ->
                RenderCard(visible[i], ctx)
            }
        }
        if (pinned.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF13262D))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pinned.forEach { RenderCard(it, ctx) }
            }
        }
    }
}

@Composable
private fun RenderCard(cardConfig: CardConfig, ctx: CardContext) {
    val renderer = CardRegistry.get(cardConfig.type)
    if (renderer != null) {
        renderer.Render(cardConfig, ctx)
    } else {
        UnknownCard(cardConfig.type)
    }
}

@Composable
private fun PageIndicator(
    pages: List<PageConfig>,
    current: Int,
    onDotClick: (Int) -> Unit,
    onSwipeUp: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Swipe up anywhere along the bottom bar to open the info/sync panel.
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    if (dragAmount < -6f) {
                        change.consume()
                        onSwipeUp()
                    }
                }
            }
            .padding(top = 4.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No grab-handle affordance: the swipe-up gesture still works anywhere
        // along this bar, it just isn't advertised.
        Row(verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, _ ->
                val active = i == current
                Box(
                    modifier = Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) Color(0xFF6EA8FE) else Color(0xFF33525E))
                        .clickable { onDotClick(i) },
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                pages.getOrNull(current)?.name ?: "",
                color = Color(0xFF93AFB6),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    if (connection == ConnectionState.CONNECTED) return
    val (label, color) = when (connection) {
        ConnectionState.CONNECTING,
        ConnectionState.AUTHENTICATING -> "Connecting…" to Color(0xFF3A506B)
        ConnectionState.AUTH_FAILED -> "Auth failed — check token" to Color(0xFF7A2E2E)
        ConnectionState.ERROR -> "Connection error — retrying" to Color(0xFF7A2E2E)
        else -> "Disconnected" to Color(0xFF33525E)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ConfigNoticeBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF4A3B1E))
            .padding(10.dp),
    ) {
        Text(text, color = Color(0xFFE8C77B), fontSize = 12.sp)
    }
}

@Composable
private fun UnknownCard(type: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2030))
            .padding(14.dp),
    ) {
        Text("Unknown card type: \"$type\"", color = Color(0xFFE0A0A0), fontSize = 13.sp)
    }
}

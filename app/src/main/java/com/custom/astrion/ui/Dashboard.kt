package com.custom.astrion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.custom.astrion.BuildConfig
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.impl.ConditionalCard
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.mergedWith
import com.custom.astrion.config.OverlayConfig
import com.custom.astrion.voice.VoiceOverlay
import com.custom.astrion.voice.VoiceState
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import androidx.compose.runtime.snapshotFlow
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.input.HardwareKey
import kotlin.math.roundToInt
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
    /** The page the pager has SETTLED on; MainActivity uses it to scope hotkeys. */
    onPageChange: (Int) -> Unit = {},
    /** True while the remote is on power; pages with `dock_cards` swap to them. */
    docked: Boolean = false,
    /** Swipe-up panel visibility, owned by MainActivity (see the note below). */
    settingsOpen: Boolean = false,
    onSettingsOpen: (Boolean) -> Unit = {},
    /** Full-screen Status view visibility, likewise owned by MainActivity. */
    statusOpen: Boolean = false,
    onStatusOpen: (Boolean) -> Unit = {},
    /** Invoked when the user taps Sync in the swipe-up info panel. */
    onSync: () -> Unit = {},
    /** Live HA base URL (from ConnectionConfig, not BuildConfig) for the info panel. */
    haUrl: String = "",
    /** This remote's configured name, shown in the info panel. */
    deviceName: String = "",
    /** Network-adb state for the info panel; computed by MainActivity. */
    adbStatus: String = "",
    /** Non-null while the setup web server is listening (its browsable URL). */
    setupUrl: String? = null,
    /** Toggles the setup web server from the info panel. */
    onSetup: () -> Unit = {},
    /** True while the input bridge is connected (screen-off keys available). */
    bridgeConnected: Boolean = false,
    /** The exact adb command that starts the bridge, apk path resolved. */
    bridgeCommand: String = "",
    /** Fires a logical hardware key; an on-screen `dpad` card routes through it. */
    onHardwareKey: (HardwareKey) -> Unit = {},
    /** Correct exit PIN entered: MainActivity drops out of lock task. */
    onKioskExit: () -> Unit = {},
    /** Whether the OEM launcher is currently allowed to run. */
    stockAllowed: Boolean = false,
    onStockAllowedChange: (Boolean) -> Unit = {},
    /** How many times the watchdog has force-stopped it, and when last. */
    stockStops: Int = 0,
    stockStopAt: Long = 0L,
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
    val onKeyState = rememberUpdatedState(onHardwareKey)
    val ctx = remember(client, config.ui) {
        // The lambda is routed through a State so a new callback identity does
        // not change the context's identity -- the whole reason this is
        // remembered (see CardContext's note on skipping).
        CardContext(
            entityMap,
            client,
            openTargetState,
            onHardwareKey = { key -> onKeyState.value(key) },
            mediaArtMaxHeight = config.ui.mediaArtMaxHeight,
        )
    }

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

    // settledPage, NOT currentPage: currentPage flips the moment a drag crosses
    // the snap threshold, so a half-swipe that springs back would momentarily
    // repoint the physical keys at another room -- a volume press landing on the
    // wrong speaker because a thumb wobbled. rememberUpdatedState so the
    // collector is not restarted by every recomposition handing it a fresh
    // lambda (the same idiom as openTargetState below).
    val onPageChangeState = rememberUpdatedState(onPageChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect {
            // Swiping to another room lands on THAT room's top-level grid. Both
            // room cards read the same key, so one write resets both -- and it
            // stops the dots from staying hidden because the page you left had
            // a submenu open.
            com.custom.astrion.cards.impl.DockMenuState.path = emptyList()
            onPageChangeState.value(it)
        }
    }

    // Overlay visibility is HOISTED to MainActivity, not remembered here.
    //
    // It has to be: dispatchKeyEvent resolves BACK against the key router BEFORE
    // Android ever calls onBackPressed(), and BACK *is* bound (to the AV back
    // action), so it is consumed there and a BackHandler in this composable
    // would never run -- BACK would navigate the Apple TV while a sheet sat open
    // on the remote. The activity needs to know an overlay is up to intercept it.
    val showSettings = settingsOpen
    val showStatus = statusOpen

    // Still registered, for the case where BACK is NOT bound as a hotkey: then
    // dispatchKeyEvent falls through to super and this is what closes the sheet.
    BackHandler(enabled = showStatus) { onStatusOpen(false) }
    BackHandler(enabled = showSettings && !showStatus) { onSettingsOpen(false) }

    // Nothing to show until the app knows which HA to talk to: on a fresh
    // install, put the setup server's address + port on screen so it can be
    // provisioned from any browser on the LAN (no adb).
    if (haUrl.isBlank()) {
        SetupScreen(setupUrl)
        return
    }

    // Shrink the WHOLE dashboard by overriding the density rather than by
    // giving cards tablet-specific sizes. Density drives both dp and sp, so one
    // number keeps every proportion the design already has -- padding, corner
    // radii, icon sizes, type -- and no card has to know what it is running on.
    val baseDensity = LocalDensity.current
    val scaled = remember(baseDensity, config.ui.scale) {
        Density(baseDensity.density * config.ui.scale, baseDensity.fontScale)
    }
    // The exit hatch lives in the swipe-up panel rather than behind a secret
    // tap count: the panel already IS the maintenance surface (build, sync,
    // setup server), it is where someone servicing the tablet is already
    // looking, and it takes a deliberate edge gesture to reach -- which is the
    // same protection a hidden gesture offered, without being undiscoverable
    // to whoever inherits this.
    var showExit by remember { mutableStateOf(false) }
    if (showExit) {
        KioskExitDialog(
            pinEntity = config.ui.kioskPinEntity,
            ctx = ctx,
            onUnlock = { showExit = false; onKioskExit() },
            onDismiss = { showExit = false },
        )
    }

    CompositionLocalProvider(LocalDensity provides scaled) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E2229)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(config.ui.padding.dp)) {
            // Above the banners on purpose: the clock and battery should hold
            // the same spot whether or not a connection banner is showing.
            // One clock setting, not two. `screensaver.clock_24h` already exists and
            // is per-device; the status bar had an is24Hour parameter that nothing
            // ever passed, so it stayed 12-hour while the idle clock went 24-hour
            // and the same remote showed both formats.
            // The page's header card, with the clock and battery ON TOP of it.
            //
            // A dock card marked `dock_header: true` is hoisted out of the
            // scrolling list and drawn here instead. Nothing else changes about
            // it -- it is an ordinary card, conditionals and all -- but it now
            // occupies the band the status bar used to have to itself.
            //
            // Why a Box rather than a taller StatusBar: the card decides its own
            // height, and the box takes the larger of the two. So when the card
            // is hidden (its conditional not matching, or the page having no
            // header card at all) this collapses to exactly the status bar that
            // was always here, with no empty band left behind. That is also why
            // the status bar keeps its own minimum height -- it is the floor.
            // ALL of them, not the first: the Living Room has one now-playing
            // row per source, each gated on its own player, and taking only the
            // first would have permanently hidden the Kaleidescape's. They are
            // conditionals, so at most one draws; if two ever matched, stacking
            // them is the honest outcome rather than silently dropping one.
            // Index only. A submenu is a full grid of its own and wants every
            // row it can get -- which was the original reason the now-playing
            // strip carried `dock_index_only`. Moving it into the header made it
            // cheap enough to leave on everywhere, but cheap is not free: it
            // still costs a submenu the band, and you are looking at a menu, not
            // at what is playing.
            // Index only.
            //
            // It was briefly shown during a back-swipe as well, so the player
            // would not appear to load late. That was the wrong fix for the
            // right complaint: this Box is ABOVE the pager, so revealing it
            // mid-gesture shrinks the page area and shoves the submenu that is
            // sliding away down by the band height. A layout jump during a drag
            // is worse than a header that arrives at the end of one.
            //
            // The complaint itself is answered elsewhere: the artwork is cached
            // now (see ArtCache in MediaPlayerCard), so the header that appears
            // on commit is drawn complete in its first frame rather than
            // fetching a poster you then watch arrive.
            // Always. The page underneath is ALWAYS the index now -- a submenu
            // is an overlay drawn on top of it (see DockSubmenuOverlay), not a
            // different thing rendered in its place. So the header is never
            // unmounted, and swiping a submenu away uncovers a header that was
            // there the whole time instead of snapping one into existence.
            val headerCards = config.pages.getOrNull(pagerState.currentPage)
                ?.dockCards?.filter { it.options["dock_header"] == true }.orEmpty()
            // A FIXED band on the index, whether or not anything is playing.
            //
            // The buttons are positioned from the bottom of this, so letting it
            // collapse when the room is off moved the entire grid up -- the same
            // six tiles in two different places depending on whether music
            // happened to be on. Reserving the space costs a strip of background
            // in the quiet case and buys a layout that never moves.
            //
            // The status bar is NOT in here any more -- it is drawn at the root,
            // above the submenu overlay, so the clock stays readable while a
            // submenu covers everything else.
            Box(modifier = Modifier.fillMaxWidth().height(HEADER_BAND)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    headerCards.forEach { RenderCard(it, ctx) }
                }
            }
            ConnectionBanner(connection)
            if (configNotice != null) ConfigNoticeBanner(configNotice)

            HorizontalPager(
                state = pagerState,
                // Room-swiping is an INDEX gesture only. Inside a dock submenu a
                // horizontal drag means "go back a level", and the two were
                // fighting: the pager is an ancestor of the card, and
                // Modifier.draggable does NOT participate in nested scroll (only
                // `scrollable` does), so the pager kept winning the gesture --
                // which both swallowed the back-swipe and let you slide into
                // another room mid-menu. Switching the pager off while a submenu
                // is open hands the drag to the card cleanly and fixes both.
                userScrollEnabled = !com.custom.astrion.cards.impl.DockMenuState.inSubmenu,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { pageIndex ->
                // Only the settled/current page acts on a scroll request; other
                // (pre-composed) pages get null so they don't consume it early.
                // Forced to the INDEX. A submenu is an overlay now, so the
                // page beneath it never changes -- which is the whole point:
                // nothing under the overlay moves, appears or disappears while
                // you are in a menu, so backing out reveals rather than rebuilds.
                CompositionLocalProvider(
                    com.custom.astrion.cards.impl.LocalDockPath provides emptyList()
                ) {
                    PageContent(
                        config.pages[pageIndex],
                        ctx,
                        if (pageIndex == pagerState.currentPage) scrollTarget else null,
                        onScrollHandled,
                        docked = docked,
                        columns = config.ui.columns,
                    )
                }
            }

            // The bottom bar is GONE. The room name and dots moved into the
            // status bar's middle, which was empty, so the whole row it used to
            // occupy goes to the buttons instead.
        }

        // The submenu, drawn OVER the index instead of in place of it.
        //
        // Order matters here: this comes after the Column, so it covers the
        // header band and the grid; the status bar comes after IT, so the clock
        // stays readable. Backing out slides this aside and what appears
        // underneath is the index exactly as it was left -- header included,
        // nothing remounted, nothing re-measured.
        val submenuPage = config.pages.getOrNull(pagerState.currentPage)
        if (com.custom.astrion.cards.impl.DockMenuState.inSubmenu &&
            submenuPage != null && submenuPage.dockCards.isNotEmpty()) {
            DockSubmenuOverlay(submenuPage, ctx)
        }

        StatusBar(
            onSwipeDown = config.gestures?.swipeDown?.let { { onSettingsOpen(true) } },
            is24Hour = config.screensaver.forDevice(deviceName).clock24h,
            center = {
                // Hidden inside a submenu: you are not choosing a room there,
                // and the back pill already says where you are.
                if (!com.custom.astrion.cards.impl.DockMenuState.inSubmenu) {
                    RoomIndicator(
                        pages = config.pages,
                        current = pagerState.currentPage,
                        onDotClick = { i ->
                            scope.launch { pagerState.animateScrollToPage(i) }
                        },
                        onOpenPanel = { onSettingsOpen(true) },
                    )
                }
            },
        )

        // Page-effective, for the same reason the voice config below is: the
        // volume keys are page-scoped, so on the bedroom page they move the
        // BEDROOM speaker, and an OSD wired to the living room would either stay
        // silent or announce the wrong room. A page without its own block keeps
        // the global one, so rooms sharing the main speaker need no config.
        val overlayCfg = config.pages.getOrNull(pagerState.currentPage)?.overlay
            ?: config.overlay
        overlayCfg?.let { VolumeOverlay(it, ctx) }

        // Prompts are gated on an entity so they can be specific to what is on
        // (movie searches in front of the Kaleidescape, nothing elsewhere). No
        // gate entity configured means always show them. Reading the one key
        // here keeps the per-key observation intact -- see CardContext.
        // Page-effective: the listening prompts (and the Apple TV the audio is
        // posted to) should belong to the room whose page is showing.
        val voiceCfg = config.voice.mergedWith(
            config.pages.getOrNull(pagerState.currentPage)?.voice
        )
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

        config.gestures?.swipeDown?.takeIf { showSettings }?.let { panel ->
            SettingsSheet(
                panel = panel,
                haUrl = haUrl,
                setupUrl = setupUrl,
                onSetup = onSetup,
                deviceName = deviceName,
                adbStatus = adbStatus,
                statusTitle = config.status?.title,
                onStatus = { onStatusOpen(true) },
                onSync = { onSync(); onSettingsOpen(false) },
                bridgeConnected = bridgeConnected,
                bridgeCommand = bridgeCommand,
                stockAllowed = stockAllowed,
                onStockAllowedChange = onStockAllowedChange,
                stockStops = stockStops,
                stockStopAt = stockStopAt,
                deviceInternals = config.ui.deviceInternals,
                kiosk = config.ui.kiosk,
                onKioskExit = { onSettingsOpen(false); showExit = true },
                onPick = { item ->
                    onSettingsOpen(false)
                    // Launch from the ACTIVITY and WITHOUT FLAG_ACTIVITY_NEW_TASK,
                    // so Settings stacks on this app's task and BACK walks back
                    // here. With NEW_TASK it lands in a task of its own, BACK
                    // only descends Settings' own stack, and on a device whose
                    // launcher is stopped there is then nothing to return to --
                    // which stranded the first version of this.
                    runCatching {
                        val a = gestureContext as? android.app.Activity
                        val intent = intentFor(item)
                        if (a != null) a.startActivity(intent)
                        else gestureContext.startActivity(
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Unit
                },
                onDismiss = { onSettingsOpen(false) },
            )
        }

        // Above the settings sheet: opened FROM it, and closing it returns there.
        config.status?.takeIf { showStatus }?.let { st ->
            StatusSheet(
                title = st.title,
                cards = st.cards,
                ctx = ctx,
                onDismiss = { onStatusOpen(false) },
            )
        }
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

    // Keyed on the CONFIG. Swiping to another page swaps this overlay's entities
    // for a different room's, and un-keyed state carried the old room's volume
    // across -- so the first reading from the new room compared against the old
    // one, looked like a change, and flashed the OSD on every page swipe.
    // Re-keying re-seeds instead.
    var visible by remember(cfg) { mutableStateOf(false) }
    var lastVol by remember(cfg) { mutableStateOf<Float?>(null) }
    var lastMuted by remember(cfg) { mutableStateOf<Boolean?>(null) }
    var seeded by remember(cfg) { mutableStateOf(false) }

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

/**
 * Maps a named gesture action to its Settings intent. Every one of these was
 * checked to resolve on the HA100's Android 8.1 build before being offered -- a
 * sheet row that resolves to nothing is worse than no row.
 */
private fun intentFor(item: com.custom.astrion.config.GestureAction): Intent = when (item.action) {
    "app_info" -> Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${item.packageName}"),
    )
    "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
    "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
    "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
    "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
    "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
    "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    "device_info" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    else -> Intent(Settings.ACTION_SETTINGS)
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

@Composable
private fun SettingsSheet(
    panel: com.custom.astrion.config.GesturePanel,
    onPick: (com.custom.astrion.config.GestureAction) -> Unit,
    onDismiss: () -> Unit,
    /** Names THIS remote in the panel, above the build. */
    deviceName: String = "",
    /** Network-adb state, shown beside the bridge (both are deploy-time facts). */
    adbStatus: String = "",
    /** Shown as a row that opens the full-screen Status view; null hides the row. */
    statusTitle: String? = null,
    onStatus: () -> Unit = {},
    haUrl: String,
    setupUrl: String?,
    onSetup: () -> Unit,
    onSync: () -> Unit,
    bridgeConnected: Boolean,
    bridgeCommand: String,
    stockAllowed: Boolean,
    onStockAllowedChange: (Boolean) -> Unit,
    stockStops: Int,
    stockStopAt: Long,
    /**
     * Show the HA100-internal sections (input bridge / OEM launcher). False on
     * a device that has neither -- a tablet has no evdev key bridge to start and
     * no stock launcher to force-stop, so those rows are noise at best and
     * misleading at worst ("Input bridge: not running" is not a fault there).
     */
    deviceInternals: Boolean = true,
    /** True when the device is locked into this app; adds the Exit row. */
    kiosk: Boolean = false,
    /** Opens the PIN pad that leaves kiosk mode. */
    onKioskExit: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .background(Color(0xFF16303A))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(panel.title, color = Color(0xFFF1F4FA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            statusTitle?.let { title ->
                Text(
                    title,
                    color = Color(0xFFD7E3EA),
                    fontSize = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E3841))
                        .clickable { onStatus() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                )
            }
            panel.items.forEach { item ->
                Text(
                    item.name,
                    color = Color(0xFFD7E3EA),
                    fontSize = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E3841))
                        .clickable { onPick(item) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                )
            }

            if (deviceInternals) {
                Spacer(Modifier.height(4.dp))
                Text("Screen-off keys", color = Color(0xFFF1F4FA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                InfoRow("Input bridge", if (bridgeConnected) "connected" else "not running")
                // Next to the bridge because they fail together: a reboot drops
                // network adb AND kills the bridge, and this row is how you find out
                // without discovering it mid-deploy.
                InfoRow("ADB over TCP", adbStatus.ifBlank { "—" })
                if (!bridgeConnected) {
                    // Shown only when it is missing, and it explains itself: the app
                    // cannot start this. /dev/input needs group 1004 and the
                    // input_device SELinux label, which no app has and none can be
                    // granted -- so it takes a shell, every boot.
                    Text(
                        "Keys work normally without this. Start it over adb to make the " +
                            "press that wakes the screen also do its job:",
                        color = Color(0xFF93AFB6),
                        fontSize = 13.sp,
                    )
                    Text(
                        bridgeCommand,
                        color = Color(0xFFD7E3EA),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0E2229))
                            .clickable { clipboard.setText(AnnotatedString(bridgeCommand)) }
                            .padding(10.dp),
                    )
                    Text("tap to copy", color = Color(0xFF6E8A93), fontSize = 11.sp)
                }

                Spacer(Modifier.height(4.dp))
                Text("Stock app", color = Color(0xFFF1F4FA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ToggleRow(
                    // A FIXED label. It read "Allowed to run" / "Kept stopped",
                    // which changed with the switch and left it ambiguous whether
                    // the words described the current state or what tapping would do.
                    label = "Allow stock app to run",
                    checked = stockAllowed,
                    enabled = bridgeConnected,
                    onChange = onStockAllowedChange,
                )
                InfoRow(
                    "Force-stopped",
                    if (stockStops == 0) "never" else buildString {
                        append(stockStops)
                        append(if (stockStops == 1) " time" else " times")
                        if (stockStopAt > 0) {
                            append(", last ")
                            append(
                                android.text.format.DateUtils.getRelativeTimeSpanString(
                                    stockStopAt,
                                    System.currentTimeMillis(),
                                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                )
                            )
                        }
                    },
                )
                Text(
                    if (bridgeConnected) {
                        "The OEM launcher holds a wake lock the whole time it runs, so the " +
                            "bridge force-stops it every minute. Allow it if you need it back " +
                            "-- it stays installed either way and is never disabled."
                    } else {
                        "Needs the input bridge: force-stopping a package is a shell " +
                            "privilege, so the app cannot do it on its own."
                    },
                    color = Color(0xFF93AFB6),
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("Astrion Custom", color = Color(0xFFF1F4FA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            // Which remote this is, above the build: with three identical units
            // on one layout, "which one am I holding" is the first question the
            // panel should answer.
            InfoRow("Remote", deviceName.ifBlank { "unnamed" })
            InfoRow("Build", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Home Assistant", haUrl.ifBlank { "not configured" })
            InfoRow("Setup server", setupUrl ?: "off")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Sync", onSync)
                SheetButton(if (setupUrl == null) "Setup on" else "Setup off", onSetup)
                // Only when actually locked -- an Exit button on a device that
                // is not in kiosk mode would do nothing and read as broken.
                if (kiosk) SheetButton("Exit kiosk", onKioskExit)
                SheetButton("Close", onDismiss)
            }

            // Handle at the BOTTOM, closing on an upward drag, to match the
            // Status view -- and because this panel hangs from the top edge and
            // is pulled DOWN to open. Closing it with a second downward pull was
            // asking the same gesture to mean both "open this" and "put it
            // away". The detector lives on the handle and not on the Column
            // because the Column is verticalScroll: a pointerInput there would
            // either swallow the scroll or never fire.
            GrabHandle(onClose = onDismiss, up = true)
        }
    }
}

@Composable
private fun SheetButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFFD7E3EA),
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E3841))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E3841))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = if (enabled) Color(0xFFD7E3EA) else Color(0xFF6E8A93),
            fontSize = 15.sp,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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
    columns: Int = 1,
    docked: Boolean = false,
) {
    // A page with `dock_cards` renders those, ON OR OFF the charger.
    //
    // This started as a cradle-only view -- hence the name -- on the assumption
    // that a remote in the hand wants a dense scrolling list and a remote across
    // the room wants six big targets. In use the big targets won in both cases,
    // so the power state no longer chooses; `docked` is still threaded through
    // for the screensaver and is deliberately NOT consulted here.
    //
    // NOTE: this makes a page's ORIGINAL `cards:` list unreachable wherever
    // `dock_cards` exists. That is the intent, not an oversight -- but it means
    // anything still only present in the old list is now gone from that page,
    // so removing a control from `dock_cards` removes it outright.
    //
    // Falling back to the normal list when `dock_cards` is empty is what keeps
    // every other page working untouched.
    val shownAll = (if (page.dockCards.isNotEmpty()) page.dockCards else page.cards)
        // Hoisted into the header by AstrionDashboard; drawing it here as well
        // would render it twice.
        .filter { it.options["dock_header"] != true }
    // `dock_index_only` cards (the now-playing row) belong to the dock INDEX.
    // A submenu needs that height for its own sixth button; with the row left in
    // place a six-item menu overflows the screen.
    val shown = if (com.custom.astrion.cards.impl.DockMenuState.inSubmenu) {
        shownAll.filter { it.options["dock_index_only"] != true }
    } else shownAll
    // Cards with options["pin"] == "bottom" float at the bottom, always visible;
    // the rest scroll above them.
    val pinned = remember(shown) { shown.filter { it.options["pin"] == "bottom" } }
    val scrolling = remember(shown) { shown.filter { it.options["pin"] != "bottom" } }

    // Only lay out cards that will actually draw something. A hidden
    // `conditional` still occupies a slot, and the spacing puts a gap around
    // every slot -- so the three conditionals stacked above the first separator
    // on Main left ~30dp of hole between the status bar and "Watch" whenever
    // nothing was playing. Filtering here (not inside the card) is what removes
    // the gap as well as the content.
    val visible = scrolling.filter {
        it.type != "conditional" || ConditionalCard.matches(it, ctx)
    }
    fun separatorIndex(name: String) = visible.indexOfFirst {
        it.type == "separator" &&
            (it.options["name"] as? String)?.equals(name, ignoreCase = true) == true
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    val multi = columns > 1

    // Hardware "scroll to section": scroll so the matching separator sits at the
    // top. Only the page that actually has the section consumes the request.
    LaunchedEffect(scrollTarget, multi) {
        val name = scrollTarget ?: return@LaunchedEffect
        val idx = separatorIndex(name)
        if (idx >= 0) {
            if (multi) gridState.animateScrollToItem(idx) else listState.animateScrollToItem(idx)
            onScrollHandled()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (multi) {
            // A card may claim a lane with `column: N` (1-based); everything else
            // FLOWS through the lanes nobody claimed. That is what makes a
            // sidebar expressible -- "media player and dpad on the right" -- while
            // the rest of the page still reflows as a grid. On a single-column
            // device the option is simply never read, so the same base layout
            // carries it harmlessly.
            // A separator's `column:` claims the SECTION it opens, not just its
            // own line -- "put Watch in lane 1" should not mean tagging every
            // card underneath it, and a section that later grows a card would
            // otherwise silently land in the wrong lane. An individual card
            // still overrides, and a separator with no lane opens an
            // unassigned section.
            var section: Int? = null
            val tagged = visible.map { card ->
                val own = card.lane()?.takeIf { it in 1..columns }
                // Only a TAGGED separator switches lanes. An untagged one
                // continues the current section, so "Lights in lane 2" carries
                // Shades and Climate with it and each new section does not have
                // to repeat the lane it is already in.
                if (card.type == "separator" && own != null) section = own
                card to (own ?: section)
            }
            val claimed = tagged.mapNotNull { it.second }.toSet()
            // When every lane is spoken for there is no flow area, so anything
            // unassigned joins the first claimed lane instead of vanishing.
            val fallback = if ((1..columns).any { it !in claimed }) null else claimed.minOrNull()
            val resolved = tagged.map { (c, l) -> c to (l ?: fallback) }
            val flowCards = resolved.filter { it.second == null }.map { it.first }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                var lane = 1
                var flowDrawn = false
                while (lane <= columns) {
                    if (lane in claimed) {
                        LaneColumn(
                            resolved.filter { it.second == lane }.map { it.first },
                            ctx,
                            Modifier.weight(1f),
                        )
                        lane++
                    } else {
                        // Consecutive unclaimed lanes merge into ONE grid, so a
                        // separator spanning "the full line" spans the flow area
                        // rather than the whole screen -- otherwise a section rule
                        // would run underneath the sidebar too.
                        var span = 0
                        while (lane <= columns && lane !in claimed) { span++; lane++ }
                        if (!flowDrawn) {
                            flowDrawn = true
                            FlowGrid(flowCards, ctx, gridState, span, Modifier.weight(span.toFloat()))
                        } else {
                            Spacer(Modifier.weight(span.toFloat()))
                        }
                    }
                }
            }
        } else {
            // No back-swipe here any more, and no preview layer.
            //
            // Both existed to fake a transition between two levels rendered in
            // the same place. A submenu is an overlay now (DockSubmenuOverlay),
            // so the thing underneath is the real index, live -- there is
            // nothing to preview and nothing to slide out of the way.
            if (page.dockCards.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    visible.forEach { RenderCard(it, ctx) }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible.size, key = { it }) { i ->
                        RenderCard(visible[i], ctx)
                    }
                }
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

/** `column: N` on a card, 1-based; null when unset or not a number. */
private fun CardConfig.lane(): Int? = (options["column"] as? Number)?.toInt()

/** The reflowing part of a multi-column page. */
@Composable
private fun FlowGrid(
    cards: List<CardConfig>,
    ctx: CardContext,
    state: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    // Staggered rather than a fixed grid: cards differ a lot in height (a
    // climate card against a separator), and a fixed grid would pad every row up
    // to its tallest member. Separators take a full line so a section still
    // reads as a section instead of being dealt into whichever column had space.
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            cards.size,
            key = { it },
            span = {
                if (cards[it].type == "separator") StaggeredGridItemSpan.FullLine
                else StaggeredGridItemSpan.SingleLane
            },
        ) { i -> RenderCard(cards[i], ctx) }
    }
}

/**
 * One explicitly-claimed lane. Scrolls on its own: a sidebar holding a media
 * card and a D-pad is a different length from the page beside it, and tying them
 * to one scrollbar would mean scrolling the dashboard to reach the transport.
 */
@Composable
private fun LaneColumn(cards: List<CardConfig>, ctx: CardContext, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { RenderCard(it, ctx) }
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
private fun RoomIndicator(
    pages: List<PageConfig>,
    current: Int,
    onDotClick: (Int) -> Unit,
    onOpenPanel: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        pages.forEachIndexed { i, _ ->
            val active = i == current
            // The same muted colour as the clock and the room name, not the
            // accent blue. In the status bar the dots sit beside two readouts
            // they are subordinate to; an accent there reads as a notification.
            // Presence is carried by opacity instead.
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF93AFB6) else Color(0x5993AFB6))
                    .clickable { onDotClick(i) },
            )
        }
        Spacer(Modifier.width(6.dp))
        // Tapping the room name opens the device panel. This is NOT redundant
        // with the swipe-down on this same bar: the panel is the only route out
        // of kiosk mode, and an exit hatch that depends on landing a drag in a
        // thin edge band is not an exit hatch. The dots keep their own job
        // (switching rooms); only the label does this.
        //
        // The tap target is deliberately small in padding terms -- it sits
        // inside the status bar now, and a generous one would eat into the swipe
        // band that opens the same panel.
        Text(
            pages.getOrNull(current)?.name ?: "",
            color = Color(0xFF93AFB6),
            // Same size as the clock and battery either side of it. At 12sp it
            // read as a caption belonging to something rather than as the third
            // readout on the row.
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenPanel() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * A dock submenu, drawn over the index rather than in place of it.
 *
 * The index and its now-playing header stay mounted underneath and never move.
 * That is the whole reason this exists: while a submenu lived inside the page,
 * the header had to be unmounted to give the grid its rows back, so backing out
 * snapped it into view. Nothing can snap into view if it never left.
 *
 * It starts below the status bar, so the clock and battery stay legible; the
 * status bar is drawn after this at the root for the same reason.
 */
@Composable
private fun DockSubmenuOverlay(page: PageConfig, ctx: CardContext) {
    var swipePx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // The header card is hoisted into the band and must not be drawn again here.
    val cards = remember(page) { page.dockCards.filter { it.options["dock_header"] != true } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swipe right to go back. Measured on this Box, which does not move;
            // only the content inside it does. Putting the offset and the
            // detector on one node makes the gesture measure itself in a
            // coordinate space it is changing, and the page visibly shakes.
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val commit = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var dx = 0f
                    var claimed = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        dx = ch.position.x - down.position.x
                        if (!claimed && dx > slop) claimed = true
                        if (claimed) {
                            ch.consume()
                            swipePx = dx.coerceAtLeast(0f)
                        }
                    }
                    if (claimed && dx > commit) {
                        val w = size.width.toFloat()
                        scope.launch {
                            animate(swipePx, w, animationSpec = tween(160)) { v, _ -> swipePx = v }
                            com.custom.astrion.cards.impl.DockMenuState.popOne()
                            swipePx = 0f
                        }
                    } else if (claimed) {
                        scope.launch { animate(swipePx, 0f) { v, _ -> swipePx = v } }
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(swipePx.roundToInt(), 0) }
                // Opaque, and covering the FULL height including the strip
                // behind the status bar. Starting below the status bar instead
                // left the top of the header's blurred artwork showing above
                // the menu -- the index leaking through a gap the overlay was
                // supposed to close.
                //
                // The background belongs on this moving layer, not on the Box
                // around it: a backdrop that stays put would be what a swipe
                // reveals, instead of the index.
                .background(Color(0xFF0E2229))
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    // Clears the status bar, which is drawn above this.
                    top = STATUS_BAR_BAND + 8.dp,
                    bottom = 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cards.forEach { RenderCard(it, ctx) }
        }
    }
}

/** The status bar's own height, which the submenu overlay starts below. */
private val STATUS_BAR_BAND = 46.dp

/**
 * Height reserved at the top of a dock index for the now-playing header.
 *
 * Constant on purpose: the grid starts below it, so anything that changes this
 * moves every button on the page. Sized to the header's natural height with its
 * text capped at two lines -- see MediaPlayerCard's header variant, which is
 * what makes "natural height" a single number rather than one per source.
 */
private val HEADER_BAND = 132.dp

/** How long a connection may be down before it is worth telling anyone. */
private const val BANNER_GRACE_MS = 4000L

@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    if (connection == ConnectionState.CONNECTED) return

    // Don't announce a blip. Waking the screen reconnects in 2-3s, and a banner
    // that appears and clears in that window is pure noise -- it says "something
    // is wrong" about the normal path back from sleep. AUTH_FAILED is exempt:
    // that one does not resolve itself, so hiding it would just delay the news.
    //
    // Grace applies to the transient states only, and resets whenever the state
    // changes, so a real outage still surfaces after a couple of seconds and
    // stays up.
    val transient = connection == ConnectionState.CONNECTING ||
        connection == ConnectionState.AUTHENTICATING ||
        connection == ConnectionState.ERROR ||
        connection == ConnectionState.DISCONNECTED
    var settled by remember(connection) { mutableStateOf(!transient) }
    LaunchedEffect(connection) {
        if (transient) {
            kotlinx.coroutines.delay(BANNER_GRACE_MS)
            settled = true
        }
    }
    if (!settled) return

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

/**
 * A short bar at the top of a sheet that closes it on a downward drag.
 *
 * Separate from the sheet body on purpose: sheet bodies scroll, and a vertical
 * drag detector on a scrolling container either consumes the scroll or never
 * sees the gesture. Giving the gesture its own non-scrolling strip resolves
 * that, and makes the affordance visible rather than hidden.
 *
 * The 6f threshold matches StatusBar's swipe-to-open so both directions feel
 * the same.
 */
@Composable
private fun GrabHandle(onClose: () -> Unit, up: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A band, not just the 4dp bar that is drawn. The pill is an
            // affordance; the thing you actually have to hit is this.
            .heightIn(min = 34.dp)
            .then(if (up) Modifier.padding(top = 4.dp) else Modifier.padding(bottom = 4.dp))
            .pointerInput(onClose, up) {
                detectVerticalDragGestures { change, drag ->
                    // A sheet leaves the way it arrived. The device panel hangs
                    // from the top edge and is pulled down to open, so it is
                    // pushed back up to close; anything anchored the other way
                    // keeps the downward gesture.
                    if (if (up) drag < -6f else drag > 6f) {
                        change.consume()
                        onClose()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3A5A66)),
        )
    }
}

/**
 * The Status view: the same cards any page can hold, full screen, reached from
 * the swipe-up panel instead of occupying a dot on the pager.
 *
 * These readouts are reference material you go LOOKING for -- "what is the chain
 * actually doing right now" -- rather than something glanced at in passing, so
 * they do not earn a permanent place next to the rooms used daily.
 */
@Composable
private fun StatusSheet(
    title: String,
    cards: List<CardConfig>,
    ctx: CardContext,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E2229)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                title,
                color = Color(0xFFF1F4FA),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            // weight, not fillMaxSize: the list has to leave the handle below it
            // its row. fillMaxSize would claim the whole column and push the
            // handle off the bottom of the screen -- present in the tree,
            // invisible and unreachable, which is the worst of both.
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cards) { card -> RenderCard(card, ctx) }
            }
            // The gesture lives on the handle rather than on the whole page
            // BECAUSE of that list: a swipe-up anywhere would be indistinguishable
            // from scrolling down through the readouts, and this view is mostly
            // list. One unambiguous strip at the bottom instead.
            GrabHandle(onClose = onDismiss, up = true)
        }
    }
}

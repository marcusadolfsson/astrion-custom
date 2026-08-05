package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityMap
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
) {
    val entities by entitiesState
    val connection by connectionState
    val ctx = CardContext(entities = entities, client = client)
    val scope = rememberCoroutineScope()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E2229)),
    ) {
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
        )
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
    val pinned = page.cards.filter { it.options["pin"] == "bottom" }
    val scrolling = page.cards.filter { it.options["pin"] != "bottom" }

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
            items(scrolling) { RenderCard(it, ctx) }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

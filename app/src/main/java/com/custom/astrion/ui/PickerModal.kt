package com.custom.astrion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.custom.astrion.ha.HaClient

/**
 * The full-screen picker used by both `source_modal` and the D-pad's app drawer.
 *
 * It lives here rather than inside a card because two different cards need to
 * OPEN the same thing: the remotes reach the launcher from a pill on the page
 * (they have no on-screen D-pad to hang it off), while the tablet reaches it
 * from an icon in the corner of its drawn pad.
 */
object PickerModal {

    /**
     * One entry. [section] starts a labelled break above this item, which is
     * what lets a single modal carry apps first and then channels without
     * being two modals the user has to choose between up front.
     */
    data class Entry(
        val name: String,
        val image: String? = null,
        val section: String? = null,
        /**
         * Columns for the section this entry OPENS. Lets one modal mix layouts:
         * the Kaleidescape picker wants its five browse controls as a row of
         * buttons and its 207 titles as a list, and splitting that into two
         * modals would mean choosing which one to open before you know what you
         * want. Null inherits the modal's own column count.
         */
        val sectionColumns: Int? = null,
        val onPick: () -> Unit,
    )

    /** Sorting and indexing ignore a leading article, as the Kaleidescape UI does. */
    private fun indexKey(name: String): String {
        var t = name
        for (a in listOf("The ", "A ", "An ")) if (t.startsWith(a)) { t = t.substring(a.length); break }
        return t
    }

    /** '#' for anything not starting with a letter, so digits group together. */
    private fun indexLetter(name: String): Char {
        val c = indexKey(name).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    @Composable
    fun Show(
        title: String,
        entries: List<Entry>,
        client: HaClient,
        columns: Int,
        tileHeight: Dp,
        fontSize: TextUnit,
        onDismiss: () -> Unit,
    ) {
        OpenOverlays.Track(true)
        Dialog(
            onDismissRequest = onDismiss,
            // The stock dialog width is far too narrow on a 480x800 remote; the
            // whole point here is large targets, so take the screen.
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Hardware keys still belong to the page underneath.
            ForwardHardwareKeys()
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
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = Color(0xFFE6F0F1),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, "Close", tint = Color(0xFFCBDCE0))
                    }
                }

                // Flatten to grid slots so a section header, an item and the
                // letter rail all address the SAME index space -- scrolling to
                // "M" means scrolling to a grid index, and that only works if
                // headers are counted too.
                data class Slot(val header: String?, val entry: Entry?, val cols: Int)
                val slots = remember(entries, columns) {
                    val out = mutableListOf<Slot>()
                    var cur = columns
                    entries.forEach { e ->
                        if (e.section != null) {
                            cur = e.sectionColumns ?: columns
                            out += Slot(e.section, null, cur)
                        }
                        out += Slot(null, e, cur)
                    }
                    out
                }
                // ONE grid, spans doing the mixed layout. A section declares how
                // many columns IT wants; the grid runs at the widest of them and
                // each item spans the difference -- so a 3-wide button section
                // and a 1-wide list live in the same scroll, which two separate
                // grids could not.
                val gridCols = remember(slots) { maxOf(columns, slots.maxOf { it.cols }) }
                val gridState = rememberLazyGridState()
                val scope = rememberCoroutineScope()

                // First slot for each letter, over LIST-shaped sections only:
                // an index into a row of buttons is meaningless.
                val letterAt = remember(slots) {
                    val m = linkedMapOf<Char, Int>()
                    slots.forEachIndexed { i, s2 ->
                        val e = s2.entry
                        if (e != null && s2.cols == 1) m.putIfAbsent(indexLetter(e.name), i)
                    }
                    m
                }
                // Only worth the rail when there is genuinely too much to scroll.
                val showRail = letterAt.size >= 8

                Row(modifier = Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridCols),
                        state = gridState,
                        modifier = Modifier.weight(1f).padding(start = 10.dp, end = if (showRail) 2.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            slots.size,
                            key = { i -> slots[i].header?.let { "h-$it" } ?: "i-$i-" + slots[i].entry!!.name },
                            span = { i ->
                                val s2 = slots[i]
                                if (s2.header != null) GridItemSpan(maxLineSpan)
                                else GridItemSpan(maxLineSpan / s2.cols)
                            },
                        ) { i ->
                            val s2 = slots[i]
                            if (s2.header != null) {
                                Text(
                                    s2.header,
                                    color = Color(0xFF93AFB6),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            } else {
                                Tile(s2.entry!!, client, tileHeight, fontSize, list = s2.cols == 1)
                            }
                        }
                    }

                    if (showRail) {
                        // A-Z rail. Tap a letter, jump there. 207 titles is a
                        // lot of flicking otherwise, and this is a remote held
                        // in one hand.
                        // A fast-scroller, not 27 tiny buttons.
                        //
                        // Even given its own slice of the rail, each letter is
                        // only ~20dp tall -- 27 of them have to share the screen
                        // height -- and 20dp is well under a thumb. So the rail
                        // takes press AND drag anywhere along it and maps the y
                        // position to a letter: land near M and you get M, slide
                        // and it scrubs. Precision stops mattering, which is the
                        // only way this works one-handed.
                        val letters = remember(letterAt) { letterAt.toList() }
                        var railTop by remember { mutableStateOf(0f) }
                        var active by remember { mutableStateOf<Char?>(null) }
                        fun pickAt(y: Float, h: Int) {
                            if (h <= 0 || letters.isEmpty()) return
                            val idx = ((y / h) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                            val (letter, slot) = letters[idx]
                            if (active != letter) {
                                active = letter
                                scope.launch { gridState.scrollToItem(slot) }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(34.dp)
                                .pointerInput(letters) {
                                    detectVerticalDragGestures(
                                        onDragStart = { pickAt(it.y, size.height) },
                                        onDragEnd = { active = null },
                                    ) { change, _ -> pickAt(change.position.y, size.height) }
                                }
                                .pointerInput(letters) {
                                    detectTapGestures { pickAt(it.y, size.height) }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            letters.forEach { (letter, _) ->
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        letter.toString(),
                                        color = if (active == letter) Color(0xFFE6F0F1) else Color(0xFF9FC4CE),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * One logo tile. Falls back to the entry's NAME when it has no image or the
     * fetch fails -- a blank square is indistinguishable from a broken app.
     */
    @Composable
    private fun Tile(
        e: Entry,
        client: HaClient,
        height: Dp,
        fontSize: TextUnit,
        /**
         * One column is a LIST, not a column of buttons. 207 movie titles read
         * as rows -- left-aligned, wrapping, sized to their text -- and as
         * centred fixed-height tiles they read as 207 buttons, which is both
         * uglier and harder to scan.
         */
        list: Boolean = false,
    ) {
        var bmp by remember(e.image) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(e.image) {
            // Bounded decode: 43 logos at full size is far more bitmap than the
            // HA100's ~6 MB heap allows, and the tiles are small by design.
            bmp = e.image?.let { client.fetchBitmap(it, maxPx = 128) }
        }
        val b = bmp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // FIXED height. ContentScale.Fit scales to the SMALLER ratio, so
                // with an unbounded height the limit is the image's own pixels
                // and these 70x52 guide logos draw at natural size in a much
                // wider tile.
                // Fixed height ONLY when there is an image. ContentScale.Fit
                // scales to the smaller ratio, so an unbounded height makes a
                // 70x52 logo draw at natural size. Text tiles get a minimum
                // instead, so a two-line label ("Collections") grows rather
                // than truncating.
                .then(if (b != null) Modifier.height(height) else Modifier.heightIn(min = height))
                .clip(RoundedCornerShape(10.dp))
                // Logo tiles go light: Spectrum's guide art and most brand marks
                // are drawn for white, and on a dark chip the near-black ones
                // (abc, CBS, FOX, TNT, bravo, A&E) are invisible rather than
                // subtle. Text tiles keep the dark chip.
                .background(if (b != null) Color(0xFFF2F5F7) else Color(0xFF1E3841))
                // Deliberately does NOT close. Picking an app is often the
                // first of several tries ("no, the other one"), and a modal
                // that shuts on every tap makes you reopen and re-find your
                // place each time. The X and the back gesture close it.
                .clickable { e.onPick() }
                .padding(horizontal = if (list) 14.dp else 6.dp, vertical = if (list) 10.dp else 6.dp),
            contentAlignment = if (list) Alignment.CenterStart else Alignment.Center,
        ) {
            if (b != null) {
                Image(b, e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(
                    e.name,
                    color = Color(0xFFE6F0F1),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = if (list) TextAlign.Start else TextAlign.Center,
                    // Buttons truncate; list rows wrap. A two-line button label
                    // makes the whole row of buttons grow to match it, which
                    // looked worse than "Collectio...".
                    maxLines = if (list) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
        val onPick: () -> Unit,
    )

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

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEachIndexed { i, e ->
                        if (e.section != null) {
                            item(key = "sec-" + e.section, span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    e.section,
                                    color = Color(0xFF93AFB6),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = "i-$i-" + e.name) {
                            Tile(e, client, tileHeight, fontSize)
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
                .height(height)
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
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (b != null) {
                Image(b, e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(
                    e.name,
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
}

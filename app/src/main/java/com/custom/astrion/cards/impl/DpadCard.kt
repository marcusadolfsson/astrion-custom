package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.custom.astrion.cards.CuratedItems
import com.custom.astrion.ui.PickerModal
import com.custom.astrion.cards.LauncherButton
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback

/**
 * An on-screen navigation cluster for devices with no buttons.
 *
 * THE POINT: it fires logical [HardwareKey]s through the app's own key router
 * rather than calling services itself. Every binding already lives in the
 * layout's `hotkeys:` (and a page's own `hotkeys:` overrides them), so the
 * drawn D-pad on the tablet and the moulded one on an HA100 do the same thing
 * by construction -- including per-page overrides -- and neither can drift from
 * the other when a script is renamed. A card that restated `lr_av_nav_up` would
 * be a second copy of the truth.
 *
 * A key with no binding on the current page simply does nothing, exactly as the
 * physical button does.
 *
 * Config (all optional):
 * ```yaml
 * - type: dpad
 *   options:
 *     transport: true       # play/pause + skip row under the pad
 *     volume: true          # volume down / mute / up row
 *     back: true            # BACK and MENU pills either side of the pad
 * ```
 * Wrap it in a `conditional` to show it only when something is playing:
 * ```yaml
 * - type: conditional
 *   entity: input_select.lr_av_activity
 *   state_not: "Off"
 *   card: { type: dpad, options: { transport: true } }
 * ```
 */
object DpadCard : CardRenderer {
    override val type = "dpad"

    private val FACE = Color(0xFF13262D)
    private val KEY = Color(0xFF1E3841)
    private val CENTER = Color(0xFF2A6E80)
    private val FG = Color(0xFFD7E3EA)

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val showTransport = config.bool("transport", false)
        val showVolume = config.bool("volume", false)
        val showBack = config.bool("back", true)
        val showSkip = config.bool("skip", false)
        val keySize = config.int("key_size", 66)

        // The app drawer, if this pad is configured with one. It hangs off the
        // pad rather than sitting on the page because on a tablet the pad IS the
        // remote -- reaching for apps is the same motion as reaching for OK. The
        // physical remotes have no drawn pad, so there the same list is a pill on
        // the page instead (see source_modal in the layout).
        @Suppress("UNCHECKED_CAST")
        val drawer = config.options["drawer"] as? Map<String, Any?>

        Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(FACE)
                .padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pad in the middle, volume down the left, skip down the right.
            // Flanking columns rather than extra rows: they keep the pad itself
            // big, and on a wall tablet the pad is the thing you aim at without
            // looking.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (showVolume) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(ctx, HardwareKey.VOLUME_UP, "Vol +", width = 82)
                        Pill(ctx, HardwareKey.VOLUME_DOWN, "Vol −", width = 82)
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Key(ctx, HardwareKey.UP, Icons.Filled.KeyboardArrowUp, size = keySize)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Key(ctx, HardwareKey.LEFT, Icons.Filled.KeyboardArrowLeft, size = keySize)
                        // OK is the only key in the accent colour: on a pad of
                        // five identical circles the thumb needs one landmark to
                        // aim from without looking down.
                        Key(ctx, HardwareKey.CENTER, null, label = "OK", bg = CENTER, size = (keySize * 6) / 5)
                        Key(ctx, HardwareKey.RIGHT, Icons.Filled.KeyboardArrowRight, size = keySize)
                    }
                    Key(ctx, HardwareKey.DOWN, Icons.Filled.KeyboardArrowDown, size = keySize)
                }
                if (showSkip) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(ctx, HardwareKey.PAGE_UP, "Next", width = 82)
                        Pill(ctx, HardwareKey.PAGE_DOWN, "Prev", width = 82)
                    }
                }
            }

            // Back and Menu BELOW the pad. Flanking it, they competed with the
            // pad for the same reach and pushed the arrows inward.
            if (showBack) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(ctx, HardwareKey.BACK, "Back")
                    Pill(ctx, HardwareKey.MENU, "Menu")
                }
            }

            // Explicit rows win over the `transport:` shorthand. A drawn pad can
            // carry buttons no physical remote has -- rewind and fast-forward
            // among them -- and which KEY each one fires has to stay in config,
            // because the same keycode carries a different legend per unit
            // (LIGHT/AC are printed SCAN and SCAN FWD on an HA100B).
            val rows = parseRows(config)
            if (rows.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { b -> Pill(ctx, b.key, b.label, b.wide) }
                    }
                }
            } else if (showTransport) {
                Spacer(Modifier.size(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(ctx, HardwareKey.PAGE_DOWN, "Prev")
                    Pill(ctx, HardwareKey.CENTER, "Play/Pause", wide = true)
                    Pill(ctx, HardwareKey.PAGE_UP, "Next")
                }
            }

        }
        if (drawer != null) {
            // The SHARED button, not a local copy of it. The copy skipped
            // LauncherButton's "is there anything to launch onto" test, so the
            // pad offered an Apple TV drawer while the room was watching
            // Kaleidescape -- and tapping it would have switched the Apple TV
            // out from under a film that was playing.
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                LauncherButton(drawer, ctx, size = 46.dp)
            }
        }
        }
    }

    private data class Btn(val key: HardwareKey, val label: String, val wide: Boolean)

    /**
     * `rows:` is a list of rows, each a list of `{key, label, wide}`. An
     * unrecognised key name drops that button rather than the whole card -- a
     * typo should cost one pill, not the navigation pad you are looking at.
     */
    private fun parseRows(config: CardConfig): List<List<Btn>> {
        val raw = config.options["rows"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { row ->
            (row as? List<*>)?.mapNotNull { b ->
                val m = b as? Map<*, *> ?: return@mapNotNull null
                val key = runCatching {
                    HardwareKey.valueOf((m["key"] as? String).orEmpty().uppercase())
                }.getOrNull() ?: return@mapNotNull null
                Btn(
                    key = key,
                    label = (m["label"] as? String) ?: key.name,
                    wide = (m["wide"] as? Boolean) ?: false,
                )
            }?.takeIf { it.isNotEmpty() }
        }
    }

    @Composable
    private fun Key(
        ctx: CardContext,
        key: HardwareKey,
        icon: ImageVector?,
        label: String? = null,
        bg: Color = KEY,
        size: Int = 66,
    ) {
        // Same latching feedback the shades use: an AV command's result shows up
        // on a projector a second or two later, so a ripple is not an answer to
        // "did that register".
        val (state, click) = rememberPressFeedback { ctx.onHardwareKey(key) }
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(ackColor(bg, state))
                .pressFeedback(state, click),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = key.name, tint = FG, modifier = Modifier.size(38.dp))
            } else if (label != null) {
                Text(label, color = FG, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun Pill(
        ctx: CardContext,
        key: HardwareKey,
        label: String,
        wide: Boolean = false,
        width: Int = 0,
    ) {
        val (state, click) = rememberPressFeedback { ctx.onHardwareKey(key) }
        Box(
            modifier = Modifier
                .width(if (width > 0) width.dp else if (wide) 132.dp else 88.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ackColor(KEY, state))
                .pressFeedback(state, click)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = FG, fontSize = 15.sp)
        }
    }
}

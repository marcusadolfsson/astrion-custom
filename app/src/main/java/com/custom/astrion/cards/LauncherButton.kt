package com.custom.astrion.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.impl.CardIcons
import com.custom.astrion.ui.PickerModal
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback

/**
 * The app-drawer icon that opens a curated [PickerModal].
 *
 * Shared because it hangs off two different rows: the Activity selector and the
 * now-playing strip. Both want the same list, the same hiding rules, and the
 * same "does this even have anything to launch onto" test -- duplicating that
 * is how the two quietly stop agreeing.
 *
 * ```yaml
 * launcher:
 *   icon: apps
 *   name: Apple TV
 *   columns: 3
 *   hidden_for: ['Off']              # states of the HOST row that hide it
 *   target_from: sensor.…            # entity whose state is the media_player
 *   items: !include atv_items_lr.yaml
 * ```
 */
@Composable
fun LauncherButton(
    launcher: Map<String, Any?>?,
    ctx: CardContext,
    /** The host row's current state, for `hidden_for`. Null disables that test. */
    hostState: String? = null,
    size: Dp = 44.dp,
) {
    if (launcher == null) return
    val hiddenFor = (launcher["hidden_for"] as? List<*>).orEmpty().filterIsInstance<String>()
    if (hostState != null && hostState in hiddenFor) return

    // A resolver that is not pointing at an entity IS the "nothing to launch
    // onto" signal, so no second condition is needed to hide the button.
    val targetFrom = launcher["target_from"] as? String
    if (targetFrom != null &&
        ctx.entities[targetFrom]?.state?.startsWith("media_player.") != true
    ) return

    var open by remember { mutableStateOf(false) }
    val entries = remember(launcher) { CuratedItems.parse(launcher["items"], ctx, targetFrom) }
    if (entries.isEmpty()) return

    val (press, click) = rememberPressFeedback { open = true }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ackColor(Color(0xFF13272E), press))
            .pressFeedback(press, click),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CardIcons.forName(launcher["icon"] as? String ?: "apps"),
            contentDescription = launcher["name"] as? String ?: "Apps",
            tint = Color(0xFF9FC4CE),
            modifier = Modifier.size(22.dp),
        )
    }
    if (open) {
        PickerModal.Show(
            title = launcher["name"] as? String ?: "Apps",
            entries = entries,
            client = ctx.client,
            columns = (launcher["columns"] as? Number)?.toInt() ?: 3,
            tileHeight = ((launcher["item_height"] as? Number)?.toInt() ?: 64).dp,
            fontSize = ((launcher["item_font_size"] as? Number)?.toInt() ?: 12).sp,
            onDismiss = { open = false },
        )
    }
}

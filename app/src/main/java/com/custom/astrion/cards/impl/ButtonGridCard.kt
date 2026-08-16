package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import java.io.File

/**
 * Generic grid of action buttons, each firing a HA service call. Buttons can
 * carry a PNG icon loaded from a file path (e.g. /sdcard/astrion/icons/mos.png),
 * a text label, or both. Used for the TV-app row, Group/Ungroup, and the
 * playlist buttons.
 *
 * SELECTED STATE: a button can reflect live HA state so the active choice is
 * highlighted (e.g. the current AV activity, the current brightness mode). Give
 * the card a default `active_entity`, and each button an `active_value`; a button
 * whose `active_value` equals that entity's state renders highlighted (accent
 * fill + border + bold). A button may override with its own `active_entity`.
 *
 * TOUCH TARGETS: `button_height` (dp) sets the row height for the whole grid;
 * defaults are already finger-sized (64 dp text-only, 84 dp with an icon).
 *
 * Config shape:
 *   { "type": "button_grid", "options": {
 *       "columns": 2,
 *       "button_height": 76,
 *       "active_entity": "input_select.lr_av_activity",
 *       "buttons": [
 *         { "name": "Apple TV", "service": "script.lr_av_watch_apple_tv",
 *           "active_value": "Watch Apple TV" },
 *         { "name": "Netflix", "service": "media_player.play_media",
 *           "entity_id": "media_player.the_club_tvv",
 *           "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
 *       ]
 *   } }
 */
class ButtonGridCard : CardRenderer {
    override val type = "button_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 3).coerceAtLeast(1)
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val cardActiveEntity = config.string("active_entity")
        // 0 = "auto" (per-button default based on icon presence).
        val buttonHeight = config.int("button_height", 0)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { b ->
                        GridButton(b, ctx, cardActiveEntity, buttonHeight, Modifier.weight(1f)) { fire(ctx, b) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    @Composable
    private fun GridButton(
        b: Map<String, Any?>,
        ctx: CardContext,
        cardActiveEntity: String?,
        buttonHeight: Int,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        val bitmap = remember(iconPath) {
            iconPath?.let {
                runCatching {
                    val f = File(it)
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
                }.getOrNull()
            }
        }
        val hasIcon = bitmap != null

        // Selected when this button's active_value matches the live state of its
        // active_entity (button-level override, else the card default).
        val activeEntity = (b["active_entity"] as? String) ?: cardActiveEntity
        val activeValue = b["active_value"] as? String
        val active = activeEntity != null && activeValue != null &&
            ctx.entities[activeEntity]?.state == activeValue

        val height = when {
            buttonHeight > 0 -> buttonHeight.dp
            hasIcon -> 84.dp
            else -> 64.dp
        }
        val bg = if (active) Color(0xFF2E7D95) else Color(0xFF2A4954)
        val fg = if (active) Color.White else Color(0xFFE6F0F1)

        // Same latched feedback as the shade buttons. It matters here too: the
        // Lutron scene buttons and the bed presets both take a moment to report
        // back, and `active` only lights up once the tracker catches up.
        val (press, click) = rememberPressFeedback(onClick)

        var box = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(ackColor(bg, press))
        if (active) box = box.border(2.dp, Color(0xFF7FD8F0), RoundedCornerShape(14.dp))

        Column(
            modifier = box
                .pressFeedback(press, click)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = name, modifier = Modifier.size(34.dp))
                if (!name.isNullOrBlank()) Spacer(Modifier.height(4.dp))
            }
            if (!name.isNullOrBlank()) {
                Text(
                    name,
                    color = fg,
                    fontSize = if (hasIcon) 13.sp else 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

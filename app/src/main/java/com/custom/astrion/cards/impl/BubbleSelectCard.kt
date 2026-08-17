package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import kotlin.math.roundToInt

/**
 * Bubble-Card-style selector pill, matching the look of the HACS Bubble Card
 * `select` cards on the wall dashboards but touch-sized for the remote's small
 * screen: a circular icon + name + current value, tap to open a dropdown of
 * options. Each option fires a HA service (same option shape as button_grid's
 * buttons), so it drives script-based selectors (Activity, Brightness, Screen
 * size, Light Scenes) while `active_entity` supplies the current value.
 *
 * `open_on`: when set to a section (separator) name, the dropdown auto-opens
 * when a hardware key scrolls to that section — for sections with a single
 * selector, so one keypress lands right on the options.
 *
 * Config:
 *   { "type": "bubble_select", "options": {
 *       "name": "Activity", "icon": "tv", "open_on": "Watch",
 *       "active_entity": "input_select.lr_av_activity",
 *       "options": [
 *         { "name": "Apple TV", "service": "script.lr_av_watch_apple_tv",
 *           "active_value": "Watch Apple TV" }, ... ] } }
 *
 * An option may instead be a brightness SLIDER, for a light that belongs with the
 * scenes around it but needs a level rather than a choice:
 *
 *   { "type": "light", "entity_id": "light.master_bedroom_nightstand_1",
 *     "name": "Nightstand His" }
 *
 * Put these LAST — they are controls, not choices, and the menu reads as a list of
 * options until it stops being one.
 *
 * `toggle` adds a latch button to the pill itself, beside the chevron:
 *
 *   "toggle": { "icon": "remote", "entity": "input_select.lr_key_target",
 *               "on_value": "Source 2", "off_value": "Default",
 *               "hidden_for": ["Off", "Switch"] }
 *
 * It is a RADIO rather than a checkbox — every card writes its own `on_value`
 * into ONE shared entity, so latching a second card releases the first with no
 * coordination between them, and "only one at a time" is a property of the data
 * rather than a rule someone has to enforce. `hidden_for` lists states of the
 * card's own `active_entity` for which the button is not drawn at all, for
 * targets that cannot be acted on; an inert button is worse than no button,
 * because it invites the press it will ignore.
 */
class BubbleSelectCard : CardRenderer {
    override val type = "bubble_select"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val name = config.string("name") ?: ""
        val icon = CardIcons.forName(config.string("icon"))
        val activeEntity = config.string("active_entity")
        val options = (config.options["options"] as? List<Map<String, Any?>>) ?: emptyList()
        val openOn = config.string("open_on")

        val currentState = activeEntity?.let { ctx.entities[it]?.state }
        val currentName = (options.firstOrNull { it["active_value"] == currentState }?.get("name") as? String)
            ?: currentState ?: "—"

        var expanded by remember { mutableStateOf(false) }

        // When a hardware key scrolls to this bubble's (single-control) section,
        // pop the dropdown open too — the section name comes in via openTarget.
        LaunchedEffect(ctx.openTarget) {
            if (openOn != null && ctx.openTarget?.equals(openOn, ignoreCase = true) == true) {
                expanded = true
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFF1E3841))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2E7D95)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (name.isNotBlank()) {
                        Text(name, color = Color(0xFF93AFB6), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(currentName, color = Color(0xFFF1F4FA), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                // Optional latch button, drawn between the value and the chevron.
                // It is a RADIO, not a checkbox: it writes this card's on_value
                // into one shared entity, so latching another card releases this
                // one with no coordination between them.
                @Suppress("UNCHECKED_CAST")
                val toggle = config.options["toggle"] as? Map<String, Any?>
                if (toggle != null) {
                    val toggleEntity = toggle["entity"] as? String
                    val onValue = toggle["on_value"] as? String
                    val offValue = toggle["off_value"] as? String ?: ""
                    // Hidden for source values that cannot be driven at all --
                    // an affordance that is present but inert is worse than one
                    // that is absent, because the first invites a press.
                    val hiddenFor = (toggle["hidden_for"] as? List<String>).orEmpty()
                    if (toggleEntity != null && onValue != null && currentState !in hiddenFor) {
                        val latched = ctx.entities[toggleEntity]?.state == onValue
                        val (press, click) = rememberPressFeedback {
                            ctx.client.callService(
                                ServiceCall.of(
                                    "input_select", "select_option", toggleEntity,
                                    "option" to (if (latched) offValue else onValue),
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ackColor(if (latched) Color(0xFF2E7D95) else Color(0xFF13272E), press))
                                .pressFeedback(press, click),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                CardIcons.forName(toggle["icon"] as? String ?: "remote"),
                                contentDescription = "Control this source",
                                tint = if (latched) Color.White else Color(0xFF6C8A94),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFF93AFB6))
            }

            // Right-aligned dropdown with large touch targets (fat-finger sized).
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        val label = opt["name"] as? String ?: return@forEach
                        // A `"type": "light"` entry is a brightness SLIDER, not a
                        // menu choice. It deliberately does NOT go through
                        // DropdownMenuItem: that consumes the gesture and closes
                        // the menu on touch-down, which would dismiss the dropdown
                        // the instant you started dragging -- i.e. the control
                        // would be impossible to use. Rendering it as a plain row
                        // that owns its own pointerInput keeps the menu open for
                        // the whole drag.
                        if (opt["type"] == "light") {
                            LightSliderRow(opt, ctx)
                            return@forEach
                        }
                        val selected = opt["active_value"] == currentState
                        DropdownMenuItem(
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            text = {
                                Text(
                                    label,
                                    fontSize = 30.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Color(0xFF2E7D95) else Color.Unspecified,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            onClick = { expanded = false; fire(ctx, opt) },
                        )
                    }
                }
            }
        }
    }

    /**
     * A brightness slider living INSIDE the dropdown, for lights that belong to
     * the same decision as the scenes above them ("make the room be like this")
     * but need a level rather than a choice — the bedside nightstands.
     *
     * Same drag/commit semantics as `bubble_light` so the two behave identically:
     * drag or tap anywhere along the row sets brightness, the fill tracks it
     * locally for instant feedback and commits to HA on release, and dragging to
     * zero turns the light off rather than setting 0%.
     *
     * Deliberately NOT a DropdownMenuItem — see the call site. Also deliberately
     * without the long-press colour dialog `bubble_light` has: a popup opening on
     * top of an already-open dropdown is a stack of two transient surfaces on a
     * 3" screen, and the colour picker stays reachable from the full card.
     */
    @Composable
    private fun LightSliderRow(opt: Map<String, Any?>, ctx: CardContext) {
        val entityId = opt["entity_id"] as? String ?: return
        val e = ctx.entities[entityId]
        val on = e?.isOn == true
        val label = opt["name"] as? String ?: e?.friendlyName ?: entityId

        val brightness = e?.attrInt("brightness")
        val level: Float = when {
            !on -> 0f
            brightness != null -> (brightness / 255f).coerceIn(0f, 1f)
            else -> 1f
        }
        var dragLevel by remember(level) { mutableStateOf(level) }

        fun commit(fraction: Float) {
            val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
            if (pct <= 0) {
                ctx.client.callService(ServiceCall("light", "turn_off", entityId))
            } else {
                ctx.client.callService(
                    ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct)
                )
            }
        }

        // A plain Box, NOT BoxWithConstraints: a DropdownMenu measures its
        // children's INTRINSIC width to size itself, and BoxWithConstraints is a
        // SubcomposeLayout, which cannot answer an intrinsic measurement — it
        // throws IllegalStateException and takes the whole app down the moment
        // the menu opens. (Learned the hard way; the crash also drops the unit
        // into Android's home-app chooser, since this app is HOME on Astrion 1.)
        //
        // Width is explicit for the same reason: fillMaxWidth() is meaningless
        // inside a menu that sizes itself to its content. 240.dp fits the 480px
        // / density-220 screen (~349dp wide) with room for the menu's margins.
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .width(240.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF16292F))
                .pointerInput(entityId) {
                    detectHorizontalDragGestures(
                        onDragEnd = { commit(dragLevel) },
                    ) { change, _ ->
                        dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(entityId) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        dragLevel = frac
                        commit(frac)
                    }
                },
        ) {
            if (on) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(dragLevel.coerceIn(0.001f, 1f))
                        .background(Color(0xFFFFC24B).copy(alpha = 0.30f))
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = Color(0xFFF1F4FA),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (on) "${(dragLevel * 100).roundToInt()}%" else "Off",
                    color = Color(0xFF93AFB6),
                    fontSize = 20.sp,
                )
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
}

/** Maps a small set of config icon names to Material icons (shared by the bubble
 *  select + separator cards). Extend as needed. */
object CardIcons {
    fun forName(n: String?): ImageVector = when (n?.lowercase()) {
        "tv", "activity", "source", "watch" -> Icons.Filled.Tv
        "brightness", "sun", "light-level" -> Icons.Filled.BrightnessMedium
        "screen", "aspect", "layout", "display" -> Icons.Filled.AspectRatio
        "movie", "theater", "scene" -> Icons.Filled.Movie
        "light", "bulb", "lights" -> Icons.Filled.Lightbulb
        "curtain", "shade", "shades", "blinds" -> Icons.Filled.Blinds
        "thermostat", "climate", "ac", "temp" -> Icons.Filled.Thermostat
        "remote" -> Icons.Filled.SettingsRemote
        "bed", "sleep", "position" -> Icons.Filled.KingBed
        "fan", "air", "ceiling-fan" -> Icons.Filled.Air
        else -> Icons.Filled.Tune
    }
}

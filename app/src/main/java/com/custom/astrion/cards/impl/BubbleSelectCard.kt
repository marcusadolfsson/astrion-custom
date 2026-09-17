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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AirlineSeatFlatAngled
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.LooksTwo
import androidx.compose.material.icons.filled.Looks3
import androidx.compose.material.icons.filled.AirlineSeatFlat
import androidx.compose.material.icons.filled.AirlineSeatReclineExtra
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CuratedItems
import com.custom.astrion.ui.PickerModal
import com.custom.astrion.cards.LauncherButton
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.OpenOverlays
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
        val configured = (config.options["options"] as? List<Map<String, Any?>>) ?: emptyList()
        val openOn = config.string("open_on")

        val currentState = activeEntity?.let { ctx.entities[it]?.state }

        // Hide choices the entity will not currently accept.
        //
        // An input_select's `options` attribute is rebuilt at runtime by HA --
        // the multi-view source slots are narrowed to what the VRROOM can
        // actually carry -- but this card's list is STATIC config, so it kept
        // offering Kaleidescape and the Switch after the engine had removed
        // them. Picking one did nothing, which reads as a broken remote rather
        // than an unavailable source.
        //
        // Only filters entries that name an `active_value` (the ones that map
        // to a specific option) and only when the entity actually publishes a
        // list -- a light slider row or a plain script button is untouched, and
        // an entity with no `options` attribute behaves exactly as before.
        val liveOptions = activeEntity
            ?.let { ctx.entities[it] }
            ?.attrStringList("options")
            ?.takeIf { it.isNotEmpty() }
        val options = remember(configured, liveOptions) {
            if (liveOptions == null) configured
            else configured.filter { o ->
                val v = o["active_value"] as? String
                v == null || v in liveOptions
            }
        }
        val currentName = (options.firstOrNull { it["active_value"] == currentState }?.get("name") as? String)
            ?: currentState ?: "—"

        var expanded by remember { mutableStateOf(false) }
        OpenOverlays.Track(expanded)

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
                // Optional app launcher, in the same slot as the latch. Shared
                // with the now-playing strip -- see LauncherButton.
                @Suppress("UNCHECKED_CAST")
                val launcher = config.options["launcher"] as? Map<String, Any?>
                if (launcher != null) {
                    LauncherButton(launcher, ctx, hostState = currentState)
                    // Wider than the latch's gap on purpose: this sits beside
                    // the chevron, and the chevron is what you reach for to
                    // change the activity. Opening a modal by accident is a
                    // worse miss than opening a dropdown.
                    Spacer(Modifier.width(18.dp))
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFF93AFB6))
            }

            // Right-aligned dropdown with large touch targets (fat-finger sized).
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // Rule off the choices from the controls.
                    //
                    // Without it a slider sitting under a scene called "Off"
                    // reads its own state as "Off" directly beneath it, and the
                    // two look like the same kind of thing. They are not: one is
                    // a choice you make, the other is a level a lamp happens to
                    // be at. The line is what says so.
                    val firstSlider = options.indexOfFirst { it["type"] == "light" }
                    options.forEachIndexed { index, opt ->
                        if (index == firstSlider && index > 0) {
                            Box(
                                Modifier
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0x33FFFFFF))
                            )
                        }
                        val label = opt["name"] as? String ?: return@forEachIndexed
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
                            return@forEachIndexed
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
/**
 * A ceiling fan, drawn rather than borrowed.
 *
 * Compose's Material set has no `mode_fan`: the only fan glyph is `ModeFanOff`,
 * which carries an "off" slash, and the near-misses are worse -- `Toys` is a toy
 * CAR and `FilterVintage` is a flower. Four blades round a hub at 24x24, matching
 * the Material grid so it sits at the same weight as the icons beside it.
 */
/**
 * Material Symbols `mode_fan`, verbatim.
 *
 * Compose's bundled icon set has no `mode_fan` -- the only fan glyph is
 * `ModeFanOff`, which carries an "off" slash. Four substitutions were tried and
 * all read wrong at 44dp (`Toys` is a toy CAR, `FilterVintage` is a flower, and a
 * hand-drawn cross/clover looked like neither), so this is the real path data
 * from fonts.gstatic.com rather than another approximation.
 *
 * Two things make it work:
 *  - PathParser parses the SVG string at runtime, so the 1.1kB path is embedded
 *    as-is instead of being hand-converted into PathBuilder calls.
 *  - Material Symbols use a `0 -960 960 960` viewBox: x runs 0..960 but y runs
 *    -960..0. ImageVector has no viewBox offset, so the path sits in a group
 *    translated +960 in y. Without that it draws entirely above the canvas and
 *    renders as nothing at all.
 */
private val CeilingFan: ImageVector by lazy {
    val d = "M424-80q-51 0-77.5-30.5T320-180q0-26 11.5-50.5T367-271q22-14 35.5-36t18.5-47l-12-6q-6-3-11-7l-92 33q-17 6-33 10t-33 4q-63 0-111.5-55T80-536q0-51 30.5-77.5T179-640q26 0 51 11.5t41 35.5q14 22 36 35.5t47 18.5l6-12q3-6 7-11l-33-92q-6-17-10-33t-4-32q0-64 55-112.5T536-880q51 0 77.5 30.5T640-781q0 26-11.5 51T593-689q-22 14-35.5 36T539-606l12 6q6 3 11 7l92-34q17-6 32.5-9.5T719-640q81 0 121 67t40 149q0 51-32 77.5T777-320q-25 0-48.5-11.5T689-367q-14-22-36-35.5T606-421l-6 12q-3 6-7 11l33 92q6 16 10 30.5t4 30.5q1 65-54 115T424-80Zm56-340q25 0 42.5-17.5T540-480q0-25-17.5-42.5T480-540q-25 0-42.5 17.5T420-480q0 25 17.5 42.5T480-420Zm-46-192q6-2 12.5-3.5T459-618q8-42 30.5-78t59.5-60q5-4 8-10t3-15q0-8-6-13.5t-18-5.5q-38 0-86 16.5T400-719q0 9 2.5 17t4.5 15l27 75ZM240-400q14 0 33-7l75-27q-2-6-3.5-12.5T342-459q-42-8-78-30.5T204-549q-4-5-10.5-8t-14.5-3q-9 0-14 6t-5 18q0 54 20.5 95t59.5 41Zm184 240q47 0 92.5-19t43.5-66q0-8-2.5-15t-4.5-13l-27-75q-6 2-12.5 3.5T501-342q-8 42-30.5 78T411-204q-5 4-8.5 10.5T400-180q1 8 6 14t18 6Zm353-240q9 0 16-5t7-19q0-38-16-86.5T719-560q-9 0-17 2t-15 4l-75 28q2 6 3.5 12.5T618-501q42 8 78 30.5t60 59.5q3 5 9 8t12 3ZM618-501ZM459-618ZM342-459Zm159 117Z"
    ImageVector.Builder(
        name = "ModeFan",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 960f, viewportHeight = 960f,
    ).apply {
        addGroup(name = "shift", translationY = 960f)
        addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
        clearGroup()
    }.build()
}

/**
 * Material Symbols `table_lamp`, verbatim -- same treatment as [CeilingFan]:
 * Compose's bundled set has no lamp beyond a bare bulb, and the nightstand
 * lights want to read as lamps rather than as another scene.
 *
 * Note the `0 -960 960 960` viewBox: y runs -960..0, so the path sits in a group
 * translated +960 or it draws entirely off-canvas.
 */
private val TableLamp: ImageVector by lazy {
    val d = "M520-120v-80h320v80H520ZM221-600h139v-160h-69l-70 160Zm419 360v-400q0-17-11.5-28.5T600-680H440v120q0 17-11.5 28.5T400-520H160q-22 0-34-18t-3-38l95-216q10-22 29.5-35t43.5-13h69q33 0 56.5 23.5T440-760h160q50 0 85 35t35 85v400h-80ZM221-600h139-139Z"
    ImageVector.Builder(
        name = "TableLamp",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 960f, viewportHeight = 960f,
    ).apply {
        addGroup(name = "shift", translationY = 960f)
        addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
        clearGroup()
    }.build()
}

object CardIcons {
    fun forName(n: String?): ImageVector = when (n?.lowercase()) {
        "tv", "activity", "source", "watch" -> Icons.Filled.Tv
        "brightness", "sun", "light-level" -> Icons.Filled.BrightnessMedium
        "screen", "aspect", "layout", "display" -> Icons.Filled.AspectRatio
        "movie", "theater", "scene" -> Icons.Filled.Movie
        "light", "bulb", "lights" -> Icons.Filled.Lightbulb
        "lamp", "table-lamp", "nightstand" -> TableLamp
        "curtain", "shade", "shades", "blinds" -> Icons.Filled.Blinds
        "thermostat", "climate", "ac", "temp" -> Icons.Filled.Thermostat
        "remote" -> Icons.Filled.SettingsRemote
        "open", "up", "arrow-up" -> Icons.Filled.KeyboardArrowUp
        "close", "down", "arrow-down" -> Icons.Filled.KeyboardArrowDown
        "stop" -> Icons.Filled.Stop
        "x", "cancel", "clear" -> Icons.Filled.Close
        "lock", "locked" -> Icons.Filled.Lock
        // The app-drawer grid, for the launcher slot.
        "apps", "grid", "drawer" -> Icons.Filled.Apps
        "bed", "sleep", "position" -> Icons.Filled.KingBed
        // A ceiling fan reads as blades from below, not as moving air. NB the
        // trap: Material's pinwheel-sounding icon is a toy CAR. FilterVintage is
        // the petal/rotor shape and is the closest thing to a fan in the set.
        "fan", "ceiling-fan", "mode_fan", "propeller" -> CeilingFan
        "air", "airflow" -> Icons.Filled.Air
        // Power, for every "Off" in the dock -- the one name that appears in
        // nearly every menu and meant "no icon" until now.
        "power", "off", "standby" -> Icons.Filled.PowerSettingsNew
        "game", "gamepad", "console", "nintendo" -> Icons.Filled.SportsEsports
        // Brightness is a RAMP, so the three presets get the three steps of one
        // family rather than three unrelated glyphs. "brightness" above is the
        // middle of it, which is what Normal uses.
        "brightness-auto" -> Icons.Filled.BrightnessAuto
        "bright", "brightness-high" -> Icons.Filled.BrightnessHigh
        "dim", "brightness-low" -> Icons.Filled.BrightnessLow
        "entertain", "party" -> Icons.Filled.Celebration
        "relax", "spa", "calm" -> Icons.Filled.Spa
        "romance", "heart", "love" -> Icons.Filled.Favorite
        "night", "good-night", "bedtime", "moon" -> Icons.Filled.Bedtime
        // NB "auto" is deliberately NOT a key. It is the one word in the config
        // that names two different things -- the wall's brightness preset and
        // the thermostat's mode -- so each spells out which it means and an
        // unqualified "auto" stays an error rather than silently picking one.
        "cool", "cooling", "snowflake" -> Icons.Filled.AcUnit
        "heat", "heating", "flame" -> Icons.Filled.LocalFireDepartment
        "auto-mode", "hvac-auto" -> Icons.Filled.Autorenew
        // Fan speeds as numbered settings, which is how every fan remote in the
        // house labels them. The first attempt used the cellular-signal bars --
        // graded correctly, but the shape means RECEPTION to anyone who has used
        // a phone, and a fan tile is not where you want that read.
        "speed-high" -> Icons.Filled.Looks3
        "speed-medium" -> Icons.Filled.LooksTwo
        "speed-low" -> Icons.Filled.LooksOne
        // Shade targets. Compass for the rooms named by direction, and the same
        // arrows re-used for the bay window's left/centre/right.
        "north" -> Icons.Filled.North
        "east", "right" -> Icons.Filled.East
        "west", "left" -> Icons.Filled.West
        "center", "centre" -> Icons.Filled.VerticalAlignCenter
        // Bed positions. The airline-seat family is the only set in Material
        // that draws a RECLINE ANGLE, which is exactly what these presets are --
        // so ALL FOUR use it, and the four glyphs happen to sit in the same
        // order as the positions themselves:
        //
        //   Flat -> Snore (barely raised) -> Zero G -> TV (most upright)
        //
        // Snore and TV were the odd ones out before: a snooze clock and a
        // television, both describing what the position is FOR rather than what
        // the bed is doing. Next to two seat glyphs they read as a different
        // kind of control.
        "flat" -> Icons.Filled.AirlineSeatFlat
        "snore", "flat-angled" -> Icons.Filled.AirlineSeatFlatAngled
        "zero-g", "recline" -> Icons.Filled.AirlineSeatReclineExtra
        "upright", "sit", "bed-tv" -> Icons.Filled.AirlineSeatReclineNormal
        else -> Icons.Filled.Tune
    }
}

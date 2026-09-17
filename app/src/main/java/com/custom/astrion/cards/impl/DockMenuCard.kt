package com.custom.astrion.cards.impl

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.PickerModal
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Where the dock menu currently is, as a PATH of menu names.
 *
 * A path rather than a single name because menus nest: Shades -> Which shades is
 * two levels down, and Back/swipe pop ONE level rather than jumping home.
 * Empty = the index.
 *
 * Deliberately NOT per-card local state. Dashboard hides the page dots while a
 * submenu is up (that strip is the room selector, and a submenu wants the row it
 * occupies) and clears the path when the settled page changes, so swiping to
 * another room always lands on that room's top-level grid. Both room cards read
 * the same value, which is what makes one write reset both.
 */
object DockMenuState {
    var path by mutableStateOf<List<String>>(emptyList())

    /** Convenience for Dashboard: is any submenu open? */
    val inSubmenu: Boolean get() = path.isNotEmpty()

    /** Back one level. Called by the page-level swipe handler and by Back. */
    fun popOne() { if (path.isNotEmpty()) path = path.dropLast(1) }
}

/**
 * Renders this card at a path OTHER than the live one, without touching it.
 *
 * Exists for the back-swipe, which has to draw two levels at once: the one
 * sliding away and the one appearing behind it. The destination level cannot
 * come from [DockMenuState] because the whole point is that the pop has not
 * happened yet -- reading the global state would draw the same level twice and
 * the gesture would slide a page off over empty background.
 *
 * Only the WALK honours this. Every tap still writes the real path, so a preview
 * layer can never navigate; it is a picture of where you are about to land.
 */
val LocalDockPath = androidx.compose.runtime.compositionLocalOf<List<String>?> { null }

/**
 * Dock menu -- nested grids of large buttons for a remote in its cradle.
 *
 * Read at arm's length, so: fewer and bigger targets, live status on the index,
 * and no precision needed anywhere. At 480x800 / density 220 the panel is ~349dp
 * wide, so two columns give ~165dp-wide buttons.
 *
 * INDEX TILES ARE ICON + STATUS, NOT A LABEL. The icon says which subsystem the
 * tile is; the text under it is live state (`status_entity`, optionally one of
 * its attributes via `status_attribute`), so the grid doubles as the room's
 * status panel. Three refinements that matter:
 *  - `status_from_items` labels the tile with whichever ITEM matches the current
 *    value, so it reads in the same words as the buttons behind it ("Medium",
 *    not "67"). It also fixes a wrong reading: a fan that is OFF keeps its last
 *    `percentage`, so state is checked first and an off entity takes the name of
 *    the item that sets no value for the tracked attribute.
 *  - A status of "Off" renders as ICON ONLY. Off is the resting state of most of
 *    these, and six tiles all reading "Off" is noise, not information.
 *  - No status configured, or an unknown/unavailable entity, falls back to the
 *    menu NAME, so a broken entity id degrades to a readable label.
 *
 * Note the display-only use of the tracker selects: input_select.
 * master_bedroom_active_scene and bed_active_preset are WRITTEN by automation and
 * must never be written back to, but they are exactly right to READ here.
 *
 * NAVIGATION IS INTERNAL STATE, NOT PAGES. Deliberately not real pages:
 * `startPage` is a positional index into `pages:` and both bedroom remotes rely
 * on `1` meaning Master Bedroom, so pages appearing and disappearing with dock
 * state would drift those indices. The pager never learns this card exists,
 * which is also why swiping on the INDEX still changes room.
 *
 *   { "type": "dock_menu", "options": {
 *       "button_height": 128, "columns": 2,
 *       "menus": [
 *         { "name": "Shades", "icon": "shade",
 *           "status_entity": "input_select.lr_shade_target",
 *           "items": [
 *             { "name": "Open",  "service": "script.lr_shade_target_open" },
 *             { "name": "Which", "items": [ { "name": "North", ... } ] } ] },
 *         { "name": "Activity", "icon": "tv", "close_on_select": true,
 *           "lock": { "entity": "input_boolean.tv_wall_locked",
 *                     "pin_entry": "input_text.tv_wall_lock_pin_entry",
 *                     "pin_entity": "input_text.tv_wall_lock_pin" },
 *           "items": [ ... ] }
 *       ]
 *   } }
 */
class DockMenuCard : CardRenderer {
    override val type = "dock_menu"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val menus = (config.options["menus"] as? List<Map<String, Any?>>) ?: emptyList()
        val columns = config.int("columns", 2).coerceAtLeast(1)

        // `button_height` is the FULL-HEIGHT size, used whenever the index has
        // the screen to itself. `compact_when` names the same condition the
        // now-playing card is gated on, so when that strip is present the tiles
        // step down to `button_height_compact` and everything still fits.
        //
        // The condition is stated twice -- here and on the conditional card --
        // which is the cost of the card not being able to measure its siblings:
        // PageContent lays cards out in a LazyColumn, so vertical space is
        // unbounded and `weight` cannot claim what is left. Keep the two in sync.
        val tall = config.int("button_height", 128)
        val compactH = config.int("button_height_compact", tall)
        @Suppress("UNCHECKED_CAST")
        val cw = config.options["compact_when"] as? Map<String, Any?>
        val compact = cw != null && run {
            val st = (cw["entity_id"] as? String)?.let { ctx.entities[it]?.state }
            val list = (cw["state_in"] as? List<*>)?.map { it.toString() }
            if (list != null) st in list else st != null && st == cw["state"]
        }

        // Walk the path. A stale segment (a Sync renamed a menu under us) stops
        // the walk at the last level that still resolves, rather than rendering
        // a blank screen with no way back.
        var node: Map<String, Any?>? = null
        var level: List<Map<String, Any?>> = menus
        for (seg in (LocalDockPath.current ?: DockMenuState.path)) {
            val hit = level.firstOrNull { (it["name"] as? String) == seg } ?: break
            node = hit
            level = (hit["items"] as? List<Map<String, Any?>>) ?: emptyList()
        }

        // Submenus never show the now-playing strip (it is `dock_index_only`),
        // so they always get the full-height tiles.
        if (node == null) {
            IndexGrid(menus, columns, if (compact) compactH else tall, ctx)
        } else {
            SubGrid(node, level, columns, tall, ctx)
        }
    }

    // ---- index ------------------------------------------------------------

    @Composable
    private fun IndexGrid(
        menus: List<Map<String, Any?>>,
        columns: Int,
        height: Int,
        ctx: CardContext,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            menus.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { m ->
                        val name = m["name"] as? String ?: ""
                        // `show_status: false` keeps the menu NAME on the index
                        // while still using status_entity for the nested tile and
                        // the highlight underneath. Shades wants this: its status
                        // is a TARGET ("All Shades"), which answers a question the
                        // index never asked and reads like a different subsystem.
                        // `index_status` lets the INDEX tile show something
                        // other than the menu's own status_entity, using the
                        // same keys. Shades needs it: `status_entity` there is
                        // the TARGET ("All Shades") because the submenu's nested
                        // tile and its highlight are driven from it, but the
                        // target answers a question the index never asked. What
                        // the index wants is whether the shades are open.
                        @Suppress("UNCHECKED_CAST")
                        val indexStatus = m["index_status"] as? Map<String, Any?>
                        val status = when {
                            indexStatus != null -> statusOf(indexStatus, ctx)
                            m["show_status"] == false -> null
                            else -> statusOf(m, ctx)
                        }
                        // With the name carried separately below, "Off" is free
                        // to be shown: it can no longer be misread as the label.
                        // A menu with no status at all keeps the name on the main
                        // line rather than dropping it to caption size -- a
                        // caption alone would be the only text on the tile, and
                        // the quiet treatment is only quiet next to something
                        // louder.
                        val label = status ?: name
                        val caption = if (status == null) null else name
                        key(name) {
                            Tile(
                                label = label,
                                icon = m["icon"] as? String ?: name,
                                height = height,
                                modifier = Modifier.weight(1f),
                                // Thermostat layout: CURRENT temperature big
                                // beside the icon, setpoint in a pill beneath.
                                // Two equal numbers read as a fraction; one large
                                // and one chipped reads as "it is this, aiming
                                // for that".
                                bigValue = format(
                                    m["status_big"] as? String,
                                    m["status_entity"] as? String, ctx,
                                ),
                                pills = pillsOf(m, ctx),
                                caption = caption,
                                // Every index tile opens a menu -- so it gets
                                // the same corner chevron a nested submenu tile
                                // has. Same promise, same mark.
                                navigates = true,
                                nested = true,
                            ) { DockMenuState.path = listOf(name) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    // ---- submenu, any depth ------------------------------------------------

    @Composable
    private fun SubGrid(
        node: Map<String, Any?>,
        items: List<Map<String, Any?>>,
        columns: Int,
        height: Int,
        ctx: CardContext,
    ) {
        val active = statusOf(node, ctx, useFormat = false)
        val closeOnSelect = node["close_on_select"] == true
        // `visible_when` hides an item until its entity says it is relevant --
        // Resume only appears while the thermostat is actually holding. Filtered
        // before chunking so the grid closes up rather than leaving a hole.
        @Suppress("UNCHECKED_CAST")
        val visible = items.filter { it ->
            val w = it["visible_when"] as? Map<String, Any?> ?: return@filter true
            val st = (w["entity_id"] as? String)?.let { id -> ctx.entities[id]?.state }
            val list = (w["state_in"] as? List<*>)?.map { v -> v.toString() }
            if (list != null) st in list else st != null && st == w["state"]
        }
        // `corner: true` lifts an item out of the grid and into the back row,
        // beside the lock. Used for things that qualify the menu rather than
        // being one of its choices -- screen layout and brightness belong TO
        // watching, they are not things you are choosing to watch. They keep
        // their nested menus; tapping one still drills in.
        val corners = visible.filter { it["corner"] == true }
        val items = visible.filter { it["corner"] != true }
        val backLock = node["lock"] as? Map<String, Any?>

        // NOTE: the back-swipe is NOT handled here any more.
        //
        // It cannot be: this Column sits inside PageContent's LazyColumn, and the
        // logs showed the gesture coroutine being CANCELLED the instant real
        // movement began -- taps logged fine, anything with moves produced no log
        // at all. The scrolling ancestor claims the pointer (a real thumb arcs, so
        // it gets a vertical component to grab) and the child's detector dies with
        // it. Detection lives in PageContent, ABOVE the LazyColumn. See
        // DockMenuState.popOne.
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A menu can be a CARD rather than a grid. The thermostat uses this
            // to render the same bubble_climate the in-hand page does -- a
            // rollable temperature and +/- steppers, with the hold shown inline.
            // Fixed ladders could never express a setpoint between the rungs, and
            // in heat_cool they could not express two bounds at all.
            @Suppress("UNCHECKED_CAST")
            val embedded = (node["card"] as? Map<String, Any?>)?.let { c ->
                CardConfig(
                    type = c["type"] as? String ?: "",
                    options = (c["options"] as? Map<String, Any?>).orEmpty(),
                )
            }
            BackBar(
                backLock,
                node["modes"] as? Map<String, Any?>,
                format(node["header_format"] as? String, node["status_entity"] as? String, ctx),
                node["action"] as? Map<String, Any?>,
                corners,
                ctx,
            ) { DockMenuState.path = DockMenuState.path.dropLast(1) }
            if (embedded != null) {
                CardRegistry.get(embedded.type)?.Render(embedded, ctx)
            }
            items.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { b ->
                        val name = b["name"] as? String ?: ""
                        val nested = (b["items"] as? List<Map<String, Any?>>)?.isNotEmpty() == true
                        // `active_value` for items whose label is not the entity's
                        // wording ("Apple TV" vs the select's "Watch Apple TV");
                        // same idiom as ButtonGridCard.
                        val key = (b["active_value"] as? String) ?: name
                        // Keyed by name: without this, Compose reuses the slot
                        // when one grid replaces another of the same shape, and
                        // the press-ack animation started on the tile you tapped
                        // carries over to whatever now occupies that position --
                        // which is why picking a shade target made one of
                        // Open/Close/Stop flash grey on the way back.
                        key(name) {
                        Tile(
                            // A nested tile is labelled with what is currently
                            // SELECTED behind it ("North"), not with the name of
                            // the menu it opens ("Which shades") -- the question
                            // is already implied by the row it sits in, and the
                            // answer is the useful half.
                            label = if (nested) {
                                statusOf(b, ctx) ?: name
                            } else {
                                format(
                                    b["label_format"] as? String,
                                    b["label_entity"] as? String, ctx,
                                ) ?: name
                            },
                            // The question, under the answer. The note above
                            // traded one for the other because there was only
                            // one line to spend; with a caption slot the row no
                            // longer has to imply what it opens.
                            caption = if (nested) name else null,
                            icon = b["icon"] as? String,
                            height = height,
                            modifier = Modifier.weight(1f),
                            selected = active != null && key == active,
                            nested = nested,
                            navigates = nested,
                        ) {
                            if (nested) {
                                DockMenuState.path = DockMenuState.path + name
                            } else {
                                fire(ctx, b)
                                // Pick-one menus close on choice; menus you might
                                // press twice (Shades, where Stop follows Open)
                                // deliberately stay put.
                                //
                                // Pops ONE level rather than jumping home, which
                                // is the same thing for a top-level menu (one
                                // level up IS the index) but the right thing for
                                // a nested one: choosing a shade target returns
                                // you to Shades, where Open/Close are waiting.
                                if (closeOnSelect) {
                                    DockMenuState.path = DockMenuState.path.dropLast(1)
                                }
                            }
                        }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    /**
     * The coloured setpoint pills for an index tile.
     *
     * Colour carries the meaning: BLUE is the cool setpoint, ORANGE the heat one.
     * In heat_cool a thermostat has BOTH, so both are drawn; in a single mode
     * only one resolves.
     *
     * Mode filtering is mostly free: `temperature` is null in heat_cool and
     * `target_temp_low`/`high` are null outside it, and a format with an
     * unresolvable placeholder returns null and is skipped. Only the two entries
     * that share `{temperature}` need an explicit `state`, because they differ by
     * colour alone -- the same number is blue when cooling and orange when
     * heating.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pillsOf(m: Map<String, Any?>, ctx: CardContext): List<Pair<String, Color>> {
        val id = m["status_entity"] as? String ?: return emptyList()
        val st = ctx.entities[id]?.state?.trim()
        val specs = (m["status_pills"] as? List<Map<String, Any?>>) ?: return emptyList()
        return specs.mapNotNull { spec ->
            val want = spec["state"] as? String
            if (want != null && want != st) return@mapNotNull null
            val text = format(spec["format"] as? String, id, ctx) ?: return@mapNotNull null
            val colour = when (spec["color"] as? String) {
                "heat", "orange" -> Color(0xFFE8A33D)
                else -> Color(0xFF7FD8F0)
            }
            text to colour
        }
    }

    /**
     * Resolve "{attr}" / "{state}" placeholders against an entity.
     *
     * Shared by every formatted string on the card (index big/pill text, the
     * submenu header, an item's label) so they behave identically: any
     * placeholder that cannot resolve makes the WHOLE string null, which stops a
     * half-rendered "Holding \u00B0" reaching the screen.
     */
    private fun format(fmt: String?, id: String?, ctx: CardContext): String? {
        if (fmt == null || id == null) return null
        val e = ctx.entities[id] ?: return null
        var ok = true
        val out = Regex("\\{([a-z_]+)\\}").replace(fmt) { mr ->
            val k = mr.groupValues[1]
            val v = (if (k == "state") e.state else e.attr(k)?.toString())?.trim()
            if (v.isNullOrEmpty() || v.lowercase() in setOf("unknown", "unavailable", "none", "null")) {
                ok = false; ""
            } else v.removeSuffix(".0")
        }
        return if (ok) out else null
    }

    /**
     * Live status for a tile, or null to fall back to the menu name.
     *
     * See the class doc for `status_from_items` and why state is checked before
     * the attribute.
     */
    @Suppress("UNCHECKED_CAST")
    private fun statusOf(
        m: Map<String, Any?>,
        ctx: CardContext,
        /**
         * Whether `status_format` applies. Display uses it; MATCHING must not --
         * the composed label ("74\u00B0 / 74\u00B0") can never equal an item's
         * active_value ("74"), so formatting the menu silently killed the
         * setpoint highlight until these two callers were separated.
         */
        useFormat: Boolean = true,
    ): String? {
        val id = m["status_entity"] as? String ?: return null
        val e = ctx.entities[id] ?: return null
        val attr = m["status_attribute"] as? String

        // `status_format` composes several values into one label --
        // "{current_temperature}\u00B0 / {temperature}\u00B0" reads as "75 / 74",
        // which is the pair you actually want off a thermostat. {state} is the
        // entity state; anything else is an attribute. If ANY placeholder is
        // missing the whole thing returns null, so a half-resolved label like
        // "75 / " never reaches a tile.
        (if (useFormat) m["status_format"] as? String else null)?.let { fmt ->
            var ok = true
            val out = Regex("\\{([a-z_]+)\\}").replace(fmt) { mr ->
                val k = mr.groupValues[1]
                val v = (if (k == "state") e.state else e.attr(k)?.toString())?.trim()
                if (v.isNullOrEmpty() || v.lowercase() in setOf("unknown", "unavailable", "none", "null")) {
                    ok = false; ""
                } else v.removeSuffix(".0")
            }
            return if (ok) out else null
        }

        val raw = if (attr != null) e.attr(attr)?.toString() else e.state
        val v = raw?.trim().orEmpty()

        if (m["status_from_items"] == true) {
            val items = (m["items"] as? List<Map<String, Any?>>) ?: emptyList()
            fun dataVal(it: Map<String, Any?>): Double? {
                val d = (it["data"] as? Map<String, Any?>)?.get(attr ?: return null)
                return (d as? Number)?.toDouble() ?: (d as? String)?.toDoubleOrNull()
            }
            if (e.state?.trim()?.lowercase() == "off") {
                return (items.firstOrNull { dataVal(it) == null }?.get("name") as? String) ?: "Off"
            }
            val cur = v.toDoubleOrNull()
            if (cur != null) {
                val best = items
                    .mapNotNull { i -> dataVal(i)?.let { i to kotlin.math.abs(it - cur) } }
                    .minByOrNull { it.second }?.first
                (best?.get("name") as? String)?.let { return it }
            }
        }

        if (v.isEmpty() || v.lowercase() in setOf("unknown", "unavailable", "none", "null")) return null
        // Trim a trailing ".0" so a numeric attribute reads 74, not 74.0.
        return v.removeSuffix(".0")
    }

    // ---- chrome -----------------------------------------------------------

    /**
     * A back PILL, not a full-width bar: it is one control, and sizing it like a
     * control frees the rest of the row for [action] on the right.
     */
    @Composable
    private fun BackBar(
        lock: Map<String, Any?>?,
        modes: Map<String, Any?>?,
        header: String?,
        action: Map<String, Any?>?,
        corners: List<Map<String, Any?>>,
        ctx: CardContext,
        onBack: () -> Unit,
    ) {
        // Navigation, so no ack latch: see rememberPressFeedback's `latch`. This
        // pill is the same composable at every level, so a 900ms hold started on
        // one page was still running when the next one drew, and Back arrived
        // greyed.
        val (press, click) = rememberPressFeedback(latch = false, onClick = onBack)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(ackColor(Color(0xFF1E3640), press))
                    .pressFeedback(press, click),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "< Back",
                    color = Color(0xFFE6F0F1),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (header != null) {
                Text(
                    header,
                    color = Color(0xFF9FD4E3),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            corners.forEach { c ->
                val nested = (c["items"] as? List<Map<String, Any?>>)?.isNotEmpty() == true
                CornerPill(c, ctx, navigates = nested) {
                    if (nested) DockMenuState.path = DockMenuState.path + (c["name"] as? String ?: "")
                    else fire(ctx, c)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (modes != null) ModePills(modes, ctx)
            if (action != null) {
                if (modes != null) Spacer(Modifier.width(8.dp))
                ActionPill(action, ctx)
            }
            if (lock != null) {
                if (modes != null) Spacer(Modifier.width(8.dp))
                LockPill(lock, ctx)
            }
        }
    }

    /**
     * Right-hand pill on the back row: the AV lock, behaving EXACTLY like the
     * one on the in-hand page's Watch separator.
     *
     * Same asymmetry, and it is the point: locking is one tap, unlocking opens
     * the PIN keypad. Locking something already off is harmless; unlocking is
     * what lets a stray press or a voice command power the wall up.
     *
     * The keypad holds no opinion about the PIN -- it writes the entry to the
     * scratchpad helper and automation.tv_wall_validate_unlock_pin does the
     * comparing, so there is one definition of the PIN in the house and the
     * remote never stores the secret. Colours and icons are lifted from
     * SeparatorCard so it reads as the same control in both places.
     */
    @Composable
    private fun LockPill(lock: Map<String, Any?>, ctx: CardContext) {
        val lockEntity = lock["entity"] as? String ?: return
        val pinEntry = lock["pin_entry"] as? String
        val locked = ctx.entities[lockEntity]?.state == "on"
        var keypad by remember { mutableStateOf(false) }
        val (press, click) = rememberPressFeedback {
            if (locked) keypad = true
            else ctx.client.callService(ServiceCall("input_boolean", "turn_on", lockEntity))
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(ackColor(if (locked) Color(0xFFB4472F) else Color(0xFF1E3841), press))
                .pressFeedback(press, click),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (locked) "Unlock" else "Lock",
                tint = if (locked) Color.White else Color(0xFF6C8A94),
                modifier = Modifier.size(22.dp),
            )
        }
        if (keypad && pinEntry != null) {
            LockKeypad(lockEntity, pinEntry, ctx, pinEntity = lock["pin_entity"] as? String ?: "") {
                keypad = false
            }
        }
    }

    /**
     * The thermostat's mode: ONE pill showing what it is now, tapping opens a
     * modal to change it.
     *
     * Four pills fitted, but they crowded the back row and made a rarely-used
     * setting look like a primary control. A modal costs one extra tap on the
     * uncommon path and gives the common one -- reading the mode at a glance --
     * the whole label.
     */
    @Composable
    private fun ModePills(modes: Map<String, Any?>, ctx: CardContext) {
        @Suppress("UNCHECKED_CAST")
        val all = (modes["options"] as? List<Map<String, Any?>>) ?: return
        val ent = modes["entity_id"] as? String
        val cur = ent?.let { ctx.entities[it]?.state?.trim() }
        // Only the modes this thermostat actually advertises -- same rule the
        // in-hand climate card applies. Offering a button the device would reject
        // is worse than not offering it.
        val advertised = ent?.let { ctx.entities[it]?.attrStringList("hvac_modes") }.orEmpty()
        val opts = if (advertised.isEmpty()) all
                   else all.filter { (it["active_value"] as? String) in advertised }
        val label = opts.firstOrNull { (it["active_value"] as? String ?: it["name"]) == cur }
            ?.get("name") as? String ?: cur?.replaceFirstChar { it.uppercase() } ?: "Mode"
        var open by remember { mutableStateOf(false) }
        val (press, click) = rememberPressFeedback(latch = false) { open = true }
        Box(
            Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(ackColor(Color(0xFF2E7D95), press))
                .pressFeedback(press, click),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        if (open) {
            PickerModal.Show(
                title = modes["title"] as? String ?: "Mode",
                // Dismiss on pick: PickerModal fires onPick but does NOT close
                // itself, and a mode is a one-shot choice -- leaving the sheet up
                // reads as "that did not take".
                entries = opts.map { o ->
                    PickerModal.Entry(o["name"] as? String ?: "") { fire(ctx, o); open = false }
                },
                client = ctx.client,
                columns = 2,
                tileHeight = 92.dp,
                fontSize = 20.sp,
            ) { open = false }
        }
    }

    /**
     * A single icon action in the back row's top-right -- same corner, size and
     * accent as the lock, because it is the same KIND of thing: a property of
     * the menu you are in rather than one of the choices it offers.
     *
     * Used for "make this pane the remote's target": it belongs to the pane, not
     * alongside its source options, where it read as a fifth source.
     */
    @Composable
    private fun ActionPill(action: Map<String, Any?>, ctx: CardContext) {
        val activeEntity = action["active_entity"] as? String
        val activeValue = action["active_value"] as? String
        val on = activeEntity != null && activeValue != null &&
            ctx.entities[activeEntity]?.state?.trim() == activeValue
        val (press, click) = rememberPressFeedback { fire(ctx, action) }
        var box = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(ackColor(if (on) Color(0xFF2E7D95) else Color(0xFF1E3841), press))
        if (on) box = box.border(2.dp, Color(0xFF7FD8F0), RoundedCornerShape(23.dp))
        Box(box.pressFeedback(press, click), contentAlignment = Alignment.Center) {
            Icon(
                CardIcons.forName(action["icon"] as? String),
                contentDescription = null,
                tint = if (on) Color.White else Color(0xFF9FD4E3),
                modifier = Modifier.size(22.dp),
            )
        }
    }

    /** A corner item: icon only, same size and shape as the lock beside it. */
    @Composable
    private fun CornerPill(
        c: Map<String, Any?>,
        ctx: CardContext,
        /** True when the tap opens a menu rather than calling a service. */
        navigates: Boolean = false,
        onTap: () -> Unit,
    ) {
        val activeEntity = c["status_entity"] as? String
        val on = (c["active_value"] as? String)?.let { av ->
            activeEntity != null && ctx.entities[activeEntity]?.state?.trim() == av
        } ?: false
        val (press, click) = rememberPressFeedback(latch = !navigates, onClick = onTap)
        var box = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(ackColor(if (on) Color(0xFF2E7D95) else Color(0xFF1E3841), press))
        if (on) box = box.border(2.dp, Color(0xFF7FD8F0), RoundedCornerShape(23.dp))
        Box(box.pressFeedback(press, click), contentAlignment = Alignment.Center) {
            Icon(
                CardIcons.forName(c["icon"] as? String),
                contentDescription = c["name"] as? String,
                tint = if (on) Color.White else Color(0xFF9FD4E3),
                modifier = Modifier.size(22.dp),
            )
        }
    }

    @Composable
    private fun Tile(
        label: String,
        icon: String?,
        height: Int,
        modifier: Modifier,
        selected: Boolean = false,
        /** Draws the goes-deeper chevron; see the note in [SubGrid]. */
        nested: Boolean = false,
        /** Large value shown BESIDE the icon instead of under it. */
        bigValue: String? = null,
        /** Outlined setpoint pills under the icon row; colour carries meaning. */
        pills: List<Pair<String, Color>> = emptyList(),
        /**
         * Small muted name under the live value.
         *
         * The index used to show ONE line that was the status when there was one
         * and the menu name when there wasn't -- so the three tiles whose thing
         * was off (their status being the word "Off", which was suppressed for
         * reading like a label) were bare icons with no text at all. You had to
         * already know which glyph was which. Splitting the two means the name is
         * always there and the status can say "Off" without being mistaken for
         * the name of the menu.
         *
         * Null where it would merely repeat the label -- a submenu's Open/Close
         * buttons are already named by their label.
         */
        caption: String? = null,
        /**
         * True when the tap opens another menu. Suppresses the acknowledgement
         * hold, which is meant for a control whose effect you cannot yet see --
         * the opposite of one that replaces the screen.
         */
        navigates: Boolean = false,
        onClick: () -> Unit,
    ) {
        val (press, click) = rememberPressFeedback(latch = !navigates, onClick = onClick)
        // Same accent as ButtonGridCard's selected state, so "this is the live
        // one" looks identical wherever it appears in the app.
        val bg = if (selected) Color(0xFF2E7D95) else Color(0xFF2A4954)
        var box = modifier
            .height(height.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ackColor(bg, press))
        if (selected) box = box.border(2.dp, Color(0xFF7FD8F0), RoundedCornerShape(16.dp))
        Box(box.pressFeedback(press, click), contentAlignment = Alignment.Center) {
            // A chevron in the corner, not a character in the label: the label is
            // live state now, and decorating state text makes it read as part of
            // the value ("North..." looks like a truncated room name).
            if (nested) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color(0x807FD8F0),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (bigValue != null) {
                    // Icon and value side by side, so the number gets the height
                    // rather than sharing it with a stacked label.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon != null) {
                            Icon(
                                CardIcons.forName(icon),
                                contentDescription = null,
                                tint = if (selected) Color.White else Color(0xFF9FD4E3),
                                modifier = Modifier.size((height * 0.30f).coerceIn(24f, 56f).dp),
                            )
                        }
                        Text(
                            bigValue,
                            color = if (selected) Color.White else Color(0xFFE6F0F1),
                            fontSize = (height * 0.26f).coerceIn(22f, 40f).sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (pills.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pills.forEach { (text, colour) ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(2.dp, colour, RoundedCornerShape(12.dp)),
                                ) {
                                    Text(
                                        text,
                                        color = colour,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (icon != null) {
                    Icon(
                        CardIcons.forName(icon),
                        contentDescription = null,
                        tint = if (selected) Color.White else Color(0xFF9FD4E3),
                        // Scaled to the tile, not fixed: the index switches
                        // between 154dp and 114dp depending on whether the
                        // now-playing strip is present, and a 44dp glyph that
                        // looked right in the compact grid was lost in the tall
                        // one. The ratio is the compact pair (44/114) held
                        // constant, so the crowded case is unchanged.
                        modifier = Modifier.size((height * 0.386f).coerceIn(28f, 72f).dp),
                    )
                }
                // Value and name are ONE block with a tight gap, not two items
                // in the 8dp stack above -- at that spacing they read as two
                // unrelated lines rather than as a thing and its label.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    // `bigValue` replaces the plain label rather than joining it
                    // -- otherwise the thermostat tile renders the temperature
                    // three times (big, pill, and again as the status line).
                    if (label.isNotEmpty() && bigValue == null) {
                        Text(
                            label,
                            color = if (selected) Color.White else Color(0xFFE6F0F1),
                            // Status values run longer than a menu name ("Watch
                            // Apple TV"), so the text is a step smaller under an
                            // icon -- and likewise grows with the tile.
                            fontSize = if (icon != null) (height * 0.149f).coerceIn(14f, 23f).sp else 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    // Not on a tile that already shows a live VALUE. The
                    // thermostat tile carries the room temperature and the
                    // setpoint pill; adding the word CLIMATE under them names
                    // something the two numbers have already introduced, and
                    // three stacked elements make one tile look like several.
                    if (caption != null && caption.isNotBlank() && bigValue == null) {
                        Text(
                            caption.uppercase(),
                            // Deliberately quiet: it is the one thing on the tile
                            // that never changes, so it should be the last thing
                            // the eye stops on. Letter-spacing is what keeps it
                            // legible at this size rather than just small.
                            color = if (selected) Color(0xCCFFFFFF) else Color(0xFF7FA4B0),
                            fontSize = (height * 0.075f).coerceIn(9f, 12f).sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    /**
     * Byte-for-byte the same as ButtonGridCard.fire, so a dock item takes
     * identical config to a grid button. Note `ServiceCall.of` (not the
     * constructor): the constructor wants Map<String, JsonElement>, and `of`
     * is the vararg helper that converts loose YAML values for you.
     */
    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray()),
        )
    }
}

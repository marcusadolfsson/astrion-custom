package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ackColor
import com.custom.astrion.ui.pressFeedback
import com.custom.astrion.ui.rememberPressFeedback
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Thermostat card, shaped after the ecobee app's own.
 *
 * Current temperature large on the left, setpoint in an outlined pill on the
 * right — with "Holding" and a clear button inside that pill when the thermostat
 * is off its schedule. Tapping the pill opens a scrolling temperature picker;
 * tapping outside closes it.
 *
 * ONE ROW, not stacked. The stacked version matched the phone app but the phone
 * app gets a whole screen per thermostat; here the card shares a scrolling page
 * with lights, shades, the bed and a fan, and 100dp of mostly-empty card pushed
 * everything else below the fold. Side by side keeps both numbers legible in
 * about half the height.
 *
 * THE ROOM NAME IS GONE on purpose: every card sits under a `separator` naming
 * its section, and the page itself is the room.
 *
 * Setpoint changes live behind the picker rather than as − / + on the face. On a
 * 3" screen those circles were the biggest touch targets on the card, so the most
 * destructive action was also the easiest to hit while scrolling past.
 *
 * Config:
 *   { "type": "bubble_climate", "options": {
 *       "entity_id": "climate.living_room", "step": 1,
 *       "hold_entity": "binary_sensor.living_room_holding",
 *       "clear_hold": { "service": "button.press",
 *                       "entity_id": "button.living_room_clear_hold" } } }
 */
class BubbleClimateCard : CardRenderer {
    override val type = "bubble_climate"

    private companion object {
        /**
         * How long the card ignores a still-true hold_entity after Resume.
         *
         * Pressing Resume has to look like it worked immediately, and it cannot:
         * the press goes to the thermostat over HomeKit, the thermostat updates
         * its own state, and only then does the characteristic change — several
         * seconds during which a truthful UI still says "Holding" and the button
         * reads as broken.
         *
         * So the chip is hidden optimistically. It is BOUNDED rather than
         * permanent: if the hold is still there when the window expires, the chip
         * comes back, which is the honest outcome for a resume that failed (or a
         * thermostat on eco+, where clear-hold legitimately does nothing).
         */
        const val RESUME_OPTIMISTIC_MS = 12_000L

        /**
         * Width of the two flanking slots in the picker row.
         *
         * Equal to StepBtn's diameter on purpose: the mode button on the left and
         * the +/- column on the right have to occupy the SAME width or the wheel
         * between them is not on the dialog's centre line.
         */
        val SIDE_SLOT = 52.dp
    }

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val step = (config.options["step"] as? Number)?.toDouble() ?: 1.0

        val holdEntity = config.string("hold_entity")
        val holdingReported = holdEntity?.let { ctx.entities[it]?.isOn == true } == true
        @Suppress("UNCHECKED_CAST")
        val clearHold = config.options["clear_hold"] as? Map<String, Any?>

        // Optimistic dismissal, see RESUME_OPTIMISTIC_MS.
        var resumedAt by remember(entityId) { mutableStateOf(0L) }
        var suppressHold by remember(entityId) { mutableStateOf(false) }
        LaunchedEffect(resumedAt) {
            if (resumedAt == 0L) return@LaunchedEffect
            suppressHold = true
            delay(RESUME_OPTIMISTIC_MS)
            suppressHold = false
        }
        // A hold that genuinely clears also clears the suppression early, so a
        // NEW hold started inside the window still shows up.
        LaunchedEffect(holdingReported) { if (!holdingReported) suppressHold = false }
        val holding = holdingReported && !suppressHold

        val target = e?.attr("temperature")?.let { num(it) }
        val low = e?.attr("target_temp_low")?.let { num(it) }
        val high = e?.attr("target_temp_high")?.let { num(it) }
        val current = e?.attr("current_temperature")?.let { num(it) }
        val minTemp = e?.attr("min_temp")?.let { num(it) } ?: 45.0
        val maxTemp = e?.attr("max_temp")?.let { num(it) } ?: 92.0
        val mode = e?.state ?: "—"
        val action = e?.attrString("hvac_action")

        // MODE decides the colour; action only refines it in Auto, where mode
        // alone cannot say which way the system is currently working.
        //
        // The earlier version keyed off `action` first and got heat wrong: a
        // thermostat set to heat but sitting idle has action "idle", which
        // matched neither the cooling nor the heating branch and fell through to
        // the blue default -- so a heating system was drawn in cooling colours
        // whenever it was not actively burning.
        val heatColor = Color(0xFFD98032)
        val coolColor = Color(0xFF4C8DFF)
        val accent = when (mode) {
            "heat" -> heatColor
            "cool" -> coolColor
            "off" -> Color(0xFF41606B)
            "heat_cool" -> when (action) {
                "heating" -> heatColor
                "cooling" -> coolColor
                else -> coolColor
            }
            else -> coolColor
        }

        val hvacModes = e?.attrStringList("hvac_modes") ?: listOf("off", "heat", "cool", "heat_cool")

        fun setTemp(t: Double) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to t)
            )
        }

        // In heat_cool the entity has no single `temperature` -- it carries a
        // LOW and a HIGH, and HA rejects a partial update, so both bounds go on
        // every call even when only one of them moved.
        fun setRange(lo: Double, hi: Double) {
            ctx.client.callService(
                ServiceCall.of(
                    "climate", "set_temperature", entityId,
                    "target_temp_low" to lo, "target_temp_high" to hi,
                )
            )
        }

        fun setMode(m: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_hvac_mode", entityId, "hvac_mode" to m)
            )
        }

        var picking by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1B333C))
                // The WHOLE card opens the picker, not just the pill. The pill is
                // a 42dp target on a 3" screen and the card around it was dead
                // space, so a near-miss did nothing at all. Deliberately a plain
                // clickable with no press-scale: shrinking the entire card reads
                // as the layout lurching rather than a button depressing (the
                // same reason the media card body is excluded from press
                // feedback). The pill keeps its own scale, and the X keeps its
                // own clickable, which consumes the tap before it reaches here.
                .clickable { picking = true }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // What the room IS -- the reason anyone looks at the card -- with
            // what the equipment is DOING under it. Those answer different
            // questions: 73 tells you whether to complain, "Cooling" tells you
            // whether complaining will help. The setpoint alone cannot say
            // either, which is why it is the smallest of the three.
            Column {
                Text(
                    current?.let { fmt(it) } ?: "—",
                    color = Color(0xFFF1F4FA),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    actionLabel(action, mode),
                    // Tinted only while actually running. Idle in grey keeps the
                    // colour meaningful: if everything were accent-coloured the
                    // colour would stop being the signal.
                    color = if (action in setOf("cooling", "heating", "drying")) accent
                            else Color(0xFF93AFB6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))

            // What it has been ASKED to be. Outlined, so it reads as a control
            // sitting beside a readout rather than a second readout.
            val (press, click) = rememberPressFeedback { picking = true }
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(ackColor(Color(0x00000000), press))
                    .border(2.dp, accent, RoundedCornerShape(21.dp))
                    .pressFeedback(press, click)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // In heat_cool there is no single `temperature` -- the entity
                // carries a low and a high -- so reading `temperature` alone
                // rendered a bare "—" on the card for a thermostat that had two
                // perfectly good setpoints. Show BOTH, tinted to match the modes
                // they belong to, so the pair reads as a range rather than a sum.
                if (mode == "heat_cool" && (low != null || high != null)) {
                    Text(
                        low?.let { fmt(it) } ?: "—",
                        color = heatColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "  ·  ",
                        color = Color(0xFF5A7683),
                        fontSize = 20.sp,
                    )
                    Text(
                        high?.let { fmt(it) } ?: "—",
                        color = coolColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        target?.let { fmt(it) } ?: "—",
                        color = accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (holding) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF5A7683)))
                    Spacer(Modifier.width(8.dp))
                    Text("Holding", color = Color(0xFFF1F4FA), fontSize = 15.sp)
                    if (clearHold != null) {
                        Spacer(Modifier.width(8.dp))
                        // Its own clickable so the tap clears the hold instead of
                        // falling through to the pill and opening the picker.
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFFF1F4FA), CircleShape)
                                .clickable {
                                    fire(ctx, clearHold)
                                    resumedAt = System.currentTimeMillis()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Resume schedule",
                                tint = Color(0xFFF1F4FA),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        if (picking) {
            TempPicker(
                mode = mode,
                hvacModes = hvacModes,
                target = target,
                low = low,
                high = high,
                step = step,
                minTemp = minTemp,
                maxTemp = maxTemp,
                accent = accent,
                heatColor = heatColor,
                onPick = { setTemp(it) },
                onPickRange = { lo, hi -> setRange(lo, hi) },
                onMode = { setMode(it) },
                onDismiss = { picking = false },
            )
        }
    }

    /**
     * The picker: mode, which setpoint is being edited, and a scrolling wheel.
     *
     * A Dialog, so tapping outside dismisses it for free and the back key works.
     *
     * THE AUTO CASE IS WHY THIS IS NOT JUST A WHEEL. In heat_cool a thermostat
     * has no single setpoint -- it has a low and a high -- so "set the
     * temperature" is an ambiguous instruction until you say which end. The
     * Heat/Cool toggle appears only in that mode, and only then, because in cool
     * or heat it would be a control with one possible answer.
     *
     * The commit is driven by SETTLING, not by every frame: a fling across
     * fifteen degrees would otherwise fire fifteen service calls at the
     * thermostat. `snapshotFlow` on "is it still scrolling" collapses a whole
     * gesture into the single value it came to rest on.
     */
    @Composable
    private fun TempPicker(
        mode: String,
        hvacModes: List<String>,
        target: Double?,
        low: Double?,
        high: Double?,
        step: Double,
        minTemp: Double,
        maxTemp: Double,
        accent: Color,
        heatColor: Color,
        onPick: (Double) -> Unit,
        onPickRange: (Double, Double) -> Unit,
        onMode: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val stepped = if (step <= 0) 1.0 else step
        val isAuto = mode == "heat_cool"
        // Which bound the wheel is editing. Heat first: it is the lower number,
        // so the pair reads low-to-high like the range it describes.
        var editHeat by remember(mode) { mutableStateOf(true) }
        var modeOpen by remember { mutableStateOf(false) }

        val editing: Double? = when {
            isAuto && editHeat -> low
            isAuto -> high
            else -> target
        }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF14262E))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (modeOpen) {
                    // Only the modes this thermostat actually advertises --
                    // rendering a button the device would reject is worse than
                    // not offering it.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf(
                            "cool" to "Cool", "heat" to "Heat",
                            "heat_cool" to "Auto", "off" to "Off",
                        )) {
                            if (value !in hvacModes) continue
                            ModePill(label, selected = mode == value, accent = accent) {
                                onMode(value)
                                modeOpen = false
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (isAuto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModePill(
                            low?.let { "Heat ${fmt(it)}" } ?: "Heat",
                            selected = editHeat, accent = heatColor,
                        ) { editHeat = true }
                        ModePill(
                            high?.let { "Cool ${fmt(it)}" } ?: "Cool",
                            selected = !editHeat, accent = Color(0xFF4C8DFF),
                        ) { editHeat = false }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (mode == "off" || editing == null) {
                    // Nothing sensible to scroll to. Saying so is better than a
                    // wheel that commits a setpoint to a system that is off.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeSlot(mode, modeOpen, accent) { modeOpen = !modeOpen }
                        Text(
                            if (mode == "off") "System is off" else "No setpoint",
                            color = Color(0xFF93AFB6),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 22.dp, horizontal = 12.dp),
                        )
                        Spacer(Modifier.width(SIDE_SLOT))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The mode button sits INLINE, in a slot the same width
                        // as the +/- column opposite it, rather than alone in the
                        // dialog's top-right corner. Two reasons: a lone corner
                        // button spends a whole line on the least-used control,
                        // and with steppers on one side only, the wheel -- the
                        // thing the eye actually goes to -- sat off-centre by half
                        // their width. Matching slots put it back on the middle.
                        ModeSlot(mode, modeOpen, accent) { modeOpen = !modeOpen }
                        Spacer(Modifier.width(12.dp))
                        Wheel(
                            // Keyed on MODE as well as bound. Heat and Cool store
                            // separate setpoints, so switching between them has to
                            // rebuild the wheel -- keying on the bound alone left
                            // it sitting on the previous mode's number.
                            key = mode + if (isAuto) (if (editHeat) ":lo" else ":hi") else "",
                            value = editing,
                            stepped = stepped,
                            minTemp = minTemp,
                            maxTemp = maxTemp,
                            accent = if (isAuto && editHeat) heatColor else accent,
                        ) { picked ->
                            if (isAuto) {
                                val lo = if (editHeat) picked else (low ?: minTemp)
                                val hi = if (editHeat) (high ?: maxTemp) else picked
                                // Never let the bounds cross -- the thermostat
                                // would reject it, silently on some backends.
                                onPickRange(minOf(lo, hi), maxOf(lo, hi))
                            } else {
                                onPick(picked)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StepBtn(Icons.Filled.Add, accent) {
                                val v = (editing + stepped).coerceAtMost(maxTemp)
                                if (isAuto) {
                                    if (editHeat) onPickRange(minOf(v, high ?: maxTemp), high ?: maxTemp)
                                    else onPickRange(low ?: minTemp, v)
                                } else onPick(v)
                            }
                            StepBtn(Icons.Filled.Remove, accent) {
                                val v = (editing - stepped).coerceAtLeast(minTemp)
                                if (isAuto) {
                                    if (editHeat) onPickRange(v, high ?: maxTemp)
                                    else onPickRange(low ?: minTemp, maxOf(v, low ?: minTemp))
                                } else onPick(v)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun modeIcon(mode: String): ImageVector = when (mode) {
        "cool" -> Icons.Filled.AcUnit
        "heat" -> Icons.Filled.Whatshot
        // SwapVert, not Autorenew: a refresh loop reads as "syncing",
        // where Auto actually means the system works in BOTH directions.
        "heat_cool" -> Icons.Filled.SwapVert
        "off" -> Icons.Filled.PowerSettingsNew
        else -> Icons.Filled.Thermostat
    }

    /**
     * The mode toggle, in a fixed-width slot that mirrors the stepper column.
     *
     * The button itself is smaller than the slot -- it is a secondary control and
     * should not compete with the +/- circles -- so the slot centres it. Only the
     * SLOT's width matters for the layout; the button's does not.
     */
    @Composable
    private fun ModeSlot(mode: String, open: Boolean, accent: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier.width(SIDE_SLOT),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (open) accent else Color(0xFF1E3841))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                // Unlabelled: the icon already names the mode, and a snowflake
                // beside the word "Cool" says it twice.
                Icon(
                    modeIcon(mode),
                    contentDescription = "Mode",
                    tint = if (open) Color.White else accent,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }

    /** Small selectable pill, used for both the mode row and the bound toggle. */
    @Composable
    private fun ModePill(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
        val (press, click) = rememberPressFeedback(onClick)
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(ackColor(if (selected) accent else Color(0xFF1E3841), press))
                .pressFeedback(press, click)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) Color.White else Color(0xFF93AFB6),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }

    /**
     * The scrolling wheel itself.
     *
     * FIVE visible rows at a shorter row height, rather than three tall ones: the
     * dialog was using well under half the screen, and a three-row wheel shows
     * only one neighbour each way, which reads as a stepper rather than a dial.
     * 44dp x 5 still leaves room for the mode row and the Auto bound toggle above
     * it on a 800px-tall screen.
     */
    @Composable
    private fun Wheel(
        key: String,
        value: Double,
        stepped: Double,
        minTemp: Double,
        maxTemp: Double,
        accent: Color,
        onSettle: (Double) -> Unit,
    ) {
        val itemH = 44.dp
        val values = remember(minTemp, maxTemp, stepped) {
            val out = mutableListOf<Double>()
            var v = maxTemp
            // Descending, so scrolling DOWN lowers the temperature -- the same
            // direction the numbers move on a physical dial.
            while (v >= minTemp) { out.add(v); v -= stepped }
            out
        }
        val startIndex = remember(key, values) {
            values.indexOfFirst { kotlin.math.abs(it - value) < stepped / 2 }.coerceAtLeast(0)
        }
        val listState = remember(key) { LazyListState(firstVisibleItemIndex = startIndex) }
        val fling = rememberSnapFlingBehavior(lazyListState = listState)

        val centeredIndex by remember(key) {
            derivedStateOf {
                val info = listState.layoutInfo
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull {
                    kotlin.math.abs((it.offset + it.size / 2) - mid)
                }?.index ?: startIndex
            }
        }

        // Re-centre when the value arrives from HA rather than from a scroll.
        // Switching Heat<->Cool fires a service call and the new mode's setpoint
        // only lands a moment later, so rebuilding the wheel on the key alone
        // showed the OLD number until something else nudged it. Guarded on
        // isScrollInProgress so it can never fight a finger.
        LaunchedEffect(key, value) {
            if (!listState.isScrollInProgress) {
                val idx = values.indexOfFirst { kotlin.math.abs(it - value) < stepped / 2 }
                if (idx >= 0) listState.scrollToItem(idx)
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) {
                    values.getOrNull(centeredIndex)?.let { v ->
                        if (kotlin.math.abs(v - value) >= stepped / 2) onSettle(v)
                    }
                }
            }
        }

        Box(
            modifier = Modifier.width(132.dp).height(itemH * 5),
            contentAlignment = Alignment.Center,
        ) {
            // A fixed frame the numbers move THROUGH, rather than a highlight
            // that travels -- which is what makes it read as a dial.
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = itemH)
                    .clip(RoundedCornerShape(26.dp))
                    .background(accent)
            )
            LazyColumn(
                state = listState,
                flingBehavior = fling,
                contentPadding = PaddingValues(vertical = itemH * 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(values) { v ->
                    val selected = values.getOrNull(centeredIndex) == v
                    Box(
                        modifier = Modifier.height(itemH).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            fmt(v),
                            color = if (selected) Color.White else Color(0xFF7E97A1),
                            fontSize = if (selected) 32.sp else 22.sp,
                            fontWeight = FontWeight.Light,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StepBtn(icon: ImageVector, accent: Color, onClick: () -> Unit) {
        val (press, click) = rememberPressFeedback(onClick)
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(ackColor(Color(0x00000000), press))
                .border(2.dp, accent, CircleShape)
                .pressFeedback(press, click),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
        }
    }

    /**
     * What the equipment is doing right now, in the words the thermostat uses.
     *
     * Falls back to the MODE when hvac_action is absent -- not every backend
     * reports it, and "Cool" (what it is set to do) is a better answer than a
     * blank line, as long as it is not dressed up as "Cooling" (what it is
     * actually doing).
     */
    private fun actionLabel(action: String?, mode: String): String = when (action) {
        "cooling" -> "Cooling"
        "heating" -> "Heating"
        "drying" -> "Drying"
        "fan" -> "Fan"
        "idle" -> "Idle"
        "off" -> "Off"
        else -> when (mode) {
            "cool" -> "Cool"
            "heat" -> "Heat"
            "heat_cool" -> "Auto"
            "off" -> "Off"
            else -> mode.replaceFirstChar { it.uppercase() }
        }
    }

    /** Whole degrees render without a trailing .0; halves keep one decimal. */
    private fun fmt(v: Double): String {
        val whole = v.roundToInt()
        if (v == whole.toDouble()) return whole.toString()
        val tenths = kotlin.math.round(v * 10).toInt()
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }

    private fun num(el: kotlinx.serialization.json.JsonElement): Double? =
        (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    /** Fire an action map {service, entity_id, data} -- same shape as elsewhere. */
    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(
                service.substringBefore('.'), service.substringAfter('.'), entityId,
                *data.entries.map { it.key to it.value }.toTypedArray(),
            )
        )
    }
}
